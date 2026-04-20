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
    private val lock = Any()

    /** True if the message for [camera] should be suppressed right now. */
    fun shouldSkip(camera: String, globalSec: Int, perCameraSec: Int): Boolean {
        if (globalSec <= 0 && perCameraSec <= 0) return false
        val now = clock()
        synchronized(lock) {
            if (globalSec > 0 && now - lastGlobalMs < globalSec * 1000L) return true
            if (perCameraSec > 0) {
                val last = lastPerCameraMs[camera] ?: 0L
                if (now - last < perCameraSec * 1000L) return true
            }
        }
        return false
    }

    /** Record that a notification was delivered for [camera] just now. */
    fun recordNotified(camera: String) {
        val now = clock()
        synchronized(lock) {
            lastGlobalMs = now
            lastPerCameraMs[camera] = now
        }
    }
}
