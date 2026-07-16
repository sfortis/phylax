package com.asksakis.freegate.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.asksakis.freegate.R
import com.asksakis.freegate.auth.ServerProfileStore
import com.asksakis.freegate.notifications.FrigateAlertService
import com.asksakis.freegate.utils.ClientCertManager
import com.asksakis.freegate.utils.NetworkUtils
import com.asksakis.freegate.utils.OkHttpClientFactory
import com.asksakis.freegate.utils.UrlNormalizer
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import okhttp3.Request

/**
 * First-run / add-server screen. A focused form: the Frigate URL is the one required
 * field; name and credentials are optional; a separate LAN URL hides under Advanced.
 *
 * On save it creates and activates a real profile via [ServerProfileStore] and calls
 * [NetworkUtils.start] so networking comes online only now - never on a blank config.
 * That, together with the Home empty state, is what replaces the old "connect on
 * launch and spam failure toasts" first-run behaviour.
 */
class SetupFragment : Fragment(R.layout.fragment_setup) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Location request fired at save time only when a separate LAN URL is configured
     * (auto LAN/remote switching needs the SSID). Whatever the user answers, we then
     * refresh networking and return to Home.
     */
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (isAdded) NetworkUtils.getInstance(requireContext()).forceRefresh()
        goHome()
    }

    private lateinit var urlLayout: TextInputLayout
    private lateinit var urlInput: TextInputEditText
    private lateinit var nameInput: TextInputEditText
    private lateinit var usernameInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var internalUrlLayout: TextInputLayout
    private lateinit var internalUrlInput: TextInputEditText
    private lateinit var advancedSection: View
    private lateinit var statusView: TextView
    private lateinit var testButton: MaterialButton
    private lateinit var saveButton: MaterialButton

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        urlLayout = view.findViewById(R.id.setup_url_layout)
        urlInput = view.findViewById(R.id.setup_url)
        nameInput = view.findViewById(R.id.setup_name)
        usernameInput = view.findViewById(R.id.setup_username)
        passwordInput = view.findViewById(R.id.setup_password)
        internalUrlLayout = view.findViewById(R.id.setup_internal_url_layout)
        internalUrlInput = view.findViewById(R.id.setup_internal_url)
        advancedSection = view.findViewById(R.id.setup_advanced_section)
        statusView = view.findViewById(R.id.setup_status)
        testButton = view.findViewById(R.id.setup_test_button)
        saveButton = view.findViewById(R.id.setup_save_button)

        view.findViewById<MaterialButton>(R.id.setup_advanced_toggle).setOnClickListener {
            advancedSection.visibility =
                if (advancedSection.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Clear the inline URL error as the user edits - next Test/Save recomputes it.
        urlInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) urlLayout.error = null }

        testButton.setOnClickListener { onTest() }
        saveButton.setOnClickListener { onSave() }
    }

    /** Normalise the primary URL. Local Frigate installs are http by default. */
    private fun normalizePrimary(): UrlNormalizer.Result =
        UrlNormalizer.normalize(urlInput.text?.toString().orEmpty(), external = false)

    private fun onTest() {
        val result = normalizePrimary()
        val url = result.normalized
        if (url == null) {
            urlLayout.error = result.error ?: getString(R.string.setup_url_hint)
            return
        }
        urlLayout.error = null
        setBusy(true)
        showStatus(getString(R.string.setup_testing), R.color.text_secondary)
        Thread {
            val probe = probe(url)
            mainHandler.post {
                if (!isAdded) return@post
                setBusy(false)
                if (probe.reachable) {
                    showStatus("✓ ${getString(R.string.setup_test_ok)} (${probe.detail})", R.color.status_ok)
                } else {
                    showStatus("✗ ${probe.detail}", R.color.status_failed)
                }
            }
        }.start()
    }

    private data class ProbeResult(val reachable: Boolean, val detail: String)

    /**
     * One-shot HEAD probe using the shared client factory (same TLS/mTLS policy as
     * the rest of the app). Any HTTP response - including 401/403 - means the server
     * is reachable; only transport errors count as unreachable.
     */
    private fun probe(url: String): ProbeResult {
        return try {
            val client = OkHttpClientFactory.build(
                url,
                ClientCertManager.getInstance(requireContext()),
                OkHttpClientFactory.Timeouts(connectSeconds = 8, readSeconds = 8),
            )
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 Phylax/1.0 Setup")
                .build()
            client.newCall(request).execute().use { resp ->
                ProbeResult(reachable = true, detail = "HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            ProbeResult(reachable = false, detail = e.message ?: "Connection failed")
        }
    }

    private fun onSave() {
        val result = normalizePrimary()
        val primary = result.normalized
        if (primary == null) {
            urlLayout.error = result.error ?: "Enter a valid URL"
            return
        }
        urlLayout.error = null

        // Optional separate LAN URL. When present it becomes the internal URL and the
        // primary is used as the external one; otherwise the single URL serves both so
        // auto mode resolves regardless of which network the user is on.
        val rawInternal = internalUrlInput.text?.toString().orEmpty().trim()
        val internalUrl: String?
        val externalUrl: String?
        if (rawInternal.isNotEmpty()) {
            val internalResult = UrlNormalizer.normalize(rawInternal, external = false)
            if (internalResult.normalized == null) {
                internalUrlLayout.error = internalResult.error ?: "Enter a valid URL"
                advancedSection.visibility = View.VISIBLE
                return
            }
            internalUrlLayout.error = null
            internalUrl = internalResult.normalized
            externalUrl = primary
        } else {
            internalUrl = primary
            externalUrl = primary
        }

        val store = ServerProfileStore.getInstance(requireContext())
        store.createAndActivate(
            name = nameInput.text?.toString().orEmpty().ifBlank { "Frigate" },
            internalUrl = internalUrl,
            externalUrl = externalUrl,
            username = usernameInput.text?.toString(),
            password = passwordInput.text?.toString(),
        )

        // Arm the one-time "Enable notifications?" offer. HomeFragment shows it after
        // the first successful connection, so the prompt is tied to adding a server
        // (never shown to existing users on upgrade) and never fires on a dead URL.
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit().putBoolean(PREF_PENDING_NOTIF_OFFER, true).apply()

        // Networking was idle until now; bring it online for the freshly saved server.
        NetworkUtils.getInstance(requireContext()).start()
        // Respect an already-enabled notification preference (unlikely on first run,
        // but harmless): re-evaluate the alert service against the new server.
        FrigateAlertService.updateForContext(requireContext(), forceRestart = true)

        // Reliability boundary: a separate LAN URL means auto LAN/remote switching is in
        // play, which needs the current Wi-Fi SSID (ACCESS_FINE_LOCATION). Ask for it now,
        // only in that case - a single-URL setup never needs it. Navigate to Home after
        // the user answers so the system prompt isn't left dangling over Home.
        if (rawInternal.isNotEmpty() && !hasLocationPermission()) {
            com.asksakis.freegate.ui.FreegateDialogs.builder(requireContext())
                .setTitle("Location for automatic switching")
                .setMessage(
                    "You added a separate local URL. Phylax reads your current Wi-Fi network " +
                        "name to switch between the local and remote URL automatically. It is " +
                        "used only for that - no GPS, no tracking. Grant location access?"
                )
                .setPositiveButton("Continue") { _, _ ->
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                .setNegativeButton("Skip") { _, _ -> goHome() }
                .setOnCancelListener { goHome() }
                .show()
        } else {
            goHome()
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /** Return to Home, which leaves the setup empty state and loads the WebView. */
    private fun goHome() {
        if (!isAdded) return
        if (!findNavController().popBackStack(R.id.nav_home, false)) {
            findNavController().navigate(R.id.nav_home)
        }
    }

    private fun setBusy(busy: Boolean) {
        testButton.isEnabled = !busy
        saveButton.isEnabled = !busy
    }

    private fun showStatus(text: String, colorRes: Int) {
        statusView.text = text
        statusView.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        statusView.visibility = View.VISIBLE
    }

    companion object {
        /**
         * Set true when a server is added here; consumed once by HomeFragment to show
         * the one-time "Enable notifications?" offer after the first successful connect.
         */
        const val PREF_PENDING_NOTIF_OFFER = "pending_notif_offer"
    }
}
