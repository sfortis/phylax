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
import com.asksakis.freegate.notifications.OemSettingsIntents
import com.asksakis.freegate.notifications.ServiceLifecycleLog
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
        setupOemBackgroundPreference()
        setupLastAlertPreference()
        setupDiagnosticsPreference()
        setupCameraFilterPreference()
        setupZoneFilterPreference()
    }

    override fun onResume() {
        super.onResume()
        // Child pickers (Cameras / Zones) write prefs directly — when the user backs
        // out, rebind the summaries here so the parent screen isn't stale.
        refreshFilterSummaries()
        refreshLastAlertSummary()
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
            // Still surface the OEM tip on first-enable devices — battery exemption
            // alone isn't enough on Samsung / MIUI / ColorOS.
            if (autoPrompt && OemSettingsIntents.hasCustomBackgroundSettings()) {
                showOemReliabilityPrompt(ctx)
            }
            return
        }

        com.asksakis.freegate.ui.FreegateDialogs.builder(ctx)
            .setTitle("Keep notifications reliable")
            .setMessage(
                if (autoPrompt)
                    "Android may silently kill the background listener after a while. " +
                        "Allow Phylax to bypass battery optimization so alerts arrive " +
                        "reliably?"
                else
                    "Allow Phylax to bypass battery optimization?"
            )
            .setPositiveButton("Allow") { _, _ ->
                BatteryOptHelper.requestIgnore(ctx)
                preferenceManager.sharedPreferences?.edit()
                    ?.putBoolean("battery_opt_prompted", true)?.apply()
                // Chain to the OEM prompt — on Samsung et al, battery-opt exemption is
                // only half the story.
                if (OemSettingsIntents.hasCustomBackgroundSettings()) {
                    showOemReliabilityPrompt(ctx)
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showOemReliabilityPrompt(ctx: android.content.Context) {
        val oem = OemSettingsIntents.current()
        com.asksakis.freegate.ui.FreegateDialogs.builder(ctx)
            .setTitle("One more step on ${oem.displayName}")
            .setMessage(OemSettingsIntents.instructionsFor(oem))
            .setPositiveButton("Open settings") { _, _ ->
                OemSettingsIntents.openBackgroundRestrictions(ctx)
            }
            .setNegativeButton("Later", null)
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

    /**
     * OEM-specific background-restrictions deep link. Only shown on devices that have a
     * known custom settings screen (Samsung, Xiaomi, OPPO, Huawei, Vivo). On stock Android
     * the pref is hidden — the battery-optimization exemption above is enough.
     */
    private fun setupOemBackgroundPreference() {
        val pref = findPreference<Preference>("oem_background_restrictions") ?: return
        if (!OemSettingsIntents.hasCustomBackgroundSettings()) {
            pref.isVisible = false
            return
        }
        val oem = OemSettingsIntents.current()
        pref.summary = OemSettingsIntents.instructionsFor(oem)
        pref.setOnPreferenceClickListener {
            OemSettingsIntents.openBackgroundRestrictions(requireContext())
            true
        }
    }

    private fun setupLastAlertPreference() {
        refreshLastAlertSummary()
    }

    private fun setupDiagnosticsPreference() {
        val pref = findPreference<Preference>("service_diagnostics") ?: return
        pref.setOnPreferenceClickListener {
            showDiagnosticsDialog()
            true
        }
    }

    private fun showDiagnosticsDialog() {
        val prefs = preferenceManager.sharedPreferences ?: return
        val now = System.currentTimeMillis()
        fun line(label: String, key: String): String {
            val ts = prefs.getLong(key, 0L)
            val v = if (ts <= 0L) "never" else "${formatRelativeAge(now - ts)} ago"
            return "$label: $v"
        }
        val body = buildString {
            appendLine(line("Service started", ServiceLifecycleLog.PREF_SERVICE_STARTED_MS))
            appendLine(line("Previous start", ServiceLifecycleLog.PREF_SERVICE_PREV_STARTED_MS))
            appendLine(line("Service destroyed", ServiceLifecycleLog.PREF_SERVICE_DESTROYED_MS))
            appendLine(line("WS connected", ServiceLifecycleLog.PREF_WS_CONNECTED_MS))
            appendLine(line("WS disconnected", ServiceLifecycleLog.PREF_WS_DISCONNECTED_MS))
            appendLine(line("Last alert delivered", FrigateAlertService.PREF_LAST_ALERT_MS))
        }
        com.asksakis.freegate.ui.FreegateDialogs.builder(requireContext())
            .setTitle("Service diagnostics")
            .setMessage(body)
            .setPositiveButton("Close", null)
            .show()
    }

    /**
     * Displays relative time since the last notification was actually delivered. This is
     * the most reliable indicator a user has that background policies killed the service:
     * if this says "3 days ago" but cameras have been triggering, something's wrong.
     */
    private fun refreshLastAlertSummary() {
        val pref = findPreference<Preference>("last_alert_received") ?: return
        val ts = preferenceManager.sharedPreferences
            ?.getLong(FrigateAlertService.PREF_LAST_ALERT_MS, 0L) ?: 0L
        if (ts <= 0L) {
            pref.summary = "No alerts received yet."
            return
        }
        val ageMs = System.currentTimeMillis() - ts
        val relative = formatRelativeAge(ageMs)
        val stale = ageMs > STALE_ALERT_THRESHOLD_MS
        pref.summary = if (stale) {
            "$relative ago. If this is unexpected, check the Background restrictions row."
        } else {
            "$relative ago."
        }
    }

    private fun formatRelativeAge(ageMs: Long): String {
        val sec = ageMs / 1000
        val min = sec / 60
        val hr = min / 60
        val day = hr / 24
        return when {
            day > 0 -> "${day}d"
            hr > 0 -> "${hr}h"
            min > 0 -> "${min}m"
            else -> "${sec}s"
        }
    }

    private companion object {
        // If we haven't surfaced an alert in 24h, flag the row as potentially stale. Keeps
        // false-positives low (many homes do have ~no alerts overnight).
        const val STALE_ALERT_THRESHOLD_MS = 24L * 60 * 60 * 1000
    }
}
