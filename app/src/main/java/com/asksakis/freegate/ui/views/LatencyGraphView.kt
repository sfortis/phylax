package com.asksakis.freegate.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.asksakis.freegate.R

/**
 * Lightweight polyline plot of recent HEAD-probe latencies. Reads samples in ms; any
 * zero is treated as a failure and rendered as a gap/error tick. Mirrors the Compose
 * ConnectionLatencyGraph in our other app — kept deliberately minimal, no axes,
 * gridlines or labels, just the trend.
 */
class LatencyGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var samples: List<Long> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.accent_orange)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent_orange)
        alpha = 40 // ~16% opacity
    }
    private val errorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.cert_missing)
        alpha = 80
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
    }

    fun setSamples(values: List<Long>) {
        samples = values.takeLast(24)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = samples.size
        if (count < 2) {
            canvas.drawText(
                if (count == 0) "No RTT data yet" else "Collecting samples…",
                width / 2f,
                height / 2f,
                emptyPaint,
            )
            return
        }

        val padV = dp(6f)
        val graphHeight = (height - 2 * padV).coerceAtLeast(1f)
        val maxSample = (samples.maxOrNull() ?: 1L).toFloat().coerceAtLeast(1f)
        val stepX = width.toFloat() / (count - 1).coerceAtLeast(1)

        // Draw error ticks behind the line for failures (sample == 0).
        samples.forEachIndexed { i, s ->
            if (s <= 0L) {
                val x = stepX * i
                canvas.drawRect(
                    x - dp(1.5f), padV,
                    x + dp(1.5f), height - padV,
                    errorPaint,
                )
            }
        }

        // Smooth with a 3-tap weighted window so the line isn't jittery.
        val smoothed = samples.mapIndexed { i, s ->
            if (s <= 0L) 0f
            else {
                val prev = samples.getOrNull(i - 1)?.takeIf { it > 0L }?.toFloat() ?: s.toFloat()
                val next = samples.getOrNull(i + 1)?.takeIf { it > 0L }?.toFloat() ?: s.toFloat()
                prev * 0.2f + s.toFloat() * 0.6f + next * 0.2f
            }
        }
        val points = smoothed.mapIndexed { i, s ->
            val x = stepX * i
            val normalized = if (s <= 0f) 0f else (s / maxSample).coerceIn(0f, 1f)
            val y = height - padV - normalized * graphHeight
            x to y
        }

        // Build a quadratic-bezier smoothed path through the points.
        val line = Path().apply {
            moveTo(points.first().first, points.first().second)
            for (i in 1 until points.size) {
                val (px, py) = points[i - 1]
                val (cx, cy) = points[i]
                val midX = (px + cx) / 2f
                val midY = (py + cy) / 2f
                quadTo(px, py, midX, midY)
                if (i == points.size - 1) lineTo(cx, cy)
            }
        }
        // Fill under the line.
        val fill = Path(line).apply {
            lineTo(points.last().first, height - padV)
            lineTo(points.first().first, height - padV)
            close()
        }
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, linePaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
