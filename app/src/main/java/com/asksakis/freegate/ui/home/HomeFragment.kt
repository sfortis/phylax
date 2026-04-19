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
        setupUrlObserver()

        // If the user has Frigate credentials stored, seed the auth cookie into the
        // WebView's CookieManager so the first page load comes back authenticated. We
        // fire this in parallel with the observer's initial load; when it finishes,
        // reload the WebView so the (now authenticated) page replaces the login screen.
        primeFrigateSessionAsync()

        return root
    }

    private fun primeFrigateSessionAsync() {
        val credentials = com.asksakis.freegate.auth.CredentialsStore.getInstance(requireContext())
        if (!credentials.hasCredentials()) return
        val authManager = com.asksakis.freegate.auth.FrigateAuthManager.getInstance(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            val baseUrl = resolveBaseUrlForLogin() ?: return@launch
            val ok = authManager.ensureLoggedIn(baseUrl)
            if (!ok) {
                Log.w(TAG, "Pre-login failed; WebView will fall back to login form")
                return@launch
            }
            val web = _binding?.webView ?: return@launch

            // If a notification tap has staged a deep-link, load the review URL instead of
            // the base — otherwise the base load would overwrite the deep-link target.
            val deepLinkId = com.asksakis.freegate.notifications.DeepLinkRouter.consumePendingReviewId()
            val target = if (deepLinkId != null) "$baseUrl/review?id=$deepLinkId" else baseUrl
            Log.d(TAG, "Pre-login succeeded, loading $target with session cookie")
            web.loadUrl(target)
            currentLoadedUrl = target
            preLoginDone = true
        }
    }

    private fun resolveBaseUrlForLogin(): String? {
        networkUtils.currentUrl.value?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return (prefs.getString("internal_url", null) ?: prefs.getString("external_url", null))
            ?.trimEnd('/')
    }
    
    private var lastUrlChangeTime = 0L
    private val URL_DEBOUNCE_MS = 10000L // Debounce URL changes by 10 seconds
    private val URL_DEBOUNCE_MODE_SWITCH_MS = 500L // Much shorter debounce for INT->EXT switches
    private val NETWORK_TRANSITION_DEBOUNCE_MS = 3000L // Debounce for network transitions
    private var lastRequestedUrl: String? = null
    private var networkValidationInProgress = false
    
    private fun setupUrlObserver() {
        // Observe URL changes from NetworkUtils via HomeViewModel
        homeViewModel.currentUrl.observe(viewLifecycleOwner) { url ->
            Log.d(TAG, "URL LiveData updated: $url")
            
            if (url != null && !urlLoadInProgress) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastChange = currentTime - lastUrlChangeTime
                
                // Check if this is an INT to EXT mode switch (high priority)
                val isSignificantModeSwitch = isInternalToExternalSwitch(url) || isExternalToInternalSwitch(url)
                val debounceTime = if (isSignificantModeSwitch) URL_DEBOUNCE_MODE_SWITCH_MS else URL_DEBOUNCE_MS
                
                // Debounce URL changes to avoid rapid reloads, use shorter debounce for mode switches
                if (timeSinceLastChange < debounceTime && !isSignificantModeSwitch) {
                    Log.d(TAG, "Ignoring URL change due to debouncing (${timeSinceLastChange}ms < ${debounceTime}ms)")
                    return@observe
                }
                
                // On significant mode switch (INT<->EXT), always force reload with connectivity check
                if (isSignificantModeSwitch) {
                    val isExtToInt = isExternalToInternalSwitch(url)
                    Log.d(TAG, "Network mode switch detected (extToInt=$isExtToInt) - forcing reload")
                    binding.loadingProgress.visibility = View.VISIBLE
                    lastRequestedUrl = url
                    lastUrlChangeTime = currentTime
                    // For EXT->INT switch, wait for cellular to be fully torn down
                    loadUrlWithConnectivityCheck(url, waitForCellularTeardown = isExtToInt)
                    return@observe
                }

                // Early exit if this is the same URL we already requested
                if (url == lastRequestedUrl) {
                    Log.d(TAG, "Ignoring duplicate URL request: $url")
                    return@observe
                }
                
                lastRequestedUrl = url
                
                // Create a local copy of the URL to avoid concurrency issues
                val localCurrentUrl = currentLoadedUrl
                
                // Handle first load case
                if (localCurrentUrl == null) {
                    Log.d(TAG, "Initial load: $url")
                    binding.loadingProgress.visibility = View.VISIBLE
                    loadUrlWithConnectivityCheck(url)
                    lastUrlChangeTime = currentTime
                    return@observe
                }
                
                // We now know localCurrentUrl is not null
                // Get base URLs without fragments for comparison
                val currentBase = localCurrentUrl.split("#")[0]
                val newBase = url.split("#")[0]
                
                // Determine if we need to reload
                if (currentBase != newBase) {
                    // Base URL changed, do full reload
                    val priority = if (isSignificantModeSwitch) "⚠️ HIGH PRIORITY" else "normal"
                    Log.d(TAG, "$priority Base URL changed from $currentBase to $newBase")
                    binding.loadingProgress.visibility = View.VISIBLE
                    
                    // Force reload for significant mode switches, especially internal->external
                    // This is critical for user experience
                    if (isSignificantModeSwitch) {
                        Log.d(TAG, "Significant mode switch detected - forcing immediate reload")

                        // Show loading indicator
                        binding.loadingProgress.visibility = View.VISIBLE

                        // Note: Don't clear cache/cookies to preserve authentication
                        // Mode switches are silent; the loading indicator is enough user feedback.

                        // Direct load for mode switches - most reliable approach
                        binding.webView.loadUrl(url)
                        currentLoadedUrl = url
                        lastUrlChangeTime = System.currentTimeMillis()
                    } else {
                        // For non-critical changes, use the connectivity check approach
                        Log.d(TAG, "Non-critical URL change, using connectivity check")
                        loadUrlWithConnectivityCheck(url)
                        lastUrlChangeTime = currentTime
                    }
                } else if (localCurrentUrl != url) {
                    // Only fragment changed, use direct navigation (no reload needed)
                    Log.d(TAG, "Only fragment changed, using JS navigation")
                    binding.webView.loadUrl(url)
                    lastUrlChangeTime = currentTime
                } else {
                    // Completely unchanged
                    Log.d(TAG, "URL completely unchanged, skipping reload")
                }
            }
        }
        
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
                    
                    // Hide loading indicator if present
                    if (binding.loadingProgress.visibility == View.VISIBLE) {
                        binding.loadingProgress.visibility = View.GONE
                    }
                }
                NetworkUtils.ValidationStatus.FAILED, NetworkUtils.ValidationStatus.TIMEOUT -> {
                    Log.d(TAG, "URL validation failed: ${result.url} - ${result.message}")
                    networkValidationInProgress = false

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
    private fun loadUrlWithConnectivityCheck(url: String, retryCount: Int = 0, waitForCellularTeardown: Boolean = false) {
        // Prevent multiple simultaneous URL loads
        if (urlLoadInProgress && retryCount == 0) {
            Log.d(TAG, "URL load already in progress, skipping new request for: $url")
            return
        }

        urlLoadInProgress = true

        // Advanced connectivity check - verify not just presence of network but validation state
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork

        val capabilities = if (activeNetwork != null) {
            connectivityManager.getNetworkCapabilities(activeNetwork)
        } else null

        val hasValidatedNetwork = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        // Only check for cellular teardown when explicitly requested (during EXT->INT switch)
        val anyCellularActive = if (waitForCellularTeardown) {
            connectivityManager.allNetworks.any { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            }
        } else {
            false // Don't care about cellular for normal loads
        }

        // For internal URLs during mode switch, wait for cellular teardown
        val networkReady = if (waitForCellularTeardown) {
            hasValidatedNetwork && isWifi && !anyCellularActive
        } else {
            hasValidatedNetwork
        }

        Log.d(TAG, "Network check: validated=$hasValidatedNetwork, wifi=$isWifi, waitCellular=$waitForCellularTeardown, anyCellular=$anyCellularActive, ready=$networkReady")

        // Always check if binding is still valid
        _binding?.let { safeBinding ->
            if (networkReady) {
                // Direct URL loading approach for validated networks
                safeBinding.webView.loadUrl(url)
                currentLoadedUrl = url
                Log.d(TAG, "Loading URL: $url")
                urlLoadInProgress = false
            } else {
                Log.d(TAG, "Validated network not available for URL: $url (retry #$retryCount)")
                
                // Implement exponential backoff for retries
                if (retryCount < 3) { // Max 3 retries (0, 1, 2)
                    val backoffTime = 1000L * (1L shl retryCount) // 1s, 2s, 4s
                    Log.d(TAG, "Scheduling retry #${retryCount+1} in ${backoffTime}ms")
                    
                    // Schedule a retry with exponential backoff
                    safeBinding.webView.postDelayed({
                        // Check again if binding and fragment are valid
                        if (_binding != null && isAdded && (currentLoadedUrl != url || currentLoadedUrl == null)) {
                            Log.d(TAG, "Executing retry #${retryCount+1} for URL: $url")
                            loadUrlWithConnectivityCheck(url, retryCount + 1, waitForCellularTeardown)
                        } else {
                            urlLoadInProgress = false
                            Log.d(TAG, "Cancelled retry #${retryCount+1} - binding gone or URL changed")
                        }
                    }, backoffTime)
                } else {
                    // Max retries reached - give up and reset loading state
                    Log.d(TAG, "Max retries reached, giving up on URL: $url")
                    urlLoadInProgress = false
                    
                    // Don't show toast for retry failures - just hide the loader
                    activity?.runOnUiThread {
                        // Hide loading indicator
                        safeBinding.loadingProgress.visibility = View.GONE
                    }
                }
            }
        } ?: run {
            Log.d(TAG, "Binding is null, cannot load URL: $url")
            urlLoadInProgress = false
        }
    }
    
    /**
     * Detect if this is a switch from internal to external URL (high priority change)
     * Delegates to UrlUtils for centralized URL detection logic.
     */
    private fun isInternalToExternalSwitch(newUrl: String): Boolean {
        val isSwitch = UrlUtils.isInternalToExternalSwitch(currentLoadedUrl, newUrl)
        if (isSwitch) {
            Log.d(TAG, "Detected internal->external URL switch (high priority)")
        }
        return isSwitch
    }

    /**
     * Detect if this is a switch from external to internal URL (also high priority)
     * Delegates to UrlUtils for centralized URL detection logic.
     */
    private fun isExternalToInternalSwitch(newUrl: String): Boolean {
        val isSwitch = UrlUtils.isExternalToInternalSwitch(currentLoadedUrl, newUrl)
        if (isSwitch) {
            Log.d(TAG, "Detected external->internal URL switch (high priority)")
        }
        return isSwitch
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
                    // Only bypass SSL errors for internal/private hosts where self-signed
                    // certs are expected. Reject on external/public hosts to prevent MITM.
                    if (UrlUtils.isPrivateIpUrl(url)) {
                        Log.w(TAG, "SSL error on internal URL: $primaryError at $url - proceeding")
                        handler?.proceed()
                    } else {
                        Log.e(TAG, "SSL error on external URL: $primaryError at $url - cancelling")
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

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    Log.d(TAG, "shouldOverrideUrlLoading: $url")
                    
                    // Handle intent:// URLs
                    if (url?.startsWith("intent://") == true) {
                        try {
                            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                            if (intent != null) {
                                val packageManager = view?.context?.packageManager
                                val info = packageManager?.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                                if (info != null) {
                                    view.context?.startActivity(intent)
                                } else {
                                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                    if (fallbackUrl != null) {
                                        view?.loadUrl(fallbackUrl)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        return true
                    }
                    
                    // Handle regular URLs
                    return if (url?.startsWith("http://") == true || url?.startsWith("https://") == true) {
                        view?.loadUrl(url)
                        true
                    } else {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            startActivity(intent)
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
                            // Clear the old WebView
                            safeBinding.webView.run {
                                stopLoading()
                                clearHistory()
                                clearCache(true)
                                loadUrl("about:blank")
                                onPause()
                                removeAllViews()
                                destroy()
                            }
                            
                            // Re-create the WebView and reload
                            activity?.runOnUiThread {
                                // Instead of recreating WebView, create a new one in place of the old one
                                val webViewParent = safeBinding.webView.parent as ViewGroup
                                val webViewIndex = webViewParent.indexOfChild(safeBinding.webView)
                                
                                // Remove old WebView
                                webViewParent.removeView(safeBinding.webView)
                                
                                // Create new WebView with reduced features to improve stability
                                val newWebView = WebView(requireContext())
                                newWebView.layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                newWebView.id = safeBinding.webView.id
                                
                                // Add new WebView where the old one was
                                webViewParent.addView(newWebView, webViewIndex)
                                
                                // Set up the new WebView
                                setupWebView()
                                
                                // Trigger a reload of the current URL after a slightly longer delay
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    Toast.makeText(
                                        context,
                                        "Recovering from WebView crash...",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    homeViewModel.refreshStatus()
                                }, 1500)
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
                        // Only log if URL actually changed
                        if (currentLoadedUrl != url) {
                            Log.d(TAG, "Page finished loading and URL saved: $url")
                            currentLoadedUrl = url
                        }
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
            // Check for required audio permissions
            val hasRecordAudio = ContextCompat.checkSelfPermission(
                requireContext(), 
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            
            val hasModifyAudio = ContextCompat.checkSelfPermission(
                requireContext(), 
                Manifest.permission.MODIFY_AUDIO_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED
            
            // Log WebRTC capability status
            if (hasRecordAudio && hasModifyAudio) {
                Log.d(TAG, "WebRTC audio permissions granted, enabling audio capabilities")
                
                // Enable WebRTC audio features
                binding.webView.settings.mediaPlaybackRequiresUserGesture = false
                
                // Set audio mode for two-way communication
                val audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setMode(AudioManager.MODE_NORMAL)
                
                // No need to manually call onPermissionRequest - WebView will trigger it when needed
                // and our WebChromeClient will handle it via overridden onPermissionRequest method
            } else {
                Log.d(TAG, "WebRTC audio permissions not granted, requesting permissions")
                // Request permissions if not granted yet
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.MODIFY_AUDIO_SETTINGS
                    ),
                    102
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up WebRTC audio: ${e.message}")
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