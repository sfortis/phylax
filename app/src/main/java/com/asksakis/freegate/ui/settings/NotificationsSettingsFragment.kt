package com.asksakis.freegate.ui.settings

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.asksakis.freegate.R
import com.asksakis.freegate.auth.CredentialsStore
import com.asksakis.freegate.notifications.BatteryOptHelper
import com.asksakis.freegate.notifications.FrigateAlertService
import com.asksakis.freegate.notifications.FrigateConfigFetcher
import com.asksakis.freegate.utils.NetworkUtils
import kotlinx.coroutines.launch

/**
 * Notification listener toggle + filters + behavior + reliability (battery opt).
 */
class NotificationsSettingsFragment : PreferenceFragmentCompat() {

    private lateinit var networkUtils: NetworkUtils

    /**
     * Held as a field so the SharedPreferences weak-ref registry doesn't GC it while the
     * fragment is alive. Restarts the listener service whenever a live filter changes.
     */
    private val notificationPrefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key in liveNotificationKeys) {
                FrigateAlertService.updateForContext(requireContext())
            }
            if (key == "notifications_enabled" &&
                prefs.getBoolean("notifications_enabled", false) &&
                !prefs.getBoolean("battery_opt_prompted", false)
            ) {
                maybePromptBatteryOptimization(autoPrompt = true)
            }
        }

    private val liveNotificationKeys = setOf(
        "notifications_enabled",
        CredentialsStore.PREF_USERNAME,
        "notify_alerts",
        "notify_detections",
        "notify_cameras",
        "notify_zones",
        "notify_tap_action",
    )

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_notifications, rootKey)
        networkUtils = NetworkUtils.getInstance(requireContext())

        preferenceManager.sharedPreferences
            ?.registerOnSharedPreferenceChangeListener(notificationPrefsListener)

        setupBatteryOptimizationPreference()
        setupCameraFilterPreference()
        setupZoneFilterPreference()
    }

    override fun onResume() {
        super.onResume()
        // Child pickers (Cameras / Zones) write prefs directly — when the user backs
        // out, rebind the summaries here so the parent screen isn't stale.
        refreshFilterSummaries()
    }

    private fun refreshFilterSummaries() {
        val prefs = preferenceManager.sharedPreferences ?: return
        findPreference<Preference>("notify_cameras")?.apply {
            val selected = prefs.getStringSet("notify_cameras", emptySet()).orEmpty()
            summary = if (selected.isEmpty()) "All cameras" else selected.sorted().joinToString(", ")
        }
        findPreference<Preference>("notify_zones")?.apply {
            val selected = prefs.getStringSet("notify_zones", emptySet()).orEmpty()
            summary = if (selected.isEmpty()) "All zones" else selected.sorted().joinToString(", ")
        }
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences
            ?.unregisterOnSharedPreferenceChangeListener(notificationPrefsListener)
        super.onDestroy()
    }

    private fun setupBatteryOptimizationPreference() {
        val pref = findPreference<Preference>("battery_optimization") ?: return
        fun refresh() {
            pref.summary = if (BatteryOptHelper.isIgnoringOptimizations(requireContext())) {
                "Exempted — the listener can run in the background."
            } else {
                "Battery optimization is ON — the listener may be killed. Tap to exempt."
            }
        }
        refresh()
        pref.setOnPreferenceClickListener {
            maybePromptBatteryOptimization(autoPrompt = false)
            view?.postDelayed({ refresh() }, 1_500)
            true
        }
    }

    private fun maybePromptBatteryOptimization(autoPrompt: Boolean) {
        val ctx = requireContext()
        if (BatteryOptHelper.isIgnoringOptimizations(ctx)) {
            preferenceManager.sharedPreferences?.edit()
                ?.putBoolean("battery_opt_prompted", true)?.apply()
            return
        }

        com.asksakis.freegate.ui.FreegateDialogs.builder(ctx)
            .setTitle("Keep notifications reliable")
            .setMessage(
                if (autoPrompt)
                    "Android may silently kill the background listener after a while. " +
                        "Allow Frigate Viewer to bypass battery optimization so alerts arrive " +
                        "reliably?"
                else
                    "Allow Frigate Viewer to bypass battery optimization?"
            )
            .setPositiveButton("Allow") { _, _ ->
                BatteryOptHelper.requestIgnore(ctx)
                preferenceManager.sharedPreferences?.edit()
                    ?.putBoolean("battery_opt_prompted", true)?.apply()
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun setupZoneFilterPreference() {
        val pref = findPreference<Preference>("notify_zones") ?: return

        fun refreshSummary() {
            val selected = preferenceManager.sharedPreferences
                ?.getStringSet("notify_zones", emptySet()).orEmpty()
            pref.summary = if (selected.isEmpty()) {
                "All zones"
            } else {
                selected.sorted().joinToString(", ")
            }
        }
        refreshSummary()

        pref.setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_notifications_to_zones)
            true
        }
    }

    private fun setupCameraFilterPreference() {
        val pref = findPreference<Preference>("notify_cameras") ?: return

        fun refreshSummary() {
            val selected = preferenceManager.sharedPreferences
                ?.getStringSet("notify_cameras", emptySet()).orEmpty()
            pref.summary = if (selected.isEmpty()) "All cameras" else selected.sorted().joinToString(", ")
        }
        refreshSummary()

        pref.setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_notifications_to_cameras)
            true
        }
    }
}
