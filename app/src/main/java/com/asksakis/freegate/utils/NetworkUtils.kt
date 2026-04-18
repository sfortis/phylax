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
import android.security.KeyChain
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.X509KeyManager

/**
 * Manages network detection and URL selection
 */
class NetworkUtils private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "NetworkUtils"
        private val FLAG_INCLUDE_LOCATION_INFO = 
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
        
        // URL validation constants
        private const val VALIDATION_TIMEOUT_MS = 5000L // 5 seconds timeout for validation
        private const val MAX_VALIDATION_RETRIES = 3 // Maximum number of validation retries

        // Must match HomeFragment.PREF_CLIENT_CERT_ALIAS
        private const val PREF_CLIENT_CERT_ALIAS = "client_cert_alias"
        
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
    private val wifiNetworkManager = WifiNetworkManager(context)
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    
    // Single source for network URL
    private val _currentUrl = MutableLiveData<String?>()
    val currentUrl: LiveData<String?> = _currentUrl
    
    // URL validation status
    private val _urlValidationStatus = MutableLiveData<ValidationResult>()
    val urlValidationStatus: LiveData<ValidationResult> = _urlValidationStatus
    
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
    private val MIN_TRANSITION_INTERVAL_MS = 5000L // 5 seconds minimum between transitions
    @Volatile private var pendingUrlUpdate: String? = null
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
     * Creates a common network callback with the appropriate configuration for the Android version
     * This reduces code duplication by using a single implementation of the callback logic
     */
    private fun createNetworkCallback(): ConnectivityManager.NetworkCallback {
        return object : ConnectivityManager.NetworkCallback(
            // Add location info flag only on Android S and above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) FLAG_INCLUDE_LOCATION_INFO else 0
        ) {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available")
                
                // Notify about network availability (no URL change yet)
                sendNetworkEvent(
                    NetworkEventType.WIFI_CONNECTED,
                    "Network connected, checking type...",
                    false
                )
                
                // For consistency, we simply log here and let onCapabilitiesChanged 
                // handle all URL updates when the network is validated
                Log.d(TAG, "Waiting for network validation before updating URL")
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                
                // Notify about network loss
                sendNetworkEvent(
                    NetworkEventType.WIFI_DISCONNECTED,
                    "Network disconnected",
                    false
                )
                
                // We don't immediately update URLs when networks are lost
                // Instead, we wait for another network to be validated
                Log.d(TAG, "Network lost - waiting for new validated network")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                // This is our single point of network validation and URL updates
                val hasWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val hasCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val hasValidatedInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                         networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                
                // Core requirement: Only proceed if network has validated internet
                if (!hasValidatedInternet) {
                    Log.d(TAG, "Network not validated yet, waiting for validation")
                    return
                }
                
                // Create a signature to detect network changes
                val transportSignature = "wifi:$hasWifi|cellular:$hasCellular|validated:true"
                
                // Check if this is a new or changed validation state
                if (transportSignature != lastTransportSignature || forceRefresh) {
                    Log.d(TAG, "Validated network detected: $transportSignature (was: $lastTransportSignature)")
                    lastTransportSignature = transportSignature
                    
                    // Check if we're in a transition cooldown period
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastTransition = currentTime - lastTransitionTime
                    
                    if (networkTransitionInProgress.get() && timeSinceLastTransition < MIN_TRANSITION_INTERVAL_MS && !forceRefresh) {
                        Log.d(TAG, "Network transition in progress, delaying URL update (${timeSinceLastTransition}ms < ${MIN_TRANSITION_INTERVAL_MS}ms)")
                        
                        // Cancel any existing scheduled check
                        scheduledTransitionCheck?.let { handler.removeCallbacks(it) }
                        
                        // Schedule a check after the cooldown period
                        scheduledTransitionCheck = Runnable {
                            Log.d(TAG, "Running delayed network transition check")
                            networkTransitionInProgress.set(false)
                            checkAndUpdateUrl()
                        }
                        
                        // Schedule the check
                        handler.postDelayed(scheduledTransitionCheck!!, 
                            (MIN_TRANSITION_INTERVAL_MS - timeSinceLastTransition).coerceAtLeast(1000))
                        
                        return
                    }
                    
                    // Mark transition in progress and update timestamp
                    networkTransitionInProgress.set(true)
                    lastTransitionTime = currentTime
                    
                    // Use the same URL update flow for all network changes
                    Log.d(TAG, "Network validated - updating URL based on current network state")
                    checkAndUpdateUrl()
                    
                    // Schedule transition cooldown end
                    scheduledTransitionCheck?.let { handler.removeCallbacks(it) }
                    scheduledTransitionCheck = Runnable {
                        Log.d(TAG, "Network transition cooldown ended")
                        networkTransitionInProgress.set(false)
                    }
                    handler.postDelayed(scheduledTransitionCheck!!, MIN_TRANSITION_INTERVAL_MS)
                }
            }
        }
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
    fun forceRefresh() {
        Log.d(TAG, "Force refresh requested - bypassing all filters")

        // Reset any ongoing transitions
        scheduledTransitionCheck?.let { handler.removeCallbacks(it) }
        networkTransitionInProgress.set(false)
        
        // Clear any pending URL updates
        pendingUrlUpdate = null
        
        // Set force refresh flag
        forceRefresh = true
        
        // Check and update URL
        checkAndUpdateUrl()
        
        // Reset force refresh flag
        forceRefresh = false
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
     * Build KeyManagers that provide the user's saved client certificate for mTLS.
     * Must be called from a background thread (KeyChain.getPrivateKey blocks).
     * Returns null if no alias is saved or the keys cannot be loaded.
     */
    private fun buildClientCertKeyManagers(): Array<javax.net.ssl.KeyManager>? {
        val alias = prefs.getString(PREF_CLIENT_CERT_ALIAS, null) ?: return null
        return try {
            val privateKey: PrivateKey = KeyChain.getPrivateKey(context, alias) ?: return null
            val chain: Array<X509Certificate> = KeyChain.getCertificateChain(context, alias) ?: return null

            val keyManager = object : X509KeyManager {
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
                override fun chooseClientAlias(
                    keyType: Array<out String>?,
                    issuers: Array<out Principal>?,
                    socket: Socket?
                ) = alias
                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
                override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
                override fun getCertificateChain(alias: String?) = chain
                override fun getPrivateKey(alias: String?) = privateKey
            }
            arrayOf(keyManager)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load client certificate for validation: ${e.message}")
            null
        }
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
                val outcome = runOneValidationAttempt(url, isInternal)
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
     */
    private fun runOneValidationAttempt(url: String, isInternal: Boolean): AttemptOutcome {
        var connection: java.net.HttpURLConnection? = null
        return try {
            val urlObj = java.net.URL(url)
            connection = (urlObj.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = VALIDATION_TIMEOUT_MS.toInt()
                readTimeout = VALIDATION_TIMEOUT_MS.toInt()
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 FrigateViewer/1.0 URL-Validator")
            }

            if (connection is javax.net.ssl.HttpsURLConnection) {
                configureHttpsForValidation(connection, isInternal)
            }

            val startTime = System.currentTimeMillis()
            connection.connect()
            val responseCode = connection.responseCode
            val responseTime = System.currentTimeMillis() - startTime

            when (responseCode) {
                in 200..299 -> AttemptOutcome.success(responseCode, responseTime)
                in 300..399 -> {
                    Log.d(TAG, "URL redirects to: ${connection.getHeaderField("Location")}")
                    AttemptOutcome.success(responseCode, responseTime)
                }
                in 400..499 -> AttemptOutcome.terminalFailure(
                    "Connection failed with status code: $responseCode",
                    responseCode
                )
                else -> AttemptOutcome.retryable(
                    "Connection failed with status code: $responseCode",
                    responseCode
                )
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.d(TAG, "URL validation timed out: $url")
            AttemptOutcome.timeout()
        } catch (e: Exception) {
            Log.e(TAG, "URL validation error: ${e.message}")
            AttemptOutcome.retryable("Error: ${e.message}", responseCode = null)
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun configureHttpsForValidation(
        connection: javax.net.ssl.HttpsURLConnection,
        isInternal: Boolean
    ) {
        try {
            val keyManagers = buildClientCertKeyManagers()
            val trustManagers: Array<javax.net.ssl.TrustManager>? = if (isInternal) {
                arrayOf(
                    object : javax.net.ssl.X509TrustManager {
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    }
                )
            } else null

            if (keyManagers != null || trustManagers != null) {
                val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                sslContext.init(keyManagers, trustManagers, java.security.SecureRandom())
                connection.sslSocketFactory = sslContext.socketFactory
                if (isInternal) {
                    connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure SSL for URL validation: ${e.message}")
        }
    }

    private fun publishValidationResult(url: String, isInternal: Boolean, outcome: AttemptOutcome) {
        when (outcome.status) {
            ValidationStatus.SUCCESS -> {
                Log.d(TAG, "URL validation successful: $url (code: ${outcome.responseCode}, time: ${outcome.timeMs}ms)")
                _urlValidationStatus.postValue(ValidationResult(
                    ValidationStatus.SUCCESS, url, isInternal,
                    "Connection successful (${outcome.responseCode}) in ${outcome.timeMs}ms"
                ))
                sendNetworkEvent(
                    NetworkEventType.VALIDATION_SUCCESS,
                    if (isInternal) "Internal URL verified" else "External URL verified",
                    false,
                    mapOf("url" to url, "response_code" to (outcome.responseCode ?: 0), "time_ms" to (outcome.timeMs ?: 0))
                )
            }
            ValidationStatus.TIMEOUT -> {
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
                AttemptOutcome(ValidationStatus.SUCCESS, terminal = true, responseCode = code, timeMs = timeMs)
            fun terminalFailure(message: String, responseCode: Int) =
                AttemptOutcome(ValidationStatus.FAILED, terminal = true, message = message, responseCode = responseCode)
            fun retryable(message: String, responseCode: Int?) =
                AttemptOutcome(ValidationStatus.FAILED, terminal = false, message = message, responseCode = responseCode)
            fun timeout() =
                AttemptOutcome(ValidationStatus.TIMEOUT, terminal = false)
        }
    }
    
    /**
     * Core function that checks the current network and determines the appropriate URL
     * This is the primary function that handles all network state evaluation
     */
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
            
            // If in transition cooldown and not significant, schedule for later
            if (networkTransitionInProgress.get() && !isSignificant && !forceRefresh) {
                Log.d(TAG, "Minor network change during transition cooldown, deferring update")
                pendingUrlUpdate = getUrl() // Save the URL we would use
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
                _currentUrl.postValue(internalUrl)
                
                // Update internal URL state
                isInternalUrl = true
                
                // Send event for URL mode change
                sendNetworkEvent(
                    NetworkEventType.INTERNAL_URL,
                    "Using internal URL (Manual override)",
                    true, // Show toast for manual mode change
                    mapOf("url" to internalUrl, "mode" to "internal")
                )
                
                // Validate the internal URL
                validateUrl(internalUrl, true)
                return
            } else if (connectionMode == "external") {
                Log.d(TAG, "Using external URL (forced by settings)")
                val externalUrl = getExternalUrl()
                _currentUrl.postValue(externalUrl)
                
                // Update internal URL state
                isInternalUrl = false
                
                // Send event for URL mode change
                sendNetworkEvent(
                    NetworkEventType.EXTERNAL_URL,
                    "Using external URL (Manual override)",
                    true, // Show toast for manual mode change
                    mapOf("url" to externalUrl, "mode" to "external")
                )
                
                // Validate the external URL
                validateUrl(externalUrl, false)
                return
            }
            
            // Check if we're connected to WiFi
            if (!isWifiConnected()) {
                Log.d(TAG, "Not connected to WiFi, using external URL")
                val externalUrl = getExternalUrl()
                _currentUrl.postValue(externalUrl)
                
                // Update internal URL state
                isInternalUrl = false
                
                // Send event for WiFi state and URL change - no toast
                sendNetworkEvent(
                    NetworkEventType.WIFI_DISCONNECTED,
                    "Not connected to WiFi, using external URL",
                    false, // No toast for WiFi state change
                    mapOf("url" to externalUrl, "connected" to false)
                )
                
                // Validate the external URL
                validateUrl(externalUrl, false)
                return
            }
            
            // Check for manual override
            val manualOverride = prefs.getString("manual_home_network", "")
            if (!manualOverride.isNullOrEmpty()) {
                Log.d(TAG, "Using manual override: $manualOverride")
                
                val homeNetworks = getHomeNetworks()
                if (homeNetworks.any { it.equals(manualOverride, ignoreCase = true) }) {
                    Log.d(TAG, "Manual override matches home network, using internal URL")
                    val internalUrl = getInternalUrl()
                    _currentUrl.postValue(internalUrl)
                    
                    // Update internal URL state and last known SSID
                    isInternalUrl = true
                    lastKnownSsid = manualOverride
                    
                    // Send event for home network detection - no toast
                    sendNetworkEvent(
                        NetworkEventType.HOME_NETWORK_DETECTED,
                        "Home network detected (Manual override: $manualOverride)",
                        false, // No toast for network detection
                        mapOf("url" to internalUrl, "ssid" to manualOverride, "source" to "manual")
                    )
                    
                    // Validate the internal URL
                    validateUrl(internalUrl, true)
                    return
                }
            }
            
            // Try to get SSID
            val ssid = getSsid()
            Log.d(TAG, "Got SSID: $ssid")
            
            if (ssid == null || ssid.isEmpty() || ssid == "<unknown ssid>" || ssid == "Current WiFi") {
                Log.d(TAG, "Could not determine SSID - defaulting to internal URL for better UX")
                val internalUrl = getInternalUrl()
                _currentUrl.postValue(internalUrl)
                
                // Update internal URL state
                isInternalUrl = true
                
                // Send event for SSID detection failure - no toast
                sendNetworkEvent(
                    NetworkEventType.INTERNAL_URL,
                    "WiFi detected but could not determine network name - using internal URL",
                    false, // No toast for SSID detection failure
                    mapOf("url" to internalUrl, "detection" to "failed")
                )
                
                // Validate the internal URL
                validateUrl(internalUrl, true)
                return
            }
            
            val cleanSsid = ssid.trim().removeSurrounding("\"")
            Log.d(TAG, "Cleaned SSID: $cleanSsid")
            
            val isInHomeList = isNetworkInHomeList(cleanSsid)
            if (isInHomeList) {
                Log.d(TAG, "SSID matches home network, using internal URL")
                val internalUrl = getInternalUrl()
                _currentUrl.postValue(internalUrl)
                
                // Check if this is a network change or initial detection
                val networkChanged = lastKnownSsid != cleanSsid
                
                // Update internal URL state and last known SSID
                isInternalUrl = true
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
                _currentUrl.postValue(externalUrl)
                
                // Check if this is a network change or initial detection
                val networkChanged = lastKnownSsid != cleanSsid
                
                // Update internal URL state and last known SSID
                isInternalUrl = false
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
            val internalUrl = getInternalUrl()
            _currentUrl.postValue(internalUrl) // Fail safe to internal URL
            
            // Validate the internal URL even in error case
            validateUrl(internalUrl, true)
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
        val manualOverride = prefs.getString("manual_home_network", "") ?: ""
        
        return "$connectionMode|$isWifi|$ssid|$manualOverride"
    }
    
    /**
     * Determines if a network change is significant enough to bypass debouncing
     * Significant changes include WiFi to cellular transitions and vice versa
     */
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
        if (newMode == "auto" && oldWifi && newWifi && newParts.size > 2 && oldParts.size > 2) {
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
        
        if (ssid == null || ssid.isEmpty() || ssid == "<unknown ssid>" || ssid == "Current WiFi") {
            val manualOverride = prefs.getString("manual_home_network", "")
            if (!manualOverride.isNullOrEmpty()) {
                val homeNetworks = getHomeNetworks()
                return homeNetworks.any { it.equals(manualOverride, ignoreCase = true) }
            }
            
            return true
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
    fun getSsid(): String? {
        try {
            // First check: Are we even connected to WiFi?
            if (!isWifiConnected()) {
                Log.d(TAG, "Not connected to WiFi")
                return null
            }
            
            // Second check: Do we have necessary permissions?
            val hasNearbyDevicesPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
            
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            Log.d(TAG, "Permissions - NEARBY_WIFI_DEVICES: $hasNearbyDevicesPermission, LOCATION: $hasLocationPermission")
            
            // Helper function to sanitize SSID
            fun sanitizeSsid(ssid: String?): String? {
                if (ssid == null || ssid.isEmpty() || ssid == "<unknown ssid>") return null
                return ssid.trim().removeSurrounding("\"")
            }
            
            // Try all SSID detection methods in order of reliability
            
            // APPROACH 1: Modern API - TransportInfo (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (hasNearbyDevicesPermission || hasLocationPermission)) {
                try {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
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
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
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
            
            // APPROACH 4: Manual override from user preferences
            val manualOverride = prefs.getString("manual_home_network", "")
            if (!manualOverride.isNullOrEmpty()) {
                Log.d(TAG, "Using manual home network override: $manualOverride")
                return manualOverride
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