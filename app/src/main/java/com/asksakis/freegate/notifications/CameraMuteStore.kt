package com.asksakis.freegate.notifications

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-active-profile mute table.
 *
 * Each entry is a (group name, expiry timestamp) pair: while the wall clock is
 * before `expiresAtMs`, any alert/detection whose camera belongs to that group
 * is dropped before notification. Expired entries are filtered out lazily on
 * each read — there's no background sweep — so the only path that ever sees
 * a stale entry is the very next [activeMutes] / [isCameraMuted] call.
 *
 * Stored in the default SharedPreferences under a profile-scoped key so each
 * Frigate server profile keeps its own mute table; the [ServerProfileStore]
 * swap mirrors the flat key on profile change.
 */
class CameraMuteStore private constructor(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /** Whether a mute entry targets a single camera or a whole camera-group. */
    enum class Kind { CAMERA, GROUP }

    /**
     * Composite key for the active-mutes map. Lets a single camera and a
     * group that *happens* to share the same name coexist (rare in practice
     * but possible — Frigate doesn't enforce a global namespace).
     */
    data class Key(val kind: Kind, val name: String)

    /**
     * Active mutes (still in the future), keyed by [Key]. Expired entries are
     * filtered out lazily; the on-disk file is pruned on the next [save].
     */
    fun activeMutes(now: Long = System.currentTimeMillis()): Map<Key, Long> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            val live = LinkedHashMap<Key, Long>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                // Newer entries carry `k` (kind). Older entries — written when
                // group-only mutes were the only kind — only have `g` (group
                // name); keep treating them as group mutes for upgraders.
                val name = obj.optString("n").takeIf { it.isNotEmpty() }
                    ?: obj.optString("g").takeIf { it.isNotEmpty() }
                    ?: continue
                val kind = when (obj.optString("k")) {
                    "c" -> Kind.CAMERA
                    "g", "" -> Kind.GROUP
                    else -> continue
                }
                val expiry = obj.optLong("e", 0L)
                if (expiry > now) live[Key(kind, name)] = expiry
            }
            live
        }.getOrElse { emptyMap() }
    }

    /**
     * Add or replace a mute. Replacing is intentional — picking a fresh
     * duration for an already-muted target should extend (or shorten) the
     * timer, not stack a second entry.
     */
    fun mute(
        kind: Kind,
        name: String,
        durationMs: Long,
        now: Long = System.currentTimeMillis(),
    ) {
        val updated = activeMutes(now).toMutableMap()
        updated[Key(kind, name)] = now + durationMs
        save(updated)
    }

    /** Remove a single mute. */
    fun unmute(kind: Kind, name: String) {
        val updated = activeMutes().toMutableMap()
        updated.remove(Key(kind, name))
        save(updated)
    }

    /** Remove every mute. */
    fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    /**
     * True if any active mute covers [camera]. [groupCameras] maps group name
     * → camera list (typically the latest fetch from `FrigateConfigFetcher`).
     * Called from the alert hot path, so the lookup is intentionally cheap:
     * one prefs read + a `contains` per active group.
     */
    fun isCameraMuted(
        camera: String,
        groupCameras: Map<String, List<String>>,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (camera.isEmpty()) return false
        val mutes = activeMutes(now)
        if (mutes.isEmpty()) return false
        for ((key, _) in mutes) {
            when (key.kind) {
                Kind.CAMERA -> if (key.name == camera) return true
                Kind.GROUP -> {
                    val cams = groupCameras[key.name] ?: continue
                    if (camera in cams) return true
                }
            }
        }
        return false
    }

    /**
     * Last-known mapping of camera-group name → camera list, persisted so the
     * alert hot path can resolve "is this camera muted?" without re-fetching
     * `/api/config`. Refreshed by the UI any time it loads the group picker.
     */
    fun saveGroupCameras(groupCameras: Map<String, List<String>>) {
        val obj = JSONObject()
        groupCameras.forEach { (name, cams) ->
            obj.put(name, JSONArray(cams))
        }
        prefs.edit().putString(KEY_GROUP_CAMERAS, obj.toString()).apply()
    }

    /** Mirror of [saveGroupCameras]; empty map if nothing has been cached yet. */
    fun loadGroupCameras(): Map<String, List<String>> {
        val raw = prefs.getString(KEY_GROUP_CAMERAS, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val out = LinkedHashMap<String, List<String>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val arr = obj.optJSONArray(name) ?: continue
                out[name] = (0 until arr.length()).mapNotNull {
                    arr.optString(it).takeIf { s -> s.isNotEmpty() }
                }
            }
            out
        }.getOrElse { emptyMap() }
    }

    private fun save(mutes: Map<Key, Long>) {
        if (mutes.isEmpty()) {
            prefs.edit().remove(KEY).commit()
            return
        }
        val arr = JSONArray()
        mutes.forEach { (key, expiry) ->
            val k = when (key.kind) {
                Kind.CAMERA -> "c"
                Kind.GROUP -> "g"
            }
            arr.put(JSONObject().put("k", k).put("n", key.name).put("e", expiry))
        }
        // Synchronous .commit() so the UI's invalidateOptionsMenu() (which reads
        // activeMutes() from the same prefs) sees the updated state immediately
        // after mute/unmute — apply()'s async flush was leaving the toolbar
        // icon stale until the SharedPreferences write actually landed.
        prefs.edit().putString(KEY, arr.toString()).commit()
    }

    companion object {
        /**
         * Flat-key store name — mirrored across profile swaps via
         * [com.asksakis.freegate.auth.ServerProfileStore] like every other
         * notification-filter pref.
         */
        const val KEY = "notify_mutes_v1"
        private const val KEY_GROUP_CAMERAS = "notify_camera_groups_v1"

        @Volatile
        private var INSTANCE: CameraMuteStore? = null

        fun getInstance(context: Context): CameraMuteStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CameraMuteStore(context).also { INSTANCE = it }
            }
    }
}
