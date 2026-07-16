package com.asksakis.freegate.auth

import org.json.JSONArray
import org.json.JSONObject

/**
 * A snapshot of one Frigate deployment's settings: URLs, credentials, network mode,
 * TLS posture, client certificate, home-Wi-Fi SSIDs, and notification filters.
 *
 * Only one profile is "active" at a time — its values are mirrored into the flat
 * SharedPreferences keys that every legacy consumer (NetworkUtils, CredentialsStore,
 * ClientCertManager, AlertFilter, …) already reads from. Inactive profiles live as
 * serialized snapshots in [ServerProfileStore] and are swapped in/out atomically on
 * an active-server change. See [ServerProfileStore.setActive].
 *
 * Keeping the schema serialisable as a single JSON object (rather than per-field
 * `SharedPreferences` rows) makes the swap operation trivially atomic and lets us
 * stash secrets in the encrypted prefs file alongside the rest of the snapshot.
 */
data class ServerProfile(
    val id: String,
    val name: String,
    val internalUrl: String? = null,
    val externalUrl: String? = null,
    val connectionMode: String = "auto",
    val strictTlsExternal: Boolean = false,
    val homeWifiNetworks: Set<String> = emptySet(),
    val username: String? = null,
    val password: String? = null,
    val mtlsAlias: String? = null,
    val notifyCameras: Set<String> = emptySet(),
    val notifyZones: Set<String> = emptySet(),
    val notifyCamerasTotal: Int = 0,
    val notifyZonesTotal: Int = 0,
    /**
     * Per-server notification toggles. A typical setup is "home" with detections on
     * and "office" with alerts only — keeping these in the profile preserves each
     * server's posture across switches.
     */
    val notifyAlerts: Boolean = true,
    val notifyDetections: Boolean = false,
    val cooldownGlobalSec: String = "0",
    val cooldownCameraSec: String = "0",
    /** Per-server "listening since" epoch (drops trackers older than this on activate). */
    val listeningSinceMs: Long = 0L,
) {

    /**
     * True when this profile carries at least one URL that looks like a real
     * endpoint (http/https with a host). An "unusable" profile is the empty
     * placeholder a fresh install starts with: the app treats it as "no server
     * configured yet" rather than a connection failure, so networking stays idle
     * and the Home screen shows the setup empty state instead of a broken viewer.
     */
    val isUsable: Boolean
        get() = looksLikeEndpoint(internalUrl) || looksLikeEndpoint(externalUrl)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("internal_url", internalUrl ?: JSONObject.NULL)
        put("external_url", externalUrl ?: JSONObject.NULL)
        put("connection_mode", connectionMode)
        put("strict_tls_external", strictTlsExternal)
        put("home_wifi_networks", JSONArray(homeWifiNetworks.toList()))
        put("username", username ?: JSONObject.NULL)
        put("password", password ?: JSONObject.NULL)
        put("mtls_alias", mtlsAlias ?: JSONObject.NULL)
        put("notify_cameras", JSONArray(notifyCameras.toList()))
        put("notify_zones", JSONArray(notifyZones.toList()))
        put("notify_cameras_total", notifyCamerasTotal)
        put("notify_zones_total", notifyZonesTotal)
        put("notify_alerts", notifyAlerts)
        put("notify_detections", notifyDetections)
        put("notify_cooldown_global", cooldownGlobalSec)
        put("notify_cooldown_camera", cooldownCameraSec)
        put("notify_listening_since_ms", listeningSinceMs)
    }

    companion object {
        fun fromJson(json: JSONObject): ServerProfile = ServerProfile(
            id = json.getString("id"),
            name = json.getString("name"),
            internalUrl = json.optString("internal_url").takeIf { !json.isNull("internal_url") && it.isNotEmpty() },
            externalUrl = json.optString("external_url").takeIf { !json.isNull("external_url") && it.isNotEmpty() },
            connectionMode = json.optString("connection_mode", "auto"),
            strictTlsExternal = json.optBoolean("strict_tls_external", false),
            homeWifiNetworks = jsonArrayToSet(json.optJSONArray("home_wifi_networks")),
            username = json.optString("username").takeIf { !json.isNull("username") && it.isNotEmpty() },
            password = json.optString("password").takeIf { !json.isNull("password") && it.isNotEmpty() },
            mtlsAlias = json.optString("mtls_alias").takeIf { !json.isNull("mtls_alias") && it.isNotEmpty() },
            notifyCameras = jsonArrayToSet(json.optJSONArray("notify_cameras")),
            notifyZones = jsonArrayToSet(json.optJSONArray("notify_zones")),
            notifyCamerasTotal = json.optInt("notify_cameras_total", 0),
            notifyZonesTotal = json.optInt("notify_zones_total", 0),
            notifyAlerts = json.optBoolean("notify_alerts", true),
            notifyDetections = json.optBoolean("notify_detections", false),
            cooldownGlobalSec = json.optString("notify_cooldown_global", "0"),
            cooldownCameraSec = json.optString("notify_cooldown_camera", "0"),
            listeningSinceMs = json.optLong("notify_listening_since_ms", 0L),
        )

        /**
         * Cheap structural check: is [url] a non-blank http/https URL with a host?
         * This is a "did the user configure something" gate, not a reachability
         * test - NetworkUtils still validates the endpoint over the wire before use.
         */
        fun looksLikeEndpoint(url: String?): Boolean {
            val u = url?.trim().orEmpty()
            if (u.isEmpty()) return false
            val lower = u.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
            // Something must follow the scheme (a host), not just "https://".
            val afterScheme = u.substringAfter("://", "")
            return afterScheme.isNotBlank()
        }

        private fun jsonArrayToSet(arr: JSONArray?): Set<String> {
            if (arr == null) return emptySet()
            return buildSet {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }
    }
}
