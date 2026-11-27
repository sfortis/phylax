package com.asksakis.freegate.widget

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

/**
 * The latest notified events for the recent events widget, newest first, with a small
 * picture each. Kept in the widget preferences file and image folder, which are excluded
 * from backup like the rest of [WidgetStore].
 */
class RecentEventsStore(context: Context) {

    private val images = WidgetStore(context)
    private val prefs = context.applicationContext.getSharedPreferences(WidgetStore.PREFS_FILE, Context.MODE_PRIVATE)

    fun events(): List<WidgetStore.LastEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching { decode(raw) }.getOrDefault(emptyList())
    }

    /**
     * Add [event], or replace the entry of the same review, and keep the [MAX_EVENTS]
     * newest. A replacement without a picture keeps the one already stored.
     */
    fun add(event: WidgetStore.LastEvent, image: Bitmap?) {
        val updated = merge(events(), event)
        prefs.edit().putString(KEY_EVENTS, encode(updated)).apply()
        if (updated.none { it.reviewId == event.reviewId }) return
        val key = images.imageKey(IMAGE_PREFIX, event.reviewId)
        if (image != null && !images.saveImage(key, image, THUMBNAIL_SIDE)) images.deleteImage(key)
        images.pruneImages(IMAGE_PREFIX, updated.map { images.imageKey(IMAGE_PREFIX, it.reviewId) }.toSet())
    }

    fun image(reviewId: String): Bitmap? = images.loadImage(images.imageKey(IMAGE_PREFIX, reviewId))

    /** Delete the list and its pictures, when the last recent events widget is removed. */
    fun clear() {
        prefs.edit().remove(KEY_EVENTS).apply()
        images.pruneImages(IMAGE_PREFIX, emptySet())
    }

    companion object {
        const val MAX_EVENTS = 10
        private const val KEY_EVENTS = "recent_events"
        private const val IMAGE_PREFIX = "recent_"

        /** Rows show a small picture, so a much smaller file than the full widgets use. */
        private const val THUMBNAIL_SIDE = 320

        internal fun merge(current: List<WidgetStore.LastEvent>, event: WidgetStore.LastEvent) =
            (current.filter { it.reviewId != event.reviewId } + event)
                .sortedByDescending { it.timeMs }
                .take(MAX_EVENTS)

        internal fun encode(events: List<WidgetStore.LastEvent>): String = JSONArray().apply {
            events.forEach {
                put(
                    JSONObject()
                        .put("id", it.reviewId)
                        .put("camera", it.camera)
                        .put("title", it.title)
                        .put("subtitle", it.subtitle)
                        .put("alert", it.isAlert)
                        .put("time", it.timeMs),
                )
            }
        }.toString()

        internal fun decode(raw: String): List<WidgetStore.LastEvent> {
            val array = JSONArray(raw)
            return (0 until array.length()).map { i ->
                val j = array.getJSONObject(i)
                WidgetStore.LastEvent(
                    reviewId = j.getString("id"),
                    camera = j.optString("camera"),
                    title = j.optString("title"),
                    subtitle = j.optString("subtitle"),
                    isAlert = j.optBoolean("alert", true),
                    timeMs = j.optLong("time"),
                )
            }
        }
    }
}
