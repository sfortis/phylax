package com.asksakis.freegate.ui.settings

import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.asksakis.freegate.R
import com.asksakis.freegate.auth.CredentialsStore
import com.asksakis.freegate.notifications.AlarmSoundPlayer
import com.asksakis.freegate.notifications.BatteryOptHelper
import com.asksakis.freegate.notifications.BundledTonesInstaller
import com.asksakis.freegate.notifications.DetectionSoundPlayer
import com.asksakis.freegate.notifications.FrigateAlertService
import com.asksakis.freegate.notifications.FrigateConfigFetcher
import com.asksakis.freegate.notifications.FrigateNotifier
import kotlinx.coroutines.Dispatchers
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
            // Any time the user shifts what should reach them (cameras / zones / which
            // severities, enable toggle), reset the cutoff so trackers from *before*
            // the change are filtered out — otherwise the user gets a retroactive
            // notification for an object Frigate has been tracking for hours.
            if (key in configChangeKeys) {
                FrigateAlertService.markListeningSince(requireContext())
            }
            if (key == "notifications_enabled" &&
                prefs.getBoolean("notifications_enabled", false)
            ) {
                if (!prefs.getBoolean("battery_opt_prompted", false)) {
                    maybePromptBatteryOptimization(autoPrompt = true)
                }
                // DND access is independent of battery optimisation: prompt
                // unconditionally on first enable so the alarm-stream override
                // can actually take effect.
                maybePromptDndAccess(autoPrompt = true)
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

    /**
     * Subset of [liveNotificationKeys] that affects *which* events should reach the
     * user. Touching any of them resets [FrigateAlertService.PREF_LISTENING_SINCE_MS]
     * so older trackers don't notify retroactively.
     */
    private val configChangeKeys = setOf(
        "notifications_enabled",
        "notify_alerts",
        "notify_detections",
        "notify_cameras",
        "notify_zones",
    )

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_notifications, rootKey)
        networkUtils = NetworkUtils.getInstance(requireContext())

        preferenceManager.sharedPreferences
            ?.registerOnSharedPreferenceChangeListener(notificationPrefsListener)

        setupDndBypassPreference()
        setupBatteryOptimizationPreference()
        setupOemBackgroundPreference()
        setupLastAlertPreference()
        setupDiagnosticsPreference()
        setupCameraFilterPreference()
        setupZoneFilterPreference()
        setupSoundPreferences()
    }

    override fun onResume() {
        super.onResume()
        // Child pickers (Cameras / Zones) write prefs directly — when the user backs
        // out, rebind the summaries here so the parent screen isn't stale.
        refreshFilterSummaries()
        refreshLastAlertSummary()
        refreshDndBypassSummary()
    }

    private fun refreshFilterSummaries() {
        val prefs = preferenceManager.sharedPreferences ?: return
        findPreference<Preference>("notify_cameras")?.apply {
            val selected = prefs.getStringSet("notify_cameras", emptySet()).orEmpty()
            val total = prefs.getInt("notify_cameras_total", 0)
            summary = filterSummary("camera", selected, total)
        }
        findPreference<Preference>("notify_zones")?.apply {
            val selected = prefs.getStringSet("notify_zones", emptySet()).orEmpty()
            val total = prefs.getInt("notify_zones_total", 0)
            summary = filterSummary("zone", selected, total)
        }
    }

    /**
     * Three observable states, mirroring the picker:
     *  - empty after the user opened the picker (`total > 0`) → "No …" (filter
     *    is muting everything; matches AlertFilter strict-empty behaviour).
     *  - selection covers everything that exists → "All …" (filter inactive).
     *  - anything in between → the explicit picks, joined.
     *
     * `total == 0` means the user has never opened the picker; we keep the
     * legacy "no filter" wording ("All …") so upgraded users aren't surprised.
     */
    private fun filterSummary(kind: String, selected: Set<String>, total: Int): String {
        val pickerOpened = total > 0
        if (selected.isEmpty()) {
            return if (pickerOpened) "No ${kind}s" else "All ${kind}s"
        }
        if (pickerOpened && selected.size >= total) return "All ${kind}s"
        return selected.sorted().joinToString(", ")
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences
            ?.unregisterOnSharedPreferenceChangeListener(notificationPrefsListener)
        super.onDestroy()
    }

    /**
     * "Do Not Disturb access" is granted via a separate system screen from
     * POST_NOTIFICATIONS. Without it, the alert channel's setBypassDnd(true) is
     * silently dropped. Surface a dedicated row that reflects the live grant state
     * and links to the right settings page.
     */
    private fun setupDndBypassPreference() {
        val pref = findPreference<Preference>("dnd_bypass") ?: return
        pref.setOnPreferenceClickListener {
            openDndAccessSettings()
            true
        }
        refreshDndBypassSummary()
    }

    /**
     * Auto-prompt path triggered the first time the user enables notifications
     * (or whenever they tap the dedicated DND row). Skips silently if access is
     * already granted or if the user has dismissed the auto-prompt before.
     */
    private fun maybePromptDndAccess(autoPrompt: Boolean) {
        val ctx = requireContext()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) return
        val prefs = preferenceManager.sharedPreferences
        if (autoPrompt && prefs?.getBoolean("dnd_prompted", false) == true) return

        com.asksakis.freegate.ui.FreegateDialogs.builder(ctx)
            .setTitle("Override Do Not Disturb")
            .setMessage(
                if (autoPrompt)
                    "Phylax can ring alerts at alarm volume even while Do Not " +
                        "Disturb is on, so you don't miss a real event overnight. " +
                        "Grant Do Not Disturb access?"
                else
                    "Grant Phylax permission to override Do Not Disturb so alerts " +
                        "ring at alarm volume?"
            )
            .setPositiveButton("Open settings") { _, _ ->
                prefs?.edit()?.putBoolean("dnd_prompted", true)?.apply()
                openDndAccessSettings()
            }
            .setNegativeButton("Not now") { _, _ ->
                prefs?.edit()?.putBoolean("dnd_prompted", true)?.apply()
            }
            .show()
    }

    private fun openDndAccessSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            .onFailure {
                Toast.makeText(
                    requireContext(),
                    "Unable to open DND access settings",
                    Toast.LENGTH_SHORT,
                ).show()
            }
    }

    private fun refreshDndBypassSummary() {
        val pref = findPreference<Preference>("dnd_bypass") ?: return
        val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        pref.summary = if (nm.isNotificationPolicyAccessGranted) {
            "Granted — alerts ring at alarm volume even while DND is on."
        } else {
            "Tap to grant Do Not Disturb access so alerts can override DND."
        }
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
        refreshFilterSummaries()
        pref.setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_notifications_to_zones)
            true
        }
    }

    private fun setupCameraFilterPreference() {
        val pref = findPreference<Preference>("notify_cameras") ?: return
        refreshFilterSummaries()
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

    /**
     * Both sound rows open the same in-app `ACTION_RINGTONE_PICKER` and store
     * the user's pick in a per-kind SharedPreferences key. Neither relies on
     * the NotificationChannel sound path: alerts route through [AlarmSoundPlayer]
     * (STREAM_ALARM, survives Samsung's vibrate-mode silencing) and detections
     * route through [DetectionSoundPlayer] (STREAM_NOTIFICATION, honours DND).
     * Channel-level `sound` is `null` for both, so a system picker would have
     * nothing to bind to — the in-app picker is the only surface the user has
     * to choose "Silent / Default / custom tone" without losing the playback
     * path each player owns.
     *
     * Side-effect: register the bundled CC0 tones in MediaStore so they show
     * up by name ("Phylax Alert", "Phylax Chime") in either picker.
     */
    private fun setupSoundPreferences() {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            BundledTonesInstaller.installIfNeeded(ctx)
        }
        findPreference<Preference>("notify_alert_sound")?.setOnPreferenceClickListener {
            launchSoundPicker(SoundKind.ALERT)
            true
        }
        findPreference<Preference>("notify_detection_sound")?.setOnPreferenceClickListener {
            launchSoundPicker(SoundKind.DETECTION)
            true
        }
        refreshSoundSummaries()
    }

    /**
     * Both sound rows share the picker flow: launch [android.media.RingtoneManager.ACTION_RINGTONE_PICKER]
     * pre-selected on the user's current choice (or the bundled Phylax tone as
     * default), save the URI back to the relevant preference, and refresh the
     * summary. The kind decides which pref + which bundled tone to default to.
     */
    private enum class SoundKind(
        val prefKey: String,
        val sentinel: String,
        val defaultFileName: String,
        val pickerTitle: String,
    ) {
        ALERT(
            AlarmSoundPlayer.PREF_ALERT_SOUND_URI,
            AlarmSoundPlayer.SILENT_SENTINEL,
            BundledTonesInstaller.ALERT_TONE_FILENAME,
            "Alert sound",
        ),
        DETECTION(
            DetectionSoundPlayer.PREF_DETECTION_SOUND_URI,
            DetectionSoundPlayer.SILENT_SENTINEL,
            BundledTonesInstaller.CHIME_TONE_FILENAME,
            "Detection sound",
        ),
    }

    private var pickerKind: SoundKind? = null

    private val soundPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val kind = pickerKind ?: return@registerForActivityResult
        pickerKind = null
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val picked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.data?.getParcelableExtra(
                android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                android.net.Uri::class.java,
            )
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        // null URI means the user picked "None / Silent" in the picker.
        val storedValue = picked?.toString() ?: kind.sentinel
        preferenceManager.sharedPreferences
            ?.edit()
            ?.putString(kind.prefKey, storedValue)
            ?.apply()
        refreshSoundSummaries()
    }

    private fun launchSoundPicker(kind: SoundKind) {
        // Pre-select either the saved pick or the bundled Phylax tone so the
        // picker opens with a clear "this is the default" highlight instead of
        // an unselected list. resolveToneUri may return null if BundledTonesInstaller
        // hasn't installed the MediaStore entry yet — fine, picker just opens
        // unselected in that case.
        val current = readSoundUri(kind)
            ?: BundledTonesInstaller.resolveToneUri(requireContext(), kind.defaultFileName)
        pickerKind = kind
        val intent = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(
                android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                android.media.RingtoneManager.TYPE_NOTIFICATION,
            )
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, kind.pickerTitle)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        }
        runCatching { soundPicker.launch(intent) }.onFailure {
            Toast.makeText(requireContext(), "Couldn't open ringtone picker", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Returns the saved URI for this sound, or null when the user has never picked
     * one (default state) or has explicitly picked Silent. Callers that need to
     * distinguish those two should check [readRawSoundChoice].
     */
    private fun readSoundUri(kind: SoundKind): android.net.Uri? {
        val raw = readRawSoundChoice(kind)
        return when {
            raw == null -> null
            raw == kind.sentinel -> null
            else -> runCatching { android.net.Uri.parse(raw) }.getOrNull()
        }
    }

    private fun readRawSoundChoice(kind: SoundKind): String? =
        preferenceManager.sharedPreferences?.getString(kind.prefKey, null)

    private fun refreshSoundSummaries() {
        SoundKind.values().forEach { kind ->
            val pref = findPreference<Preference>(
                when (kind) {
                    SoundKind.ALERT -> "notify_alert_sound"
                    SoundKind.DETECTION -> "notify_detection_sound"
                },
            ) ?: return@forEach
            val raw = readRawSoundChoice(kind)
            pref.summary = when {
                raw == null -> when (kind) {
                    SoundKind.ALERT -> "Phylax Alert (default)"
                    SoundKind.DETECTION -> "Phylax Chime (default)"
                }
                raw == kind.sentinel -> "Silent"
                else -> runCatching {
                    android.media.RingtoneManager.getRingtone(requireContext(), android.net.Uri.parse(raw))
                        ?.getTitle(requireContext()).orEmpty()
                }.getOrDefault("").ifEmpty { "Custom sound" }
            }
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
