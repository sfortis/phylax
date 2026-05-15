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
                    pingSeconds = 20,
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
