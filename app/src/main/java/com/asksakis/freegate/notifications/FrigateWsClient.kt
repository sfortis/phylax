package com.asksakis.freegate.notifications

import android.util.Log
import com.asksakis.freegate.auth.FrigateAuthManager
import com.asksakis.freegate.utils.ClientCertManager
import com.asksakis.freegate.utils.OkHttpClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.SocketException
import kotlin.math.min

/**
 * OkHttp-backed WebSocket client for the Frigate `/ws` endpoint.
 *
 * Handles:
 *  - Deriving the `wss://.../ws` URL from a Frigate base URL (http or https).
 *  - Injecting the `frigate_token` cookie via [FrigateAuthManager] for auth.
 *  - Presenting the user's mTLS client certificate when present (external URLs
 *    behind Cloudflare Access / nginx mTLS).
 *  - Reconnect with exponential backoff (1s, 2s, 4s, ..., capped at 60s).
 *  - Re-auth and retry once on a 401/403 handshake failure.
 *
 * Callers receive each message as its parsed top-level JSON object via [Listener.onMessage].
 */
class FrigateWsClient(
    private val authManager: FrigateAuthManager,
    private val clientCertManager: ClientCertManager,
    private val listener: Listener,
) {

    interface Listener {
        /** One frame received. [topic] is the `topic` field; [json] is the full envelope. */
        fun onMessage(topic: String, json: JSONObject)

        /** Connection state transitions, for logging / persistent notification copy. */
        fun onState(state: State) {}
    }

    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        /** Repeated 401s with no usable credentials — the loop has stopped. */
        AUTH_REQUIRED,
    }

    @Volatile private var job: Job? = null
    @Volatile private var socket: WebSocket? = null

    /**
     * When true, per-camera motion frames (`<camera>/motion`) are also forwarded to the
     * listener. Off by default so the fast-gate keeps dropping the whole firehose for
     * users who haven't opted any camera into motion notifications. The service flips this
     * from the `motion_notify_cameras` preference on every (re)start / settings change.
     */
    @Volatile var motionEnabled: Boolean = false

    fun start(scope: CoroutineScope, baseUrl: String) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop(baseUrl) }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close(1000, "stopped") }
        socket = null
    }

    @Suppress("LoopWithTooManyJumpStatements") // two continues mirror two distinct retry
    // paths (login failure vs handshake auth failure); splitting would hurt readability
    private suspend fun runLoop(baseUrl: String) {
        var attempt = 0
        var reauthRetried = false
        var authFailures = 0
        while (true) {
            listener.onState(if (attempt == 0) State.CONNECTING else State.RECONNECTING)

            if (!authManager.ensureLoggedIn(baseUrl)) {
                Log.w(TAG, "No session; waiting before retry")
                delay(backoffMs(++attempt))
                continue
            }

            val wsUrl = wsUrlFor(baseUrl)
            val cookie = authManager.getCookieHeader().orEmpty()
            val client = OkHttpClientFactory.build(
                baseUrl,
                clientCertManager,
                OkHttpClientFactory.Timeouts(
                    connectSeconds = 15,
                    readSeconds = 0, // keep-alive for WS
                    // 60s keeps the socket warm with far fewer CPU wake-ups than the
                    // old 20s cadence. A genuinely dead socket is still caught quickly
                    // by the network-regain callback kick + the WorkManager watchdog,
                    // so the slower ping doesn't widen the missed-alert window.
                    pingSeconds = 60,
                ),
            )
            val req = Request.Builder()
                .url(wsUrl)
                .header("User-Agent", "FrigateViewer/1.0 Notifications")
                .apply { if (cookie.isNotEmpty()) header("Cookie", cookie) }
                .build()

            val outcome = connectOnce(client, req)
            when (outcome) {
                is Outcome.Closed -> {
                    attempt = if (outcome.graceful) 0 else attempt + 1
                    reauthRetried = false
                    authFailures = 0
                }
                is Outcome.AuthFailed -> {
                    authFailures++
                    if (!reauthRetried) {
                        Log.w(TAG, "Handshake ${outcome.code}, forcing re-login")
                        authManager.invalidate()
                        reauthRetried = true
                        continue
                    }
                    if (authFailures >= MAX_AUTH_FAILURES_BEFORE_PAUSE) {
                        // Persistent 401 with no credentials configured means the
                        // server requires auth and we can't satisfy it. Stop the
                        // loop entirely — reconnect happens only when the service
                        // is restarted (e.g. user adds credentials, profile swap).
                        Log.w(TAG, "Auth failed $authFailures times in a row; pausing until restart")
                        listener.onState(State.AUTH_REQUIRED)
                        return
                    }
                    attempt++
                }
                is Outcome.Failed -> {
                    // A non-auth failure (network blip, server unreachable)
                    // breaks the "consecutive auth failures" streak; without
                    // this reset, a sequence like [401, network-error, 401,
                    // network-error, 401, 401] would silently trip
                    // AUTH_REQUIRED for a user with valid credentials.
                    authFailures = 0
                    attempt++
                }
            }
            listener.onState(State.DISCONNECTED)
            delay(backoffMs(attempt))
        }
    }

    private suspend fun connectOnce(client: OkHttpClient, req: Request): Outcome {
        val result = kotlinx.coroutines.CompletableDeferred<Outcome>()

        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WS open code=${response.code}")
                listener.onState(State.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Fast gate before any JSON parse. Frigate's /ws is a broadcast bus
                // and ~95% of frames are the high-rate events / per-camera state
                // firehose this client never consumes — it only acts on reviews. Dropping
                // those here avoids building a JSONObject tree for every firehose
                // frame (sustained CPU on the listener thread). The radio cost of
                // *receiving* the bytes is upstream of this and unaffected.
                if (!isReviewsFrame(text) && !(motionEnabled && isMotionFrame(text))) return
                try {
                    val json = JSONObject(text)
                    val topic = json.optString("topic", "")
                    listener.onMessage(topic, json)
                } catch (e: Exception) {
                    Log.w(TAG, "Unparseable frame: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closing code=$code reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closed code=$code reason=$reason")
                result.complete(Outcome.Closed(graceful = code == 1000))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: -1
                Log.w(TAG, "WS failure code=$code msg=${t.message}")
                val outcome = when {
                    code == 401 || code == 403 -> Outcome.AuthFailed(code)
                    t is SocketException -> Outcome.Failed
                    else -> Outcome.Failed
                }
                result.complete(outcome)
            }
        })
        socket = ws
        return try {
            result.await()
        } finally {
            socket = null
        }
    }


    /**
     * Cheap pre-parse gate: is this raw frame a Frigate `reviews` frame?
     *
     * Frigate serialises the topic as the first key (`{"topic":"reviews",...}`),
     * so we decide from a bounded prefix only — never scanning the multi-KB
     * payload. The compact form is the zero-allocation hot path; the fallback
     * tolerates a space after the colon without walking the whole string. Every
     * other topic (events, the per-camera state topics, camera_activity, …) is
     * rejected.
     */
    private fun isReviewsFrame(text: String): Boolean {
        if (text.startsWith(REVIEWS_PREFIX)) return true // {"topic":"review… (no whitespace)
        val head = if (text.length <= TOPIC_SCAN_LIMIT) text else text.substring(0, TOPIC_SCAN_LIMIT)
        return head.contains(REVIEWS_KEY)
    }

    /**
     * Cheap pre-parse gate for per-camera motion frames (`{"topic":"<camera>/motion",…}`).
     * The camera name sits between the fixed `{"topic":"` prefix and `/motion"`, so a
     * bounded scan of the head is enough; we never touch the payload. Only consulted when
     * [motionEnabled] is set, so it costs nothing for users without motion notifications.
     */
    private fun isMotionFrame(text: String): Boolean {
        val head = if (text.length <= MOTION_SCAN_LIMIT) text else text.substring(0, MOTION_SCAN_LIMIT)
        return head.contains(MOTION_KEY)
    }

    private fun wsUrlFor(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        return when {
            trimmed.startsWith("https://") -> trimmed.replaceFirst("https://", "wss://") + "/ws"
            trimmed.startsWith("http://") -> trimmed.replaceFirst("http://", "ws://") + "/ws"
            else -> "$trimmed/ws"
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val pow = min(attempt, 6) // cap at 64s
        return min(60_000L, 1000L * (1L shl pow))
    }

    private sealed interface Outcome {
        data class Closed(val graceful: Boolean) : Outcome
        data class AuthFailed(val code: Int) : Outcome
        data object Failed : Outcome
    }

    companion object {
        private const val TAG = "FrigateWsClient"

        // Fast-gate literals (see isReviewsFrame). REVIEWS_PREFIX matches the exact
        // compact frame Frigate emits; REVIEWS_KEY is the whitespace-tolerant
        // fallback. Both cover the `reviews` and `review` topics via the `review`
        // stem. TOPIC_SCAN_LIMIT bounds the fallback so we never scan the payload.
        private const val REVIEWS_PREFIX = "{\"topic\":\"review"
        private const val REVIEWS_KEY = "\"review"
        private const val TOPIC_SCAN_LIMIT = 48

        // Motion fast-gate (see isMotionFrame). The topic is the first key and the camera
        // name is bounded, so scanning a slightly wider head than the reviews gate covers
        // long camera names (e.g. "dahua_frontentrance/motion") without touching payloads.
        private const val MOTION_KEY = "/motion\""
        private const val MOTION_SCAN_LIMIT = 96

        /**
         * Give up the WS loop after this many 401s in a row (post re-login
         * retry). The server consistently refusing means we either have no
         * credentials for a Frigate that needs them, or the stored credentials
         * are wrong — neither is a network blip we can fix by retrying with
         * backoff. The service stays up and the loop resumes only when an
         * explicit restart fires (profile swap, settings change, app restart).
         *
         * 6 attempts with exponential backoff (capped at 60s) buy roughly
         * two minutes before we pause — generous enough to ride out a Frigate
         * service restart but quick enough to surface "wrong credentials"
         * without forever-retrying.
         */
        private const val MAX_AUTH_FAILURES_BEFORE_PAUSE = 6
    }
}
