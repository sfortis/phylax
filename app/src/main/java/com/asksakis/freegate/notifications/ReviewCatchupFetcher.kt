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
    suspend fun fetchSince(baseUrl: String, sinceSec: Double): List<JSONObject> =
        withContext(Dispatchers.IO) {
            if (!authManager.ensureLoggedIn(baseUrl)) {
                Log.w(TAG, "Login failed; skipping catch-up fetch")
                return@withContext emptyList()
            }
            val cookie = authManager.getCookieHeader()
            val client = OkHttpClientFactory.build(
                baseUrl,
                clientCertManager,
                OkHttpClientFactory.Timeouts(connectSeconds = 10, readSeconds = 10),
            )
            // `after` is epoch seconds; Frigate returns newest-first. We sort oldest-first
            // below so notifications post in chronological order.
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/review?after=${sinceSec.toLong()}")
                .apply { if (!cookie.isNullOrEmpty()) header("Cookie", cookie) }
                .header("User-Agent", "FrigateViewer/1.0 Catchup")
                .build()
            try {
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Catch-up fetch HTTP ${response.code}")
                        return@withContext emptyList()
                    }
                    val body = response.body?.string() ?: return@withContext emptyList()
                    parseReviews(body, sinceSec)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Catch-up fetch failed: ${e.message}")
                emptyList()
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
