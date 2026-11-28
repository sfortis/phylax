package com.asksakis.freegate.ha

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Creates an ingress session for a Home Assistant add-on over the HA WebSocket API.
 *
 * The call is blocking, because its only caller is an OkHttp interceptor that already runs
 * on a background thread. Each call opens `/api/websocket`, authenticates, sends at most
 * two commands and closes the socket.
 *
 * Home Assistant added dedicated commands on 2026-10-23 (`supervisor/ingress/info` and
 * `supervisor/ingress/session`) that also work for a non-admin user. Older versions answer
 * `unknown_command`, and the client then uses the generic `supervisor/api` passthrough,
 * which requires an admin user.
 */
class HaWebSocketClient(
    private val client: OkHttpClient,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    sealed interface Result {
        /** [ingressPath] is null when the caller did not ask for it. */
        data class Success(val ingressPath: String?, val session: String) : Result {
            override fun toString() = "Success(ingressPath=$ingressPath)"
        }

        /** Home Assistant rejected the access token. */
        data object AuthInvalid : Result

        data class Failed(val reason: String) : Result
    }

    fun createSession(origin: String, accessToken: String, slug: String, needIngressPath: Boolean): Result {
        val events = LinkedBlockingQueue<Event>()
        val request = Request.Builder()
            .url(webSocketUrl(origin))
            .header("User-Agent", USER_AGENT)
            .tag(HaInternalRequest::class.java, HaInternalRequest)
            .build()
        val socket = client.newWebSocket(request, QueueListener(events))
        val conversation = Conversation(socket, events, System.currentTimeMillis() + timeoutMs)
        return try {
            conversation.authenticate(accessToken)
            val ingressPath = if (needIngressPath) conversation.ingressPath(slug) else null
            Result.Success(ingressPath, conversation.session(slug))
        } catch (_: AuthRejected) {
            Result.AuthInvalid
        } catch (e: ConversationFailed) {
            Result.Failed(e.message.orEmpty())
        } finally {
            socket.close(NORMAL_CLOSURE, null)
        }
    }

    private class Conversation(
        private val socket: WebSocket,
        private val events: LinkedBlockingQueue<Event>,
        private val deadlineMs: Long,
    ) {
        private var nextId = 1

        fun authenticate(accessToken: String) {
            expectType("auth_required")
            send(JSONObject().put("type", "auth").put("access_token", accessToken))
            when (val type = next().optString("type")) {
                "auth_ok" -> Unit
                "auth_invalid" -> throw AuthRejected()
                else -> throw ConversationFailed("Unexpected auth answer: $type")
            }
        }

        fun ingressPath(slug: String): String {
            val info = commandWithFallback(
                JSONObject().put("type", "supervisor/ingress/info").put("slug", slug),
                JSONObject().put("type", "supervisor/api")
                    .put("endpoint", "/addons/$slug/info").put("method", "get"),
            )
            val path = info.optString("ingress_url").takeIf { it.isNotEmpty() && !info.isNull("ingress_url") }
                ?: throw ConversationFailed("Add-on $slug has no ingress URL")
            return normalizeIngressPath(path)
        }

        fun session(slug: String): String {
            val result = commandWithFallback(
                JSONObject().put("type", "supervisor/ingress/session").put("slug", slug),
                JSONObject().put("type", "supervisor/api")
                    .put("endpoint", "/ingress/session").put("method", "post"),
            )
            return result.optString("session").takeIf { it.isNotEmpty() }
                ?: throw ConversationFailed("No session in ingress answer")
        }

        private fun commandWithFallback(primary: JSONObject, legacy: JSONObject): JSONObject = try {
            command(primary)
        } catch (e: CommandError) {
            if (e.code != "unknown_command") throw ConversationFailed("${primary.optString("type")}: ${e.message}")
            try {
                command(legacy)
            } catch (legacyError: CommandError) {
                throw ConversationFailed("supervisor/api: ${legacyError.message}")
            }
        }

        private fun command(payload: JSONObject): JSONObject {
            val id = nextId++
            send(payload.put("id", id))
            while (true) {
                val message = next()
                if (message.optString("type") != "result" || message.optInt("id") != id) continue
                if (message.optBoolean("success")) {
                    return message.optJSONObject("result") ?: JSONObject()
                }
                val error = message.optJSONObject("error")
                throw CommandError(error?.optString("code").orEmpty(), error?.optString("message").orEmpty())
            }
        }

        private fun expectType(type: String) {
            val actual = next().optString("type")
            if (actual != type) throw ConversationFailed("Expected $type, got $actual")
        }

        private fun send(json: JSONObject) {
            if (!socket.send(json.toString())) throw ConversationFailed("WebSocket closed")
        }

        private fun next(): JSONObject {
            val remaining = deadlineMs - System.currentTimeMillis()
            val event = if (remaining > 0) events.poll(remaining, TimeUnit.MILLISECONDS) else null
            val text = (event as? Event.Message)?.text
                ?: throw ConversationFailed((event as? Event.Closed)?.reason ?: "Timed out")
            return parseOrNull(text) ?: throw ConversationFailed("Malformed message")
        }

        private fun parseOrNull(text: String): JSONObject? = try {
            JSONObject(text)
        } catch (_: JSONException) {
            null
        }
    }

    private sealed interface Event {
        data class Message(val text: String) : Event
        data class Closed(val reason: String) : Event
    }

    private class QueueListener(private val events: LinkedBlockingQueue<Event>) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            events.put(Event.Message(text))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            events.put(Event.Closed("Closed $code"))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code?.let { "HTTP $it: " }.orEmpty()
            events.put(Event.Closed(code + (t.message ?: t.javaClass.simpleName)))
        }
    }

    private class AuthRejected : Exception()
    private class ConversationFailed(message: String) : Exception(message)
    private class CommandError(val code: String, message: String) : Exception(message)

    companion object {
        private const val USER_AGENT = "Phylax/1.0 HomeAssistant"
        private const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val NORMAL_CLOSURE = 1000

        internal fun webSocketUrl(origin: String): String = when {
            origin.startsWith("https://") -> "wss://" + origin.removePrefix("https://")
            else -> "ws://" + origin.removePrefix("http://")
        } + "/api/websocket"

        /** `/api/hassio_ingress/<token>/` with exactly one leading and one trailing slash. */
        internal fun normalizeIngressPath(path: String): String = "/" + path.trim('/') + "/"
    }
}
