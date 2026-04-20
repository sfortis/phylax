package com.asksakis.freegate.notifications

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
 * Lightweight client for `GET /api/config`, used by the Settings UI to populate the
 * camera multi-select filter. Runs on [Dispatchers.IO].
 */
class FrigateConfigFetcher(context: Context) {

    private val appContext = context.applicationContext
    private val authManager = FrigateAuthManager.getInstance(appContext)
    private val clientCertManager = ClientCertManager.getInstance(appContext)

    suspend fun fetchCameraNames(baseUrl: String): List<String> =
        withContext(Dispatchers.IO) {
            if (!authManager.ensureLoggedIn(baseUrl)) {
                Log.w(TAG, "Not logged in; can't fetch config")
                return@withContext emptyList()
            }
            val cookie = authManager.getCookieHeader() ?: return@withContext emptyList()
            val client = OkHttpClientFactory.build(baseUrl, clientCertManager)
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/config")
                .header("Cookie", cookie)
                .header("User-Agent", "FrigateViewer/1.0 ConfigFetcher")
                .build()
            try {
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Config fetch HTTP ${response.code}")
                        return@withContext emptyList()
                    }
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val cameras = JSONObject(body).optJSONObject("cameras") ?: return@withContext emptyList()
                    val names = mutableListOf<String>()
                    val keys = cameras.keys()
                    while (keys.hasNext()) names += keys.next()
                    names.sort()
                    names
                }
            } catch (e: Exception) {
                Log.e(TAG, "Config fetch failed: ${e.message}")
                emptyList()
            }
        }


    companion object {
        private const val TAG = "FrigateConfigFetcher"
    }
}
