package com.asksakis.freegate.notifications

/**
 * One-shot bridge between a notification tap and [com.asksakis.freegate.ui.home.HomeFragment].
 *
 * The MainActivity deep-link handler stores a pending review id here; HomeFragment reads
 * it the next time it comes to the foreground and navigates the WebView to the matching
 * `/review/<id>` path. Consumed exactly once so a subsequent app launch doesn't jump back
 * to the old review.
 */
object DeepLinkRouter {

    sealed interface Target {
        /** Frigate review-segment deep link → `/review?id=<id>`. */
        data class Review(val reviewId: String) : Target
        /** Frigate single-event deep link → `/explore?event_id=<id>` on 0.14+ UIs. */
        data class Event(val eventId: String) : Target
    }

    @Volatile
    private var pending: Target? = null

    fun setPending(target: Target) {
        pending = target
    }

    /** Return and clear the pending target atomically. */
    @Synchronized
    fun consumePending(): Target? {
        val t = pending
        pending = null
        return t
    }
}
