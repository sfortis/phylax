package com.asksakis.freegate.stats

import android.content.Context
import android.util.Log
import com.asksakis.freegate.auth.FrigateAuthManager
import com.asksakis.freegate.utils.ClientCertManager
import com.asksakis.freegate.utils.OkHttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Fetches Frigate's `/api/stats`. Used by the toolbar poller while the app is open and by
 * the server status home screen widget in the background.
 */
class FrigateStatsClient(context: Context) {

    private val authManager = FrigateAuthManager.getInstance(context.applicationContext)
    private val clientCertManager = ClientCertManager.getInstance(context.applicationContext)

    /** The stats body, or null on any failure. A 401 or 403 drops a session that this call's login confirmed. */
    suspend fun fetch(baseUrl: String): JSONObject? = withContext(Dispatchers.IO) {
        // Like SnapshotDownloader: a failed login must not stop the request. Frigate behind a
        // reverse proxy that does the authentication has no working /api/login and still
        // answers /api/stats. A server that really needs a session answers 401 below.
        val loggedIn = authManager.ensureLoggedIn(baseUrl)
        if (!loggedIn) Log.d(TAG, "Login failed; fetching stats without a fresh session")
        val client = OkHttpClientFactory.build(
            baseUrl,
            clientCertManager,
            OkHttpClientFactory.Timeouts(connectSeconds = 5, readSeconds = 5),
        )
        val cookie = authManager.getCookieHeader(baseUrl).orEmpty()
        val req = Request.Builder()
            .url("$baseUrl/api/stats")
            .header("User-Agent", "FrigateViewer/1.0 Stats")
            .apply { if (cookie.isNotEmpty()) header("Cookie", cookie) }
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.code == HTTP_UNAUTHORIZED || resp.code == HTTP_FORBIDDEN) {
                    // Drop only a session that this call's login produced or confirmed.
                    // After a failed login the cookie can be a stale WebView token, and
                    // invalidating then would clear the login cooldown and make the poller
                    // retry /api/login on every tick.
                    if (loggedIn && cookie.isNotEmpty()) authManager.invalidate()
                    return@use null
                }
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isEmpty()) return@use null
                runCatching { JSONObject(body) }.getOrNull()
            }
        }.getOrElse {
            Log.w(TAG, "Stats request failed: ${it.message}")
            null
        }
    }

    private companion object {
        const val TAG = "FrigateStatsClient"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
