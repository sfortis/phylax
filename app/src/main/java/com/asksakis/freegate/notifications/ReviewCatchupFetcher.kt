package com.asksakis.freegate.notifications

import android.content.Context
import android.util.Log
import com.asksakis.freegate.auth.FrigateAuthManager
import com.asksakis.freegate.utils.ClientCertManager
import com.asksakis.freegate.utils.OkHttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches reviews that occurred while the WebSocket was disconnected, so a reboot /
 * network switch / doze drop doesn't silently lose alerts (Frigate's `/ws` is live-only
 * and never replays missed frames).
 *
 * Returns the raw review objects from `GET /api/review?after=<sinceSec>`; the caller
 * wraps each in a synthetic `reviews` envelope and runs it through the exact same
 * filter + cooldown/dedupe + notify path as live frames, so already-notified reviews
 * are dropped and nothing is double-posted. Mirrors [FrigateConfigFetcher]'s setup.
 */
class ReviewCatchupFetcher(context: Context) {

    private val appContext = context.applicationContext
    private val authManager = FrigateAuthManager.getInstance(appContext)
    private val clientCertManager = ClientCertManager.getInstance(appContext)

    /**
     * Reviews with `start_time` at or after [sinceSec] (epoch seconds), oldest first.
     * Empty list on any auth/network/parse failure — catch-up is best-effort and must
     * never crash the listener or block the live path.
     */
    suspend fun fetchSince(baseUrl: String, sinceSec: Double): List<JSONObject> {
        // `after` is epoch seconds; Frigate returns newest-first. We sort oldest-first
        // below so notifications post in chronological order.
        val body = fetch(baseUrl, "after=${sinceSec.toLong()}", "Catch-up") ?: return emptyList()
        return parseReviews(body, sinceSec)
    }

    /**
     * The [limit] newest reviews, newest first, only alerts when [alertsOnly]. Null when
     * the server could not be reached or refused the request, so the caller can try
     * another URL. Used to fill the recent events widget when it is added.
     */
    suspend fun fetchLatest(baseUrl: String, limit: Int, alertsOnly: Boolean): List<JSONObject>? {
        val query = "limit=$limit" + if (alertsOnly) "&severity=alert" else ""
        val body = fetch(baseUrl, query, "Latest") ?: return null
        return try {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Latest reviews parse failed: ${e.message}")
            null
        }
    }

    /** The body of `GET /api/review?<query>`, or null on any auth, network or HTTP failure. */
    private suspend fun fetch(baseUrl: String, query: String, purpose: String): String? =
        withContext(Dispatchers.IO) {
            // A failed login is not a reason to skip the fetch: setups that authenticate
            // in a reverse proxy, or run Frigate with auth disabled, have no working
            // /api/login yet answer /api/review without a cookie. A server that does
            // require auth returns 401 and the non-2xx path below yields null.
            if (!authManager.ensureLoggedIn(baseUrl)) {
                Log.d(TAG, "No session; running $purpose fetch without a cookie")
            }
            val cookie = authManager.getCookieHeader(baseUrl)
            val client = OkHttpClientFactory.build(
                baseUrl,
                clientCertManager,
                OkHttpClientFactory.Timeouts(connectSeconds = 10, readSeconds = 10),
            )
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/review?$query")
                .apply { if (!cookie.isNullOrEmpty()) header("Cookie", cookie) }
                .header("User-Agent", "FrigateViewer/1.0 Catchup")
                .build()
            try {
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "$purpose fetch HTTP ${response.code}")
                        return@withContext null
                    }
                    response.body?.string()
                }
            } catch (e: Exception) {
                Log.e(TAG, "$purpose fetch failed: ${e.message}")
                null
            }
        }

    private fun parseReviews(body: String, sinceSec: Double): List<JSONObject> {
        return try {
            val arr = JSONArray(body)
            val out = ArrayList<JSONObject>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                // Defensive: only keep reviews strictly newer than the watermark. Frigate's
                // `after` is inclusive-ish, so this drops the boundary review we already saw.
                if (obj.optDouble("start_time", 0.0) > sinceSec) out += obj
            }
            // Oldest first, so the per-event cooldown/notify order matches live delivery.
            out.sortBy { it.optDouble("start_time", 0.0) }
            out
        } catch (e: Exception) {
            Log.e(TAG, "Catch-up parse failed: ${e.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "ReviewCatchupFetcher"
    }
}
