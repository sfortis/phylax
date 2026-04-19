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
    @Volatile
    var pendingReviewId: String? = null

    /** Return and clear the pending review id atomically. */
    @Synchronized
    fun consumePendingReviewId(): String? {
        val id = pendingReviewId
        pendingReviewId = null
        return id
    }
}
