package com.asksakis.freegate.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan

/**
 * Inline pill-shaped badge: solid-colour rounded rectangle with text on top.
 *
 * Used as an inline marker in list-entry labels (e.g. "MyHome  · not nearby")
 * so the user can see at a glance which saved Wi-Fi networks the radio
 * currently sees vs. ones that were saved earlier but aren't in range.
 *
 * Drawn via [ReplacementSpan] so the whole pill — background, padding,
 * text — measures as a single glyph and lays out correctly inside a CheckBox
 * or TextView without the parent needing to know it's there.
 *
 * @param backgroundColor solid fill behind the pill text (ARGB int).
 * @param textColor pill text colour.
 * @param textSizePx pill text size in pixels.
 * @param paddingHorizontalPx left/right inset between pill text and the rounded edge.
 * @param paddingVerticalPx top/bottom inset between pill text and the rounded edge.
 * @param cornerRadiusPx pill corner radius. Pass `Float.MAX_VALUE` for a fully
 *   pill-shaped capsule (the draw call clamps the radius to half the height).
 */
class BadgeSpan(
    private val backgroundColor: Int,
    private val textColor: Int,
    private val textSizePx: Float,
    private val paddingHorizontalPx: Float,
    private val paddingVerticalPx: Float,
    private val cornerRadiusPx: Float,
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        val pillPaint = pillPaint(paint)
        val width = pillPaint.measureText(text, start, end) + 2f * paddingHorizontalPx
        if (fm != null) {
            // Preserve the host paint's line metrics so the badge sits on the
            // text baseline instead of pushing the row taller. The badge's
            // background height is taken from the host font, not the badge font.
            val hostFm = paint.fontMetricsInt
            fm.ascent = hostFm.ascent
            fm.top = hostFm.top
            fm.descent = hostFm.descent
            fm.bottom = hostFm.bottom
        }
        return width.toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val pillPaint = pillPaint(paint)
        val textWidth = pillPaint.measureText(text, start, end)

        // Centre the pill vertically inside the surrounding line's metrics.
        val hostFm = paint.fontMetricsInt
        val lineTop = (y + hostFm.ascent).toFloat()
        val lineBottom = (y + hostFm.descent).toFloat()
        val lineHeight = lineBottom - lineTop
        val pillFm = pillPaint.fontMetrics
        val pillTextHeight = pillFm.descent - pillFm.ascent
        val pillHeight = pillTextHeight + 2f * paddingVerticalPx
        // Make sure the pill never pokes outside the line — useful when the
        // badge font happens to be taller than the row's stock font.
        val pillTop = lineTop + (lineHeight - pillHeight) / 2f
        val pillBottom = pillTop + pillHeight

        val rect = RectF(x, pillTop, x + textWidth + 2f * paddingHorizontalPx, pillBottom)
        val effectiveRadius = minOf(cornerRadiusPx, rect.height() / 2f)

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = backgroundColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, effectiveRadius, effectiveRadius, backgroundPaint)

        val textBaseline = pillTop + paddingVerticalPx - pillFm.ascent
        canvas.drawText(text!!, start, end, x + paddingHorizontalPx, textBaseline, pillPaint)
    }

    private fun pillPaint(host: Paint): Paint = Paint(host).apply {
        color = textColor
        textSize = textSizePx
        isAntiAlias = true
        isFakeBoldText = true
    }
}
