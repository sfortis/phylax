package com.asksakis.freegate.ui.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.asksakis.freegate.notifications.FrigateConfigFetcher
import com.asksakis.freegate.utils.FrigateNameFormatter
import com.asksakis.freegate.utils.NetworkUtils
import kotlinx.coroutines.launch

/**
 * Flat camera-filter screen. One [SwitchPreferenceCompat] per Frigate camera. Empty
 * selection still means "all cameras" (the AlertFilter pass-through), so switching
 * everything off doesn't accidentally silence notifications — we treat that identically
 * to the "no selection" default. When at least one is on, only those cameras pass.
 */
class NotificationCamerasFragment : PreferenceFragmentCompat() {

    private lateinit var networkUtils: NetworkUtils
    private lateinit var prefs: SharedPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        networkUtils = NetworkUtils.getInstance(requireContext())
        prefs = preferenceManager.sharedPreferences!!

        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())

        val placeholder = Preference(requireContext()).apply {
            title = "Loading cameras..."
            summary = "Fetching Frigate configuration"
            isSelectable = false
        }
        preferenceScreen.addPreference(placeholder)

        loadCameras()
    }

    private fun loadCameras() {
        val baseUrl = networkUtils.getUrl().trimEnd('/').takeIf { it.isNotBlank() }
        if (baseUrl == null) {
            showError("Set the Frigate URL first")
            return
        }

        lifecycleScope.launch {
            val cameras = FrigateConfigFetcher(requireContext())
                .fetchCameraNames(baseUrl)
            if (cameras.isEmpty()) {
                showError("Couldn't fetch cameras (check credentials and URL)")
                return@launch
            }
            renderScreen(cameras)
        }
    }

    private fun renderScreen(cameras: List<String>) {
        val ctx = requireContext()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        val selected = prefs.getStringSet("notify_cameras", emptySet()).orEmpty()

        val hint = Preference(ctx).apply {
            applyHintSummary(selected.size, cameras.size)
            isSelectable = false
            isIconSpaceReserved = false
        }
        screen.addPreference(hint)

        for (camera in cameras) {
            val switch = SwitchPreferenceCompat(ctx).apply {
                key = "notify_camera:$camera"
                title = FrigateNameFormatter.pretty(camera)
                isChecked = camera in selected
                isIconSpaceReserved = false
                setOnPreferenceChangeListener { _, newValue ->
                    val checked = newValue as Boolean
                    val updated = prefs.getStringSet("notify_cameras", emptySet())
                        .orEmpty().toMutableSet()
                    if (checked) updated += camera else updated -= camera
                    prefs.edit().putStringSet("notify_cameras", updated).apply()
                    hint.applyHintSummary(updated.size, cameras.size)
                    true
                }
            }
            screen.addPreference(switch)
        }

        preferenceScreen = screen
    }

    /**
     * Consistent title + summary for the filter-state hint row. The two lines used to
     * drift — title said "All cameras selected" even when only a subset was on, while
     * the summary reported the correct count. Collapse to a single source of truth.
     */
    private fun Preference.applyHintSummary(selectedCount: Int, total: Int) {
        if (selectedCount == 0) {
            title = "All cameras"
            summary = "No filter — every camera triggers notifications."
        } else {
            title = "$selectedCount of $total selected"
            summary = "Only the cameras below will trigger notifications."
        }
    }

    private fun showError(message: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        val msg = Preference(ctx).apply {
            title = "Unable to load cameras"
            summary = message
            isSelectable = false
        }
        screen.addPreference(msg)
        preferenceScreen = screen
    }
}
