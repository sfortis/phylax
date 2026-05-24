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

    /**
     * Monitor that serialises read-modify-write on [mute]/[unmute]/[clear]. The
     * alert-service WebSocket thread, the UI thread, and the bottom-sheet
     * countdown ticker all hit the store; without this lock two near-simultaneous
     * `mute(...)` calls would race and one entry could be silently dropped.
     */
    private val lock = Any()

    /**
     * In-memory mirror of the prefs JSON. Lazily populated on first read, kept
     * in sync by [save] / [saveGroupCameras] so the alert hot path doesn't
     * re-parse JSON on every event. `null` means "load from prefs next read".
     */
    @Volatile private var cachedMutes: Map<Key, Long>? = null
    @Volatile private var cachedGroupCameras: Map<String, List<String>>? = null

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
     * filtered out on every read; the on-disk file is pruned on the next [save].
     *
     * Reads from the in-memory cache when populated, parsing the prefs JSON
     * only on first call (or after a [save] invalidation) so the alert hot
     * path doesn't pay JSON parsing per event.
     */
    fun activeMutes(now: Long = System.currentTimeMillis()): Map<Key, Long> {
        val mirror = cachedMutes ?: loadFromPrefsLocked()
        if (mirror.isEmpty()) return emptyMap()
        return mirror.filter { it.value > now }
    }

    /** Read the JSON list from prefs and seed [cachedMutes]. */
    private fun loadFromPrefsLocked(): Map<Key, Long> = synchronized(lock) {
        cachedMutes?.let { return@synchronized it }
        val raw = prefs.getString(KEY, null)
        if (raw == null) {
            val empty = emptyMap<Key, Long>()
            cachedMutes = empty
            return@synchronized empty
        }
        val parsed = runCatching {
            val arr = JSONArray(raw)
            val live = LinkedHashMap<Key, Long>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("n").takeIf { it.isNotEmpty() } ?: continue
                val kind = when (obj.optString("k")) {
                    "c" -> Kind.CAMERA
                    "g" -> Kind.GROUP
                    else -> continue
                }
                val expiry = obj.optLong("e", 0L)
                if (expiry > 0L) live[Key(kind, name)] = expiry
            }
            live
        }.getOrElse { emptyMap() }
        cachedMutes = parsed
        parsed
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
        synchronized(lock) {
            val updated = activeMutes(now).toMutableMap()
            updated[Key(kind, name)] = now + durationMs
            save(updated)
        }
    }

    /** Remove a single mute. */
    fun unmute(kind: Kind, name: String) {
        synchronized(lock) {
            val updated = activeMutes().toMutableMap()
            updated.remove(Key(kind, name))
            save(updated)
        }
    }

    /** Remove every mute. */
    fun clear() {
        synchronized(lock) {
            cachedMutes = emptyMap()
            prefs.edit().remove(KEY).apply()
        }
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
        synchronized(lock) {
            cachedGroupCameras = groupCameras
            val obj = JSONObject()
            groupCameras.forEach { (name, cams) ->
                obj.put(name, JSONArray(cams))
            }
            prefs.edit().putString(KEY_GROUP_CAMERAS, obj.toString()).apply()
        }
    }

    /**
     * Mirror of [saveGroupCameras]; empty map if nothing has been cached yet.
     * Reads from the in-memory cache after the first call so the alert hot
     * path doesn't re-parse JSON on every event.
     */
    fun loadGroupCameras(): Map<String, List<String>> {
        cachedGroupCameras?.let { return it }
        return synchronized(lock) {
            cachedGroupCameras?.let { return@synchronized it }
            val raw = prefs.getString(KEY_GROUP_CAMERAS, null)
            if (raw == null) {
                val empty = emptyMap<String, List<String>>()
                cachedGroupCameras = empty
                return@synchronized empty
            }
            val parsed = runCatching {
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
            cachedGroupCameras = parsed
            parsed
        }
    }

    private fun save(mutes: Map<Key, Long>) {
        // Update the in-memory mirror first so subsequent reads in the same
        // tick (e.g. MainActivity.onPrepareOptionsMenu re-running after the
        // OnMutesChanged callback) see the new state immediately. `apply()`'s
        // async disk flush is fine because nothing reads from the on-disk
        // file until the next process restart, by which point the write is
        // committed.
        cachedMutes = mutes
        if (mutes.isEmpty()) {
            prefs.edit().remove(KEY).apply()
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
        prefs.edit().putString(KEY, arr.toString()).apply()
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
