package com.asksakis.freegate.ha

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal Home Assistant for unit tests: the token endpoint, the WebSocket API with the
 * ingress commands (current and legacy), an ingress path that checks the session cookie,
 * and the HA frontend page on every other path.
 */
class FakeHomeAssistant : Closeable {

    val server = MockWebServer()

    /** Answer `unknown_command` to the dedicated ingress commands, like HA before 2026-10-23. */
    @Volatile var legacyOnly = false

    /** Answer `invalid_grant` on refresh. */
    @Volatile var refreshTokenRevoked = false

    /** The add-on's current ingress path; change it to simulate a reinstall. */
    @Volatile var currentIngressPath = INGRESS_PATH

    /** Frigate behind the add-on answers 401 by itself (authentication enabled). */
    @Volatile var frigateRejects = false

    /** Frigate answers with a redirect to this URL. */
    @Volatile var redirectTo: String? = null

    val acceptedAccessTokens: MutableSet<String> = Collections.synchronizedSet(mutableSetOf(VALID_ACCESS_TOKEN))
    val validSessions: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    val refreshCount = AtomicInteger()
    val sessionCount = AtomicInteger()
    val commandTypes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val ingressRequests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    val origin: String get() = server.url("/").toString().trimEnd('/')

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/api/websocket" -> MockResponse().withWebSocketUpgrade(Script())
                    path == "/auth/token" -> tokenResponse(request)
                    path.startsWith("/api/hassio_ingress/") -> ingressResponse(request)
                    else -> MockResponse().setBody("<html>Home Assistant</html>")
                }
            }
        }
        server.start()
    }

    private fun tokenResponse(request: RecordedRequest): MockResponse {
        refreshCount.incrementAndGet()
        val form = request.body.readUtf8()
        if (refreshTokenRevoked || !form.contains("refresh_token=$REFRESH_TOKEN")) {
            return MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
        }
        acceptedAccessTokens += REFRESHED_ACCESS_TOKEN
        return MockResponse().setBody(
            """{"access_token":"$REFRESHED_ACCESS_TOKEN","expires_in":1800,"token_type":"Bearer"}""",
        )
    }

    private fun ingressResponse(request: RecordedRequest): MockResponse {
        ingressRequests += request
        val cookie = request.getHeader("Cookie").orEmpty()
        val session = cookie.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("ingress_session=") }?.removePrefix("ingress_session=")
        val path = request.path.orEmpty()
        val redirect = redirectTo
        return when {
            session == null || session !in validSessions -> MockResponse().setResponseCode(401)
            !path.startsWith(currentIngressPath) -> MockResponse().setResponseCode(503)
            frigateRejects -> MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}""")
            redirect != null -> MockResponse().setResponseCode(302).setHeader("Location", redirect)
            else -> MockResponse().setBody("frigate:$path")
        }
    }

    private inner class Script : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            webSocket.send("""{"type":"auth_required","ha_version":"2026.11.3"}""")
        }

        /** Answer the client's close frame, otherwise the server waits for it at shutdown. */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = JSONObject(text)
            val type = msg.optString("type")
            if (type == "auth") {
                val ok = msg.optString("access_token") in acceptedAccessTokens
                webSocket.send(if (ok) """{"type":"auth_ok"}""" else """{"type":"auth_invalid","message":"x"}""")
                return
            }
            commandTypes += if (type == "supervisor/api") "supervisor/api ${msg.optString("endpoint")}" else type
            val id = msg.optInt("id")
            val dedicated = type.startsWith("supervisor/ingress/")
            val result = when {
                dedicated && legacyOnly -> null
                type == "supervisor/ingress/info" ||
                    (type == "supervisor/api" && msg.optString("endpoint").endsWith("/info")) ->
                    JSONObject().put("ingress_url", currentIngressPath)
                type == "supervisor/ingress/session" ||
                    (type == "supervisor/api" && msg.optString("endpoint") == "/ingress/session") ->
                    JSONObject().put("session", newSession())
                else -> null
            }
            val reply = JSONObject().put("id", id).put("type", "result")
            if (result != null) {
                reply.put("success", true).put("result", result)
            } else {
                reply.put("success", false)
                    .put("error", JSONObject().put("code", "unknown_command").put("message", "Unknown command."))
            }
            webSocket.send(reply.toString())
        }
    }

    private fun newSession(): String = "session-${sessionCount.incrementAndGet()}".also { validSessions += it }

    fun tokens(accessToken: String? = VALID_ACCESS_TOKEN, expiresAtMs: Long = Long.MAX_VALUE) = HaTokens(
        hassUrl = origin,
        clientId = "$origin/",
        refreshToken = REFRESH_TOKEN,
        accessToken = accessToken,
        accessTokenExpiresAtMs = expiresAtMs,
    )

    override fun close() {
        server.shutdown()
    }

    companion object {
        const val SLUG = "ccab4aaf_frigate-proxy"
        const val INGRESS_PATH = "/api/hassio_ingress/TOKEN123/"
        const val REFRESH_TOKEN = "refresh-1"
        const val VALID_ACCESS_TOKEN = "access-ok"
        const val REFRESHED_ACCESS_TOKEN = "access-new"
    }
}

/** In-memory token source for [HaIngressSessions] tests. */
class FakeTokenSource : HaIngressSessions.TokenSource {
    val stored = mutableMapOf<String, HaTokens>()

    override fun tokens(origin: String) = stored[origin]
    override fun save(tokens: HaTokens) {
        stored[HaPanelUrl.originOf(tokens.hassUrl)!!] = tokens
    }
    override fun dropTokens(origin: String) {
        stored.remove(origin)
    }
}
