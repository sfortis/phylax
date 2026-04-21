package com.asksakis.freegate.ui.settings

import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.asksakis.freegate.R
import com.asksakis.freegate.auth.CredentialsStore
import com.asksakis.freegate.utils.ClientCertManager
import com.asksakis.freegate.utils.NetworkUtils
import com.asksakis.freegate.utils.UrlNormalizer

/**
 * URLs + network mode + Frigate account + mTLS + strict-TLS opt-in.
 */
class ConnectionSettingsFragment : PreferenceFragmentCompat() {

    private lateinit var networkUtils: NetworkUtils

    private val tlsPrefListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "strict_tls_external") {
                com.asksakis.freegate.utils.OkHttpClientFactory.invalidate()
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_connection, rootKey)
        networkUtils = NetworkUtils.getInstance(requireContext())

        setupConnectionModePreference()
        setupHomeWifiNetworksPreference()
        setupFrigatePasswordPreference()
        setupClientCertPreference()
        disableAutocorrectForUrlInputs()
        setupUrlSummaryProviders()
        updateWifiStatus()

        preferenceManager.sharedPreferences
            ?.registerOnSharedPreferenceChangeListener(tlsPrefListener)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        networkUtils.currentUrl.observe(viewLifecycleOwner) { _ -> updateWifiStatus() }
    }

    override fun onResume() {
        super.onResume()
        updateWifiStatus()
    }

    override fun onStop() {
        super.onStop()
        networkUtils.forceRefresh()
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences
            ?.unregisterOnSharedPreferenceChangeListener(tlsPrefListener)
        super.onDestroy()
    }

    /**
     * Intercept the URL preferences so we can show a custom AlertDialog that refuses
     * to dismiss on invalid input. The stock EditTextPreference dialog closes on OK
     * regardless of the change-listener's return value, which silently dropped user
     * input. This replacement validates before dismissing and keeps focus on the
     * EditText so the user can correct the typo without re-tapping the row.
     */
    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is EditTextPreference &&
            (preference.key == "internal_url" || preference.key == "external_url")
        ) {
            showUrlEditDialog(preference)
            return
        }
        super.onDisplayPreferenceDialog(preference)
    }

    private fun showUrlEditDialog(pref: EditTextPreference) {
        val external = pref.key == "external_url"
        val urlType = if (external) "External" else "Internal"
        val hint = if (external) "e.g. https://frigate.example.com" else "e.g. http://frigate.local:5000"
        val ctx = requireContext()

        val input = EditText(ctx).apply {
            inputType =
                InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            setText(pref.text.orEmpty())
            setSelection(text.length)
            setHint(hint)
        }
        val statusTv = android.widget.TextView(ctx).apply {
            setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Caption)
            setPadding(0, 24, 0, 0)
            text = ""
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
            addView(statusTv)
        }

        val dialog = com.asksakis.freegate.ui.FreegateDialogs.builder(ctx)
            .setTitle("$urlType URL")
            .setView(container)
            .setPositiveButton("Save", null) // handled in OnShowListener so we can block dismiss
            .setNeutralButton("Test", null)  // same — we probe without dismissing
            .setNegativeButton("Cancel", null)
            .create()

        var errorToast: Toast? = null
        fun toast(msg: String) {
            errorToast?.cancel()
            errorToast = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).also { it.show() }
        }

        // Observe validation results only while this dialog is visible, and only react
        // to the URL we most recently probed from here. Prevents cross-talk with other
        // probes (badge dialog heartbeat, automatic revalidation).
        var probingUrl: String? = null
        val observer = androidx.lifecycle.Observer<NetworkUtils.ValidationResult> { r ->
            if (r.url != probingUrl) return@Observer
            statusTv.text = when (r.status) {
                NetworkUtils.ValidationStatus.IN_PROGRESS -> "Probing ${r.url}…"
                NetworkUtils.ValidationStatus.SUCCESS -> "✓ ${r.message}"
                NetworkUtils.ValidationStatus.FAILED,
                NetworkUtils.ValidationStatus.TIMEOUT -> "✗ ${r.message}"
            }
        }
        networkUtils.urlValidationStatus.observe(this, observer)

        dialog.setOnShowListener {
            input.requestFocus()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val result = UrlNormalizer.normalize(input.text.toString(), external)
                val normalized = result.normalized
                if (normalized == null) {
                    toast(result.error ?: "Invalid URL")
                    return@setOnClickListener
                }
                result.warning?.let { toast(it) }
                errorToast?.cancel()
                if (pref.callChangeListener(normalized)) {
                    pref.text = normalized
                }
                dialog.dismiss()
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val result = UrlNormalizer.normalize(input.text.toString(), external)
                val normalized = result.normalized
                if (normalized == null) {
                    statusTv.text = "✗ ${result.error ?: "Invalid URL"}"
                    return@setOnClickListener
                }
                // Reflect the normalised form back so the user sees what we'll save.
                if (input.text.toString() != normalized) {
                    input.setText(normalized)
                    input.setSelection(normalized.length)
                }
                probingUrl = normalized
                statusTv.text = "Probing $normalized…"
                networkUtils.validateUrl(normalized, isInternal = !external)
            }
        }
        dialog.setOnDismissListener {
            networkUtils.urlValidationStatus.removeObserver(observer)
            errorToast?.cancel()
        }
        dialog.show()
    }

    /** Show the saved URL in the preference summary instead of static placeholder text. */
    private fun setupUrlSummaryProviders() {
        listOf("internal_url", "external_url").forEach { key ->
            findPreference<EditTextPreference>(key)?.summaryProvider =
                androidx.preference.Preference.SummaryProvider<EditTextPreference> { p ->
                    p.text?.takeIf { it.isNotBlank() } ?: "Not set"
                }
        }
    }


    private fun setupConnectionModePreference() {
        val connModePref = findPreference<ListPreference>("connection_mode") ?: return
        connModePref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        connModePref.setOnPreferenceChangeListener { _, newValue ->
            val toast = when (newValue.toString()) {
                "auto" -> "Auto mode: URL depends on WiFi network"
                "internal" -> "Always using Internal URL"
                "external" -> "Always using External URL"
                else -> null
            }
            toast?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }

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
            com.asksakis.freegate.ui.FreegateDialogs.builder(requireContext())
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
        pref.setOnBindEditTextListener { editText -> editText.setText(store.getPassword().orEmpty()) }
        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            if (store.getPassword().isNullOrEmpty()) "Not set" else "••••••••"
        }
    }

    private fun setupClientCertPreference() {
        val pref = findPreference<Preference>("client_cert_alias") ?: return
        val certManager = ClientCertManager.getInstance(requireContext())

        fun refreshSummary() {
            val alias = certManager.getSavedAlias()
            if (alias == null) {
                val red = ContextCompat.getColor(requireContext(), R.color.cert_missing)
                val span = SpannableString("Not selected")
                span.setSpan(ForegroundColorSpan(red), 0, span.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                pref.summary = span
            } else {
                val accent = ContextCompat.getColor(requireContext(), R.color.cert_present)
                val prefix = "Active: "
                val span = SpannableString(prefix + alias)
                val exclusive = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                span.setSpan(ForegroundColorSpan(accent), prefix.length, span.length, exclusive)
                span.setSpan(StyleSpan(android.graphics.Typeface.BOLD), prefix.length, span.length, exclusive)
                pref.summary = span
            }
        }
        refreshSummary()

        pref.setOnPreferenceClickListener {
            val current = certManager.getSavedAlias()
            val options = if (current == null) {
                arrayOf("Select certificate")
            } else {
                arrayOf("Replace certificate", "Clear")
            }
            com.asksakis.freegate.ui.FreegateDialogs.builder(requireContext())
                .setTitle("Client certificate")
                .setItems(options) { _, which ->
                    when (options[which]) {
                        "Select certificate", "Replace certificate" -> {
                            certManager.promptForCertificate(requireActivity(), request = null) { alias ->
                                if (alias != null) {
                                    Log.i(TAG, "User picked cert alias: $alias")
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

    private fun disableAutocorrectForUrlInputs() {
        val urlPrefs = listOfNotNull(
            findPreference<EditTextPreference>("internal_url"),
            findPreference<EditTextPreference>("external_url"),
        )

        for (pref in urlPrefs) {
            pref.setOnPreferenceChangeListener { _, _ ->
                // Validation + persistence is handled by onDisplayPreferenceDialog's
                // custom AlertDialog — that dialog only closes after a successful save,
                // so by the time this callback fires the value is already valid.
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
    }

    private fun updateWifiStatus() {
        val wifiStatusPref = findPreference<Preference>("current_wifi_status") ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"

        if (connectionMode != "auto") {
            wifiStatusPref.summary = when (connectionMode) {
                "internal" -> "Always using Internal URL\n(Connection mode: Forced Internal)"
                else -> "Always using External URL\n(Connection mode: Forced External)"
            }
            return
        }

        try {
            val wifiManager = requireContext().applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            val isConnectedToWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

            var currentSsid = getActiveWifiSsid()
            if (currentSsid == "Current WiFi" || currentSsid == "<unknown ssid>") {
                currentSsid = getHomeNetworks().firstOrNull()
            }

            // Ask NetworkUtils for the currently-resolved URL + classification — that's
            // the authoritative answer (same one the service and WebView use). Avoids
            // the UI ever claiming a different URL than the app is actually loading.
            val resolvedIsHome = networkUtils.isHome()
            val urlUsed = if (resolvedIsHome) "Internal URL" else "External URL"

            wifiStatusPref.summary = when {
                !wifiManager.isWifiEnabled -> "WiFi is disabled\nUsing: $urlUsed"
                !isConnectedToWifi -> "WiFi is enabled but not connected\nUsing: $urlUsed"
                currentSsid != null -> {
                    "Connected to: $currentSsid\n" +
                        "Network type: ${if (resolvedIsHome) "HOME" else "EXTERNAL"}\n" +
                        "Using: $urlUsed"
                }
                else -> "Connected to WiFi (SSID unknown)\nUsing: $urlUsed"
            }
        } catch (e: Exception) {
            val fallback = if (networkUtils.isHome()) "Internal URL" else "External URL"
            wifiStatusPref.summary = "Error checking network status\nUsing: $fallback"
            Log.e(TAG, "Error updating WiFi status: ${e.message}")
        }
    }

    private fun getActiveWifiSsid(): String? = try {
        networkUtils.getSsid()?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    } catch (e: Exception) {
        Log.e(TAG, "Error getting WiFi SSID: ${e.message}")
        null
    }

    private fun getHomeNetworks(): Set<String> =
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getStringSet("home_wifi_networks", emptySet()) ?: emptySet()

    private fun saveHomeNetworks(networks: Set<String>) {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit().putStringSet("home_wifi_networks", networks).apply()
        networkUtils.forceRefresh()
    }

    companion object {
        private const val TAG = "ConnectionSettings"
    }
}
