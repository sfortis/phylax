package com.asksakis.freegate.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.webkit.ClientCertRequest
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.preference.PreferenceManager
import com.asksakis.freegate.R
import com.asksakis.freegate.databinding.FragmentHomeBinding
import com.asksakis.freegate.download.DownloadHandler
import com.asksakis.freegate.utils.ClientCertManager
import com.asksakis.freegate.utils.NetworkUtils
import com.asksakis.freegate.utils.UrlUtils
import com.asksakis.freegate.webview.WebViewConfigurator

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var homeViewModel: HomeViewModel
    private lateinit var networkUtils: NetworkUtils
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var currentLoadedUrl: String? = null
    private var urlLoadInProgress = false
    
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var wasSystemBarsVisible: Boolean = true

    private lateinit var clientCertManager: ClientCertManager
    private lateinit var downloadHandler: DownloadHandler

    /** True once primeFrigateSessionAsync has completed the cold-start login+load path. */
    @Volatile
    private var preLoginDone = false

    /**
     * True until the cold-start login has either finished (and issued the initial
     * loadUrl itself) or failed / been skipped. While this is true the URL observer
     * suppresses its own initial loadUrl so the WebView doesn't perform a first,
     * unauthenticated load that the server immediately redirects to the login page.
     */
    @Volatile
    private var suppressInitialLoad = false

    private val downloadCallbacks = object : DownloadHandler.Callbacks {
        override fun onDownloadStarted(fileName: String) {
            _binding ?: return
            Toast.makeText(context, "Downloading $fileName...", Toast.LENGTH_SHORT).show()
        }

        override fun onDownloadCompleted(fileName: String, file: java.io.File) {
            val root = _binding?.root ?: return
            com.google.android.material.snackbar.Snackbar
                .make(root, "Downloaded: $fileName", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("Open") {
                    context?.let { DownloadHandler.openFile(it, file) }
                }
                .show()
        }

        override fun onDownloadFailed(fileName: String, error: String) {
            Toast.makeText(context, "Download failed: $error", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "HomeFragment"

        private val DISABLE_ZOOM_JS = """
            (function() {
                var v = document.querySelector('meta[name=viewport]');
                if (!v) { v = document.createElement('meta'); v.name = 'viewport'; document.head.appendChild(v); }
                v.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
            })();
        """.trimIndent()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        homeViewModel = ViewModelProvider(this)[HomeViewModel::class.java]
        networkUtils = NetworkUtils.getInstance(requireContext())
        clientCertManager = ClientCertManager.getInstance(requireContext())
        downloadHandler = DownloadHandler(
            context = requireContext().applicationContext,
            scope = lifecycleScope,
            clientCertManager = clientCertManager,
            callbacks = downloadCallbacks
        )

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root
        
        setupWebView()
        setupFileChooserLauncher()
        setupBackButtonHandler()

        // Decide up front: if we have credentials, suppress the URL observer's initial
        // load. primeFrigateSessionAsync will perform the single authenticated load
        // itself, so we skip the "load without cookie → 302 to /login → login form flash
        // → reload with cookie" dance that was making cold start slow.
        val credentials = com.asksakis.freegate.auth.CredentialsStore.getInstance(requireContext())
        suppressInitialLoad = credentials.hasCredentials()

        setupUrlObserver()

        primeFrigateSessionAsync()

        return root
    }

    private fun primeFrigateSessionAsync() {
        if (!suppressInitialLoad) return  // no credentials — observer will handle it
        val authManager = com.asksakis.freegate.auth.FrigateAuthManager.getInstance(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            val baseUrl = resolveBaseUrlForLogin()
            if (baseUrl == null) {
                suppressInitialLoad = false
                triggerDeferredInitialLoad()
                return@launch
            }
            val ok = authManager.ensureLoggedIn(baseUrl)
            if (!ok) {
                Log.w(TAG, "Pre-login failed; letting the observer load the login form")
                suppressInitialLoad = false
                triggerDeferredInitialLoad()
                return@launch
            }
            val web = _binding?.webView
            if (web == null) {
                suppressInitialLoad = false
                return@launch
            }

            // If a notification tap has staged a deep-link, load the review URL instead of
            // the base — otherwise the base load would overwrite the deep-link target.
            val deepLinkId = com.asksakis.freegate.notifications.DeepLinkRouter.consumePendingReviewId()
            val target = if (deepLinkId != null) "$baseUrl/review?id=$deepLinkId" else baseUrl
            Log.d(TAG, "Pre-login succeeded, loading $target with session cookie")
            web.loadUrl(target)
            currentLoadedUrl = target
            preLoginDone = true
            suppressInitialLoad = false
        }
    }

    /** Nudge the URL observer so a staged URL actually loads after we unblock it. */
    private fun triggerDeferredInitialLoad() {
        val url = networkUtils.currentUrl.value ?: return
        val web = _binding?.webView ?: return
        Log.d(TAG, "Fallback initial load: $url")
        web.loadUrl(url)
        currentLoadedUrl = url
    }

    private fun resolveBaseUrlForLogin(): String? {
        networkUtils.currentUrl.value?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return (prefs.getString("internal_url", null) ?: prefs.getString("external_url", null))
            ?.trimEnd('/')
    }
    
    private var lastRequestedUrl: String? = null
    private var networkValidationInProgress = false
    /** Latest endpoint the observer emitted while a load was already in flight. */
    private var pendingEndpoint: NetworkUtils.ResolvedEndpoint? = null
    /** Most recently dispatched endpoint — drives readiness checks consistently. */
    private var currentEndpoint: NetworkUtils.ResolvedEndpoint? = null
    /** The endpoint (url + mode) currently rendered in the WebView. */
    private var currentLoadedEndpoint: NetworkUtils.ResolvedEndpoint? = null
    /**
     * Queue a WebView reload to run when the server validation next reports SUCCESS —
     * avoids heuristic delays after network transitions.
     */
    private var reloadOnValidationSuccess = false

    private fun setupUrlObserver() {
        // Observe URL changes from NetworkUtils via HomeViewModel.
        // Observe the atomic endpoint (URL + isInternal) so the readiness rule never
        // sees an out-of-order pair. NetworkUtils is the single source of truth for URL
        // policy + debounce; this fragment just loads whatever comes out.
        homeViewModel.endpoint.observe(viewLifecycleOwner) { resolved ->
            val url = resolved.url
            currentEndpoint = resolved
            Log.d(TAG, "Endpoint updated: $url internal=${resolved.isInternal}")

            if (urlLoadInProgress) {
                pendingEndpoint = resolved
                Log.d(TAG, "Load in progress — queued pending endpoint: $resolved")
                return@observe
            }

            // Meaningful = URL differs, OR URL same but mode flipped (same hostname used
            // for both internal and external — page needs a reload for fresh cookies).
            val urlChanged = url != currentLoadedUrl
            val modeChanged = currentLoadedEndpoint != null &&
                currentLoadedEndpoint?.url == url &&
                currentLoadedEndpoint?.isInternal != resolved.isInternal

            if (!urlChanged && !modeChanged) {
                Log.d(TAG, "Endpoint unchanged, skipping: $resolved")
                return@observe
            }

            lastRequestedUrl = url

            if (currentLoadedUrl == null && suppressInitialLoad) {
                Log.d(TAG, "Initial load deferred until pre-login finishes: $url")
                return@observe
            }

            if (modeChanged && !urlChanged) {
                // Same URL, different mode: wait for NetworkUtils' next HEAD probe to
                // succeed before reloading — that's the authoritative "server is
                // actually reachable via the new path" signal. No arbitrary delay.
                Log.d(TAG, "Mode switch queued; awaiting VALIDATION_SUCCESS for $url")
                binding.loadingProgress.visibility = View.VISIBLE
                reloadOnValidationSuccess = true
                return@observe
            }

            val currentBase = currentLoadedUrl?.split("#")?.getOrNull(0)
            val newBase = url.split("#")[0]

            if (currentBase != null && currentBase == newBase && currentLoadedUrl != url) {
                // Only the fragment changed — no reload.
                Log.d(TAG, "Fragment-only change, navigating: $url")
                binding.webView.loadUrl(url)
                currentLoadedEndpoint = resolved
                return@observe
            }

            binding.loadingProgress.visibility = View.VISIBLE
            loadUrlWithConnectivityCheck(url)
        }

        // Mode switches (same URL, different isInternal) are detected by the endpoint
        // observer above — it arms `reloadOnValidationSuccess` and the validation
        // status observer below performs the actual reload once the server responds.

        // Also observe the URL validation status
        networkUtils.urlValidationStatus.observe(viewLifecycleOwner) { result ->
            when (result.status) {
                NetworkUtils.ValidationStatus.IN_PROGRESS -> {
                    Log.d(TAG, "URL validation in progress: ${result.url}")
                    networkValidationInProgress = true
                }
                NetworkUtils.ValidationStatus.SUCCESS -> {
                    Log.d(TAG, "URL validation succeeded: ${result.url} - ${result.message}")
                    networkValidationInProgress = false

                    // If an endpoint-mode switch queued a reload, the server is now
                    // provably reachable via the new path — trigger the WebView reload
                    // authoritatively instead of relying on any timing heuristic.
                    val target = currentEndpoint?.url ?: result.url
                    if (reloadOnValidationSuccess && !urlLoadInProgress && !target.isNullOrEmpty()) {
                        reloadOnValidationSuccess = false
                        Log.d(TAG, "Validation SUCCESS — triggering queued reload: $target")
                        loadUrlWithConnectivityCheck(target)
                    } else if (binding.loadingProgress.visibility == View.VISIBLE) {
                        binding.loadingProgress.visibility = View.GONE
                    }
                }
                NetworkUtils.ValidationStatus.FAILED, NetworkUtils.ValidationStatus.TIMEOUT -> {
                    Log.d(TAG, "URL validation failed: ${result.url} - ${result.message}")
                    networkValidationInProgress = false
                    // Mode-switch reload was waiting on validation; the server is not
                    // reachable — drop the pending reload and clear the loader.
                    if (reloadOnValidationSuccess) {
                        reloadOnValidationSuccess = false
                        Log.d(TAG, "Validation failed — cancelling queued reload")
                    }

                    // Show toast with error message
                    val errorMsg = when {
                        result.message?.contains("resolve host") == true -> "DNS error - cannot resolve host"
                        result.message?.contains("403") == true -> "Access denied (403)"
                        result.message?.contains("timeout", ignoreCase = true) == true -> "Connection timeout"
                        else -> "Connection failed"
                    }
                    Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()

                    // Hide loading indicator if present
                    if (binding.loadingProgress.visibility == View.VISIBLE) {
                        binding.loadingProgress.visibility = View.GONE
                    }
                }
            }
        }
    }
    
    /**
     * Load URL with connectivity check - enhanced with exponential backoff
     * Used for non-critical URL changes (fragments, same-base URLs)
     */
    private fun loadUrlWithConnectivityCheck(url: String, retryCount: Int = 0) {
        // Guard against races where the fragment view isn't attached yet (cold-start
        // observer fire, pending-drain recursion after renderer recovery). Without the
        // early exit we log "Binding is null, cannot load URL" and leave urlLoadInProgress
        // in an inconsistent state.
        if (_binding == null || !isAdded) {
            Log.d(TAG, "Skipping load — view not ready for: $url")
            urlLoadInProgress = false
            return
        }

        if (urlLoadInProgress && retryCount == 0) {
            Log.d(TAG, "URL load already in progress, skipping new request for: $url")
            return
        }

        urlLoadInProgress = true

        val connectivityManager =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val hasValidatedNetwork = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val hasAnyTransport = caps?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true

        // Internal endpoints don't need NET_CAPABILITY_VALIDATED — home WiFi may have no
        // upstream Internet and Frigate still works on the LAN. Use the endpoint captured
        // when we last dispatched the observer so URL and isInternal stay consistent.
        val resolved = currentEndpoint
        val treatAsInternal = when {
            resolved?.url == url -> resolved.isInternal
            else -> UrlUtils.isPrivateIpUrl(url)
        }
        val networkReady = if (treatAsInternal) {
            hasAnyTransport
        } else {
            hasValidatedNetwork
        }

        Log.d(TAG, "Network check: url=$url validated=$hasValidatedNetwork anyTransport=$hasAnyTransport ready=$networkReady")

        _binding?.let { safeBinding ->
            if (networkReady) {
                safeBinding.webView.loadUrl(url)
                currentLoadedUrl = url
                currentLoadedEndpoint = resolved ?: currentLoadedEndpoint
                Log.d(TAG, "Loading URL: $url")
                urlLoadInProgress = false
                // Drain any endpoint queued while this load was running — key on either
                // URL or mode difference so a same-URL mode flip isn't swallowed.
                pendingEndpoint?.let { queued ->
                    pendingEndpoint = null
                    val different = queued.url != url ||
                        queued.isInternal != (resolved?.isInternal ?: queued.isInternal)
                    if (different) {
                        Log.d(TAG, "Draining pending endpoint after load: $queued")
                        loadUrlWithConnectivityCheck(queued.url)
                    }
                }
            } else if (retryCount < 3) {
                val backoffTime = 1000L * (1L shl retryCount) // 1s, 2s, 4s
                Log.d(TAG, "Scheduling retry #${retryCount + 1} in ${backoffTime}ms for $url")
                safeBinding.webView.postDelayed({
                    if (_binding != null && isAdded) {
                        loadUrlWithConnectivityCheck(url, retryCount + 1)
                    } else {
                        urlLoadInProgress = false
                    }
                }, backoffTime)
            } else {
                Log.d(TAG, "Max retries reached, giving up on URL: $url")
                urlLoadInProgress = false
                activity?.runOnUiThread {
                    safeBinding.loadingProgress.visibility = View.GONE
                }
                pendingEndpoint?.let { queued ->
                    pendingEndpoint = null
                    if (queued.url != url || queued.isInternal != (resolved?.isInternal ?: false)) {
                        Log.d(TAG, "Draining pending endpoint after max-retry failure: $queued")
                        loadUrlWithConnectivityCheck(queued.url)
                    }
                }
            }
        } ?: run {
            Log.d(TAG, "Binding is null, cannot load URL: $url")
            urlLoadInProgress = false
        }
    }
    
    private fun setupFileChooserLauncher() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (fileUploadCallback == null) {
                return@registerForActivityResult
            }
            
            val data = result.data
            var results: Array<Uri>? = null
            
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                if (data?.dataString != null) {
                    results = arrayOf(Uri.parse(data.dataString))
                } else if (data?.clipData != null) {
                    val count = data.clipData!!.itemCount
                    results = Array(count) { i ->
                        data.clipData!!.getItemAt(i).uri
                    }
                }
            }
            
            fileUploadCallback?.onReceiveValue(results)
            fileUploadCallback = null
        }
    }
    
    private fun setupWebView() {
        // Avoid any setup if binding is null
        if (_binding == null) return

        // Make sure we're not double-initializing
        try {
            // Enable cookie persistence for authentication across network changes
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.setAcceptThirdPartyCookies(binding.webView, true)
            }

            // Safer initialization for WebView
            binding.webView.webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    val primaryError = when (error?.primaryError) {
                        android.net.http.SslError.SSL_NOTYETVALID -> "Certificate not yet valid"
                        android.net.http.SslError.SSL_EXPIRED -> "Certificate expired"
                        android.net.http.SslError.SSL_IDMISMATCH -> "Certificate ID mismatch"
                        android.net.http.SslError.SSL_UNTRUSTED -> "Certificate not trusted"
                        android.net.http.SslError.SSL_DATE_INVALID -> "Certificate date invalid"
                        android.net.http.SslError.SSL_INVALID -> "Certificate invalid"
                        else -> "Unknown SSL error"
                    }
                    val url = error?.url.orEmpty()
                    val strictTls = androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(requireContext())
                        .getBoolean("strict_tls_external", false)
                    // Private/LAN hosts: always bypass (self-signed is the norm).
                    // Public hosts: bypass only when the user has left strict TLS off.
                    val allowBypass = UrlUtils.isPrivateIpUrl(url) || !strictTls
                    if (allowBypass) {
                        Log.w(TAG, "SSL error: $primaryError at $url — proceeding (strictTls=$strictTls)")
                        handler?.proceed()
                    } else {
                        Log.e(TAG, "SSL error: $primaryError at $url — cancelling (strictTls=$strictTls)")
                        handler?.cancel()
                    }
                }

                override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) {
                    Log.i(TAG, "Client certificate requested by ${request?.host}")
                    val savedAlias = clientCertManager.getSavedAlias()
                    if (savedAlias != null) {
                        clientCertManager.provideCertificate(request, savedAlias) { failedRequest ->
                            activity?.runOnUiThread { promptForNewCertificate(failedRequest) }
                        }
                    } else {
                        promptForNewCertificate(request)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    Log.d(TAG, "shouldOverrideUrlLoading: $url")

                    // Handle intent:// URLs
                    if (url.startsWith("intent://")) {
                        try {
                            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                            if (intent != null) {
                                val packageManager = view?.context?.packageManager
                                val info = packageManager?.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                                if (info != null) {
                                    view.context?.startActivity(intent)
                                } else {
                                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                    if (fallbackUrl != null) view?.loadUrl(fallbackUrl)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "intent:// parse failed for $url", e)
                        }
                        return true
                    }

                    return if (url.startsWith("http://") || url.startsWith("https://")) {
                        view?.loadUrl(url)
                        true
                    } else {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            true
                        } catch (e: Exception) {
                            Log.e(TAG, "Error launching intent for URL: $url", e)
                            false
                        }
                    }
                }
                
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    val didCrash = detail?.didCrash() ?: false
                    val crashReason = when {
                        detail == null -> "Unknown reason"
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> "No additional information available"
                        else -> "WebView renderer process terminated"
                    }
                    
                    // Enhanced logging for renderer crashes
                    Log.e(TAG, "WebView renderer process gone!")
                    Log.e(TAG, "  - Crashed: $didCrash")
                    Log.e(TAG, "  - Reason: $crashReason")
                    Log.e(TAG, "  - Current URL: ${currentLoadedUrl ?: "none"}")
                    Log.e(TAG, "  - Memory condition: ${getMemoryInfo()}")
                    
                    // Dump WebView debug info
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        val debugInfo = WebView.getCurrentWebViewPackage()?.toString() ?: "unknown"
                        Log.e(TAG, "  - WebView package: $debugInfo")
                    }
                    
                    // Handle the crash - if binding is null, we might be going away anyway
                    _binding?.let { safeBinding ->
                        try {
                            // The WebView is unusable once its renderer dies. Don't try to
                            // hot-swap a new WebView into the same binding — the binding
                            // field keeps pointing at the destroyed view and setupWebView()
                            // reconfigures the wrong instance. Instead, recreate the fragment
                            // view via detach+attach so the layout inflates a fresh WebView
                            // and the full WebView setup pipeline runs against it.
                            activity?.runOnUiThread {
                                Toast.makeText(
                                    context,
                                    "Recovering from WebView crash...",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                val self = this@HomeFragment
                                parentFragmentManager.beginTransaction()
                                    .detach(self)
                                    .attach(self)
                                    .commitAllowingStateLoss()
                            }

                            // Indicate we handled the crash
                            return true
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to recover from renderer crash: ${e.message}")
                            Log.e(TAG, "Stack trace: ${Log.getStackTraceString(e)}")
                            
                            // Try to stay alive by forcing a URL refresh after a delay
                            activity?.runOnUiThread {
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    homeViewModel.refreshStatus()
                                }, 2000)
                            }
                            return true
                        }
                    }
                    
                    // If binding is null, we're likely being destroyed anyway
                    return true
                }
                
                /**
                 * Get memory info for debugging purposes
                 */
                private fun getMemoryInfo(): String {
                    val runtime = Runtime.getRuntime()
                    val usedMemInMB = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
                    val maxHeapSizeInMB = runtime.maxMemory() / 1048576L
                    val availHeapSizeInMB = maxHeapSizeInMB - usedMemInMB
                    
                    return "Used: $usedMemInMB MB, Max: $maxHeapSizeInMB MB, Available: $availHeapSizeInMB MB"
                }
                
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    url?.let { applyMixedContentModeFor(it) }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val safeBinding = _binding ?: return
                    safeBinding.loadingProgress.visibility = View.GONE

                    // Save the URL for restoration if WebView gets cleared
                    if (!url.isNullOrEmpty() && url != "about:blank") {
                        if (currentLoadedUrl != url) {
                            Log.d(TAG, "Page finished loading and URL saved: $url")
                            currentLoadedUrl = url
                            currentLoadedEndpoint = currentEndpoint
                        }
                        // Persist auth cookies (frigate_token) to disk immediately so
                        // a process death before onPause doesn't force a re-login.
                        android.webkit.CookieManager.getInstance().flush()
                    }

                    // Force user-scalable=no. Frigate serves a viewport meta that re-enables
                    // pinch-zoom even when setSupportZoom(false) is set on the WebSettings.
                    view?.evaluateJavascript(DISABLE_ZOOM_JS, null)

                    safeBinding.swipeRefresh.isRefreshing = false
                }
                
                override fun onReceivedError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    val safeBinding = _binding ?: return
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val url = request.url.toString()
                        // Only log non-preview clip errors (preview clips failing is common)
                        if (!url.contains("/clips/previews/")) {
                            Log.e(TAG, "WebView error: ${error.errorCode} - ${error.description} at $url")
                        } else {
                            Log.d(TAG, "Preview clip not available: $url")
                        }
                        safeBinding.loadingProgress.visibility = View.GONE
                        
                        // Check if it's a connection error and retry only for critical resources
                        if (error.errorCode == WebViewClient.ERROR_CONNECT ||
                            error.errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
                            error.errorCode == WebViewClient.ERROR_TIMEOUT ||
                            error.errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE) {
                            
                            // If this is an analytics error, we can safely ignore it
                            if (request.url.toString().contains("cloudflareinsights") || 
                                request.url.toString().contains("analytics")) {
                                Log.d(TAG, "Ignoring analytics error - not critical for page loading")
                                return
                            }
                            
                            // Check if the error is for the main page
                            val isMainFrameError = request.isForMainFrame || 
                                (currentLoadedUrl != null && request.url.toString().startsWith(currentLoadedUrl!!))
                            
                            if (isMainFrameError) {
                                Log.d(TAG, "Critical page-loading error, refreshing network status")

                                // If SSL handshake failed and we have a saved certificate, it might be expired/invalid
                                if (error.errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE && clientCertManager.getSavedAlias() != null) {
                                    Log.w(TAG, "SSL handshake failed with saved certificate - clearing alias for re-selection")
                                    clientCertManager.clearAlias()
                                    Toast.makeText(context, "Certificate rejected - please select a new one", Toast.LENGTH_LONG).show()
                                }

                                // Trigger a network refresh which will reload the appropriate URL
                                homeViewModel.refreshStatus()
                            }
                        }
                    }
                }
            }
            
            
            // Setup WebChromeClient
            binding.webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d(TAG, "Console: ${consoleMessage?.message()} -- From line " +
                            "${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                    return true
                }
                
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback
                    
                    val intent = fileChooserParams?.createIntent()
                    try {
                        fileChooserLauncher.launch(intent)
                    } catch (e: Exception) {
                        fileUploadCallback = null
                        return false
                    }
                    return true
                }
                
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    val safeBinding = _binding ?: return
                    
                    if (newProgress < 100) {
                        safeBinding.loadingProgress.visibility = View.VISIBLE
                        safeBinding.loadingProgress.progress = newProgress
                    } else {
                        safeBinding.loadingProgress.visibility = View.GONE
                    }
                }
                
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.resources?.let { resources ->
                        val resourceList = mutableListOf<String>()
                        
                        // Check video capture permission
                        if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                            if (ContextCompat.checkSelfPermission(requireContext(), 
                                    Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                resourceList.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                            }
                        }
                        
                        // Check audio capture permission - critical for WebRTC
                        if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                            if (ContextCompat.checkSelfPermission(requireContext(), 
                                    Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                Log.d(TAG, "Granting RESOURCE_AUDIO_CAPTURE permission to WebView")
                                resourceList.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                            } else {
                                Log.d(TAG, "Cannot grant RESOURCE_AUDIO_CAPTURE - Android permission not granted")
                            }
                        }
                        
                        // Grant other requested resources by default
                        resources.forEach { resource ->
                            if (resource != PermissionRequest.RESOURCE_VIDEO_CAPTURE && 
                                resource != PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                                resourceList.add(resource)
                            }
                        }
                        
                        // Grant permissions if we have any to grant
                        if (resourceList.isNotEmpty()) {
                            try {
                                Log.d(TAG, "Granting WebView permissions: ${resourceList.joinToString()}")
                                request.grant(resourceList.toTypedArray())
                            } catch (e: Exception) {
                                Log.e(TAG, "Error granting permissions: ${e.message}")
                                request.deny()
                            }
                        } else {
                            Log.d(TAG, "No permissions to grant, denying request")
                            request.deny()
                        }
                    } ?: run {
                        Log.d(TAG, "Empty permission request, denying")
                        request?.deny()
                    }
                }
                
                override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                    Log.d(TAG, "Entering fullscreen mode")
                    
                    if (customView != null) {
                        onHideCustomView()
                        return
                    }
                    
                    customView = view
                    customViewCallback = callback
                    
                    // Hide system UI for immersive fullscreen using modern API
                    hideSystemBars()
                    
                    // Hide normal content and show fullscreen container
                    binding.swipeRefresh.visibility = View.GONE
                    binding.loadingProgress.visibility = View.GONE
                    binding.fullscreenContainer.visibility = View.VISIBLE
                    
                    // Add custom view to fullscreen container
                    binding.fullscreenContainer.addView(view)
                }
                
                override fun onHideCustomView() {
                    Log.d(TAG, "Exiting fullscreen mode")
                    
                    if (customView == null) return
                    
                    // Remove custom view from container
                    binding.fullscreenContainer.removeView(customView)
                    customView = null
                    
                    // Hide fullscreen container and show normal content
                    binding.fullscreenContainer.visibility = View.GONE
                    binding.swipeRefresh.visibility = View.VISIBLE
                    
                    // Restore system UI visibility using modern API
                    showSystemBars()
                    
                    // Notify callback that we're done
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                }
            }
            
            // Route WebView-initiated downloads through the extracted handler.
            binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                downloadHandler.handleWebViewDownload(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimetype = mimetype,
                    currentPageUrl = binding.webView.url
                )
            }
            
            try {
                setupMediaPermissions()
                WebViewConfigurator.apply(
                    binding.webView,
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                )
                binding.swipeRefresh.setOnRefreshListener {
                    homeViewModel.refreshStatus()
                    binding.webView.reload()
                    binding.swipeRefresh.isRefreshing = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during WebView setup: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WebView: ${e.message}")
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Only save WebView state if binding is not null
        _binding?.let { safeBinding ->
            safeBinding.webView.saveState(outState)
        }
    }
    
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        val web = _binding?.webView ?: return
        val stateToRestore = savedInstanceState ?: homeViewModel.savedWebViewState
        if (stateToRestore != null) {
            web.restoreState(stateToRestore)
            // Re-apply Frigate Viewer's canonical WebSettings. Without this, restoreState
            // re-hydrates the WebView with the page's own settings (including viewport
            // meta that re-enables pinch-zoom) and wins over setupWebView's earlier call.
            WebViewConfigurator.apply(
                web,
                PreferenceManager.getDefaultSharedPreferences(requireContext())
            )
        }
        // Consume the ViewModel copy so a subsequent fresh load doesn't resurrect it.
        homeViewModel.savedWebViewState = null
    }
    
    override fun onDestroyView() {
        // Clean up fullscreen mode if active
        if (customView != null) {
            Log.d(TAG, "Cleaning up fullscreen mode during destroy")
            binding.fullscreenContainer.removeView(customView)
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            // Restore system UI visibility using modern API
            showSystemBars()
        }
        
        // Handle file upload callback first
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null
        
        try {
            // Use safe binding access to prevent crashes during cleanup
            _binding?.let { safeBinding ->
                // Stash WebView state into the ViewModel so Settings<->Home navigation
                // preserves the current page, scroll position, and form state.
                homeViewModel.savedWebViewState = Bundle().also { safeBinding.webView.saveState(it) }

                // Safer WebView cleanup sequence - prevent calls that might crash renderer
                safeBinding.webView.run {
                    // Stop any loading operations first
                    stopLoading()

                    // Remove WebView callbacks to prevent memory leaks
                    setWebViewClient(WebViewClient())
                    setWebChromeClient(null)

                    // Clear WebView content with minimal operations
                    loadUrl("about:blank")

                    // Safe destroy that complies with renderer lifecycle
                    onPause()

                    // Use a delayed destroy for WebView to avoid race conditions
                    // with renderer process cleanup
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            destroy()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error destroying WebView: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during WebView cleanup: ${e.message}")
        } finally {
            // Always null out the binding reference
            _binding = null
        }
        
        // Let parent handle the remainder of cleanup
        super.onDestroyView()
    }
    
    /**
     * Basic refresh of the network status through the ViewModel
     */
    fun refreshNetworkStatus() {
        // Only proceed if fragment is still active
        if (!isAdded) {
            Log.d(TAG, "Fragment not attached, skipping network refresh")
            return
        }
        
        activity?.runOnUiThread {
            try {
                // Simply refresh the network status
                // This will trigger the URL observer which will handle any URL changes
                homeViewModel.refreshStatus()
                Log.d(TAG, "Network status refresh requested")
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing network status", e)
            }
        }
    }
    
    /**
     * Force refresh requested by user action (from settings or button)
     * Performs a complete WebView reset and reload with fresh network status
     */
    fun forceNetworkRefresh() {
        // Only proceed if fragment is still active
        if (!isAdded) {
            Log.d(TAG, "Fragment not attached, skipping force refresh")
            return
        }
        
        activity?.runOnUiThread {
            try {
                Log.d(TAG, "Force network refresh requested")
                
                // Only proceed if binding is valid
                _binding?.let { safeBinding ->
                    // Show loading indicator
                    safeBinding.loadingProgress.visibility = View.VISIBLE
                    
                    // More aggressive WebView cleanup for force refresh
                    safeBinding.webView.run {
                        // Clear everything possible
                        clearHistory()
                        clearFormData()
                        clearSslPreferences()
                    }

                    // Get current URL and reload directly (bypass debouncing)
                    val currentUrl = networkUtils.currentUrl.value
                    if (currentUrl != null) {
                        safeBinding.webView.loadUrl(currentUrl)
                        currentLoadedUrl = currentUrl
                    } else {
                        // Fallback to refreshing network status
                        homeViewModel.refreshStatus()
                    }

                    // Determine network mode (internal vs external)
                    val isHomeNetwork = networkUtils.isHome()
                    
                    // Notify user of the refresh
                    Toast.makeText(
                        context,
                        "Refreshing ${if (isHomeNetwork) "internal" else "external"} content",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during force network refresh", e)
            }
        }
    }
    
    /**
     * Inject JavaScript code (for custom fullscreen buttons)
     */
    fun injectFullscreenButton(jsCode: String) {
        activity?.runOnUiThread {
            try {
                _binding?.let { safeBinding ->
                    safeBinding.webView.evaluateJavascript(jsCode) { result ->
                        if (result != null) {
                            Log.d(TAG, "Fullscreen button injection result: $result")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error injecting fullscreen button", e)
            }
        }
    }
    
    /**
     * Handles app navigation when there's no more WebView history
     */
    private fun handleBackNavigation() {
        // Just finish the activity normally instead of force-killing the process
        activity?.finish()
    }
    
    private fun setupBackButtonHandler() {
        val callback = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // First check if we're in fullscreen mode
                if (customView != null) {
                    Log.d(TAG, "Back pressed in fullscreen mode - exiting fullscreen")
                    binding.webView.webChromeClient?.onHideCustomView()
                    return
                }
                
                // Always check binding before accessing WebView
                _binding?.let { safeBinding ->
                    if (safeBinding.webView.canGoBack()) {
                        safeBinding.webView.goBack()
                    } else {
                        // No more history, exit the app gracefully
                        handleBackNavigation()
                    }
                } ?: run {
                    // If binding is null, just finish the activity
                    handleBackNavigation()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }
    
    /**
     * Set up media permissions for WebView
     */
    private fun setupMediaPermissions() {
        try {
            val perms = arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.MODIFY_AUDIO_SETTINGS,
                Manifest.permission.CAMERA,
            )
            val missing = perms.filter {
                ContextCompat.checkSelfPermission(requireContext(), it) !=
                    PackageManager.PERMISSION_GRANTED
            }

            if (missing.isEmpty()) {
                Log.d(TAG, "WebRTC audio/video permissions granted")
                binding.webView.settings.mediaPlaybackRequiresUserGesture = false
                (requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .setMode(AudioManager.MODE_NORMAL)
                // WebChromeClient.onPermissionRequest will deliver the permissions to the WebView.
            } else {
                Log.d(TAG, "Requesting WebRTC permissions: ${missing.joinToString()}")
                ActivityCompat.requestPermissions(requireActivity(), missing.toTypedArray(), 102)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up WebRTC media permissions: ${e.message}")
        }
    }
    
    override fun onPause() {
        super.onPause()

        try {
            // Flush cookies to persist authentication across network changes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                android.webkit.CookieManager.getInstance().flush()
            }

            // Use a safer approach to pausing WebView
            _binding?.let { safeBinding ->
                safeBinding.webView.onPause()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing WebView: ${e.message}")
        }

        // Don't clear cache or force GC in onPause as this can cause renderer crashes
        // during fragment transitions
    }
    
    override fun onResume() {
        super.onResume()

        // Always refresh status when returning to the fragment
        // This ensures any URL changes made in settings are applied
        homeViewModel.refreshStatus()
        Log.d(TAG, "HomeFragment resumed - refreshing network status to get latest URL")

        // Deep-link handling for notification taps on a *warm* app (onNewIntent path).
        // On a cold start we defer to primeFrigateSessionAsync so the base-URL post-login
        // load doesn't race past us and overwrite the review target. preLoginDone turns
        // true exactly once the cold-start path is finished, so this branch only fires
        // on genuine warm invocations.
        if (preLoginDone) {
            com.asksakis.freegate.notifications.DeepLinkRouter.consumePendingReviewId()?.let { id ->
                val base = networkUtils.currentUrl.value?.trimEnd('/') ?: return@let
                val target = "$base/review?id=$id"
                Log.d(TAG, "Following notification deep-link (warm) to $target")
                _binding?.webView?.loadUrl(target)
                currentLoadedUrl = target
            }
        }
        
        // Use safe binding access to prevent crashes during resume
        _binding?.let { safeBinding ->
            try {
                // Resume WebView first so it's ready for any loading
                safeBinding.webView.onResume()
                
                // Only restore WebView if it's completely blank, not if it has a valid URL
                val webViewUrl = safeBinding.webView.url
                if ((webViewUrl == null || webViewUrl == "about:blank") && currentLoadedUrl != null) {
                    Log.d(TAG, "WebView URL is empty, reloading: $currentLoadedUrl")
                    
                    // Add a slight delay to let WebView fully initialize before loading
                    // This helps prevent renderer crashes
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        // Check binding again after delay
                        if (_binding != null && isAdded) {
                            safeBinding.webView.loadUrl(currentLoadedUrl!!)
                        }
                    }, 100) // 100ms delay
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during WebView resume: ${e.message}")
            }
        }
    }
    
    /**
     * Handle low memory conditions
     */
    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory called - trying to free resources")
        
        // Only clear cache, avoid more aggressive cleanup
        _binding?.webView?.clearCache(true)
    }
    
    /**
     * Hide system bars for fullscreen immersive experience
     */
    private fun hideSystemBars() {
        activity?.window?.let { window ->
            wasSystemBarsVisible = true // Store that bars were visible
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Modern API for Android 11+ (API 30+)
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.insetsController?.apply {
                    hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                // Fallback to deprecated API for older versions
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }
        }
    }
    
    /**
     * Show system bars when exiting fullscreen
     */
    private fun showSystemBars() {
        activity?.window?.let { window ->
            if (!wasSystemBarsVisible) return // Don't restore if they weren't visible

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Modern API for Android 11+ (API 30+)
                WindowCompat.setDecorFitsSystemWindows(window, true)
                window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            } else {
                // Fallback to deprecated API for older versions
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    /**
     * Switch the WebView's mixed-content policy based on whether the current page is
     * internal (LAN/private IP) or external (public HTTPS). Internal pages may embed
     * plain-HTTP stream endpoints; external pages must stay strict to prevent MITM.
     */
    private fun applyMixedContentModeFor(url: String) {
        val webView = _binding?.webView ?: return
        webView.settings.mixedContentMode = if (UrlUtils.isPrivateIpUrl(url)) {
            WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        } else {
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
    }

    /**
     * Prompt user to pick a new client cert, then provide it to the request
     * (or cancel the request if the user declined).
     */
    private fun promptForNewCertificate(request: ClientCertRequest?) {
        val act = activity
        if (act == null) {
            Log.e(TAG, "Activity not available for certificate selection")
            request?.cancel()
            return
        }
        clientCertManager.promptForCertificate(act, request) { alias ->
            if (alias != null) {
                clientCertManager.provideCertificate(request, alias) { failedRequest ->
                    act.runOnUiThread { failedRequest?.cancel() }
                }
            } else {
                Log.w(TAG, "No certificate selected")
                request?.cancel()
            }
        }
    }
}