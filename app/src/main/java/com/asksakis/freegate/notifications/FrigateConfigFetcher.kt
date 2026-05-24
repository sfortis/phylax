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
     * Map of camera group name → ordered camera list, as declared in the Frigate
     * Web UI (stored in Frigate's internal SQLite, surfaced under `camera_groups`
     * in `/api/config`). Empty map if Frigate hasn't defined any groups, or on
     * any auth/network/parse failure — callers degrade to "no quick-mute targets".
     */
    suspend fun fetchCameraGroups(baseUrl: String): Map<String, List<String>> =
        withContext(Dispatchers.IO) {
            val body = fetchConfigBody(baseUrl) ?: return@withContext emptyMap()
            try {
                val groupsJson = JSONObject(body).optJSONObject("camera_groups")
                    ?: return@withContext emptyMap()
                val result = mutableMapOf<String, List<String>>()
                val keys = groupsJson.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val group = groupsJson.optJSONObject(name) ?: continue
                    val arr = group.optJSONArray("cameras") ?: continue
                    val cameras = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
                    if (cameras.isNotEmpty()) result[name] = cameras
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Camera groups parse failed: ${e.message}")
                emptyMap()
            }
        }

    /**
     * Single source of truth for hitting `/api/config`. Returns the raw response
     * body string, or null on any failure (auth, network, non-2xx). Lets the
     * `fetchCamerasWithZones` and `fetchCameraGroups` paths share the same
     * login + request setup without duplicating it.
     */
    private suspend fun fetchConfigBody(baseUrl: String): String? =
        withContext(Dispatchers.IO) {
            if (!authManager.ensureLoggedIn(baseUrl)) {
                Log.w(TAG, "Login failed; can't fetch config")
                return@withContext null
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
                        return@withContext null
                    }
                    response.body?.string()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Config fetch failed: ${e.message}")
                null
            }
        }

    /**
     * Map of camera name → sorted zone names. Empty list for cameras with no zones.
     * Returns an empty map on any failure (auth, network, parse) so callers can
     * degrade gracefully.
     */
    suspend fun fetchCamerasWithZones(baseUrl: String): Map<String, List<String>> =
        withContext(Dispatchers.IO) {
            val body = fetchConfigBody(baseUrl) ?: return@withContext emptyMap()
            try {
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
            } catch (e: Exception) {
                Log.e(TAG, "Cameras parse failed: ${e.message}")
                emptyMap()
            }
        }


    companion object {
        private const val TAG = "FrigateConfigFetcher"
    }
}
