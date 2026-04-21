package com.asksakis.freegate.ui.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.asksakis.freegate.notifications.FrigateConfigFetcher
import com.asksakis.freegate.utils.FrigateNameFormatter
import com.asksakis.freegate.utils.NetworkUtils
import kotlinx.coroutines.launch

/**
 * Flat zone-picker screen. Each configured Frigate camera becomes a
 * [PreferenceCategory] header with one [SwitchPreferenceCompat] per zone below it.
 * Choices are stored as `"camera:zone"` strings in the `notify_zones` StringSet so
 * [com.asksakis.freegate.notifications.AlertFilter] can evaluate per-camera.
 */
class NotificationZonesFragment : PreferenceFragmentCompat() {

    private lateinit var networkUtils: NetworkUtils
    private lateinit var prefs: SharedPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        networkUtils = NetworkUtils.getInstance(requireContext())
        prefs = preferenceManager.sharedPreferences!!

        // Start with an empty screen; we populate it async once /api/config comes back.
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())

        val placeholder = Preference(requireContext()).apply {
            title = "Loading zones..."
            summary = "Fetching Frigate configuration"
            isSelectable = false
            key = "__loading__"
        }
        preferenceScreen.addPreference(placeholder)

        loadZones()
    }

    private fun loadZones() {
        val baseUrl = networkUtils.getUrl().trimEnd('/').takeIf { it.isNotBlank() }
        if (baseUrl == null) {
            showError("Set the Frigate URL first")
            return
        }

        lifecycleScope.launch {
            val enabledCameras = prefs.getStringSet("notify_cameras", emptySet()).orEmpty()

            val camerasWithZones = FrigateConfigFetcher(requireContext())
                .fetchCamerasWithZones(baseUrl)
                .filterValues { it.isNotEmpty() }
                // If the Cameras filter has explicit picks, respect it and only show those.
                // Empty picks means "all cameras", so don't filter at all.
                .let { all ->
                    if (enabledCameras.isEmpty()) all
                    else all.filterKeys { it in enabledCameras }
                }
                .toSortedMap()

            if (camerasWithZones.isEmpty()) {
                val msg = if (enabledCameras.isEmpty())
                    "No camera zones found. Configure zones in Frigate's config."
                else
                    "No zones to show. Enable more cameras in the Cameras filter."
                showError(msg)
                return@launch
            }

            renderScreen(camerasWithZones)
        }
    }

    private fun renderScreen(camerasWithZones: Map<String, List<String>>) {
        val ctx = requireContext()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        val saved = prefs.getStringSet("notify_zones", emptySet()).orEmpty().toMutableSet()

        for ((camera, zones) in camerasWithZones) {
            val category = PreferenceCategory(ctx).apply {
                title = FrigateNameFormatter.pretty(camera)
                isIconSpaceReserved = false
            }
            screen.addPreference(category)

            for (zone in zones) {
                val entry = "$camera:$zone"
                val switch = SwitchPreferenceCompat(ctx).apply {
                    key = "notify_zone:$entry"
                    title = FrigateNameFormatter.pretty(zone)
                    isChecked = entry in saved
                    isIconSpaceReserved = false
                    setOnPreferenceChangeListener { _, newValue ->
                        val checked = newValue as Boolean
                        val updated = prefs.getStringSet("notify_zones", emptySet())
                            .orEmpty().toMutableSet()
                        if (checked) updated += entry else updated -= entry
                        prefs.edit().putStringSet("notify_zones", updated).apply()
                        true
                    }
                }
                category.addPreference(switch)
            }
        }

        preferenceScreen = screen
    }

    private fun showError(message: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        val msg = Preference(ctx).apply {
            title = "Unable to load zones"
            summary = message
            isSelectable = false
        }
        screen.addPreference(msg)
        preferenceScreen = screen
    }

    @Suppress("UNUSED_PARAMETER")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }
}
