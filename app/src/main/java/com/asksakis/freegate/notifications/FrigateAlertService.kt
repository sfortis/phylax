package com.asksakis.freegate.notifications

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
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
    private val cooldown by lazy { CooldownTracker(this) }
    private val reviewCatchupFetcher by lazy { ReviewCatchupFetcher(this) }
    private val lastCatchupMs = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var lastBaseUrl: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var wakeLock: PowerManager.WakeLock? = null
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
        // First start after install / upgrade has no cutoff stored — seed it now so
        // existing long-running trackers from before this install don't notify.
        if (prefs.getLong(PREF_LISTENING_SINCE_MS, 0L) == 0L) {
            markListeningSince(this)
        }
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
        ServiceLifecycleLog.recordStart(this)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved — scheduling immediate revive")
        // When the user swipes the app from Recents, Samsung/MIUI/OEM skins frequently
        // kill the whole process as collateral. START_STICKY eventually restarts us,
        // but not reliably and not fast. Fire an explicit alarm that lands ~0.5s later
        // on a fresh process.
        if (prefs.getBoolean("notifications_enabled", false)) {
            ServiceReviveReceiver.scheduleImmediate(applicationContext)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (com.asksakis.freegate.BuildConfig.DEBUG && intent?.action == ACTION_DEBUG_NOTIFY) {
            // Honour the FGS contract in case the service was started fresh by this intent.
            startForegroundCompat(lastStatusText)
            postDebugNotification(intent.getStringExtra(EXTRA_DEBUG_SEVERITY) ?: "detection")
            return START_NOT_STICKY
        }

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
        ServiceLifecycleLog.recordDestroy(this)
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
        runCatching { if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
            mgr.notify(STATUS_NOTIFICATION_ID, notification) }
    }

    private fun acquireLocks() {
        // Partial wake lock only: keeps the CPU scheduling the WebSocket ping loop
        // through doze. We deliberately do NOT take a WIFI_MODE_FULL_HIGH_PERF lock
        // — that pins the Wi-Fi radio out of power-save 24/7 for a heavy battery cost
        // the low-rate (60s ping) socket doesn't justify. The socket stays associated
        // under the foreground service, and a dropped connection is recovered by the
        // network-regain callback kick + the WorkManager watchdog.
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FrigateViewer:AlertListener",
        )?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
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
        // Drop the INTERNET + VALIDATED capability filters. LAN-only Frigate deployments
        // (home router with no upstream, guest Wi-Fi, captive portals not yet passed) still
        // have a reachable Frigate host but the network would be unvalidated. Listen on any
        // transition and kick a reconnect — the WS handshake itself will filter truly dead
        // paths via its own backoff.
        val request = NetworkRequest.Builder().build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                kickReconnect("network available")
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

    /**
     * Debug-only: build a synthetic [AlertFilter.Alert] and push it through the live
     * [FrigateNotifier] so we can verify channel sound, vibration, BigText layout and
     * tap-through deep-link without waiting for a real Frigate event. Gated by
     * [BuildConfig.DEBUG]; the call site is unreachable in release builds.
     */
    private fun postDebugNotification(severityArg: String) {
        val isAlert = severityArg.equals("alert", ignoreCase = true)
        val alert = AlertFilter.Alert(
            id = "debug-${System.currentTimeMillis()}",
            camera = "front_door",
            severity = if (isAlert) AlertFilter.Severity.ALERT else AlertFilter.Severity.DETECTION,
            labels = listOf("person"),
            zones = listOf("driveway"),
            attributes = if (isAlert) listOf("package") else emptyList(),
            startTimeSec = System.currentTimeMillis() / 1000.0,
            estimatedSpeedKph = if (isAlert) 6.0 else null,
            thumbnailPath = null,
        )
        Log.d(TAG, "Debug notify: severity=${alert.severity} id=${alert.id}")
        playSoundForSeverity(alert.severity)
        notifier.notify(alert, tapAction())
    }

    /**
     * Routes sound playback through the right player based on severity.
     * Alerts go through [AlarmSoundPlayer] (STREAM_ALARM, force-max-volume,
     * always wakes the user). Detections go through [DetectionSoundPlayer]
     * (STREAM_NOTIFICATION, honours DND / notification-volume). Both honour
     * the per-kind sound-picker pref and skip when the user picked Silent.
     */
    private fun playSoundForSeverity(severity: AlertFilter.Severity) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        when (severity) {
            AlertFilter.Severity.ALERT -> AlarmSoundPlayer.play(this)
            AlertFilter.Severity.DETECTION -> DetectionSoundPlayer.play(this)
        }
    }

    private fun currentFilter(): AlertFilter {
        val cameras = prefs.getStringSet("notify_cameras", emptySet()).orEmpty()
        val zones = prefs.getStringSet("notify_zones", emptySet()).orEmpty()
        val listeningSinceMs = prefs.getLong(PREF_LISTENING_SINCE_MS, 0L)
        // `notify_*_total` is written by the camera/zone picker the first time
        // it loads the Frigate config. Presence (> 0) tells the filter the
        // empty allowlist means "explicit deselect all" rather than legacy
        // "never touched", so we don't silently mute upgraded users.
        return AlertFilter(
            allowAlerts = prefs.getBoolean("notify_alerts", true),
            allowDetections = prefs.getBoolean("notify_detections", false),
            cameraAllowlist = cameras,
            cameraPickerOpened = prefs.getInt("notify_cameras_total", 0) > 0,
            zoneAllowlist = zones,
            zonePickerOpened = prefs.getInt("notify_zones_total", 0) > 0,
            listeningSinceSec = if (listeningSinceMs > 0L) listeningSinceMs / 1000.0 else 0.0,
        )
    }

    /**
     * Evaluate one reviews frame and post a notification if it clears the filter,
     * cooldown/dedupe and mute gates. Shared by the live WS path ([WsListener.onMessage])
     * and the reconnect catch-up ([runReviewCatchup]) so both honour the *same*
     * per-event dedupe — a review can never be posted twice, no matter how it arrived.
     * Advances the last-seen watermark for every review that clears the filter (whether
     * or not it ends up notifying) so catch-up never re-fetches it. Returns true iff a
     * notification was actually posted.
     */
    private fun processReview(topic: String, json: JSONObject, fromCatchup: Boolean): Boolean {
        val alert = currentFilter().evaluate(topic, json)
        if (alert == null) {
            if (!fromCatchup) Log.d(TAG, "  -> filtered out")
            return false
        }
        // Watermark advances on any review that passes the filter, even if cooldown/mute
        // drops it below — it's still "seen", so catch-up shouldn't re-fetch it.
        alert.startTimeSec?.let { advanceReviewWatermark(it) }

        if (prefs.getBoolean("notifications_external_only", false) && networkUtils.isInternal.value == true) {
            Log.d(TAG, "  -> skipped (external only mode)")
            return false
        }

        // Camera-group mute first: a muted camera is dropped independently of dedupe, and
        // checking before the claim means a muted review doesn't consume the cooldown /
        // dedupe slot. Cheap prefs lookup, no network.
        val muteStore = CameraMuteStore.getInstance(this@FrigateAlertService)
        if (muteStore.isCameraMuted(alert.camera, muteStore.loadGroupCameras())) {
            Log.d(TAG, "  -> skipped (mute) camera=${alert.camera}")
            return false
        }

        val globalSec = prefs.getString("notify_cooldown_global", "0")?.toIntOrNull() ?: 0
        val perCamSec = prefs.getString("notify_cooldown_camera", "0")?.toIntOrNull() ?: 0
        // Atomic dedupe + cooldown claim. ONE locked call (not shouldSkip-then-record) so
        // the live WS thread and the catch-up coroutine can never both pass for the same
        // review id and double-notify. Dedupes across the new → update → end lifecycle AND
        // across the live/catch-up boundary: exactly one notification per id.
        val dedupeId = alert.id.takeIf { it.isNotEmpty() }
        if (!cooldown.tryClaim(alert.camera, globalSec, perCamSec, dedupeId)) {
            Log.d(TAG, "  -> skipped (cooldown/dedupe) camera=${alert.camera}")
            return false
        }

        Log.d(TAG, "  -> notifying${if (fromCatchup) " (catch-up)" else ""}: id=${alert.id} labels=${alert.labels}")
        // Surface "last alert received" in the Reliability section — the single most useful
        // signal that background restrictions didn't kill the service.
        prefs.edit().putLong(PREF_LAST_ALERT_MS, System.currentTimeMillis()).apply()

        playSoundForSeverity(alert.severity)

        val path = alert.thumbnailPath
        val baseUrl = lastBaseUrl
        if (path != null && baseUrl != null) {
            // Async: fetch the snapshot then post the rich notification. If it fails we
            // still post the plain notification, so a transient snapshot error never eats
            // an alert.
            scope.launch {
                val bitmap = snapshotDownloader.download(baseUrl, path)
                notifier.notify(alert, tapAction(), bitmap)
            }
        } else {
            notifier.notify(alert, tapAction())
        }
        return true
    }

    /**
     * Fetch and replay reviews that landed while the WS was down (boot, network switch,
     * doze drop) — Frigate's `/ws` is live-only and never replays. Runs on every WS
     * (re)connect but is heavily guarded against spam:
     *   - throttled to once per [CATCHUP_THROTTLE_MS] (reconnect storms don't re-fetch);
     *   - only reviews after the persisted watermark, clamped to [CATCHUP_MAX_LOOKBACK_SEC];
     *   - each review runs through [processReview], so the per-event dedupe drops anything
     *     already notified live or in a prior catch-up;
     *   - at most [CATCHUP_MAX_NOTIFICATIONS] posted (newest), the rest only advance the
     *     watermark and are logged — no flood after a long offline stretch.
     */
    private fun runReviewCatchup() {
        val now = System.currentTimeMillis()
        val prev = lastCatchupMs.get()
        if (now - prev < CATCHUP_THROTTLE_MS) return
        val baseUrl = lastBaseUrl ?: return
        // Atomic throttle: onState(CONNECTED) fires on the OkHttp callback thread, and
        // overlapping sockets (URL-change restart) can land two connects at once. CAS so
        // only one wins the window — avoids a duplicate /api/review fetch (tryClaim would
        // still dedupe the notifications, but this skips the redundant network call).
        if (!lastCatchupMs.compareAndSet(prev, now)) return
        scope.launch {
            try {
                val nowSec = System.currentTimeMillis() / 1000.0
                val storedSec = prefs.getLong(PREF_LAST_SEEN_REVIEW_TS, 0L) / 1000.0
                // First connect ever: anchor the watermark at "now" and skip — we don't
                // replay history the user has presumably already seen elsewhere.
                if (storedSec <= 0.0) {
                    advanceReviewWatermark(nowSec)
                    return@launch
                }
                val sinceSec = maxOf(storedSec, nowSec - CATCHUP_MAX_LOOKBACK_SEC)
                val missed = reviewCatchupFetcher.fetchSince(baseUrl, sinceSec)
                if (missed.isEmpty()) return@launch
                // Advance the watermark across ALL fetched reviews (even capped-out ones)
                // so the next connect doesn't re-fetch them.
                missed.maxOf { it.optDouble("start_time", 0.0) }
                    .takeIf { it > 0.0 }?.let { advanceReviewWatermark(it) }
                val toPost = if (missed.size > CATCHUP_MAX_NOTIFICATIONS) {
                    Log.w(TAG, "Catch-up: ${missed.size} missed reviews; posting newest " +
                        "$CATCHUP_MAX_NOTIFICATIONS, dropping ${missed.size - CATCHUP_MAX_NOTIFICATIONS}")
                    missed.takeLast(CATCHUP_MAX_NOTIFICATIONS)
                } else {
                    missed
                }
                var posted = 0
                for (review in toPost) {
                    val envelope = JSONObject()
                        .put("topic", "reviews")
                        .put("payload", JSONObject().put("type", "end").put("after", review))
                    if (processReview("reviews", envelope, fromCatchup = true)) posted++
                }
                if (posted > 0) Log.d(TAG, "Catch-up: posted $posted missed review(s)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // never swallow cancellation (service teardown)
            } catch (e: Exception) {
                Log.e(TAG, "Catch-up failed: ${e.message}")
            }
        }
    }

    /** Bump the persisted "last seen review" watermark forward (epoch millis). */
    @Synchronized
    private fun advanceReviewWatermark(startTimeSec: Double) {
        val ms = (startTimeSec * 1000).toLong()
        if (ms <= prefs.getLong(PREF_LAST_SEEN_REVIEW_TS, 0L)) return
        prefs.edit().putLong(PREF_LAST_SEEN_REVIEW_TS, ms).apply()
    }

    private inner class WsListener : FrigateWsClient.Listener {
        override fun onMessage(topic: String, json: JSONObject) {
            // Reviews-only by design. The `events` stream is per-tracker raw lifecycle
            // (every motion update, including false positives that Frigate later
            // reclassifies); Frigate's own UI never surfaces those as notifications.
            // Reviews are Frigate's curated, severity-tagged notification feed —
            // server-side filtered by zones, labels, score, and false-positive logic.
            // Targets Frigate >= 0.13 where reviews exist.
            if (topic != "reviews" && topic != "review") return
            Log.d(TAG, "Candidate topic=$topic raw=${json.toString().take(400)}")
            processReview(topic, json, fromCatchup = false)
        }

        override fun onState(state: FrigateWsClient.State) {
            Log.d(TAG, "WS state: $state")
            val text = when (state) {
                FrigateWsClient.State.CONNECTED -> "Listening for Frigate alerts"
                FrigateWsClient.State.CONNECTING -> "Connecting..."
                FrigateWsClient.State.RECONNECTING -> "Reconnecting..."
                FrigateWsClient.State.DISCONNECTED -> "Disconnected — retrying"
                FrigateWsClient.State.AUTH_REQUIRED ->
                    "Sign-in needed — update credentials in Settings"
            }
            updateStatusNotification(text)
            when (state) {
                FrigateWsClient.State.CONNECTED -> {
                    ServiceLifecycleLog.recordWsConnected(this@FrigateAlertService)
                    // Replay anything missed while the socket was down (boot, network
                    // switch, doze). Throttled + deduped internally — safe to call on
                    // every connect.
                    runReviewCatchup()
                }
                FrigateWsClient.State.DISCONNECTED -> ServiceLifecycleLog.recordWsDisconnected(this@FrigateAlertService)
                FrigateWsClient.State.AUTH_REQUIRED -> ServiceLifecycleLog.recordWsDisconnected(this@FrigateAlertService)
                else -> Unit
            }
        }
    }

    companion object {
        private const val TAG = "FrigateAlertService"
        private const val STATUS_NOTIFICATION_ID = 9_001
        /**
         * Debug-only intent action that posts a synthetic alert/detection through the
         * real [FrigateNotifier]. Honoured only when [BuildConfig.DEBUG] is true.
         * Trigger via:
         *   adb shell am startservice \
         *     -n com.asksakis.freegate/.notifications.FrigateAlertService \
         *     -a com.asksakis.freegate.action.DEBUG_NOTIFY \
         *     --es severity alert        # or "detection"
         */
        const val ACTION_DEBUG_NOTIFY = "com.asksakis.freegate.action.DEBUG_NOTIFY"
        const val EXTRA_DEBUG_SEVERITY = "severity"
        const val PREF_LAST_ALERT_MS = "last_alert_received_ms"
        /**
         * Wall-clock millis of the most recent config change (enable, severities,
         * cameras, zones). Used by [AlertFilter] to drop trackers that started
         * before this point — those would notify retroactively otherwise.
         */
        const val PREF_LISTENING_SINCE_MS = "notify_listening_since_ms"
        // Min interval between network-kick-driven WS restarts. Suppresses reconnect
        // storms when multiple transports flip up together (WiFi + cellular).
        private const val RECONNECT_KICK_THROTTLE_MS = 3_000L

        /** Epoch millis of the newest review we've seen — the reconnect catch-up watermark. */
        const val PREF_LAST_SEEN_REVIEW_TS = "notify_last_seen_review_ts"
        // Reconnect catch-up guards (see runReviewCatchup). Throttle stops reconnect
        // storms from re-fetching; the notification cap prevents a flood after a long
        // offline stretch; the lookback bounds how far back a stale watermark reaches.
        private const val CATCHUP_THROTTLE_MS = 30_000L
        private const val CATCHUP_MAX_NOTIFICATIONS = 5
        private const val CATCHUP_MAX_LOOKBACK_SEC = 6 * 60 * 60.0 // 6 hours

        /**
         * Start or stop the service based on the current `notifications_enabled` setting.
         * Safe to call at any time (app launch, preference toggle, settings resume).
         *
         * Pass `forceRestart = true` when the active server profile has changed:
         * a plain re-start lets [onStartCommand]'s `lastBaseUrl == baseUrl`
         * guard short-circuit and skip the WebSocket reconnect even though the
         * credentials / mTLS / WS endpoint may have just changed underneath us.
         */
        fun updateForContext(context: Context, forceRestart: Boolean = false) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val enabled = prefs.getBoolean("notifications_enabled", false)
            val intent = Intent(context, FrigateAlertService::class.java)
            if (enabled) {
                if (forceRestart) {
                    // Tear down so onCreate runs again on the new profile's state.
                    context.stopService(intent)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                // Two-layer revive: WorkManager (15min, survives reboots and app updates)
                // + AlarmManager (5min, bypasses Doze, faster recovery from OEM kills).
                AlertServiceWatchdogWorker.schedule(context)
                ServiceReviveReceiver.scheduleNext(context)
            } else {
                context.stopService(intent)
                AlertServiceWatchdogWorker.cancel(context)
                ServiceReviveReceiver.cancel(context)
            }
        }

        /**
         * Reset the start-time cutoff so any tracker that began before *now* gets
         * dropped. Call when the user enables notifications, changes filters, or
         * adds a zone — those are the points where the user's intent shifts and
         * older tracker updates are no longer relevant.
         */
        fun markListeningSince(context: Context) {
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putLong(PREF_LISTENING_SINCE_MS, System.currentTimeMillis())
                .apply()
        }
    }
}
