package com.asksakis.freegate.notifications

/**
 * Per-service cooldown gate that mirrors Frigate's `_within_cooldown` check
 * (frigate/comms/webpush.py:335). Two knobs, both in seconds:
 *   - global: minimum gap between *any* two notifications
 *   - perCamera: minimum gap between notifications from the same camera
 * A value of 0 disables that gate (matches Frigate's default).
 */
class CooldownTracker(private val clock: () -> Long = System::currentTimeMillis) {

    @Volatile private var lastGlobalMs = 0L
    private val lastPerCameraMs = mutableMapOf<String, Long>()
    // event id → last-notified timestamp. Frigate sends `update` frames every few seconds
    // while tracking; we want exactly one notification per tracked object regardless of
    // how many updates arrive.
    private val lastPerEventMs = mutableMapOf<String, Long>()
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
            }
        }
    }

    private companion object {
        // One notification per event for this long regardless of `update` frame churn.
        const val EVENT_DEDUPE_WINDOW_MS = 15 * 60 * 1000L
        const val EVENT_CACHE_MAX = 512
    }
}
