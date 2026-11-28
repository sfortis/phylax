package com.asksakis.freegate.ha

import org.json.JSONException
import org.json.JSONObject

/**
 * The Home Assistant tokens of one HA instance.
 *
 * The app copies them from the `hassTokens` entry that the HA frontend keeps in the
 * WebView's `localStorage` when the user signs in with "Keep me logged in". HA does not
 * rotate refresh tokens, so the app and the WebView can both use the same one. A refresh
 * request must send the [clientId] the token was issued to.
 *
 * The stored copy uses the same JSON shape as `hassTokens`, so one parser reads both.
 */
data class HaTokens(
    val hassUrl: String,
    val clientId: String?,
    val refreshToken: String,
    val accessToken: String?,
    val accessTokenExpiresAtMs: Long,
) {
    fun toJson(): String = JSONObject()
        .put(KEY_HASS_URL, hassUrl)
        .put(KEY_CLIENT_ID, clientId ?: JSONObject.NULL)
        .put(KEY_REFRESH_TOKEN, refreshToken)
        .put(KEY_ACCESS_TOKEN, accessToken ?: JSONObject.NULL)
        .put(KEY_EXPIRES, accessTokenExpiresAtMs)
        .toString()

    /** Never print the tokens: this object can end up in a log line. */
    override fun toString(): String = "HaTokens(hassUrl=$hassUrl, clientId=$clientId)"

    companion object {
        private const val KEY_HASS_URL = "hassUrl"
        private const val KEY_CLIENT_ID = "clientId"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_EXPIRES = "expires"

        /**
         * Parse the `hassTokens` JSON (or a stored copy). Returns null when the JSON is
         * malformed or has no refresh token, because a copy without one cannot be renewed.
         */
        fun parse(raw: String?): HaTokens? {
            if (raw.isNullOrBlank()) return null
            val json = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            val hassUrl = json.optNullableString(KEY_HASS_URL)?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                ?: return null
            val refreshToken = json.optNullableString(KEY_REFRESH_TOKEN) ?: return null
            return HaTokens(
                hassUrl = hassUrl,
                clientId = json.optNullableString(KEY_CLIENT_ID),
                refreshToken = refreshToken,
                accessToken = json.optNullableString(KEY_ACCESS_TOKEN),
                accessTokenExpiresAtMs = json.optLong(KEY_EXPIRES, 0L),
            )
        }

        /**
         * Android's `optString` turns a JSON null into the string "null", while the JVM
         * library used by the unit tests returns "". Checking [JSONObject.isNull] first
         * gives the same answer on both.
         */
        private fun JSONObject.optNullableString(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}
