package com.asksakis.freegate.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.asksakis.freegate.R
import com.asksakis.freegate.notifications.CameraMuteStore
import com.asksakis.freegate.notifications.FrigateConfigFetcher
import com.asksakis.freegate.utils.NetworkUtils
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Bottom-sheet entry point for the per-camera and per-group mutes.
 * Sections, top to bottom:
 *   1. Active mutes — countdown + Unmute per active entry.
 *   2. Cameras — every camera in `/api/config/cameras`. Tap → duration picker.
 *   3. Camera groups — every entry in `/api/config/camera_groups`. Tap → duration picker.
 */
class MuteGroupsBottomSheet : BottomSheetDialogFragment() {

    private var countdownJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_mute_groups, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderActiveMutes(view)
        loadAndRender(view)
        startCountdownTicker(view)
    }

    override fun onDestroyView() {
        countdownJob?.cancel()
        super.onDestroyView()
    }

    /**
     * Re-render the "Active mutes" section against the current store state.
     */
    private fun renderActiveMutes(root: View) {
        val container = root.findViewById<LinearLayout>(R.id.mute_active_container)
        val empty = root.findViewById<TextView>(R.id.mute_active_empty)
        val now = System.currentTimeMillis()
        val mutes = CameraMuteStore.getInstance(requireContext()).activeMutes(now)
        container.removeAllViews()
        if (mutes.isEmpty()) {
            container.visibility = View.GONE
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        container.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(requireContext())
        // Groups first, then cameras, alphabetical within each.
        val sorted = mutes.toList().sortedWith(compareBy({ it.first.kind.ordinal }, { it.first.name }))
        sorted.forEach { (key, expiresAt) ->
            val row = inflater.inflate(R.layout.row_mute_active, container, false)
            row.findViewById<TextView>(R.id.row_mute_name).text = labelFor(key)
            row.findViewById<TextView>(R.id.row_mute_countdown).text =
                formatCountdown(expiresAt - now)
            row.findViewById<MaterialButton>(R.id.row_mute_unmute).setOnClickListener {
                CameraMuteStore.getInstance(requireContext()).unmute(key.kind, key.name)
                notifyMutesChanged()
                renderActiveMutes(root)
            }
            container.addView(row)
        }
    }

    /**
     * Fetch both cameras and camera_groups concurrently, cache the group
     * mapping in [CameraMuteStore] for the alert hot path, then render both
     * picker sections.
     */
    private fun loadAndRender(root: View) {
        val loading = root.findViewById<ProgressBar>(R.id.mute_loading)
        val emptyGroups = root.findViewById<TextView>(R.id.mute_groups_empty)
        val camerasContainer = root.findViewById<LinearLayout>(R.id.mute_cameras_container)
        val groupsContainer = root.findViewById<LinearLayout>(R.id.mute_available_container)
        val muteStore = CameraMuteStore.getInstance(requireContext())

        val cachedGroups = muteStore.loadGroupCameras()
        if (cachedGroups.isNotEmpty()) renderGroupRows(groupsContainer, cachedGroups)
        loading.visibility = if (cachedGroups.isEmpty()) View.VISIBLE else View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val baseUrl = resolveBaseUrl()
            if (baseUrl == null) {
                loading.visibility = View.GONE
                if (cachedGroups.isEmpty()) emptyGroups.visibility = View.VISIBLE
                return@launch
            }
            val fetcher = FrigateConfigFetcher(requireContext())
            val (cameras, groups) = withContext(Dispatchers.IO) {
                val camerasJob = async { fetcher.fetchCameraNames(baseUrl) }
                val groupsJob = async { fetcher.fetchCameraGroups(baseUrl) }
                camerasJob.await() to groupsJob.await()
            }
            loading.visibility = View.GONE
            renderCameraRows(camerasContainer, cameras)
            if (groups.isNotEmpty()) {
                muteStore.saveGroupCameras(groups)
                renderGroupRows(groupsContainer, groups)
                emptyGroups.visibility = View.GONE
            } else if (cachedGroups.isEmpty()) {
                emptyGroups.visibility = View.VISIBLE
            }
        }
    }

    private fun renderCameraRows(container: LinearLayout, cameras: List<String>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        cameras.sorted().forEach { name ->
            val row = inflater.inflate(R.layout.row_mute_group, container, false)
            row.findViewById<TextView>(R.id.row_group_name).text = displayName(name)
            // Individual cameras have no useful subtitle — the section header
            // already says "Cameras". Hide the secondary line entirely so the
            // row collapses to a single tidy label.
            row.findViewById<TextView>(R.id.row_group_cameras).visibility = View.GONE
            row.setOnClickListener { showDurationPicker(CameraMuteStore.Kind.CAMERA, name) }
            container.addView(row)
        }
    }

    private fun renderGroupRows(container: LinearLayout, groups: Map<String, List<String>>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        groups.toSortedMap().forEach { (name, cameras) ->
            val row = inflater.inflate(R.layout.row_mute_group, container, false)
            row.findViewById<TextView>(R.id.row_group_name).text = displayName(name)
            row.findViewById<TextView>(R.id.row_group_cameras).text =
                cameras.joinToString(", ") { displayName(it) }
            row.setOnClickListener { showDurationPicker(CameraMuteStore.Kind.GROUP, name) }
            container.addView(row)
        }
    }

    /** Modal duration picker for `kind:name`. Picking a slot arms the mute. */
    private fun showDurationPicker(kind: CameraMuteStore.Kind, name: String) {
        val labels = arrayOf(
            getString(R.string.mute_duration_15m),
            getString(R.string.mute_duration_30m),
            getString(R.string.mute_duration_1h),
            getString(R.string.mute_duration_2h),
            getString(R.string.mute_duration_4h),
            getString(R.string.mute_duration_8h),
        )
        val durations = longArrayOf(
            TimeUnit.MINUTES.toMillis(15),
            TimeUnit.MINUTES.toMillis(30),
            TimeUnit.HOURS.toMillis(1),
            TimeUnit.HOURS.toMillis(2),
            TimeUnit.HOURS.toMillis(4),
            TimeUnit.HOURS.toMillis(8),
        )
        FreegateDialogs.builder(requireContext())
            .setTitle(getString(R.string.mute_duration_title, displayName(name)))
            .setItems(labels) { _, which ->
                CameraMuteStore.getInstance(requireContext()).mute(kind, name, durations[which])
                notifyMutesChanged()
                view?.let { renderActiveMutes(it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Per-second tick to keep the active-mute countdowns moving. Stops itself
     * when the sheet's view is destroyed. Only notifies the host activity
     * when an entry has actually expired between ticks — invalidating the
     * toolbar menu every second was an unnecessary main-thread storm just to
     * keep a static icon static.
     */
    private fun startCountdownTicker(view: View) {
        countdownJob?.cancel()
        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            val store = CameraMuteStore.getInstance(requireContext())
            var lastSize = store.activeMutes().size
            while (true) {
                delay(1_000L)
                if (!isAdded || this@MuteGroupsBottomSheet.view == null) break
                renderActiveMutes(view)
                val nowSize = store.activeMutes().size
                if (nowSize != lastSize) {
                    notifyMutesChanged()
                    lastSize = nowSize
                }
            }
        }
    }

    private fun notifyMutesChanged() {
        (parentFragment as? OnMutesChanged)?.onMutesChanged()
        (activity as? OnMutesChanged)?.onMutesChanged()
    }

    private fun labelFor(key: CameraMuteStore.Key): String = when (key.kind) {
        CameraMuteStore.Kind.CAMERA -> displayName(key.name)
        CameraMuteStore.Kind.GROUP -> "${displayName(key.name)} (group)"
    }

    private fun resolveBaseUrl(): String? {
        val urls = NetworkUtils.getInstance(requireContext()).currentUrl.value?.trim()?.trimEnd('/')
        if (!urls.isNullOrBlank()) return urls
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return (prefs.getString("internal_url", null) ?: prefs.getString("external_url", null))
            ?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    }

    private fun displayName(raw: String): String =
        raw.replace('_', ' ').split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    private fun formatCountdown(remainingMs: Long): String {
        val totalSeconds = (remainingMs / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "%dh %02dm left".format(hours, minutes)
            minutes > 0 -> "%dm %02ds left".format(minutes, seconds)
            else -> "%ds left".format(seconds)
        }
    }

    /**
     * Lifecycle hook for the host activity/fragment to react to mute changes.
     */
    interface OnMutesChanged {
        fun onMutesChanged()
    }

    companion object {
        const val TAG = "MuteGroupsBottomSheet"
    }
}
