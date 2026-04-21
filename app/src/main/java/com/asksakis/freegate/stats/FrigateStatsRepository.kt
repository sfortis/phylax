package com.asksakis.freegate.stats

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONObject

/**
 * Singleton sink for Frigate system-stats snapshots, observed by the UI (top-bar chip +
 * expanded stats dialog). Fed by the UI HTTP poller ([UiStatsWatcher]) while the
 * Activity is visible — Frigate's WS `stats` topic only ticks every ~15s, which feels
 * dead for a live metrics chip, so we use the `/api/stats` endpoint instead and throttle
 * on our side.
 */
@Suppress("TooManyFunctions") // small JSON-shape helpers for Frigate 0.13/0.14/0.15 deltas
object FrigateStatsRepository {

    private const val THROTTLE_MS = 1_000L
    // If no stats frame arrives for longer than this, observers should dim the chip.
    const val STALE_AFTER_MS = 10_000L

    private val _stats = MutableLiveData<FrigateStats?>(null)
    val stats: LiveData<FrigateStats?> = _stats

    @Volatile private var lastPublishMs: Long = 0L

    /**
     * Publish a parsed snapshot from a raw `/api/stats` response body. Throttled at
     * [THROTTLE_MS] to smooth out sub-second poll cadences.
     */
    fun ingest(statsBody: JSONObject) {
        val now = System.currentTimeMillis()
        if (now - lastPublishMs < THROTTLE_MS) return
        val parsed = parse(statsBody, now) ?: return
        lastPublishMs = now
        _stats.postValue(parsed)
    }

    fun clear() {
        _stats.postValue(null)
    }

    private fun parse(json: JSONObject, nowMs: Long): FrigateStats? {
        val cpu = extractCpuPercent(json)
        val gpu = extractGpuPercent(json)
        val mem = extractMemoryPercent(json)
        // Frigate 0.14+ nests uptime under `service.uptime`; older builds published it at
        // the top level. Check both so we don't regress either.
        val uptime = json.optJSONObject("service")?.optLongOrNull("uptime")
            ?: json.optLongOrNull("uptime")
        val detectors = parseDetectors(json.optJSONObject("detectors"))
        val cameras = parseCameras(json.optJSONObject("cameras"))

        if (cpu == null && gpu == null && detectors.isEmpty() && cameras.isEmpty()) {
            // Not a recognisable stats payload — probably a different topic or schema.
            return null
        }
        return FrigateStats(
            receivedAtMs = nowMs,
            cpuPercent = cpu,
            gpuPercent = gpu,
            memoryPercent = mem,
            uptimeSeconds = uptime,
            detectors = detectors,
            cameras = cameras,
        )
    }

    /**
     * Frigate 0.14+ puts per-process CPU under `cpu_usages.{pid}.cpu` (string percent).
     * We surface the highest single process — usually the main `frigate.full` process —
     * because summing all PIDs double-counts child workers on modern builds.
     */
    private fun extractCpuPercent(json: JSONObject): Int? =
        maxChildField(json.optJSONObject("cpu_usages"), "cpu")

    /**
     * Frigate 0.15 uses `gpu_usages` (map of device -> {"gpu": "34"}).
     * Frigate 0.13 used a flat `gpus` map with similar shape. Support both.
     */
    private fun extractGpuPercent(json: JSONObject): Int? =
        maxChildField(
            json.optJSONObject("gpu_usages") ?: json.optJSONObject("gpus"),
            "gpu",
        )

    private fun extractMemoryPercent(json: JSONObject): Int? =
        maxChildField(json.optJSONObject("cpu_usages"), "mem")

    /**
     * Scan a Frigate usage map ({child: {field: "<pct>"}}) and return the max percentage,
     * or null if no child exposes a numeric value. Frigate serialises these as strings.
     */
    private fun maxChildField(source: JSONObject?, field: String): Int? {
        if (source == null) return null
        val max = source.childJsonObjects().mapNotNull { it.optStringAsDouble(field) }.maxOrNull()
        return max?.toInt()?.coerceIn(0, 100)
    }

    private fun parseDetectors(root: JSONObject?): List<FrigateStats.DetectorStat> {
        if (root == null) return emptyList()
        return root.childEntries().map { (name, entry) ->
            FrigateStats.DetectorStat(name, entry.optDoubleOrNull("inference_speed"))
        }.toList()
    }

    private fun parseCameras(root: JSONObject?): List<FrigateStats.CameraStat> {
        if (root == null) return emptyList()
        return root.childEntries()
            .map { (name, entry) ->
                FrigateStats.CameraStat(
                    name = name,
                    cameraFps = entry.optDoubleOrNull("camera_fps"),
                    detectionFps = entry.optDoubleOrNull("detection_fps"),
                    processFps = entry.optDoubleOrNull("process_fps"),
                )
            }
            .sortedBy { it.name }
            .toList()
    }

    private fun JSONObject.childEntries(): Sequence<Pair<String, JSONObject>> = sequence {
        val k = keys()
        while (k.hasNext()) {
            val name = k.next()
            val child = optJSONObject(name) ?: continue
            yield(name to child)
        }
    }

    private fun JSONObject.childJsonObjects(): Sequence<JSONObject> =
        childEntries().map { it.second }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

    private fun JSONObject.optStringAsDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val raw = opt(key)) {
            is Number -> raw.toDouble()
            // Frigate is inconsistent: CPU values arrive as bare numeric strings ("40.0"),
            // but Intel VA-API and some AMD GPU backends format as "5.0%" including the
            // percent sign. Strip trailing % / whitespace before parsing.
            is String -> raw.trim().trimEnd('%').trim().toDoubleOrNull()
            else -> null
        }
    }
}
