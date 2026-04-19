package com.asksakis.freegate.notifications

import org.json.JSONObject

/**
 * Decides whether an incoming Frigate WebSocket message should surface as a
 * user-visible notification. Pure data logic so it can be unit-tested without a device.
 */
class AlertFilter(
    private val allowAlerts: Boolean,
    private val allowDetections: Boolean,
    private val cameraAllowlist: Set<String>, // empty = allow all
) {

    /**
     * Returns an [Alert] if the message is a new review/event the user wants to see,
     * or null if it should be dropped.
     */
    fun evaluate(topic: String, envelope: JSONObject): Alert? {
        val payload = parsePayload(envelope) ?: return null
        val type = payload.optString("type", "")
        if (type != "new") return null

        val after = payload.optJSONObject("after") ?: return null
        val camera = after.optString("camera", "").ifEmpty { return null }
        if (cameraAllowlist.isNotEmpty() && camera !in cameraAllowlist) return null

        // Drop known false positives — Frigate has already decided these aren't real.
        if (after.optBoolean("false_positive", false)) return null

        val severity = when (topic) {
            "reviews", "review" -> after.optString("severity", "detection").lowercase()
            "events" -> "detection"
            else -> return null
        }
        val isAlert = severity == "alert"
        if (isAlert && !allowAlerts) return null
        if (!isAlert && !allowDetections) return null

        val id = after.optString("id", "").ifEmpty { return null }
        val data = after.optJSONObject("data")
        val labels: List<String> = run {
            val arr = data?.optJSONArray("objects") ?: after.optJSONArray("data")
            if (arr != null) {
                List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }
            } else {
                after.optString("label").takeIf { it.isNotEmpty() }?.let(::listOf) ?: emptyList()
            }
        }

        // Frigate exposes thumbnails per-event only. For a review, pick the first
        // associated detection event id so we have something to fetch a snapshot for.
        val thumbnailEventId: String? = when {
            topic == "events" -> id
            else -> data?.optJSONArray("detections")?.let {
                if (it.length() > 0) it.optString(0).takeIf { s -> s.isNotEmpty() } else null
            }
        }
        val thumbnailPath = thumbnailEventId?.let { "/api/events/$it/thumbnail.jpg" }

        val subLabel = after.optString("sub_label").takeIf { it.isNotEmpty() && it != "null" }
        val plate = after.optString("recognized_license_plate").takeIf { it.isNotEmpty() && it != "null" }

        val zones = readStringArray(after.optJSONArray("entered_zones"))
            .ifEmpty { readStringArray(after.optJSONArray("current_zones")) }

        val attributes = readAttributes(after)

        return Alert(
            id = id,
            camera = camera,
            severity = if (isAlert) Severity.ALERT else Severity.DETECTION,
            labels = labels,
            subLabel = subLabel,
            plate = plate,
            zones = zones,
            attributes = attributes,
            startTimeSec = after.optDouble("start_time", 0.0).takeIf { it > 0.0 },
            thumbnailPath = thumbnailPath,
        )
    }

    private fun readStringArray(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }
    }

    /**
     * Frigate's attributes field can be either a JSON object (label → confidence) or an
     * array of strings. Return the attribute *names* in either case.
     */
    private fun readAttributes(after: JSONObject): List<String> {
        after.optJSONArray("attributes")?.let { return readStringArray(it) }
        val obj = after.optJSONObject("attributes") ?: return emptyList()
        return obj.keys().asSequence().toList()
    }

    private fun parsePayload(envelope: JSONObject): JSONObject? {
        envelope.optJSONObject("payload")?.let { return it }
        val raw = envelope.optString("payload", "").takeIf { it.isNotEmpty() } ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    enum class Severity { ALERT, DETECTION }

    data class Alert(
        val id: String,
        val camera: String,
        val severity: Severity,
        val labels: List<String>,
        /** Face-recognition name, "delivery" badge, etc. — shown in place of the raw label. */
        val subLabel: String? = null,
        /** Recognised licence plate text, if any. */
        val plate: String? = null,
        /** Zone names the object entered or is currently in. */
        val zones: List<String> = emptyList(),
        /** Visual attributes Frigate tagged on the object (e.g. "package"). */
        val attributes: List<String> = emptyList(),
        val startTimeSec: Double?,
        /** Path component (e.g. `/api/events/<id>/thumbnail.jpg`); joined with base URL at send time. */
        val thumbnailPath: String?,
    )
}
