package com.asksakis.freegate.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.asksakis.freegate.utils.ClientCertManager
import com.asksakis.freegate.utils.NetworkUtils
import com.asksakis.freegate.utils.OkHttpClientFactory
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Owns the list of configured Frigate deployments and the swap-in-place mechanism
 * that exchanges the active profile's data with the flat `SharedPreferences` keys
 * the rest of the app reads. Keeps existing consumers (NetworkUtils, CredentialsStore,
 * ClientCertManager, AlertFilter, …) untouched — they always operate on "the active
 * server", which is whatever is currently mirrored into flat state.
 *
 * Storage layout:
 *  - Active profile id lives in default prefs (`active_server_profile_id`), readable
 *    without unlocking the keystore.
 *  - The list of inactive profiles (plus the active one's last-saved snapshot) lives
 *    in the encrypted prefs file used by [CredentialsStore], so passwords are
 *    encrypted at rest alongside everything else.
 *
 * One-time migration: if no profiles exist on first launch but flat keys carry URLs,
 * synthesise a "Default" profile from the current flat state and mark it active.
 */
class ServerProfileStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val defaultPrefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(appContext)

    private val secretPrefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECRET_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.e(TAG, "Encrypted prefs init failed, falling back to plain prefs: ${e.message}")
        appContext.getSharedPreferences(SECRET_PREFS_FALLBACK_FILE, Context.MODE_PRIVATE)
    }

    /** All known profiles, sorted by name. */
    fun getAll(): List<ServerProfile> = readProfiles().sortedBy { it.name.lowercase() }

    fun getActiveId(): String? = defaultPrefs.getString(KEY_ACTIVE_ID, null)

    /**
     * Returns the active profile as seen in storage. If you want the *live* state
     * (which may have user edits not yet committed back to the snapshot), call
     * [snapshotFlatState] instead.
     */
    fun getActive(): ServerProfile? = getActiveId()?.let { id -> readProfiles().firstOrNull { it.id == id } }

    /**
     * Create a new empty profile. The new profile is NOT made active — caller does
     * that via [setActive] after the user confirms.
     */
    fun add(name: String): ServerProfile {
        val profile = ServerProfile(id = UUID.randomUUID().toString(), name = name.trim())
        val list = readProfiles().toMutableList().apply { add(profile) }
        writeProfiles(list)
        return profile
    }

    fun rename(id: String, newName: String): Boolean {
        val list = readProfiles().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list[idx] = list[idx].copy(name = newName.trim())
        writeProfiles(list)
        return true
    }

    /**
     * Delete a profile. Fails when:
     *  - The profile doesn't exist.
     *  - It's the only profile left (must keep at least one).
     */
    fun delete(id: String): Boolean {
        val list = readProfiles().toMutableList()
        if (list.size <= 1) return false
        val removed = list.removeIf { it.id == id }
        if (!removed) return false
        writeProfiles(list)
        if (getActiveId() == id) {
            // Active was deleted — switch to the first remaining profile.
            setActive(list.first().id)
        }
        return true
    }

    /**
     * Swap the live flat state with [newId]'s snapshot. Persists the outgoing
     * profile's snapshot first, then writes the new one into flat keys, then bumps
     * the active id. Caller is responsible for restarting any service that pins to
     * the URL / credentials (see [com.asksakis.freegate.notifications.FrigateAlertService]).
     */
    fun setActive(newId: String): Boolean {
        val list = readProfiles().toMutableList()
        val incomingIdx = list.indexOfFirst { it.id == newId }
        if (incomingIdx < 0) return false
        val incoming = list[incomingIdx]

        val currentId = getActiveId()
        if (currentId == newId) return true // already active

        if (currentId != null) {
            // Capture whatever the user has tweaked since the snapshot was loaded
            // back into the outgoing profile.
            val outgoingIdx = list.indexOfFirst { it.id == currentId }
            if (outgoingIdx >= 0) {
                val outgoingName = list[outgoingIdx].name
                list[outgoingIdx] = snapshotFlatState(currentId, outgoingName)
            }
        }

        writeProfiles(list)
        applyToFlatState(incoming)
        // Commit the active id last (synchronous) so a process death between the
        // flat-state writes and the active-id transition leaves the previous
        // server still flagged active, not a half-swapped state.
        defaultPrefs.edit().putString(KEY_ACTIVE_ID, newId).commit()

        // Invalidate every runtime cache that could still be serving the
        // previous profile's values:
        //   - OkHttp clients hold the previous mTLS key managers.
        //   - FrigateAuthManager has a cached session cookie tied to the previous
        //     account; without this it would replay it against the new server.
        //   - NetworkUtils.currentUrl LiveData was computed from the previous
        //     internal/external URLs; FrigateAlertService reads this first.
        //   - WebView CookieManager keeps the previous server's session cookie;
        //     without clearing, the WebView fires its next request with the
        //     outgoing server's frigate_token, which the new server rejects.
        //     Note: removeAllCookies(null) is async fire-and-forget — HomeFragment
        //     also re-runs it with a completion callback before priming the new
        //     session, so an in-flight wipe can't race past installCookie() and
        //     erase the freshly installed frigate_token.
        OkHttpClientFactory.invalidate()
        FrigateAuthManager.getInstance(appContext).invalidate()
        NetworkUtils.getInstance(appContext).forceRefresh()
        runCatching {
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        }
        return true
    }

    /**
     * Make sure the live flat state is reflected back into the active profile's
     * snapshot. Call before reading a profile in cases where the user may have
     * been editing the active server (e.g. before navigating to the Servers
     * screen so the displayed URL is fresh).
     */
    fun commitFlatStateToActive() {
        val activeId = getActiveId() ?: return
        val list = readProfiles().toMutableList()
        val idx = list.indexOfFirst { it.id == activeId }
        if (idx < 0) return
        list[idx] = snapshotFlatState(activeId, list[idx].name)
        writeProfiles(list)
    }

    /**
     * One-time migration on first launch after upgrading to multi-server. If no
     * profiles exist:
     *   - Flat state has data → create a "Default" profile from it.
     *   - Flat state empty → create an empty "Default" profile so the rest of
     *     the app always has an active server to write into.
     */
    fun ensureMigrated() {
        if (readProfiles().isNotEmpty() && getActiveId() != null) return
        Log.d(TAG, "Migrating flat state into a server profile")
        val id = UUID.randomUUID().toString()
        val profile = snapshotFlatState(id, "Default")
        writeProfiles(listOf(profile))
        defaultPrefs.edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    /** Build a profile snapshot from the current flat-pref state. */
    private fun snapshotFlatState(id: String, name: String): ServerProfile {
        val creds = CredentialsStore.getInstance(appContext)
        val cert = defaultPrefs.getString(ClientCertManager.PREF_CLIENT_CERT_ALIAS, null)
        return ServerProfile(
            id = id,
            name = name,
            internalUrl = defaultPrefs.getString("internal_url", null).orEmptyToNull(),
            externalUrl = defaultPrefs.getString("external_url", null).orEmptyToNull(),
            connectionMode = defaultPrefs.getString("connection_mode", "auto") ?: "auto",
            strictTlsExternal = defaultPrefs.getBoolean("strict_tls_external", false),
            homeWifiNetworks = defaultPrefs.getStringSet("home_wifi_networks", emptySet()).orEmpty().toSet(),
            username = creds.getUsername(),
            password = creds.getPassword(),
            mtlsAlias = cert,
            notifyCameras = defaultPrefs.getStringSet("notify_cameras", emptySet()).orEmpty().toSet(),
            notifyZones = defaultPrefs.getStringSet("notify_zones", emptySet()).orEmpty().toSet(),
            notifyCamerasTotal = defaultPrefs.getInt("notify_cameras_total", 0),
            notifyZonesTotal = defaultPrefs.getInt("notify_zones_total", 0),
            notifyAlerts = defaultPrefs.getBoolean("notify_alerts", true),
            notifyDetections = defaultPrefs.getBoolean("notify_detections", false),
            cooldownGlobalSec = defaultPrefs.getString("notify_cooldown_global", "0") ?: "0",
            cooldownCameraSec = defaultPrefs.getString("notify_cooldown_camera", "0") ?: "0",
            listeningSinceMs = defaultPrefs.getLong("notify_listening_since_ms", 0L),
        )
    }

    /** Mirror a profile back into the flat keys other components read from. */
    private fun applyToFlatState(profile: ServerProfile) {
        val editor = defaultPrefs.edit()
        if (profile.internalUrl == null) editor.remove("internal_url") else editor.putString("internal_url", profile.internalUrl)
        if (profile.externalUrl == null) editor.remove("external_url") else editor.putString("external_url", profile.externalUrl)
        editor.putString("connection_mode", profile.connectionMode)
        editor.putBoolean("strict_tls_external", profile.strictTlsExternal)
        editor.putStringSet("home_wifi_networks", profile.homeWifiNetworks)
        editor.putStringSet("notify_cameras", profile.notifyCameras)
        editor.putStringSet("notify_zones", profile.notifyZones)
        editor.putInt("notify_cameras_total", profile.notifyCamerasTotal)
        editor.putInt("notify_zones_total", profile.notifyZonesTotal)
        editor.putBoolean("notify_alerts", profile.notifyAlerts)
        editor.putBoolean("notify_detections", profile.notifyDetections)
        editor.putString("notify_cooldown_global", profile.cooldownGlobalSec)
        editor.putString("notify_cooldown_camera", profile.cooldownCameraSec)
        editor.putLong("notify_listening_since_ms", profile.listeningSinceMs)
        if (profile.mtlsAlias == null) {
            editor.remove(ClientCertManager.PREF_CLIENT_CERT_ALIAS)
        } else {
            editor.putString(ClientCertManager.PREF_CLIENT_CERT_ALIAS, profile.mtlsAlias)
        }
        // Synchronous commit so a process death between this write and the
        // active-id bump in setActive doesn't leave the two prefs files in
        // disagreement on next launch.
        editor.commit()

        val creds = CredentialsStore.getInstance(appContext)
        creds.setUsername(profile.username)
        creds.setPassword(profile.password)
    }

    private fun readProfiles(): List<ServerProfile> {
        val raw = secretPrefs.getString(KEY_PROFILES_JSON, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { ServerProfile.fromJson(arr.getJSONObject(it)) }
        }.getOrElse {
            Log.e(TAG, "Failed to parse server profiles JSON; resetting", it)
            emptyList()
        }
    }

    private fun writeProfiles(profiles: List<ServerProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        // `commit()` (synchronous) on every profile-list write. The pairings
        // matter: setActive / add / delete all update the profile list *and*
        // KEY_ACTIVE_ID; if the JSON were written async with apply() and the
        // process died between the two, the active-id could point to a profile
        // the JSON hasn't recorded yet, or a deleted profile could reappear on
        // next launch because the list-without-it never reached disk. Writes
        // here are human-paced (settings interactions), so paying for commit()
        // is fine.
        secretPrefs.edit().putString(KEY_PROFILES_JSON, arr.toString()).commit()
    }

    private fun String?.orEmptyToNull(): String? = this?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "ServerProfileStore"
        private const val SECRET_PREFS_FILE = "frigate_server_profiles"
        private const val SECRET_PREFS_FALLBACK_FILE = "frigate_server_profiles_fallback"
        private const val KEY_PROFILES_JSON = "profiles"
        private const val KEY_ACTIVE_ID = "active_server_profile_id"

        @Volatile
        private var INSTANCE: ServerProfileStore? = null

        fun getInstance(context: Context): ServerProfileStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ServerProfileStore(context).also { INSTANCE = it }
            }
    }
}
