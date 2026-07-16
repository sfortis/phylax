package com.asksakis.freegate.ui.settings

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.asksakis.freegate.R
import com.asksakis.freegate.auth.CredentialsStore
import com.asksakis.freegate.auth.ServerProfileStore
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

    /**
     * Feature-boundary location request. Reading the current Wi-Fi SSID (for auto
     * URL switching and the home-network picker) requires ACCESS_FINE_LOCATION on
     * every supported Android version, so we ask for it here - when the user opts
     * into an SSID-based feature - instead of up front at app launch.
     */
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            networkUtils.forceRefresh()
            updateWifiStatus()
            populateHomeWifiEntries()
        } else {
            Toast.makeText(
                requireContext(),
                "Location denied - automatic Wi-Fi URL switching won't work",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** Prompt for location the first time an SSID-based feature is used. No-op if granted. */
    private fun ensureLocationForSsid() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        com.asksakis.freegate.ui.FreegateDialogs.builder(requireContext())
            .setTitle("Location needed for Wi-Fi switching")
            .setMessage(
                "Phylax reads your current Wi-Fi network name to switch between the local " +
                    "and remote Frigate URLs automatically. It is used only for that - no GPS, " +
                    "no tracking. Grant location access?"
            )
            .setPositiveButton("Continue") { _, _ ->
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_connection, rootKey)
        networkUtils = NetworkUtils.getInstance(requireContext())

        setupServerPickerPreference()
        setupConnectionModePreference()
        setupHomeWifiNetworksPreference()
        setupAccountSummaries()
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
        // Profile swaps that happened while we were on the Servers picker write
        // new flat-key values without notifying preferences whose
        // SharedPreferences-change-listener was unregistered during onStop.
        // Manually re-bind every preference that backs flat state we may have
        // swapped, otherwise EditText / List / Switch rows keep showing the
        // outgoing profile's data.
        rebindActiveProfilePreferences()
        updateWifiStatus()
        refreshServerPickerSummary()

        // Listen for fresh scan results while the fragment is on screen so the
        // home-Wi-Fi picker stays in sync with whatever the radio just saw.
        val filter = android.content.IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                scanResultsReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(scanResultsReceiver, filter)
        }
        triggerWifiScan()
    }

    override fun onPause() {
        super.onPause()
        runCatching { requireContext().unregisterReceiver(scanResultsReceiver) }
    }

    private fun rebindActiveProfilePreferences() {
        val prefs = preferenceManager.sharedPreferences ?: return
        findPreference<EditTextPreference>("internal_url")?.text = prefs.getString("internal_url", null)
        findPreference<EditTextPreference>("external_url")?.text = prefs.getString("external_url", null)
        findPreference<ListPreference>("connection_mode")?.value = prefs.getString("connection_mode", "auto")
        findPreference<androidx.preference.SwitchPreferenceCompat>("strict_tls_external")?.isChecked =
            prefs.getBoolean("strict_tls_external", false)
        findPreference<MultiSelectListPreference>("home_wifi_networks")?.values =
            prefs.getStringSet("home_wifi_networks", emptySet())
        // mTLS row owns its own renderer; the alias may have been swapped under
        // the active profile change so re-render explicitly.
        refreshClientCertSummary()
        // Account fields read from CredentialsStore via summaryProvider; force the
        // adapter to redraw so the username/password rows pick up the swap.
        refreshPreferenceSummaries()
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
     * Replace stock EditTextPreference dialogs with Material text-field versions.
     *
     * URL fields use a custom dialog so we can validate-before-dismiss (the stock
     * dialog closes on OK regardless of the change-listener's return value, which
     * silently dropped user input).
     *
     * Account fields take a parallel path so the inner EditText is wrapped in a
     * TextInputLayout (floating label, password toggle on the password row) and
     * so the Autofill framework receives the right hints — without that, password
     * managers don't pop suggestions over the field.
     */
    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is EditTextPreference) {
            when (preference.key) {
                "internal_url", "external_url" -> {
                    showUrlEditDialog(preference); return
                }
                CredentialsStore.PREF_USERNAME -> {
                    showAccountFieldDialog(
                        title = "Username",
                        hint = "Frigate UI username",
                        autofillHints = arrayOf(View.AUTOFILL_HINT_USERNAME),
                        isPassword = false,
                        getter = {
                            CredentialsStore.getInstance(requireContext()).getUsername().orEmpty()
                        },
                        setter = { newValue ->
                            CredentialsStore.getInstance(requireContext()).setUsername(newValue)
                            refreshPreferenceSummaries()
                        },
                    )
                    return
                }
                CredentialsStore.PREF_PASSWORD -> {
                    showAccountFieldDialog(
                        title = "Password",
                        hint = "Frigate UI password",
                        autofillHints = arrayOf(View.AUTOFILL_HINT_PASSWORD),
                        isPassword = true,
                        getter = {
                            CredentialsStore.getInstance(requireContext()).getPassword().orEmpty()
                        },
                        setter = { newValue ->
                            CredentialsStore.getInstance(requireContext()).setPassword(newValue)
                            refreshPreferenceSummaries()
                        },
                    )
                    return
                }
            }
        }
        super.onDisplayPreferenceDialog(preference)
    }

    /** Force the preference list to re-bind so SummaryProvider runs again. */
    private fun refreshPreferenceSummaries() {
        view?.post { listView?.adapter?.notifyDataSetChanged() }
    }

    private fun showAccountFieldDialog(
        title: String,
        hint: String,
        autofillHints: Array<String>,
        isPassword: Boolean,
        getter: () -> String,
        setter: (String) -> Unit,
    ) {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_account_field, null, false)
        val til = view.findViewById<com.google.android.material.textfield.TextInputLayout>(
            R.id.account_field_input_layout,
        )
        val edit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.account_field_edit_text,
        )

        til.hint = hint
        if (isPassword) {
            til.endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            edit.setAutofillHints(*autofillHints)
            edit.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }
        edit.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        edit.setText(getter())
        edit.setSelection(edit.text?.length ?: 0)

        val dialog = com.asksakis.freegate.ui.FreegateDialogs.builder(ctx)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                setter(edit.text?.toString().orEmpty())
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener { edit.requestFocus() }
        dialog.show()
    }

    private fun showUrlEditDialog(pref: EditTextPreference) {
        val external = pref.key == "external_url"
        val urlType = if (external) "External" else "Internal"
        val placeholder = if (external) "e.g. https://frigate.example.com" else "e.g. http://frigate.local:5000"
        val ctx = requireContext()

        val view = layoutInflater.inflate(R.layout.dialog_url_field, null, false)
        val til = view.findViewById<com.google.android.material.textfield.TextInputLayout>(
            R.id.url_field_input_layout,
        )
        val input = view.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.url_field_edit_text,
        )
        val statusTv = view.findViewById<android.widget.TextView>(R.id.url_field_probe_status)

        til.hint = "$urlType URL"
        til.placeholderText = placeholder
        input.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        input.setText(pref.text.orEmpty())
        input.setSelection(input.text?.length ?: 0)

        // Clear inline validation as the user types — the next Save/Test recomputes.
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                til.error = null
            }
        })

        val dialog = com.asksakis.freegate.ui.FreegateDialogs.builder(ctx)
            .setTitle("$urlType URL")
            .setView(view)
            .setPositiveButton("Save", null) // handled in OnShowListener so we can block dismiss
            .setNeutralButton("Test", null)  // same — we probe without dismissing
            .setNegativeButton("Cancel", null)
            .create()

        fun showProbeStatus(text: String) {
            statusTv.text = text
            statusTv.visibility = View.VISIBLE
        }

        // Observe validation results only while this dialog is visible, and only react
        // to the URL we most recently probed from here. Prevents cross-talk with other
        // probes (badge dialog heartbeat, automatic revalidation).
        var probingUrl: String? = null
        val observer = androidx.lifecycle.Observer<NetworkUtils.ValidationResult> { r ->
            if (r.url != probingUrl) return@Observer
            showProbeStatus(
                when (r.status) {
                    NetworkUtils.ValidationStatus.IN_PROGRESS -> "Probing ${r.url}…"
                    NetworkUtils.ValidationStatus.SUCCESS -> "✓ ${r.message}"
                    NetworkUtils.ValidationStatus.FAILED,
                    NetworkUtils.ValidationStatus.TIMEOUT -> "✗ ${r.message}"
                    // Unconfigured carries a null url, so the probingUrl filter above
                    // already dropped it; this branch just satisfies exhaustiveness.
                    NetworkUtils.ValidationStatus.UNCONFIGURED -> return@Observer
                },
            )
        }
        networkUtils.urlValidationStatus.observe(this, observer)

        dialog.setOnShowListener {
            input.requestFocus()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val result = UrlNormalizer.normalize(input.text?.toString().orEmpty(), external)
                val normalized = result.normalized
                if (normalized == null) {
                    til.error = result.error ?: "Invalid URL"
                    return@setOnClickListener
                }
                til.error = null
                result.warning?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
                if (pref.callChangeListener(normalized)) {
                    pref.text = normalized
                }
                dialog.dismiss()
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val result = UrlNormalizer.normalize(input.text?.toString().orEmpty(), external)
                val normalized = result.normalized
                if (normalized == null) {
                    til.error = result.error ?: "Invalid URL"
                    return@setOnClickListener
                }
                til.error = null
                if (input.text?.toString() != normalized) {
                    input.setText(normalized)
                    input.setSelection(normalized.length)
                }
                probingUrl = normalized
                showProbeStatus("Probing $normalized…")
                networkUtils.validateUrl(normalized, isInternal = !external)
            }
        }
        dialog.setOnDismissListener {
            networkUtils.urlValidationStatus.removeObserver(observer)
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


    /**
     * Header row that names the active server and lets the user open the picker
     * to switch / add / rename / delete. The picker is intentionally embedded
     * inside this screen (rather than at the Settings root) so the user always
     * sees which profile the URLs / account / mTLS rows below are editing.
     */
    private fun setupServerPickerPreference() {
        val pref = findPreference<Preference>("active_server") ?: return
        pref.setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_connection_to_servers)
            true
        }
        refreshServerPickerSummary()
    }

    private fun refreshServerPickerSummary() {
        val pref = findPreference<Preference>("active_server") ?: return
        // Mirror anything the user just edited in URL / account / mTLS rows back
        // into the active profile snapshot before reading it for the row.
        val store = ServerProfileStore.getInstance(requireContext())
        store.commitFlatStateToActive()
        // Custom layout puts the static "Server" label in `@android:id/title`
        // and the profile name in `@android:id/summary` (mapped via the
        // preference binding) — gives a small label + larger name treatment.
        pref.summary = store.getActive()?.name ?: "No active server"
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

            // Auto mode is the only mode that reads the SSID - ask for location here.
            if (newValue.toString() == "auto") ensureLocationForSsid()

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

        // Fire a fresh scan when the user opens the screen — scan results are
        // cached for ~120s in WifiManager, so re-asking is essentially free if
        // a previous request is still warm.
        triggerWifiScan()

        networksPref?.setOnPreferenceClickListener {
            // Opening the picker reads nearby/active SSIDs, which needs location.
            ensureLocationForSsid()
            // Picker about to open — re-scan and re-populate so freshly-visible
            // networks appear without forcing the user to back out and re-enter.
            triggerWifiScan()
            populateHomeWifiEntries()
            false // let the framework open the picker normally
        }

        networksPref?.setOnPreferenceChangeListener { _, _ ->
            view?.post { populateHomeWifiEntries(); networkUtils.forceRefresh(); updateWifiStatus() }
            true
        }

        addPref?.setOnPreferenceClickListener {
            // Pre-filling the dialog with the current SSID needs location too.
            ensureLocationForSsid()
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
                    populateHomeWifiEntries()
                    updateWifiStatus()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        applyConnectionModeVisibility()
        populateHomeWifiEntries()
    }

    /**
     * Rebuild the home-Wi-Fi picker entries from the union of saved networks,
     * the active SSID, and the most recent scan results.
     *
     * Saved networks that aren't visible in the latest scan are still listed —
     * otherwise the user couldn't un-check a stale entry — but their display
     * label is suffixed with "(not nearby)" so it's obvious that the picker
     * isn't currently seeing that AP. The underlying entry *value* stays the
     * raw SSID so existing saved-set comparisons keep working.
     *
     * Cheap — pure in-memory rebuild — so it's safe to call from both
     * lifecycle hooks and scan-result broadcasts without re-wiring listeners.
     */
    private fun populateHomeWifiEntries() {
        val networksPref = findPreference<MultiSelectListPreference>("home_wifi_networks") ?: return
        val saved = getHomeNetworks()
        val currentSsid = getActiveWifiSsid()
            ?.takeUnless { it in setOf("Current WiFi", "Unknown WiFi", "<unknown ssid>") }
        val scannedSet = readNearbySsids().toSet()
        val allSsids = (saved + listOfNotNull(currentSsid) + scannedSet)
            .distinct()
            .sorted()
        val nearbySet = scannedSet + listOfNotNull(currentSsid)
        val labels: Array<CharSequence> = allSsids.map { ssid ->
            if (ssid in nearbySet) ssid as CharSequence else buildNotNearbyLabel(ssid)
        }.toTypedArray()
        networksPref.entries = labels
        networksPref.entryValues = allSsids.toTypedArray()
        networksPref.summary = if (saved.isEmpty()) {
            "No networks configured"
        } else {
            saved.sorted().joinToString(", ")
        }
    }

    /**
     * Build a "SSID + red pill badge" CharSequence for saved networks the radio
     * doesn't currently see. The badge is rendered inline via [BadgeSpan] so it
     * lines up with the rest of the row without any custom adapter changes.
     */
    private fun buildNotNearbyLabel(ssid: String): CharSequence {
        val ctx = requireContext()
        val display = android.util.DisplayMetrics().also {
            ctx.display?.getRealMetrics(it) ?: it.setTo(ctx.resources.displayMetrics)
        }
        fun dp(value: Float): Float = value * ctx.resources.displayMetrics.density
        val badgeText = "NOT NEARBY"
        // Three NBSPs in front of the badge so it doesn't visually crash into
        // the SSID — Android list rows don't honour CSS-style margins.
        val placeholder = "   $badgeText"
        val builder = android.text.SpannableStringBuilder(ssid).append(placeholder)
        val badgeStart = builder.length - badgeText.length
        builder.setSpan(
            com.asksakis.freegate.ui.BadgeSpan(
                backgroundColor = androidx.core.content.ContextCompat.getColor(
                    ctx,
                    R.color.cert_missing,
                ),
                textColor = androidx.core.content.ContextCompat.getColor(ctx, R.color.white),
                textSizePx = dp(11f),
                paddingHorizontalPx = dp(6f),
                paddingVerticalPx = dp(2f),
                cornerRadiusPx = dp(8f),
            ),
            badgeStart,
            builder.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return builder
    }

    private fun applyConnectionModeVisibility() {
        val auto = (preferenceManager.sharedPreferences?.getString("connection_mode", "auto")
            ?: "auto") == "auto"
        findPreference<MultiSelectListPreference>("home_wifi_networks")?.isVisible = auto
        findPreference<Preference>("add_home_network")?.isVisible = auto
    }

    /**
     * Both account preferences now show their dialog through [showAccountFieldDialog]
     * — keep the row summaries in sync with the underlying [CredentialsStore]
     * (default prefs for username, encrypted prefs for the password).
     */
    private fun setupAccountSummaries() {
        val store = CredentialsStore.getInstance(requireContext())
        findPreference<EditTextPreference>(CredentialsStore.PREF_USERNAME)?.summaryProvider =
            Preference.SummaryProvider<EditTextPreference> {
                store.getUsername()?.takeIf { it.isNotBlank() } ?: "Not set"
            }
        findPreference<EditTextPreference>(CredentialsStore.PREF_PASSWORD)?.summaryProvider =
            Preference.SummaryProvider<EditTextPreference> {
                if (store.getPassword().isNullOrEmpty()) "Not set" else "••••••••"
            }
    }

    private fun setupClientCertPreference() {
        val pref = findPreference<Preference>("client_cert_alias") ?: return
        val certManager = ClientCertManager.getInstance(requireContext())

        refreshClientCertSummary()

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
                                requireActivity().runOnUiThread { refreshClientCertSummary() }
                            }
                        }
                        "Clear" -> {
                            certManager.clearAlias()
                            Toast.makeText(requireContext(), "Certificate cleared", Toast.LENGTH_SHORT).show()
                            refreshClientCertSummary()
                        }
                    }
                }
                .show()
            true
        }
    }

    /**
     * Re-render the mTLS row from the currently-active alias. Exposed at fragment
     * scope (rather than as a closure inside [setupClientCertPreference]) so a
     * profile swap that mutates `client_cert_alias` underneath us can also force
     * the row to redraw — without this the previous profile's certificate name
     * stays on screen until the user navigates back into the fragment cold.
     */
    private fun refreshClientCertSummary() {
        val pref = findPreference<Preference>("client_cert_alias") ?: return
        val certManager = ClientCertManager.getInstance(requireContext())
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
                // (Previously did a per-host filter swap here. Filters now live in
                // the active server profile snapshot, so URL edits inside the same
                // profile leave the filter set alone — that's by design: changing
                // a server's URL doesn't change which cameras/zones the user picked
                // on it.)
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

    /**
     * Ask WifiManager to perform a fresh scan. Safe to call repeatedly — Android
     * throttles app-initiated scans (4 per 2 minutes on most builds) and the
     * call is a no-op while throttled. Results land asynchronously and are
     * picked up via [readNearbySsids] / [scanResultsReceiver]. We intentionally
     * swallow exceptions and the boolean return: failures (permission missing,
     * throttling, scan disabled) just mean the picker stays with the current
     * entry list, which is the desired graceful-degrade behaviour.
     */
    @Suppress("DEPRECATION")
    private fun triggerWifiScan() {
        val wifiManager = requireContext().applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        runCatching { wifiManager.startScan() }
            .onSuccess { accepted ->
                if (!accepted) Log.d(TAG, "startScan returned false (throttled or disabled)")
            }
            .onFailure { Log.d(TAG, "startScan threw: ${it.message}") }
    }

    /**
     * Return SSIDs from the latest [WifiManager.getScanResults]. Filters out
     * the system's placeholder strings and hidden APs (empty SSID).
     */
    @Suppress("DEPRECATION")
    private fun readNearbySsids(): List<String> {
        val wifiManager = requireContext().applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        val results = runCatching { wifiManager.scanResults }
            .onFailure { Log.d(TAG, "scanResults failed: ${it.message}") }
            .getOrNull()
            ?: return emptyList()
        return results
            .mapNotNull { it.SSID?.trim()?.removeSurrounding("\"") }
            .filter { it.isNotEmpty() && it != "<unknown ssid>" }
            .distinct()
    }

    /**
     * Re-populate the home-Wi-Fi picker entries every time the framework
     * delivers fresh scan results. Registered in onResume / unregistered in
     * onPause so we don't keep a receiver alive while the fragment is offscreen.
     * Deliberately calls only [populateHomeWifiEntries] — re-running the full
     * setup would re-arm preference listeners and re-trigger a scan, which
     * loops cheaply but pointlessly through the OS throttle.
     */
    private val scanResultsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            populateHomeWifiEntries()
        }
    }

    private fun saveHomeNetworks(networks: Set<String>) {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit().putStringSet("home_wifi_networks", networks).apply()
        networkUtils.forceRefresh()
    }

    companion object {
        private const val TAG = "ConnectionSettings"
    }
}
