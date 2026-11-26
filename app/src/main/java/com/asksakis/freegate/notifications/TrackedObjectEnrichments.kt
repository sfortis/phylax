package com.asksakis.freegate.notifications

import android.graphics.Bitmap
import org.json.JSONObject

/**
 * Remembers which notification belongs to which tracked object, so that what Frigate learns
 * later can be folded into a notification that is already in the shade.
 *
 * Frigate reports a review as soon as it starts, which is when the notification goes out,
 * and only afterwards does it recognise a face, read a licence plate or generate a
 * description. All three arrive on the `tracked_object_update` topic keyed by the tracked
 * object id, while the notification is keyed by the review id, so the two have to be tied
 * together at the moment we notify. A review payload lists its tracked object ids in
 * `data.detections`, and that list is what [register] stores.
 *
 * Measured on a live instance, a description lands between 7 and 61 seconds after the review
 * starts, and a recognised name took 34 seconds, because both follow the end of the tracked
 * object rather than its start. Entries therefore have to outlive the event by minutes, and
 * [EXPIRY_MS] is set from that rather than from an assumption.
 */
class TrackedObjectEnrichments {

    /**
     * One posted notification, with whatever has been learned about it since.
     *
     * [alert] is replaced rather than mutated as facts arrive, so a recognised name reaches
     * the notification title through the same path that a name already present on the review
     * would have taken.
     */
    class Entry(
        val notificationId: Int,
        @Volatile var alert: AlertFilter.Alert,
        /**
         * Set once the snapshot download finishes, which is after the entry is registered.
         * Registering first and filling this in later is deliberate: an update that arrives
         * while the image is still downloading would otherwise find nothing to attach to.
         */
        @Volatile var snapshot: Bitmap? = null,
        @Volatile var description: String? = null,
        /**
         * The tracked object this notification has been enriched from, once one has spoken.
         * A review covers everything that happened in the same stretch of time, so a single
         * notification can own a person, a motorcycle and a second person at once, and
         * Frigate describes each of them separately. Letting every one of them write into
         * the notification made the text flip between subjects and left whichever arrived
         * last standing next to a picture of a different one. The first object to report
         * something claims the notification; the rest are ignored.
         */
        @Volatile var claimedEventId: String? = null,
        val expiresAt: Long,
    )

    /** The outcome of folding an update in, when it was accepted. */
    data class Applied(
        val entry: Entry,
        /**
         * True the first time an object claims this notification, which is when the picture
         * has to be refetched so that it shows the object the text is about.
         */
        val claimed: Boolean,
    )

    /** One thing Frigate learned about a tracked object. */
    sealed interface Update {
        val eventId: String

        data class Name(override val eventId: String, val name: String) : Update
        data class Plate(override val eventId: String, val plate: String) : Update
        data class Description(override val eventId: String, val text: String) : Update
    }

    private val lock = Any()

    /** Tracked object id to the notification that reported it. */
    private val entries = LinkedHashMap<String, Entry>()

    /**
     * Tie every tracked object in this review to the notification just posted. A review
     * usually carries one id, but a single review can cover several objects, and any of
     * them can be the one Frigate later recognises.
     */
    fun register(
        alert: AlertFilter.Alert,
        notificationId: Int,
        now: Long = System.currentTimeMillis(),
    ): Entry? {
        if (alert.detectionIds.isEmpty()) return null
        val entry = Entry(
            notificationId = notificationId,
            alert = alert,
            expiresAt = now + EXPIRY_MS,
        )
        synchronized(lock) {
            purgeLocked(now)
            alert.detectionIds.forEach { entries[it] = entry }
            // Bitmaps are held for the lifetime of an entry, so the map is capped by count
            // as well as by age. The cap counts notifications rather than object ids,
            // because one review can name several objects that all point at the same
            // notification, and evicting them one id at a time would leave an entry that
            // answers for some of its objects and silently ignores the rest. Oldest first,
            // since a newer alert is the one still on screen and worth updating.
            val distinct = LinkedHashSet(entries.values)
            while (distinct.size > MAX_ENTRIES) {
                val oldest = distinct.first()
                distinct.remove(oldest)
                entries.entries.removeAll { it.value === oldest }
            }
        }
        return entry
    }

