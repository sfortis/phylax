package com.asksakis.freegate.ha

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Renews a Home Assistant access token with a refresh token (`POST /auth/token`).
 *
 * Home Assistant returns a new access token and keeps the refresh token unchanged, so the
 * same refresh token keeps working for the WebView and for the app.
 */
class HaAuthClient(private val client: OkHttpClient) {

    sealed interface Result {
        data class Success(val accessToken: String, val expiresInSeconds: Long) : Result {
            override fun toString() = "Success(expiresInSeconds=$expiresInSeconds)"
        }

        /** The refresh token was revoked or is unknown. The stored copy is useless. */
        data object InvalidGrant : Result

        /** Any other failure (network, server error, user not allowed). Worth retrying later. */
        data class Failed(val reason: String) : Result
    }

    fun refresh(tokens: HaTokens): Result {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", tokens.refreshToken)
            .apply { tokens.clientId?.let { add("client_id", it) } }
            .build()
        val request = Request.Builder()
            .url("${tokens.hassUrl}/auth/token")
            .post(form)
            .header("User-Agent", USER_AGENT)
            .tag(HaInternalRequest::class.java, HaInternalRequest)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> parseSuccess(body)
                    response.code == HTTP_BAD_REQUEST && errorOf(body) == "invalid_grant" -> Result.InvalidGrant
                    else -> Result.Failed("HTTP ${response.code} ${errorOf(body).orEmpty()}".trim())
                }
            }
        } catch (e: IOException) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun parseSuccess(body: String): Result = try {
        val json = JSONObject(body)
        val token = json.optString("access_token")
        if (token.isEmpty() || json.isNull("access_token")) {
            Result.Failed("No access_token in response")
        } else {
            Result.Success(token, json.optLong("expires_in", DEFAULT_EXPIRES_IN_SECONDS))
        }
    } catch (_: JSONException) {
        Result.Failed("Malformed token response")
    }

    private fun errorOf(body: String): String? = try {
        JSONObject(body).optString("error").takeIf { it.isNotEmpty() }
    } catch (_: JSONException) {
        null
    }

    companion object {
        private const val USER_AGENT = "Phylax/1.0 HomeAssistant"
        private const val HTTP_BAD_REQUEST = 400

        /** Home Assistant issues access tokens for 30 minutes. */
        private const val DEFAULT_EXPIRES_IN_SECONDS = 1800L
    }
}

/**
 * Request tag for calls the app makes to Home Assistant itself (`/auth/token`,
 * `/api/websocket`). The interceptors in
 * [com.asksakis.freegate.utils.OkHttpClientFactory] leave tagged requests alone: they must
 * not be rewritten to ingress, and they must not carry the Frigate Basic Auth header.
 */
object HaInternalRequest
