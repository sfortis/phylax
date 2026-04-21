package com.asksakis.freegate.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Observer
import androidx.preference.PreferenceManager
import com.asksakis.freegate.auth.FrigateAuthManager
import com.asksakis.freegate.utils.ClientCertManager
import com.asksakis.freegate.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Foreground service that keeps a Frigate WebSocket open and posts Android notifications
 * when matching alerts or detections arrive. Lifetime is bound to the
 * `notifications_enabled` preference via [updateForContext].
 */
class FrigateAlertService : Service() {

    private lateinit var prefs: SharedPreferences
    private lateinit var notifier: FrigateNotifier
    private lateinit var wsClient: FrigateWsClient
    private lateinit var snapshotDownloader: SnapshotDownloader
    private lateinit var networkUtils: NetworkUtils
    private val cooldown = CooldownTracker()
    @Volatile private var lastBaseUrl: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var lastStatusText: String = "Starting..."

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastReconnectKickMs: Long = 0L

    /**
     * Restart the WebSocket whenever the base URL changes. Path matters — Frigate is
     * routinely served behind a subpath reverse proxy (`https://host/frigate`), so we
     * compare the full trimmed URL instead of cherry-picking scheme+authority.
     * Trailing-slash only differences are already normalised by the [trimEnd] above.
     */
    private val urlObserver = Observer<String?> { newUrl ->
        val trimmed = newUrl?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return@Observer
        val previous = lastBaseUrl
        if (trimmed == previous) return@Observer

        lastBaseUrl = trimmed
        Log.d(TAG, "Base URL changed ($previous -> $trimmed); restarting WS")
        wsClient.stop()
        wsClient.start(scope, trimmed)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        notifier = FrigateNotifier(this)
        snapshotDownloader = SnapshotDownloader(this)
        networkUtils = NetworkUtils.getInstance(this)
        wsClient = FrigateWsClient(
            authManager = FrigateAuthManager.getInstance(this),
            clientCertManager = ClientCertManager.getInstance(this),
            listener = WsListener(),
        )

        acquireLocks()
        startForegroundCompat("Listening for Frigate alerts")
        // Track URL changes so the WS reconnects when the user switches between
        // internal/external, or the phone changes WiFi network.
        networkUtils.currentUrl.observeForever(urlObserver)
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val baseUrl = resolveBaseUrl()
        if (baseUrl == null) {
            Log.w(TAG, "No Frigate URL configured; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        // Always (re)post the persistent notification on every start. Critical on
        // Android 14+ where the user can swipe the FGS notification away — the
        // StatusNotificationReviveReceiver triggers another startForegroundService()
        // which lands here, and without this call we'd silently skip re-surfacing
        // the notification because the URL hasn't changed.
        startForegroundCompat(lastStatusText)

        if (lastBaseUrl == baseUrl) return START_STICKY
        lastBaseUrl = baseUrl
        Log.d(TAG, "Starting WS listener for $baseUrl")
        wsClient.start(scope, baseUrl)
        return START_STICKY
    }

    override fun onDestroy() {
        networkUtils.currentUrl.removeObserver(urlObserver)
        unregisterNetworkCallback()
        wsClient.stop()
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(text: String) {
        lastStatusText = text
        val notification = notifier.buildStatusNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // SPECIAL_USE has no 6h cap, unlike DATA_SYNC which killed this listener
            // after a few hours on Android 14+. The use-case subtype is declared via
            // <property> on the <service> element in the manifest.
            startForeground(
                STATUS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(STATUS_NOTIFICATION_ID, notification)
        }
    }

    private fun updateStatusNotification(text: String) {
        if (text == lastStatusText) return
        lastStatusText = text
        val notification = notifier.buildStatusNotification(text)
        val mgr = NotificationManagerCompat.from(this)
        runCatching { mgr.notify(STATUS_NOTIFICATION_ID, notification) }
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FrigateViewer:AlertListener",
        )?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wm?.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "FrigateViewer:AlertListener",
        )?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    /**
     * Forces an immediate WS reconnect when a usable network becomes available again,
     * instead of waiting for the exponential-backoff timer (which can be up to 60s).
     * Throttled so rapid multi-transport callbacks (WiFi + cellular both going up) don't
     * cause a reconnect storm.
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                kickReconnect("network available")
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    kickReconnect("network validated")
                }
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "Failed to register network callback: ${it.message}") }
    }

    private fun unregisterNetworkCallback() {
        val cm = connectivityManager
        val cb = networkCallback
        if (cm != null && cb != null) {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        connectivityManager = null
        networkCallback = null
    }

    private fun kickReconnect(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastReconnectKickMs < RECONNECT_KICK_THROTTLE_MS) return
        lastReconnectKickMs = now
        val url = lastBaseUrl ?: return
        Log.d(TAG, "Kick WS reconnect ($reason) -> $url")
        wsClient.stop()
        wsClient.start(scope, url)
    }

    private fun resolveBaseUrl(): String? {
        val explicit = NetworkUtils.getInstance(this).currentUrl.value
        if (!explicit.isNullOrBlank()) return explicit.trimEnd('/')

        // Fall back to whichever URL the user configured. Prefer internal for a background
        // listener since that's typically reachable with lowest latency and no mTLS.
        val internal = prefs.getString("internal_url", null)
        if (!internal.isNullOrBlank()) return internal.trimEnd('/')
        val external = prefs.getString("external_url", null)
        return external?.trimEnd('/')
    }

    private fun tapAction(): FrigateNotifier.TapAction =
        if (prefs.getString("notify_tap_action", "review") == "home")
            FrigateNotifier.TapAction.HOME
        else FrigateNotifier.TapAction.REVIEW

    private fun currentFilter(): AlertFilter {
        val cameras = prefs.getStringSet("notify_cameras", emptySet()).orEmpty()
        val zones = prefs.getStringSet("notify_zones", emptySet()).orEmpty()
        return AlertFilter(
            allowAlerts = prefs.getBoolean("notify_alerts", true),
            allowDetections = prefs.getBoolean("notify_detections", false),
            cameraAllowlist = cameras,
            zoneAllowlist = zones,
        )
    }

    private inner class WsListener : FrigateWsClient.Listener {
        override fun onMessage(topic: String, json: JSONObject) {
            if (topic != "reviews" && topic != "review" && topic != "events") return
            val raw = json.toString()
            Log.d(TAG, "Candidate topic=$topic raw=${raw.take(400)}")

            val alert = currentFilter().evaluate(topic, json)
            if (alert == null) {
                Log.d(TAG, "  -> filtered out")
                return
            }

            val globalSec = prefs.getString("notify_cooldown_global", "0")?.toIntOrNull() ?: 0
            val perCamSec = prefs.getString("notify_cooldown_camera", "0")?.toIntOrNull() ?: 0
            // Dedupe by event/review id across the whole lifecycle (new → update → end).
            // Events emit one update every few seconds; reviews emit at most three frames
            // per segment. In both cases we want exactly one notification per id.
            val dedupeId = alert.id.takeIf { it.isNotEmpty() }
            if (cooldown.shouldSkip(alert.camera, globalSec, perCamSec, dedupeId)) {
                Log.d(TAG, "  -> skipped (cooldown) camera=${alert.camera}")
                return
            }

            Log.d(TAG, "  -> notifying: id=${alert.id} labels=${alert.labels}")
            cooldown.recordNotified(alert.camera, dedupeId)

            val path = alert.thumbnailPath
            val baseUrl = lastBaseUrl
            if (path != null && baseUrl != null) {
                // Async: fetch the snapshot then post the rich notification. If it fails
                // we still post the plain notification, so users don't miss an alert
                // because of a transient snapshot error.
                scope.launch {
                    val bitmap = snapshotDownloader.download(baseUrl, path)
                    notifier.notify(alert, tapAction(), bitmap)
                }
            } else {
                notifier.notify(alert, tapAction())
            }
        }

        override fun onState(state: FrigateWsClient.State) {
            Log.d(TAG, "WS state: $state")
            val text = when (state) {
                FrigateWsClient.State.CONNECTED -> "Listening for Frigate alerts"
                FrigateWsClient.State.CONNECTING -> "Connecting..."
                FrigateWsClient.State.RECONNECTING -> "Reconnecting..."
                FrigateWsClient.State.DISCONNECTED -> "Disconnected — retrying"
            }
            updateStatusNotification(text)
        }
    }

    companion object {
        private const val TAG = "FrigateAlertService"
        private const val STATUS_NOTIFICATION_ID = 9_001
        // Min interval between network-kick-driven WS restarts. Suppresses reconnect
        // storms when multiple transports flip up together (WiFi + cellular).
        private const val RECONNECT_KICK_THROTTLE_MS = 3_000L

        /**
         * Start or stop the service based on the current `notifications_enabled` setting.
         * Safe to call at any time (app launch, preference toggle, settings resume).
         */
        fun updateForContext(context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val enabled = prefs.getBoolean("notifications_enabled", false)
            val intent = Intent(context, FrigateAlertService::class.java)
            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                AlertServiceWatchdogWorker.schedule(context)
            } else {
                context.stopService(intent)
                AlertServiceWatchdogWorker.cancel(context)
            }
        }
    }
}