    /**
     * Fold [update] into the notification that reported the same tracked object, and return
     * that notification so the caller can repost it. Returns null when nothing was waiting
     * for this object, which is the common case: most updates belong to reviews that were
     * filtered out, muted, or that predate the app being opened.
     */
    fun apply(update: Update, now: Long = System.currentTimeMillis()): Applied? {
        synchronized(lock) {
            purgeLocked(now)
            val entry = entries[update.eventId] ?: return null
            val claimedBy = entry.claimedEventId
            // A second object in the same review has nothing to add to a notification that
            // is already about the first one, and saying otherwise would contradict the
            // picture.
            if (claimedBy != null && claimedBy != update.eventId) return null
            val claimed = claimedBy == null
            if (claimed) entry.claimedEventId = update.eventId
            when (update) {
                is Update.Name -> entry.alert = entry.alert.copy(subLabel = update.name)
                is Update.Plate -> entry.alert = entry.alert.copy(plate = update.plate)
                is Update.Description -> entry.description = update.text
            }
            return Applied(entry, claimed)
        }
    }

    /** Drop everything, for a listener restart where the posted notifications are gone. */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    private fun purgeLocked(now: Long) {
        val expired = entries.entries.filter { it.value.expiresAt <= now }.map { it.key }
        expired.forEach { entries.remove(it) }
    }

    companion object {
        /**
         * How long a notification stays eligible for an update. Generous on purpose: a
         * description follows the end of the tracked object, so a person who lingers at the
         * door for two minutes produces a description well after the notification.
         */
        private const val EXPIRY_MS = 10L * 60 * 1000

        /** Entries hold a snapshot bitmap each, so the map is bounded by count too. */
        private const val MAX_ENTRIES = 16

        /**
         * Read one `tracked_object_update` payload.
         *
         * Face updates are published repeatedly as Frigate works on the object, with a null
         * name and a zero score until it resolves one. Those carry no information, and
         * acting on them would replace a good notification with an empty one, so they are
         * dropped here rather than at the call site.
         */
        fun parseFrame(envelope: JSONObject): Update? {
            // Frigate wraps the MQTT payload as a JSON *string* inside the websocket
            // envelope, so it has to be parsed a second time. Older frames nest it as an
            // object, which the first branch keeps working.
            val payload = envelope.optJSONObject("payload")
                ?: envelope.optString("payload").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return null
            // optString coerces anything non-string, a JSON null included, into its
            // toString, so "null" and a stringified object both have to be rejected here
            // rather than trusted as an id.
            val eventId = payload.optString("id").takeIf { isUsable(it) } ?: return null
            return when (payload.optString("type")) {
                "description" -> payload.optString("description")
                    .takeIf { isUsable(it) }
                    ?.let { Update.Description(eventId, it) }

                "face" -> payload.optString("name")
                    .takeIf { isUsable(it) }
                    ?.takeIf { payload.optDouble("score", 0.0) >= MIN_FACE_SCORE }
                    ?.let { Update.Name(eventId, it) }

                "lpr" -> payload.optString("plate")
                    .takeIf { isUsable(it) }
                    ?.let { Update.Plate(eventId, it) }

                else -> null
            }
        }

        /**
         * True for a value that can be shown to the user. Rejects the empty string, the
         * whitespace-only string, and the coerced forms of a JSON null or a structured
         * value, none of which should ever replace text already in a notification.
         */
        private fun isUsable(value: String): Boolean =
            value.isNotBlank() && value != "null" && !value.startsWith("{") && !value.startsWith("[")

        /**
         * Below this, Frigate has not settled on a name. Its own recognition threshold is
         * configured server side; this is only a floor against the zero-score placeholders.
         */
        private const val MIN_FACE_SCORE = 0.01
    }
}
