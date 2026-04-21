package com.asksakis.freegate.stats

/**
 * Snapshot of a single Frigate `stats` broadcast, normalised across Frigate 0.13 / 0.14 /
 * 0.15 schema differences. Everything except [receivedAtMs] is optional because different
 * deployments expose different subsets (no GPU, CPU-only detectors, etc.).
 */
data class FrigateStats(
    val receivedAtMs: Long,
    val cpuPercent: Int?,
    val gpuPercent: Int?,
    val memoryPercent: Int?,
    val uptimeSeconds: Long?,
    val detectors: List<DetectorStat>,
    val cameras: List<CameraStat>,
) {
    data class DetectorStat(
        val name: String,
        val inferenceMs: Double?,
    )

    data class CameraStat(
        val name: String,
        val cameraFps: Double?,
        val detectionFps: Double?,
        val processFps: Double?,
    )
}
