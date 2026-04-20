package com.asksakis.freegate.ui.settings

import android.app.AlertDialog
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.util.Log
import androidx.preference.EditTextPreference
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.asksakis.freegate.R
import androidx.navigation.fragment.findNavController
import com.asksakis.freegate.auth.CredentialsStore
import com.asksakis.freegate.utils.NetworkUtils
import com.asksakis.freegate.utils.WifiNetworkManager
import com.asksakis.freegate.utils.UpdateChecker
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.ProgressBar
// NetworkFixer has been consolidated into NetworkUtils

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var wifiNetworkManager: WifiNetworkManager
    private lateinit var networkUtils: NetworkUtils

    /** Stack of nested PreferenceScreens for manual drill-down navigation. */
    private val screenStack = ArrayDeque<androidx.preference.PreferenceScreen>()

    /**
     * Held as a field so the SharedPreferences weak-ref registry doesn't GC it while the
     * fragment is alive.
     */
    private val notificationPrefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key in liveNotificationKeys) {
                com.asksakis.freegate.notifications.FrigateAlertService
                    .updateForContext(requireContext())
            }
            if (key == "strict_tls_external") {
                // TLS policy changed → rebuild cached clients with the new posture.
                com.asksakis.freegate.utils.OkHttpClientFactory.invalidate()
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
        "notify_tap_action",
    )

    override fun onPreferenceTreeClick(preference: androidx.preference.Preference): Boolean {
        if (preference is androidx.preference.PreferenceScreen) {
            screenStack.addLast(preferenceScreen)
            preferenceScreen = preference
            (activity as? AppCompatActivity)?.supportActionBar?.title = preference.title
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screenStack.isNotEmpty()) {
                    preferenceScreen = screenStack.removeLast()
                    (activity as? AppCompatActivity)?.supportActionBar?.title =
                        if (screenStack.isEmpty()) "Settings" else preferenceScreen.title
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        
        // Initialize network managers
        wifiNetworkManager = WifiNetworkManager(requireContext())
        networkUtils = NetworkUtils.getInstance(requireContext())
        
        // Setup connection mode preference
        setupConnectionModePreference()
        
        // Setup home WiFi networks preference
        setupHomeWifiNetworksPreference()
        
        // Disable autocorrect for all EditTextPreferences
        disableAutocorrectForAllInputs()
        
        // Update WiFi status
        updateWifiStatus()
        
        // Observer is registered in onViewCreated against the view lifecycle.
        
        // Setup advanced preferences
        setupAdvancedPreferences()

        // Wire the Frigate password preference to the encrypted credentials store so
        // the secret never lands in the regular SharedPreferences file.
        setupFrigatePasswordPreference()

        // React to notification-related preference changes by restarting the listener service.
        setupNotificationPreferences()

        // Surface the saved mTLS client cert alias and let the user change or clear it.
        setupClientCertPreference()

        // Dynamic camera multi-select backed by Frigate's /api/config.
        setupCameraFilterPreference()
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
            val prefs = preferenceManager.sharedPreferences ?: return@setOnPreferenceClickListener true
            val baseUrl = resolveFrigateBaseUrl()
            if (baseUrl == null) {
                Toast.makeText(requireContext(), "Set the Frigate URL first", Toast.LENGTH_SHORT).show()
                return@setOnPreferenceClickListener true
            }

            val progress = AlertDialog.Builder(requireContext())
                .setTitle("Cameras")
                .setMessage("Fetching camera list...")
                .setCancelable(false)
                .show()

            lifecycleScope.launch {
                val names = com.asksakis.freegate.notifications.FrigateConfigFetcher(requireContext())
                    .fetchCameraNames(baseUrl)
                progress.dismiss()

                if (names.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        "Couldn't fetch cameras (check credentials and URL)",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }

                val selected = prefs.getStringSet("notify_cameras", emptySet()).orEmpty().toMutableSet()
                val checked = BooleanArray(names.size) { names[it] in selected }
                AlertDialog.Builder(requireContext())
                    .setTitle("Cameras")
                    .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked ->
                        if (isChecked) selected += names[which] else selected -= names[which]
                    }
                    .setPositiveButton("Save") { _, _ ->
                        prefs.edit().putStringSet("notify_cameras", selected).apply()
                        refreshSummary()
                    }
                    .setNeutralButton("All") { _, _ ->
                        prefs.edit().putStringSet("notify_cameras", emptySet()).apply()
                        refreshSummary()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }
    }

    private fun resolveFrigateBaseUrl(): String? {
        // Honour connection_mode / network state — the NetworkUtils resolver is the
        // single source of truth the app and notification service already use.
        return networkUtils.getUrl().trimEnd('/').takeIf { it.isNotBlank() }
    }

    private fun setupClientCertPreference() {
        val pref = findPreference<Preference>("client_cert_alias") ?: return
        val certManager = com.asksakis.freegate.utils.ClientCertManager.getInstance(requireContext())

        fun refreshSummary() {
            val alias = certManager.getSavedAlias()
            pref.summary = alias ?: "Not selected"
        }
        refreshSummary()

        pref.setOnPreferenceClickListener {
            val current = certManager.getSavedAlias()
            val options = if (current == null) {
                arrayOf("Select certificate")
            } else {
                arrayOf("Replace certificate", "Clear")
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Client certificate")
                .setItems(options) { _, which ->
                    when (options[which]) {
                        "Select certificate", "Replace certificate" -> {
                            certManager.promptForCertificate(requireActivity(), request = null) { alias ->
                                if (alias != null) {
                                    Log.i("SettingsFragment", "User picked cert alias: $alias")
                                }
                                requireActivity().runOnUiThread { refreshSummary() }
                            }
                        }
                        "Clear" -> {
                            certManager.clearAlias()
                            Toast.makeText(requireContext(), "Certificate cleared", Toast.LENGTH_SHORT).show()
                            refreshSummary()
                        }
                    }
                }
                .show()
            true
        }
    }

    private fun setupNotificationPreferences() {
        // The listener itself is a field (notificationPrefsListener) so Android's
        // weak-ref-only registry keeps hold of it for the fragment's lifetime.
        preferenceManager.sharedPreferences
            ?.registerOnSharedPreferenceChangeListener(notificationPrefsListener)
        setupBatteryOptimizationPreference()
    }

    private fun setupBatteryOptimizationPreference() {
        val pref = findPreference<Preference>("battery_optimization") ?: return
        fun refresh() {
            pref.summary = if (com.asksakis.freegate.notifications.BatteryOptHelper
                    .isIgnoringOptimizations(requireContext())
            ) {
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
        if (com.asksakis.freegate.notifications.BatteryOptHelper.isIgnoringOptimizations(ctx)) {
            preferenceManager.sharedPreferences?.edit()
                ?.putBoolean("battery_opt_prompted", true)?.apply()
            return
        }

        AlertDialog.Builder(ctx)
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
                com.asksakis.freegate.notifications.BatteryOptHelper.requestIgnore(ctx)
                preferenceManager.sharedPreferences?.edit()
                    ?.putBoolean("battery_opt_prompted", true)?.apply()
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun setupFrigatePasswordPreference() {
        val pref = findPreference<EditTextPreference>(CredentialsStore.PREF_PASSWORD) ?: return
        val store = CredentialsStore.getInstance(requireContext())

        pref.preferenceDataStore = object : androidx.preference.PreferenceDataStore() {
            override fun getString(key: String, defValue: String?): String? =
                if (key == CredentialsStore.PREF_PASSWORD) store.getPassword() ?: defValue else defValue
            override fun putString(key: String, value: String?) {
                if (key == CredentialsStore.PREF_PASSWORD) store.setPassword(value)
            }
        }
        pref.setOnBindEditTextListener { editText ->
            editText.setText(store.getPassword().orEmpty())
        }
        pref.summaryProvider = androidx.preference.Preference.SummaryProvider<EditTextPreference> {
            if (store.getPassword().isNullOrEmpty()) "Not set" else "••••••••"
        }
    }
    
    /**
     * Sets up the connection mode preference
     */
    private fun setupConnectionModePreference() {
        val connModePref = findPreference<androidx.preference.ListPreference>("connection_mode")
        
        // Set summary to show current selection
        connModePref?.summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
        
        connModePref?.setOnPreferenceChangeListener { _, newValue ->
            val toast = when (newValue.toString()) {
                "auto" -> "Auto mode: URL depends on WiFi network"
                "internal" -> "Always using Internal URL"
                "external" -> "Always using External URL"
                else -> null
            }
            toast?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }

            // Defer so SharedPreferences has persisted the new value before we read it back.
            view?.post {
                applyConnectionModeVisibility()
                updateWifiStatus()
                networkUtils.forceRefresh()
            }
            true
        }
    }
    
    private fun setupHomeWifiNetworksPreference() {
        val networksPref = findPreference<MultiSelectListPreference>("home_wifi_networks")
        val addPref = findPreference<Preference>("add_home_network")

        fun populateEntries() {
            val saved = getHomeNetworks()
            val currentSsid = getActiveWifiSsid()
                ?.takeUnless { it in setOf("Current WiFi", "Unknown WiFi", "<unknown ssid>") }
            val entries = (saved + listOfNotNull(currentSsid)).distinct().sorted()
            networksPref?.entries = entries.toTypedArray()
            networksPref?.entryValues = entries.toTypedArray()
            networksPref?.summary = if (saved.isEmpty()) {
                "No networks configured"
            } else {
                saved.sorted().joinToString(", ")
            }
        }

        // Keep summary in sync after the user edits the selection.
        networksPref?.setOnPreferenceChangeListener { _, _ ->
            view?.post { populateEntries(); networkUtils.forceRefresh(); updateWifiStatus() }
            true
        }

        addPref?.setOnPreferenceClickListener {
            val input = EditText(requireContext()).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                hint = "WiFi SSID"
                getActiveWifiSsid()
                    ?.takeUnless { it in setOf("Current WiFi", "Unknown WiFi", "<unknown ssid>") }
                    ?.let { setText(it); selectAll() }
            }
            val container = LinearLayout(requireContext()).apply {
                setPadding(48, 24, 48, 0)
                addView(input)
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Add home network")
                .setView(container)
                .setPositiveButton("Add") { _, _ ->
                    val ssid = input.text.toString().trim().removeSurrounding("\"")
                    if (ssid.isEmpty()) return@setPositiveButton
                    val updated = getHomeNetworks().toMutableSet().apply { add(ssid) }
                    saveHomeNetworks(updated)
                    populateEntries()
                    updateWifiStatus()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        applyConnectionModeVisibility()
        populateEntries()
    }

    private fun applyConnectionModeVisibility() {
        val auto = (preferenceManager.sharedPreferences?.getString("connection_mode", "auto")
            ?: "auto") == "auto"
        findPreference<MultiSelectListPreference>("home_wifi_networks")?.isVisible = auto
        findPreference<Preference>("add_home_network")?.isVisible = auto
    }
    
    /**
     * Updates the WiFi status in the preference summary with simple network information
     * Takes into account the connection mode setting
     */
    private fun updateWifiStatus() {
        val wifiStatusPref = findPreference<Preference>("current_wifi_status")
        if (wifiStatusPref == null) return
        
        // Get connection mode
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
        
        // If not in auto mode, show simple status
        if (connectionMode != "auto") {
            when (connectionMode) {
                "internal" -> {
                    wifiStatusPref.summary = "Always using Internal URL\n(Connection mode: Forced Internal)"
                }
                "external" -> {
                    wifiStatusPref.summary = "Always using External URL\n(Connection mode: Forced External)"
                }
            }
            return
        }
        
        // In auto mode, check current WiFi status
        try {
            // First check if WiFi is enabled at all
            val wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            
            // Directly check if we're connected to a WiFi network
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            val isConnectedToWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            
            var currentSsid = getActiveWifiSsid()

            // Don't surface the literal "Current WiFi" / "<unknown ssid>" placeholders.
            if (currentSsid == "Current WiFi" || currentSsid == "<unknown ssid>") {
                currentSsid = getHomeNetworks().firstOrNull()
            }
            
            // Show appropriate status
            when {
                !wifiManager.isWifiEnabled -> {
                    wifiStatusPref.summary = "WiFi is disabled\nUsing: External URL"
                }
                !isConnectedToWifi -> {
                    wifiStatusPref.summary = "WiFi is enabled but not connected\nUsing: External URL"
                }
                currentSsid != null -> {
                    val isHome = isHomeNetwork(currentSsid)
                    val status = if (isHome) "HOME" else "EXTERNAL"
                    val urlUsed = if (isHome) "Internal URL" else "External URL"
                    
                    // Show the network name
                    wifiStatusPref.summary = "Connected to: $currentSsid\nNetwork type: $status\nUsing: $urlUsed"
                }
                else -> {
                    // Must NEVER show "Current WiFi" here - use a helpful default
                    wifiStatusPref.summary = "Connected to WiFi\nUsing: Internal URL (Default)\n\nTry setting Connection Mode to 'Internal' for best results"
                }
            }
        } catch (e: Exception) {
            wifiStatusPref.summary = "Error checking network status\nUsing: Internal URL\n\nTry setting Connection Mode to 'Internal'"
            Log.e("SettingsFragment", "Error updating WiFi status: ${e.message}")
        }
    }
    
    /**
     * Get WiFi SSID using the simplified NetworkUtils approach
     */
    private fun getActiveWifiSsid(): String? {
        try {
            // Use networkUtils' simplified detection for best results
            val ssid = networkUtils.getSsid()
            Log.d("SettingsFragment", "NetworkUtils SSID: $ssid")
            
            if (ssid != null && ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                return ssid
            }

            // WiFi is up but detection failed (permissions, weird ROM) — return null so the
            // UI can degrade gracefully instead of showing a bogus placeholder name.
            return null
        } catch (e: Exception) {
            Log.e("SettingsFragment", "Error getting WiFi SSID: ${e.message}")
            return null
        }
    }
    
    private fun getHomeNetworks(): Set<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return prefs.getStringSet("home_wifi_networks", emptySet()) ?: emptySet()
    }
    
    private fun saveHomeNetworks(networks: Set<String>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.edit().putStringSet("home_wifi_networks", networks).apply()
        
        // Force refresh the network status after changing home networks list
        networkUtils.forceRefresh()
    }
    
    /**
     * Checks if a network SSID is in the home networks list
     * Now uses the NetworkUtils for better reliability
     */
    private fun isHomeNetwork(ssid: String): Boolean {
        // First check connection mode
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
        
        if (connectionMode == "internal") {
            return true // Always home in internal mode
        } else if (connectionMode == "external") {
            return false // Always external in external mode
        }
        
        // In auto mode, check the home networks list
        val homeNetworks = getHomeNetworks()
        
        // Case-insensitive matching
        val directMatch = homeNetworks.any { 
            it.equals(ssid, ignoreCase = true) 
        }
        
        return directMatch
    }
    
    /**
     * Disables autocorrect and autofill for all EditTextPreferences
     * Also sets up listeners to trigger URL refresh when settings change
     */
    private fun disableAutocorrectForAllInputs() {
        try {
            val urlPrefs = listOfNotNull(
                findPreference<EditTextPreference>("internal_url"),
                findPreference<EditTextPreference>("external_url"),
            )

            for (pref in urlPrefs) {
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val urlType = if (pref.key == "internal_url") "Internal" else "External"
                    Toast.makeText(
                        requireContext(),
                        "$urlType URL updated to: $newValue",
                        Toast.LENGTH_SHORT,
                    ).show()
                    networkUtils.forceRefresh()
                    true
                }

                pref.setOnBindEditTextListener { editText ->
                    editText.inputType =
                        InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    editText.imeOptions =
                        EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        editText.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsFragment", "Error disabling autocorrect: ${e.message}")
        }
    }
    
    /**
     * Set up observers for network state changes to update status automatically
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // viewLifecycleOwner survives fragment view recreation (drill-down into a nested
        // PreferenceScreen) — using `this` leaked an observer on the singleton URL stream.
        networkUtils.currentUrl.observe(viewLifecycleOwner) { _ -> updateWifiStatus() }
    }
    
    /**
     * Setup advanced preferences like custom user agent
     */
    private fun setupAdvancedPreferences() {
        val customUserAgentPref = findPreference<EditTextPreference>("custom_user_agent")
        customUserAgentPref?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        
        // Update the summary to show the current user agent
        val useCustomUserAgent = findPreference<SwitchPreferenceCompat>("use_custom_user_agent")
        useCustomUserAgent?.setOnPreferenceChangeListener { preference, newValue ->
            val enabled = newValue as Boolean
            if (enabled) {
                Toast.makeText(requireContext(), 
                    "Custom User Agent enabled. The page will reload with new settings.", 
                    Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), 
                    "Using default User Agent", 
                    Toast.LENGTH_SHORT).show()
            }
            true
        }
        
        // Update custom user agent edit text hint
        customUserAgentPref?.setOnPreferenceChangeListener { preference, newValue ->
            Toast.makeText(requireContext(), 
                "User Agent updated. The page will reload with new settings.", 
                Toast.LENGTH_SHORT).show()
            true
        }
        
        // Display current app version
        val appVersionPref = findPreference<Preference>("app_version")
        appVersionPref?.summary = getAppVersion()
        
        // Handle check for updates
        findPreference<Preference>("check_updates")?.setOnPreferenceClickListener {
            checkForUpdatesManually()
            true
        }
    }
    
    /**
     * Get the current app version
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().packageManager.getPackageInfo(
                    requireContext().packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            }
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    /**
     * Manually check for app updates
     */
    private fun checkForUpdatesManually() {
        val updateChecker = UpdateChecker(requireContext())
        
        // Create a progress dialog
        val progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            setPadding(0, 16, 0, 0)
        }
        
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
            addView(progressBar)
        }
        
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("Checking for updates...")
            .setView(layout)
            .setCancelable(false)
            .create()
        
        progressDialog.show()
        
        lifecycleScope.launch {
            val updateInfo = updateChecker.checkForUpdates(force = true)
            progressDialog.dismiss()
            
            if (updateInfo != null) {
                updateChecker.showUpdateDialog(requireActivity() as AppCompatActivity, updateInfo)
            } else {
                Toast.makeText(context, "No updates available", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Permission requests are handled once in MainActivity. If the user denied them,
        // updateWifiStatus() already degrades gracefully — no need for a repeated prompt here.
        updateWifiStatus()
    }
    
    override fun onStop() {
        super.onStop()

        // Force refresh network status when leaving settings
        // This ensures URLs are updated when returning to the home fragment
        networkUtils.forceRefresh()
        Log.d("SettingsFragment", "Leaving settings - refreshing network status")
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences
            ?.unregisterOnSharedPreferenceChangeListener(notificationPrefsListener)
        super.onDestroy()
    }
}