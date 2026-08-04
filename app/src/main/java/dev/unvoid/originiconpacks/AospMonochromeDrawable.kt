package com.autonavi.minimap

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import java.nio.ByteBuffer
import kotlin.math.roundToInt

class AospMonochromeDrawable(
    icon: AdaptiveIconDrawable,
    iconBitmapSize: Int
) : Drawable() {
    private val bitmapSize: Int
    private val edgePixelLength: Int
    private val alphaBitmap: Bitmap
    private val pixels: ByteArray
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
    private val source = Rect()

    init {
        val extraFactor = AdaptiveIconDrawable.getExtraInsetFraction()
        val viewportScale = 1f / (1f + 2f * extraFactor)
        bitmapSize = (iconBitmapSize * 2f * viewportScale).roundToInt().coerceAtLeast(1)
        pixels = ByteArray(bitmapSize * bitmapSize)
        edgePixelLength = bitmapSize * (bitmapSize - iconBitmapSize) / 2
        val flatBitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
        val flatCanvas = Canvas(flatBitmap)
        flatCanvas.drawColor(Color.BLACK)
        icon.background?.run {
            setBounds(0, 0, bitmapSize, bitmapSize)
            draw(flatCanvas)
        }
        icon.foreground?.run {
            setBounds(0, 0, bitmapSize, bitmapSize)
            draw(flatCanvas)
        }
        alphaBitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ALPHA_8)
        val copyPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            blendMode = BlendMode.SRC
            val matrix = ColorMatrix().apply { setSaturation(0f) }
            val values = matrix.array
            values[15] = 0.3333f
            values[16] = 0.3333f
            values[17] = 0.3333f
            values[18] = 0f
            values[19] = 0f
            colorFilter = ColorMatrixColorFilter(values)
        }
        Canvas(alphaBitmap).drawBitmap(flatBitmap, 0f, 0f, copyPaint)
        generateMono()
        source.set(0, 0, bitmapSize, bitmapSize)
    }

    private fun generateMono() {
        val buffer = ByteBuffer.wrap(pixels)
        alphaBitmap.copyPixelsToBuffer(buffer)
        var minimum = 255
        var maximum = 0
        pixels.forEach {
            val value = it.toInt() and 255
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
        }
        if (minimum >= maximum) return
        val range = maximum - minimum
        var sum = 0
        val count = edgePixelLength.coerceIn(0, pixels.size / 2)
        for (index in 0 until count) {
            sum += pixels[index].toInt() and 255
            sum += pixels[pixels.lastIndex - index].toInt() and 255
        }
        val edgeAverage = if (count == 0) minimum.toFloat() else sum / (count * 2f)
        val flip = (edgeAverage - minimum) / range > 0.5f
        for (index in pixels.indices) {
            val value = pixels[index].toInt() and 255
            val mapped = ((value - minimum) * 255f / range).roundToInt()
            pixels[index] = if (flip) (255 - mapped).toByte() else mapped.toByte()
        }
        buffer.rewind()
        alphaBitmap.copyPixelsFromBuffer(buffer)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(alphaBitmap, source, bounds, drawPaint)
    }

    override fun setAlpha(alpha: Int) {
        drawPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        drawPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
