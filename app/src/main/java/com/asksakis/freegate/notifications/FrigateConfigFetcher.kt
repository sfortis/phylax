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
        fetchCamerasWithZones(baseUrl).keys.sorted()

    /**
     * Map of camera name → sorted zone names. Empty list for cameras with no zones.
     * Returns an empty map on any failure (auth, network, parse) so callers can
     * degrade gracefully.
     */
    suspend fun fetchCamerasWithZones(baseUrl: String): Map<String, List<String>> =
        withContext(Dispatchers.IO) {
            // ensureLoggedIn returns true for both "logged in" AND "no auth needed
            // (no credentials configured)" — getCookieHeader is null in the second
            // case, which is fine: fire the request without a Cookie header and let
            // Frigate decide. An open Frigate answers; an auth-gated one returns 401
            // and the fragment surfaces the error.
            if (!authManager.ensureLoggedIn(baseUrl)) {
                Log.w(TAG, "Login failed; can't fetch config")
                return@withContext emptyMap()
            }
            val cookie = authManager.getCookieHeader()
            val client = OkHttpClientFactory.build(baseUrl, clientCertManager)
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/config")
                .apply { if (!cookie.isNullOrEmpty()) header("Cookie", cookie) }
                .header("User-Agent", "FrigateViewer/1.0 ConfigFetcher")
                .build()
            try {
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Config fetch HTTP ${response.code}")
                        return@withContext emptyMap()
                    }
                    val body = response.body?.string() ?: return@withContext emptyMap()
                    val cameras = JSONObject(body).optJSONObject("cameras")
                        ?: return@withContext emptyMap()

                    val result = mutableMapOf<String, List<String>>()
                    val keys = cameras.keys()
                    while (keys.hasNext()) {
                        val cameraName = keys.next()
                        val cam = cameras.optJSONObject(cameraName) ?: continue
                        val zonesObj = cam.optJSONObject("zones")
                        val zones = mutableListOf<String>()
                        if (zonesObj != null) {
                            val zoneKeys = zonesObj.keys()
                            while (zoneKeys.hasNext()) zones += zoneKeys.next()
                        }
                        result[cameraName] = zones.sorted()
                    }
                    result
                }
            } catch (e: Exception) {
                Log.e(TAG, "Config fetch failed: ${e.message}")
                emptyMap()
            }
        }


    companion object {
        private const val TAG = "FrigateConfigFetcher"
    }
}
