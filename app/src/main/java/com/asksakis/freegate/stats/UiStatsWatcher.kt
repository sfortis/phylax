package com.asksakis.freegate.stats

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.asksakis.freegate.auth.FrigateAuthManager
import com.asksakis.freegate.utils.ClientCertManager
import com.asksakis.freegate.utils.NetworkUtils
import com.asksakis.freegate.utils.OkHttpClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject

/**
 * Lifecycle-scoped HTTP poller for the Frigate `/api/stats` endpoint. Runs only while
 * the Activity is visible so we don't pay any battery for a poll loop in the background
 * (the notification service does its own thing).
 *
 * HTTP is preferred over the `stats` WS topic for this UI because Frigate only publishes
 * that topic every ~15 seconds, which looks frozen on a live metrics chip. The REST
 * endpoint returns the same payload and we can sample it as often as we like.
 */
class UiStatsWatcher(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val authManager = FrigateAuthManager.getInstance(context)
    private val clientCertManager = ClientCertManager.getInstance(context)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { pollLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val baseUrl = resolveBaseUrl()
            if (baseUrl == null) {
                delay(IDLE_INTERVAL_MS)
                continue
            }
            val ok = pollOnce(baseUrl)
            delay(if (ok) POLL_INTERVAL_MS else BACKOFF_INTERVAL_MS)
        }
    }

    private suspend fun pollOnce(baseUrl: String): Boolean {
        if (!authManager.ensureLoggedIn(baseUrl)) return false
        val client = OkHttpClientFactory.build(
            baseUrl,
            clientCertManager,
            OkHttpClientFactory.Timeouts(connectSeconds = 5, readSeconds = 5),
        )
        val cookie = authManager.getCookieHeader().orEmpty()
        val req = Request.Builder()
            .url("$baseUrl/api/stats")
            .header("User-Agent", "FrigateViewer/1.0 Stats")
            .apply { if (cookie.isNotEmpty()) header("Cookie", cookie) }
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    authManager.invalidate()
                    return@use false
                }
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isEmpty()) return@use false
                val json = runCatching { JSONObject(body) }.getOrNull() ?: return@use false
                FrigateStatsRepository.ingest(json)
                true
            }
        }.getOrElse {
            Log.w(TAG, "poll failed: ${it.message}")
            false
        }
    }

    private fun resolveBaseUrl(): String? {
        val live = NetworkUtils.getInstance(context).currentUrl.value
        if (!live.isNullOrBlank()) return live.trimEnd('/')
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val internal = prefs.getString("internal_url", null)?.trimEnd('/')
        if (!internal.isNullOrBlank()) return internal
        return prefs.getString("external_url", null)?.trimEnd('/')
    }

    companion object {
        private const val TAG = "UiStatsWatcher"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val BACKOFF_INTERVAL_MS = 5_000L
        private const val IDLE_INTERVAL_MS = 3_000L
    }
}
