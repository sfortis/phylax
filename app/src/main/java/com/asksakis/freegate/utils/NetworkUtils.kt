package com.asksakis.freegate.utils

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Request

/**
 * Manages network detection and URL selection.
 *
 * TODO(compose-migration): split into UrlResolver (mode→url), UrlReachabilityChecker
 * (validate/probe/latency), and NetworkCallbackAggregator (callback→state machine).
 * Until then the @Suppress("LargeClass") is deliberate — every responsibility here is
 * tied to the same SSID/transport signature and splitting without the Compose rewrite
 * would duplicate that coordination.
 */
@Suppress("LargeClass")
class NetworkUtils private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "NetworkUtils"
        private val FLAG_INCLUDE_LOCATION_INFO = 
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        
        // URL validation constants
        private const val VALIDATION_TIMEOUT_MS = 5000L // 5 seconds timeout for validation
        private const val MAX_VALIDATION_RETRIES = 3 // Maximum number of validation retries
        private const val LATENCY_HISTORY_SIZE = 24 // rolling buffer for the status dialog graph

        // Window for coalescing rapid forceRefresh() calls; longer than any expected
        // caller burst but short enough to feel instant to the user.
        private const val FORCE_REFRESH_DEBOUNCE_MS = 100L


        @Volatile
        private var INSTANCE: NetworkUtils? = null
        
        fun getInstance(context: Context): NetworkUtils {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkUtils(context.applicationContext).also { 
                    INSTANCE = it 
                }
            }
        }
    }
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    
    data class ResolvedEndpoint(val url: String, val isInternal: Boolean)

    // Combined URL + mode emitted atomically. UI observers that need both (HomeFragment's
    // readiness rules) should read this to avoid observing them out of order.
    private val _endpoint = MutableLiveData<ResolvedEndpoint>()
    val endpoint: LiveData<ResolvedEndpoint> = _endpoint

    // Kept as separate LiveData for callers that only need one side (badge, legacy
    // observers). They're derived from the same `emitEndpoint` call — in sync by design.
    private val _currentUrl = MutableLiveData<String?>()
    val currentUrl: LiveData<String?> = _currentUrl

    private val _isInternal = MutableLiveData<Boolean>()
    val isInternal: LiveData<Boolean> = _isInternal
    
    // URL validation status
    private val _urlValidationStatus = MutableLiveData<ValidationResult>()
    val urlValidationStatus: LiveData<ValidationResult> = _urlValidationStatus

    // Rolling window of recent HEAD-probe latencies (ms). Failures push 0. Used by the
    // connection-status dialog to plot a tiny graph — nothing depends on this for logic.
    private val latencyHistoryBuffer = ArrayDeque<Long>(LATENCY_HISTORY_SIZE)
    private val _latencyHistory = MutableLiveData<List<Long>>(emptyList())
    val latencyHistory: LiveData<List<Long>> = _latencyHistory

    private fun recordLatencySample(ms: Long) {
        synchronized(latencyHistoryBuffer) {
            if (latencyHistoryBuffer.size >= LATENCY_HISTORY_SIZE) {
                latencyHistoryBuffer.removeFirst()
            }
            latencyHistoryBuffer.addLast(ms)
            _latencyHistory.postValue(latencyHistoryBuffer.toList())
        }
    }
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    // Transport signature tracking for network state changes (thread-safe)
    @Volatile private var lastNetworkState: String? = null
    @Volatile private var lastTransportSignature: String? = null
    @Volatile private var forceRefresh = false
    private val validationInProgress = AtomicBoolean(false)
    private val retryCount = AtomicInteger(0)

    // Network transition tracking (thread-safe)
    private val networkTransitionInProgress = AtomicBoolean(false)
    @Volatile private var lastTransitionTime = 0L
    private val minTransitionIntervalMs = 5_000L // minimum between URL-emit transitions
    @Volatile private var scheduledTransitionCheck: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // Event system for network changes
    private val _networkEvent = MutableLiveData<NetworkEvent>()
    val networkEvent: LiveData<NetworkEvent> = _networkEvent

    // Show toast messages for important network events
    @Volatile private var showToasts = true

    // Last known network state (thread-safe)
    @Volatile private var lastKnownSsid: String? = null
    @Volatile private var isInternalUrl = true
    
    // Enum to represent validation results
    enum class ValidationStatus {
        IN_PROGRESS, SUCCESS, FAILED, TIMEOUT
    }
    
    // Data class for validation result
    data class ValidationResult(
        val status: ValidationStatus,
        val url: String?,
        val isInternal: Boolean,
        val message: String = ""
    )
    
    // Event types for network changes
    enum class NetworkEventType {
        WIFI_CONNECTED, WIFI_DISCONNECTED, 
        EXTERNAL_URL, INTERNAL_URL,
        HOME_NETWORK_DETECTED, EXTERNAL_NETWORK_DETECTED,
        CONNECTION_MODE_CHANGED, VALIDATION_SUCCESS, VALIDATION_FAILED
    }
    
    // Network event data class
    data class NetworkEvent(
        val type: NetworkEventType,
        val message: String,
        val data: Map<String, Any> = emptyMap()
    )
    
    init {
        checkAndUpdateUrl()
        registerCallback()
    }
    
    /**
     * Helper method to send network events and show toast messages
     * 
     * @param type The type of network event
     * @param message The message to include with the event
     * @param showToast Whether to show a toast message to the user
     * @param data Additional data to include with the event
     */
    private fun sendNetworkEvent(
        type: NetworkEventType, 
        message: String, 
        showToast: Boolean = false,
        data: Map<String, Any> = emptyMap()
    ) {
        Log.d(TAG, "Network event: $type - $message")
        
        // Send the event to listeners
        _networkEvent.postValue(NetworkEvent(type, message, data))
        
        // Show toast if enabled
        if (showToast && showToasts) {
            handler.post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Enable or disable toast message notifications
     */
    fun setToastNotifications(enabled: Boolean) {
        showToasts = enabled
    }
    
    /**
     * Create the ConnectivityManager callback used by [registerCallback].
     *
     * The flags-accepting `NetworkCallback(int)` constructor only exists on API 31+,
     * so we branch on Build.VERSION — instantiating it on Android 29/30 would raise
     * NoSuchMethodError at runtime. Both variants forward to the same handler methods
     * below so the logic stays single-sourced.
     */
    private fun createNetworkCallback(): ConnectivityManager.NetworkCallback {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = handleAvailable()
                override fun onLost(network: Network) = handleLost(network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    handleCapabilitiesChanged(caps)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = handleAvailable()
                override fun onLost(network: Network) = handleLost(network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    handleCapabilitiesChanged(caps)
            }
        }
    }

    private fun handleAvailable() {
        // Don't re-evaluate the URL here — onAvailable fires before the network has
        // completed its validation/DNS setup, which was causing the WebView to reload
        // with ERR_CONNECTION_ABORTED. Wait for onCapabilitiesChanged with
        // NET_CAPABILITY_VALIDATED (handleCapabilitiesChanged gates on this).
        Log.d(TAG, "Network available (waiting for validation)")
        sendNetworkEvent(
            NetworkEventType.WIFI_CONNECTED,
            "Network connected, checking type...",
            false,
        )
    }

    private fun handleLost(lostNetwork: Network) {
        // Only react when the active/default network has actually changed. Losing a
        // secondary network (e.g. cellular dropping while WiFi is primary) must not
        // force us to external.
        val activeNetwork = connectivityManager.activeNetwork
        val activeIsWifi = activeNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        Log.d(
            TAG,
            "Network lost ($lostNetwork); active=$activeNetwork activeIsWifi=$activeIsWifi",
        )

        sendNetworkEvent(
            NetworkEventType.WIFI_DISCONNECTED,
            "Network disconnected",
            false,
        )

        // Reset cached state so the next callback can re-run the evaluator.
        lastTransportSignature = null

        val mode = prefs.getString("connection_mode", "auto") ?: "auto"
        if (mode == "auto" && !activeIsWifi) {
            // The active network is no longer WiFi — pre-emit external immediately
            // instead of waiting for WifiManager's stale state to catch up.
            val externalUrl = getExternalUrl()
            emitEndpoint(externalUrl, internal = false)
            validateUrl(externalUrl, false)
        } else {
            checkAndUpdateUrl()
        }
    }

    private fun handleCapabilitiesChanged(networkCapabilities: NetworkCapabilities) {
        val hasWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val hasCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val hasValidatedInternet =
            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        if (!hasValidatedInternet) {
            Log.d(TAG, "Network not validated yet, waiting for validation")
            return
        }

        // Include SSID in the gate so a WiFi→WiFi transition (home → guest, etc.) still
        // triggers a URL re-evaluation. Transport-only signatures miss SSID changes.
        val ssidPart = getSsid().orEmpty()
        val transportSignature = "wifi:$hasWifi|cellular:$hasCellular|validated:true|ssid:$ssidPart"
        if (transportSignature == lastTransportSignature && !forceRefresh) return

        Log.d(TAG, "Validated network detected: $transportSignature (was: $lastTransportSignature)")
        lastTransportSignature = transportSignature

        val currentTime = System.currentTimeMillis()
        val timeSinceLastTransition = currentTime - lastTransitionTime

        if (networkTransitionInProgress.get() &&
            timeSinceLastTransition < minTransitionIntervalMs &&
            !forceRefresh
        ) {
            Log.d(
                TAG,
                "Network transition in progress, delaying URL update " +
                    "(${timeSinceLastTransition}ms < ${minTransitionIntervalMs}ms)",
            )
            scheduledTransitionCheck?.let { handler.removeCallbacks(it) }
            scheduledTransitionCheck = Runnable {
                Log.d(TAG, "Running delayed network transition check")
                networkTransitionInProgress.set(false)
                checkAndUpdateUrl()
            }
            handler.postDelayed(
                scheduledTransitionCheck!!,
                (minTransitionIntervalMs - timeSinceLastTransition).coerceAtLeast(1000),
            )
            return
        }

        networkTransitionInProgress.set(true)
        lastTransitionTime = currentTime
        Log.d(TAG, "Network validated - updating URL based on current network state")
        checkAndUpdateUrl()

        scheduledTransitionCheck?.let { handler.removeCallbacks(it) }
        scheduledTransitionCheck = Runnable {
            Log.d(TAG, "Network transition cooldown ended")
            networkTransitionInProgress.set(false)
        }
        handler.postDelayed(scheduledTransitionCheck!!, minTransitionIntervalMs)
    }

    /**
     * Registers for network callbacks to detect network changes
     */
    private fun registerCallback() {
        if (networkCallback != null) {
            return
        }
        
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        
        networkCallback = createNetworkCallback()
        
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        Log.d(TAG, "Registered network callback for connectivity changes")
    }
    
    fun unregisterCallback() {
        networkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
            networkCallback = null
        }
        
        // Clean up any pending handlers
        scheduledTransitionCheck?.let { handler.removeCallbacks(it) }
        scheduledTransitionCheck = null
        networkTransitionInProgress.set(false)
    }
    
    /**
     * Force a refresh of network state and URL
     * This bypasses any debounce timers
     */
    /**
     * Ask NetworkUtils to re-check the network and emit the right URL.
     *
     * Coalesces bursts of calls: on Settings->Home return, up to three separate
     * callers (SettingsFragment.onStop, HomeFragment.onResume, URL observer)
     * may call this within ~200ms. Without coalescing each triggers its own
     * HTTP validation and LiveData emit. With a short debounce we keep the
     * semantics (last writer wins) and fire exactly once.
     */
    fun forceRefresh() {
        Log.d(TAG, "Force refresh requested (debounced)")
        scheduledTransitionCheck?.let { handler.removeCallbacks(it) }
        networkTransitionInProgress.set(false)

        handler.removeCallbacks(forceRefreshRunnable)
        handler.postDelayed(forceRefreshRunnable, FORCE_REFRESH_DEBOUNCE_MS)
    }

    private val forceRefreshRunnable = Runnable {
        forceRefresh = true
        try {
            checkAndUpdateUrl()
        } finally {
            forceRefresh = false
        }
    }

    /**
     * Fire a validation probe right now for the currently-resolved URL, bypassing the
     * force-refresh debounce. Used by UI "refresh" buttons where the user expects
     * immediate feedback in the RTT graph.
     */
    fun probeCurrentUrlNow() {
        val target = endpoint.value?.url ?: _currentUrl.value ?: return
        validateUrl(target, endpoint.value?.isInternal ?: false)
    }
    
    /**
     * Validates if the URL is accessible and working
     * Uses a connection test to verify the URL is reachable
     * Returns true if successful, false otherwise
     */
    fun validateUrl(url: String?, isInternal: Boolean = false) {
        if (url == null || url.isEmpty()) {
            _urlValidationStatus.postValue(ValidationResult(
                ValidationStatus.FAILED, 
                url, 
                isInternal,
                "URL is empty or null"
            ))
            return
        }
        
        // Don't start another validation if one is already in progress
        if (!validationInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "URL validation already in progress, skipping")
            return
        }

        retryCount.set(0)
        
        // Post initial status
        _urlValidationStatus.postValue(ValidationResult(
            ValidationStatus.IN_PROGRESS, 
            url, 
            isInternal,
            "Testing URL connection..."
        ))
        
        // Run validation in a background thread
        Thread {
            doUrlValidation(url, isInternal)
        }.start()
    }
    
    /**
     * Internal method to perform actual URL validation with retry logic.
     * Iterative — never recurses — so validationInProgress is released exactly once
     * regardless of retry path. Retries only on timeouts, 5xx, and IOExceptions;
     * 4xx responses are treated as terminal (retrying won't change the answer).
     */
    private fun doUrlValidation(url: String, isInternal: Boolean) {
        try {
            Log.d(TAG, "Validating URL: $url (isInternal: $isInternal)")
            var attempt = 0
            while (true) {
                val outcome = runOneValidationAttempt(url)
                if (outcome.terminal || attempt >= MAX_VALIDATION_RETRIES) {
                    publishValidationResult(url, isInternal, outcome)
                    return
                }
                attempt++
                Log.d(TAG, "Retrying URL validation (attempt $attempt of $MAX_VALIDATION_RETRIES)")
                Thread.sleep(500L * (1 shl (attempt - 1))) // 500ms, 1s, 2s...
            }
        } finally {
            validationInProgress.set(false)
            retryCount.set(0)
        }
    }

    /**
     * One attempt. Returns an [AttemptOutcome] indicating whether validation succeeded,
     * failed permanently (terminal), or failed transiently (retry-eligible).
     *
     * TLS/mTLS is routed through [OkHttpClientFactory] so the validator and the rest of
     * the app share a single TLS policy and connection pool — no parallel trust wiring.
     */
    private fun runOneValidationAttempt(url: String): AttemptOutcome {
        val client = OkHttpClientFactory.build(
            url,
            ClientCertManager.getInstance(context),
            OkHttpClientFactory.Timeouts(
                connectSeconds = VALIDATION_TIMEOUT_MS / 1000,
                readSeconds = VALIDATION_TIMEOUT_MS / 1000,
            ),
        )
        val request = try {
            Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 FrigateViewer/1.0 URL-Validator")
                .build()
        } catch (e: IllegalArgumentException) {
            // Malformed URL (missing scheme, invalid chars, etc.) — treat as a terminal
            // failure instead of crashing the validation thread. User sees a clear error.
            Log.e(TAG, "Invalid URL for validation: $url (${e.message})")
            return AttemptOutcome.terminalFailure("Invalid URL: $url")
        }

        return try {
            val startTime = System.currentTimeMillis()
            client.newCall(request).execute().use { response ->
                val responseTime = System.currentTimeMillis() - startTime
                val code = response.code
                when (code) {
                    in 200..299 -> AttemptOutcome.success(code, responseTime)
                    in 300..399 -> {
                        Log.d(TAG, "URL redirects to: ${response.header("Location")}")
                        AttemptOutcome.success(code, responseTime)
                    }
                    in 400..499 -> AttemptOutcome.terminalFailure(
                        "Connection failed with status code: $code", code,
                    )
                    else -> AttemptOutcome.retryable(
                        "Connection failed with status code: $code", code,
                    )
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.d(TAG, "URL validation timed out: $url")
            AttemptOutcome.timeout()
        } catch (e: Exception) {
            Log.e(TAG, "URL validation error: ${e.message}")
            AttemptOutcome.retryable("Error: ${e.message}", responseCode = null)
        }
    }

    private fun publishValidationResult(url: String, isInternal: Boolean, outcome: AttemptOutcome) {
        when (outcome.status) {
            ValidationStatus.SUCCESS -> {
                Log.d(TAG, "URL validation successful: $url (code: ${outcome.responseCode}, time: ${outcome.timeMs}ms)")
                outcome.timeMs?.let { recordLatencySample(it) }
                _urlValidationStatus.postValue(ValidationResult(
                    ValidationStatus.SUCCESS, url, isInternal,
                    "Connection successful (${outcome.responseCode}) in ${outcome.timeMs}ms"
                ))
                sendNetworkEvent(
                    NetworkEventType.VALIDATION_SUCCESS,
                    if (isInternal) "Internal URL verified" else "External URL verified",
                    false,
                    mapOf(
                        "url" to url,
                        "response_code" to (outcome.responseCode ?: 0),
                        "time_ms" to (outcome.timeMs ?: 0),
                    )
                )
            }
            ValidationStatus.TIMEOUT -> {
                // Zero marks a failure — the graph renders a vertical red tick at that x.
                recordLatencySample(0L)
                _urlValidationStatus.postValue(ValidationResult(
                    ValidationStatus.TIMEOUT, url, isInternal,
                    "Connection timed out after ${VALIDATION_TIMEOUT_MS}ms"
                ))
                sendNetworkEvent(
                    NetworkEventType.VALIDATION_FAILED,
                    if (isInternal) "Internal URL connection timed out" else "External URL connection timed out",
                    false,
                    mapOf("url" to url, "timeout_ms" to VALIDATION_TIMEOUT_MS)
                )
            }
            ValidationStatus.FAILED -> {
                Log.d(TAG, "URL validation failed: $url (${outcome.message})")
                recordLatencySample(0L)
                _urlValidationStatus.postValue(ValidationResult(
                    ValidationStatus.FAILED, url, isInternal, outcome.message ?: "Connection failed"
                ))
                sendNetworkEvent(
                    NetworkEventType.VALIDATION_FAILED,
                    if (isInternal)
                        "Unable to connect to internal URL (${outcome.responseCode ?: "error"})"
                    else
                        "Unable to connect to external URL (${outcome.responseCode ?: "error"})",
                    false,
                    mapOf("url" to url, "response_code" to (outcome.responseCode ?: -1))
                )
            }
            ValidationStatus.IN_PROGRESS -> Unit // never published from here
        }
    }

    private data class AttemptOutcome(
        val status: ValidationStatus,
        val terminal: Boolean,
        val message: String? = null,
        val responseCode: Int? = null,
        val timeMs: Long? = null
    ) {
        companion object {
            fun success(code: Int, timeMs: Long) =
                AttemptOutcome(
                    ValidationStatus.SUCCESS, terminal = true,
                    responseCode = code, timeMs = timeMs,
                )
            fun terminalFailure(message: String, responseCode: Int) =
                AttemptOutcome(
                    ValidationStatus.FAILED, terminal = true,
                    message = message, responseCode = responseCode,
                )
            fun terminalFailure(message: String) =
                AttemptOutcome(ValidationStatus.FAILED, terminal = true, message = message)
            fun retryable(message: String, responseCode: Int?) =
                AttemptOutcome(
                    ValidationStatus.FAILED, terminal = false,
                    message = message, responseCode = responseCode,
                )
            fun timeout() =
                AttemptOutcome(ValidationStatus.TIMEOUT, terminal = false)
        }
    }
    
    /**
     * Publish [url] on [currentUrl] only if it differs from the last value. Prevents
     * observers from re-running expensive work (WebView reloads, HTTP validation)
     * every time the network callback fires for a state that didn't actually change.
     */
    private fun emitEndpoint(url: String, internal: Boolean) {
        isInternalUrl = internal
        val resolved = ResolvedEndpoint(url, internal)
        if (_endpoint.value != resolved) {
            _endpoint.postValue(resolved)
        }
        if (_currentUrl.value != url) {
            _currentUrl.postValue(url)
        }
        if (_isInternal.value != internal) {
            _isInternal.postValue(internal)
        }
    }


    /**
     * Core function that checks the current network and determines the appropriate URL
     * This is the primary function that handles all network state evaluation
     */
    @Suppress("ReturnCount", "LongMethod")
    // Guard-clause style: each `return` hands off to emitEndpoint + validateUrl for one
    // network state. Splitting the body into helpers just moves the same branches elsewhere
    // and loses the linear mode→wifi→ssid read. Earmarked for the Compose/UrlResolver cut.
    fun checkAndUpdateUrl() {
        try {
            // Generate a network state signature to detect actual changes
            val currentState = buildNetworkStateSignature()
            
            // Check if we have a previous state to compare
            val previousState = lastNetworkState
            
            // Skip update if network state hasn't actually changed and not forcing refresh
            if (currentState == previousState && !forceRefresh) {
                Log.d(TAG, "Network state unchanged, skipping URL update")
                return
            }
            
            // Determine if this is a significant network change
            val isSignificant = if (previousState != null) {
                isSignificantNetworkChange(currentState, previousState)
            } else {
                true // First time is always significant
            }
            
            // If in transition cooldown and not significant, drop this tick — the
            // scheduled runnable will re-run the full evaluator when the cooldown ends.
            if (networkTransitionInProgress.get() && !isSignificant && !forceRefresh) {
                Log.d(TAG, "Minor network change during transition cooldown, deferring update")
                return
            }
            
            // Update last known state
            lastNetworkState = currentState
            
            if (isSignificant) {
                Log.d(TAG, "Significant network state change to: $currentState")
            } else {
                Log.d(TAG, "Network state changed to: $currentState")
            }
            
            // Handle connection mode override from preferences
            val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
            
            if (connectionMode == "internal") {
                Log.d(TAG, "Using internal URL (forced by settings)")
                val internalUrl = getInternalUrl()
                emitEndpoint(internalUrl, internal = true)

                sendNetworkEvent(
                    NetworkEventType.INTERNAL_URL,
                    "Using internal URL (Manual override)",
                    false,
                    mapOf("url" to internalUrl, "mode" to "internal")
                )

                validateUrl(internalUrl, true)
                return
            } else if (connectionMode == "external") {
                Log.d(TAG, "Using external URL (forced by settings)")
                val externalUrl = getExternalUrl()
                emitEndpoint(externalUrl, internal = false)

                sendNetworkEvent(
                    NetworkEventType.EXTERNAL_URL,
                    "Using external URL (Manual override)",
                    false,
                    mapOf("url" to externalUrl, "mode" to "external")
                )

                validateUrl(externalUrl, false)
                return
            }

            if (!isWifiConnected()) {
                Log.d(TAG, "Not connected to WiFi, using external URL")
                val externalUrl = getExternalUrl()
                emitEndpoint(externalUrl, internal = false)

                sendNetworkEvent(
                    NetworkEventType.WIFI_DISCONNECTED,
                    "Not connected to WiFi, using external URL",
                    false,
                    mapOf("url" to externalUrl, "connected" to false)
                )

                validateUrl(externalUrl, false)
                return
            }

            val ssid = getSsid()
            Log.d(TAG, "Got SSID: $ssid")

            if (ssid == null || ssid.isEmpty() || ssid == "<unknown ssid>" || ssid == "Current WiFi") {
                val hasHomeConfigured = getHomeNetworks().isNotEmpty()
                if (hasHomeConfigured) {
                    Log.d(TAG, "SSID unknown but home networks configured — using internal URL")
                    val internalUrl = getInternalUrl()
                    emitEndpoint(internalUrl, internal = true)
                    sendNetworkEvent(
                        NetworkEventType.INTERNAL_URL,
                        "WiFi detected (SSID unknown) — using internal URL",
                        false,
                        mapOf("url" to internalUrl, "detection" to "failed"),
                    )
                    validateUrl(internalUrl, true)
                } else {
                    Log.d(TAG, "SSID unknown and no home networks configured — external URL")
                    val externalUrl = getExternalUrl()
                    emitEndpoint(externalUrl, internal = false)
                    sendNetworkEvent(
                        NetworkEventType.EXTERNAL_URL,
                        "WiFi detected but SSID unknown — using external URL",
                        false,
                        mapOf("url" to externalUrl, "detection" to "failed"),
                    )
                    validateUrl(externalUrl, false)
                }
                return
            }

            val cleanSsid = ssid.trim().removeSurrounding("\"")
            Log.d(TAG, "Cleaned SSID: $cleanSsid")

            val isInHomeList = isNetworkInHomeList(cleanSsid)
            if (isInHomeList) {
                Log.d(TAG, "SSID matches home network, using internal URL")
                val internalUrl = getInternalUrl()
                emitEndpoint(internalUrl, internal = true)
                lastKnownSsid = cleanSsid
                
                // Send event for home network detection - no toast
                sendNetworkEvent(
                    NetworkEventType.HOME_NETWORK_DETECTED,
                    "Connected to home network: $cleanSsid",
                    false, // No toast for home network detection
                    mapOf("url" to internalUrl, "ssid" to cleanSsid)
                )
                
                // Validate the internal URL
                validateUrl(internalUrl, true)
            } else {
                Log.d(TAG, "SSID not in home networks, using external URL")
                val externalUrl = getExternalUrl()
                emitEndpoint(externalUrl, internal = false)
                lastKnownSsid = cleanSsid
                
                // Send event for external network detection - no toast
                sendNetworkEvent(
                    NetworkEventType.EXTERNAL_NETWORK_DETECTED,
                    "Connected to external network: $cleanSsid",
                    false, // No toast for external network detection
                    mapOf("url" to externalUrl, "ssid" to cleanSsid)
                )
                
                // Validate the external URL
                validateUrl(externalUrl, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkAndUpdateUrl: ${e.message}")
            // Fail-safe to external — internal-by-default could keep us pointing at a
            // private IP after a crash on cellular.
            val externalUrl = getExternalUrl()
            emitEndpoint(externalUrl, internal = false)
            validateUrl(externalUrl, false)
        }
    }
    
    /**
     * Builds a signature string representing the current network state
     * This helps detect actual meaningful changes in network state
     */
    private fun buildNetworkStateSignature(): String {
        val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
        val isWifi = isWifiConnected()
        val ssid = getSsid() ?: "unknown"

        return "$connectionMode|$isWifi|$ssid"
    }
    
    /**
     * Determines if a network change is significant enough to bypass debouncing
     * Significant changes include WiFi to cellular transitions and vice versa
     */
    @Suppress("ReturnCount")
    private fun isSignificantNetworkChange(newSignature: String, oldSignature: String): Boolean {
        if (oldSignature.isEmpty() || newSignature.isEmpty()) return true
        
        // Extract the relevant parts from the signatures
        val oldParts = oldSignature.split("|")
        val newParts = newSignature.split("|")
        
        // Invalid format
        if (oldParts.size < 2 || newParts.size < 2) return true
        
        // Check if connection mode changed
        val oldMode = oldParts[0]
        val newMode = newParts[0]
        if (oldMode != newMode) return true
        
        // Check if WiFi connection state changed
        val oldWifi = oldParts[1].toBoolean()
        val newWifi = newParts[1].toBoolean()
        if (oldWifi != newWifi) return true
        
        // If we're in auto mode, check for SSID changes between home/non-home networks
        if (isAutoWifiWithSsids(newMode, oldWifi, newWifi, oldParts, newParts)) {
            val oldSsid = oldParts[2]
            val newSsid = newParts[2]

            // If SSID changed
            if (oldSsid != newSsid) {
                val wasHome = isNetworkInHomeList(oldSsid)
                val isHome = isNetworkInHomeList(newSsid)

                // If home status changed (home to non-home or vice versa)
                if (wasHome != isHome) return true
            }
        }

        return false
    }

    private fun isAutoWifiWithSsids(
        newMode: String,
        oldWifi: Boolean,
        newWifi: Boolean,
        oldParts: List<String>,
        newParts: List<String>,
    ): Boolean =
        newMode == "auto" && oldWifi && newWifi && oldParts.size > 2 && newParts.size > 2
    
    /**
     * Helper function to check if device is connected to WiFi
     */
    fun isWifiConnected(): Boolean {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
                
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking WiFi connection: ${e.message}")
            return false
        }
    }
    
    /**
     * Check if a network SSID is in the home networks list
     */
    private fun isNetworkInHomeList(ssid: String): Boolean {
        val homeNetworks = getHomeNetworks()
        Log.d(TAG, "Checking SSID '$ssid' against home networks: ${homeNetworks.joinToString(", ") { "'$it'" }}")
        
        val result = homeNetworks.any { homeNetwork -> 
            val cleanHomeNetwork = homeNetwork.trim().removeSurrounding("\"")
            val cleanSsid = ssid.trim().removeSurrounding("\"")
            val matches = cleanHomeNetwork.equals(cleanSsid, ignoreCase = true)
            Log.d(TAG, "Comparing '$cleanHomeNetwork' with '$cleanSsid': $matches")
            matches
        }
        
        Log.d(TAG, "SSID '$ssid' is in home networks: $result")
        return result
    }
    
    /**
     * Determines if current WiFi network is a home network
     */
    fun isHome(): Boolean {
        val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
        
        if (connectionMode == "internal") {
            return true
        } else if (connectionMode == "external") {
            return false
        }
        
        if (!isWifiConnected()) {
            return false
        }
        
        val ssid = getSsid()

        // Unknown SSID: if the user has configured home networks we assume they're home
        // (permissions-denied case on Android 11+); otherwise default to NOT home so we
        // don't silently mask real connectivity failures.
        if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>" || ssid == "Current WiFi") {
            return getHomeNetworks().isNotEmpty()
        }

        val cleanSsid = ssid.trim().removeSurrounding("\"")

        return isNetworkInHomeList(cleanSsid)
    }
    
    /**
     * Gets the list of configured home networks
     */
    fun getHomeNetworks(): Set<String> {
        return prefs.getStringSet("home_wifi_networks", emptySet()) ?: emptySet()
    }
    
    /**
     * Gets the internal URL from preferences
     */
    fun getInternalUrl(): String {
        return prefs.getString("internal_url", "http://frigate.local") ?: "http://frigate.local"
    }
    
    /**
     * Gets the external URL from preferences
     */
    fun getExternalUrl(): String {
        return prefs.getString("external_url", "https://demo.frigate.video") ?: "https://demo.frigate.video"
    }
    
    /**
     * Gets the appropriate URL based on current network status
     */
    fun getUrl(): String {
        val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
        
        return when (connectionMode) {
            "internal" -> getInternalUrl() 
            "external" -> getExternalUrl()
            else -> if (isHome()) getInternalUrl() else getExternalUrl()
        }
    }
    
    /**
     * Gets SSID using the most reliable method for the device
     * Consolidated implementation that tries multiple approaches in order of reliability
     */
    @Suppress("ReturnCount")
    fun getSsid(): String? {
        try {
            // First check: Are we even connected to WiFi?
            if (!isWifiConnected()) {
                Log.d(TAG, "Not connected to WiFi")
                return null
            }
            
            // Second check: Do we have necessary permissions?
            val hasNearbyDevicesPermission =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.NEARBY_WIFI_DEVICES,
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    false
                }
            
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            Log.d(
                TAG,
                "Permissions - NEARBY_WIFI_DEVICES: $hasNearbyDevicesPermission, " +
                    "LOCATION: $hasLocationPermission",
            )
            
            // Helper function to sanitize SSID
            fun sanitizeSsid(ssid: String?): String? {
                if (ssid == null || ssid.isEmpty() || ssid == "<unknown ssid>") return null
                return ssid.trim().removeSurrounding("\"")
            }
            
            // Try all SSID detection methods in order of reliability
            
            // APPROACH 1: Modern API - TransportInfo (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (hasNearbyDevicesPermission || hasLocationPermission)
            ) {
                try {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                        as? ConnectivityManager ?: return null
                    val activeNetwork = cm.activeNetwork ?: return null
                    val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return null
                    
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        val wifiInfo = capabilities.transportInfo as? WifiInfo
                        @Suppress("DEPRECATION")
                        val ssid = sanitizeSsid(wifiInfo?.ssid)
                        
                        if (ssid != null) {
                            Log.d(TAG, "Got SSID from TransportInfo API: $ssid")
                            return ssid
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error with TransportInfo approach: ${e.message}")
                    // Continue to next method on failure
                }
            }
            
            // APPROACH 2: WifiManager direct access (works on most devices if permissions are granted)
            if (hasNearbyDevicesPermission || hasLocationPermission) {
                try {
                    val wifiManager = context.applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                        ?: return null
                    @Suppress("DEPRECATION")
                    val wifiInfo = wifiManager.connectionInfo
                    
                    if (wifiInfo != null && wifiInfo.networkId != -1) {
                        @Suppress("DEPRECATION")
                        val ssid = sanitizeSsid(wifiInfo.ssid)
                        
                        if (ssid != null) {
                            Log.d(TAG, "Got SSID from WifiManager: $ssid")
                            return ssid
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error with WifiManager approach: ${e.message}")
                    // Continue to next method on failure
                }
            }
            
            // APPROACH 3: Settings database (fallback method, less reliable)
            try {
                val settingValues = listOf(
                    Settings.System.getString(context.contentResolver, "wifi_ap_ssid"),
                    Settings.Secure.getString(context.contentResolver, "wifi_ssid")
                )
                
                for (value in settingValues) {
                    val ssid = sanitizeSsid(value)
                    if (ssid != null) {
                        Log.d(TAG, "Got SSID from settings: $ssid")
                        return ssid
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting SSID from settings: ${e.message}")
                // Continue to next method on failure
            }
            
            // No methods worked
            Log.d(TAG, "Could not determine SSID with any method")
            return null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting SSID: ${e.message}")
            return null
        }
    }
}
