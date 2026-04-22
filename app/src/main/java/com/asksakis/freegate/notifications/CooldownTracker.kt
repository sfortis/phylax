package com.asksakis.freegate.notifications

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Per-service cooldown gate that mirrors Frigate's `_within_cooldown` check
 * (frigate/comms/webpush.py:335). Two knobs, both in seconds:
 *   - global: minimum gap between *any* two notifications
 *   - perCamera: minimum gap between notifications from the same camera
 * A value of 0 disables that gate (matches Frigate's default).
 */
class CooldownTracker(
    private val context: Context? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile private var lastGlobalMs = 0L
    private val lastPerCameraMs = mutableMapOf<String, Long>()
    // event id → last-notified timestamp. Persisted across service restarts (SharedPrefs)
    // so OEM kills / watchdog revives don't re-notify old tracked objects that Frigate
    // is still sending `update` frames for. Loaded lazily on first use.
    private val lastPerEventMs: MutableMap<String, Long> by lazy { loadEventMap() }
    private val lock = Any()

    /**
     * True if the message for [camera] / [eventId] should be suppressed.
     * [eventId] is optional — supply it to dedupe the Frigate events topic's repeated
     * `update` frames (each tracking cycle emits many). For `reviews` pass null.
     */
    fun shouldSkip(
        camera: String,
        globalSec: Int,
        perCameraSec: Int,
        eventId: String? = null,
    ): Boolean {
        val now = clock()
        synchronized(lock) {
            if (eventId != null) {
                // Hard dedupe: same event id within a ~15 minute window → one notification.
                val prior = lastPerEventMs[eventId]
                if (prior != null && now - prior < EVENT_DEDUPE_WINDOW_MS) return true
            }
            if (globalSec > 0 && now - lastGlobalMs < globalSec * 1000L) return true
            if (perCameraSec > 0) {
                val last = lastPerCameraMs[camera] ?: 0L
                if (now - last < perCameraSec * 1000L) return true
            }
        }
        return false
    }

    /** Record that a notification was delivered for [camera] just now. */
    fun recordNotified(camera: String, eventId: String? = null) {
        val now = clock()
        synchronized(lock) {
            lastGlobalMs = now
            lastPerCameraMs[camera] = now
            if (eventId != null) {
                lastPerEventMs[eventId] = now
                // Opportunistic GC so we don't accumulate ids forever. Frigate event ids
                // are monotonically timestamp-prefixed so it's safe to drop stale ones.
                if (lastPerEventMs.size > EVENT_CACHE_MAX) {
                    val cutoff = now - EVENT_DEDUPE_WINDOW_MS
                    lastPerEventMs.entries.removeAll { it.value < cutoff }
                }
                persistEventMap()
            }
        }
    }

    private fun loadEventMap(): MutableMap<String, Long> {
        val ctx = context ?: return mutableMapOf()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val raw = prefs.getString(PREF_EVENT_DEDUPE, null).orEmpty()
        val now = clock()
        val cutoff = now - EVENT_DEDUPE_WINDOW_MS
        val out = mutableMapOf<String, Long>()
        if (raw.isEmpty()) return out
        // Format: `id:ts,id:ts,...`. Plain-text instead of JSON to avoid pulling a parser
        // into the hot path; event ids have no commas or colons.
        for (entry in raw.split(',')) {
            val parts = entry.split(':')
            if (parts.size != 2) continue
            val ts = parts[1].toLongOrNull() ?: continue
            if (ts < cutoff) continue
            out[parts[0]] = ts
        }
        return out
    }

    private fun persistEventMap() {
        val ctx = context ?: return
        val serialized = lastPerEventMs.entries.joinToString(",") { "${it.key}:${it.value}" }
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .edit()
            .putString(PREF_EVENT_DEDUPE, serialized)
            .apply()
    }

    private companion object {
        // Dedupe window: keep event IDs for 48h so repeated Frigate `update` frames for
        // the same long-lived tracked object (e.g. a parked car) don't generate repeat
        // notifications across service restarts.
        const val EVENT_DEDUPE_WINDOW_MS = 48L * 60 * 60 * 1000L
        const val EVENT_CACHE_MAX = 512
        const val PREF_EVENT_DEDUPE = "notify_event_dedupe_v1"
    }
}
