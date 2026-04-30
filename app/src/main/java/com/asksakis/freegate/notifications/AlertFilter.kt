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
    /**
     * Allowed zones stored as `"camera:zone"` pairs. If the allowlist has **no** entry
     * for a given camera, that camera is pass-through (zones aren't filtered). If it
     * has at least one entry, the event's zones must include at least one match.
     */
    private val zoneAllowlist: Set<String> = emptySet(),
    /**
     * Unix epoch seconds when the user last changed enable / cameras / zones / severities.
     * Drop events whose `start_time` predates this — they belong to trackers that were
     * already running before the user opted into the current configuration. 0 disables
     * the gate.
     */
    private val listeningSinceSec: Double = 0.0,
) {

    /**
     * Returns an [Alert] if the message is a new review/event the user wants to see,
     * or null if it should be dropped. Guards are split into helpers so the main
     * function stays readable and detekt's return-count rule is satisfied.
     */
    fun evaluate(topic: String, envelope: JSONObject): Alert? {
        val after = passesGate(topic, envelope) ?: return null
        val camera = after.optString("camera", "")
        val severity = severityFor(topic, after)
        val isAlert = severity == "alert"
        val id = after.optString("id", "")
        val zones = readStringArray(after.optJSONArray("entered_zones"))
            .ifEmpty { readStringArray(after.optJSONArray("current_zones")) }

        return Alert(
            id = id,
            camera = camera,
            severity = if (isAlert) Severity.ALERT else Severity.DETECTION,
            labels = extractLabels(after),
            subLabel = after.optString("sub_label").takeIf { it.isNotEmpty() && it != "null" },
            plate = after.optString("recognized_license_plate")
                .takeIf { it.isNotEmpty() && it != "null" },
            zones = zones,
            attributes = readAttributes(after),
            startTimeSec = after.optDouble("start_time", 0.0).takeIf { it > 0.0 },
            // Frigate sets this to 0 unless the camera has `frigate.calibration` set up;
            // treat anything below 1 as "not meaningful" and skip in the notification.
            estimatedSpeedKph = after.optDouble("current_estimated_speed", 0.0)
                .takeIf { it >= MIN_SIGNIFICANT_SPEED_KPH },
            thumbnailPath = resolveThumbnailPath(topic, id, after.optJSONObject("data")),
        )
    }

    private companion object {
        const val MIN_SIGNIFICANT_SPEED_KPH = 1.0
    }

    /**
     * Runs every reject-rule that would make us drop the message. Returns the `after`
     * payload if the alert should be delivered, or null on any miss. The many early
     * returns are intentional guard clauses; joining them would make the filter
     * harder to read, not easier.
     */
    @Suppress("ReturnCount")
    private fun passesGate(topic: String, envelope: JSONObject): JSONObject? {
        val payload = parsePayload(envelope) ?: run { reject("no-payload", topic); return null }
        val type = payload.optString("type", "")
        // Frigate's tracking lifecycle is `new` → repeated `update`s → `end`. `new` fires
        // before zones are populated, so restricting to `new` would always drop when the
        // user has a zone filter on. Accept the whole lifecycle; the per-id cooldown
        // guarantees exactly one notification per tracked object / review segment.
        val accept = when (topic) {
            "events" -> type == "new" || type == "update"
            "reviews", "review" -> type == "new" || type == "update" || type == "end"
            else -> false
        }
        if (!accept) { reject("type=$type", topic); return null }

        // `end` events sometimes carry only `before` (the post-close snapshot). Fall back
        // to `before` so we can still read zones and deliver a notification.
        val after = payload.optJSONObject("after")
            ?: payload.optJSONObject("before")
            ?: run { reject("no-after-or-before", topic); return null }
        val camera = after.optString("camera", "")
        if (camera.isEmpty()) { reject("no-camera", topic); return null }
        if (cameraAllowlist.isNotEmpty() && camera !in cameraAllowlist) {
            reject("camera-not-allowlisted ($camera)", topic); return null
        }
        if (after.optBoolean("false_positive", false)) { reject("false-positive", topic); return null }

        val severity = severityFor(topic, after) ?: run { reject("unknown-topic-severity", topic); return null }
        val isAlert = severity == "alert"
        if (isAlert && !allowAlerts) { reject("alerts-disabled", topic); return null }
        if (!isAlert && !allowDetections) { reject("detections-disabled", topic); return null }

        if (after.optString("id", "").isEmpty()) { reject("no-id", topic); return null }

        // Frigate keeps tracking long-stationary objects (a parked car, a person sitting
        // in view) for hours. When the user enables a new zone / camera filter, those
        // long-running tracker updates would otherwise notify retroactively — drop any
        // event that started before the user's current config went live.
        val startTime = after.optDouble("start_time", 0.0)
        if (listeningSinceSec > 0.0 && startTime > 0.0 && startTime < listeningSinceSec) {
            reject("predates-config start=$startTime since=$listeningSinceSec", topic)
            return null
        }

        val zones = readStringArray(after.optJSONArray("entered_zones"))
            .ifEmpty { readStringArray(after.optJSONArray("current_zones")) }
        if (!zonesMatchAllowlist(camera, zones)) {
            reject("zones-miss camera=$camera zones=$zones", topic); return null
        }

        return after
    }

    private fun reject(reason: String, topic: String) {
        android.util.Log.d("AlertFilter", "drop topic=$topic reason=$reason")
    }

    private fun severityFor(topic: String, after: JSONObject): String? = when (topic) {
        "reviews", "review" -> after.optString("severity", "detection").lowercase()
        "events" -> "detection"
        else -> null
    }

    private fun extractLabels(after: JSONObject): List<String> {
        val arr = after.optJSONObject("data")?.optJSONArray("objects")
            ?: after.optJSONArray("data")
        if (arr != null) {
            return List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }
        }
        return after.optString("label").takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty()
    }

    /**
     * Frigate exposes thumbnails per-event only. For a review, pick the first associated
     * detection event id so we have something to fetch a snapshot for.
     */
    private fun resolveThumbnailPath(topic: String, id: String, data: JSONObject?): String? {
        val eventId = if (topic == "events") id
        else data?.optJSONArray("detections")?.let {
            if (it.length() > 0) it.optString(0).takeIf { s -> s.isNotEmpty() } else null
        }
        return eventId?.let { "/api/events/$it/thumbnail.jpg" }
    }

    private fun zonesMatchAllowlist(camera: String, zones: List<String>): Boolean {
        if (zoneAllowlist.isEmpty()) return true
        val prefix = "$camera:"
        val cameraZones = zoneAllowlist.filter { it.startsWith(prefix) }
        if (cameraZones.isEmpty()) return true // no zone filter configured for this camera
        return zones.any { z -> "$camera:$z" in cameraZones }
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
        /** Frigate's path-based speed estimate in km/h. Null when camera isn't calibrated. */
        val estimatedSpeedKph: Double? = null,
        /** Path component (e.g. `/api/events/<id>/thumbnail.jpg`); joined with base URL at send time. */
        val thumbnailPath: String?,
    )
}
