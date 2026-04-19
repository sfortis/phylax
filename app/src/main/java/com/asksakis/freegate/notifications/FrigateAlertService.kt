package com.asksakis.freegate.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
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
    @Volatile private var lastBaseUrl: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        notifier = FrigateNotifier(this)
        snapshotDownloader = SnapshotDownloader(this)
        wsClient = FrigateWsClient(
            authManager = FrigateAuthManager.getInstance(this),
            clientCertManager = ClientCertManager.getInstance(this),
            listener = WsListener(),
        )

        startForegroundCompat("Listening for Frigate alerts")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val baseUrl = resolveBaseUrl()
        if (baseUrl == null) {
            Log.w(TAG, "No Frigate URL configured; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        lastBaseUrl = baseUrl
        Log.d(TAG, "Starting WS listener for $baseUrl")
        wsClient.start(scope, baseUrl)
        return START_STICKY
    }

    override fun onDestroy() {
        wsClient.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(text: String) {
        val notification = notifier.buildStatusNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                STATUS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(STATUS_NOTIFICATION_ID, notification)
        }
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
        return AlertFilter(
            allowAlerts = prefs.getBoolean("notify_alerts", true),
            allowDetections = prefs.getBoolean("notify_detections", false),
            cameraAllowlist = cameras,
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
            Log.d(TAG, "  -> notifying: id=${alert.id} labels=${alert.labels}")

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
        }
    }

    companion object {
        private const val TAG = "FrigateAlertService"
        private const val STATUS_NOTIFICATION_ID = 9_001

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
            } else {
                context.stopService(intent)
            }
        }
    }
}
