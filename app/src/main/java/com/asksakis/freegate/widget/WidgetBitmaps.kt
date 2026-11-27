package com.asksakis.freegate.widget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/** Bitmap shaping for widgets, where RemoteViews cannot clip a picture themselves. */
object WidgetBitmaps {

    /**
     * Center-crop [image] to the width-to-height [ratio] and round its corners. The radius
     * is [radiusPerWidth] times the cropped width, so it matches a radius in dp when the
     * caller divides that radius by the view width in dp.
     */
    fun cropRounded(image: Bitmap, ratio: Float, radiusPerWidth: Float): Bitmap {
        val (cropWidth, cropHeight) = cropSize(image.width, image.height, ratio)
        val shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(
                Matrix().apply {
                    setTranslate(-(image.width - cropWidth) / 2f, -(image.height - cropHeight) / 2f)
                },
            )
        }
        val radius = radiusPerWidth * cropWidth
        val out = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRoundRect(
            RectF(0f, 0f, cropWidth.toFloat(), cropHeight.toFloat()),
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader },
        )
        return out
    }

    /** The largest part of a [width] by [height] image with the given [ratio]. */
    internal fun cropSize(width: Int, height: Int, ratio: Float): Pair<Int, Int> =
        if (width.toFloat() / height > ratio) {
            (height * ratio).toInt().coerceAtLeast(1) to height
        } else {
            width to (width / ratio).toInt().coerceAtLeast(1)
        }
}
