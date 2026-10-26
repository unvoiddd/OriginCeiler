package dev.unvoid.originceiler

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
import kotlin.math.sqrt

/**
 * Monochrome layer for icons that ship none. The flattened icon is split into:
 *  - a logo on a transparent background: the logo's silhouette is the glyph;
 *  - a logo on a plate (solid, gradient, two-tone, vivo's pre-styled bitmaps): the colors found
 *    along the plate's edge are background, everything else is the glyph;
 *  - nothing separable (photos, pixel art): a luminance mask, so texture stays instead of a slab.
 */
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
        icon.background?.run {
            setBounds(0, 0, bitmapSize, bitmapSize)
            draw(flatCanvas)
        }
        icon.foreground?.run {
            setBounds(0, 0, bitmapSize, bitmapSize)
            draw(flatCanvas)
        }
        alphaBitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ALPHA_8)
        val argb = IntArray(bitmapSize * bitmapSize)
        flatBitmap.getPixels(argb, 0, bitmapSize, 0, 0, bitmapSize, bitmapSize)
        val mask = glyphMask(argb)
        if (mask != null) {
            System.arraycopy(mask, 0, pixels, 0, pixels.size)
            alphaBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
        } else {
            luminanceMask(flatBitmap)
        }
        source.set(0, 0, bitmapSize, bitmapSize)
    }

    private fun glyphMask(argb: IntArray): ByteArray? {
        val size = bitmapSize
        // Only what the adaptive mask shows counts: layers often bleed far past it (a white field
        // around a dark plate), which would otherwise be read as the backdrop.
        val visible = visibleArea()
        val shape = BooleanArray(argb.size) { visible[it] && Color.alpha(argb[it]) > 128 }
        var left = size
        var top = size
        var right = -1
        var bottom = -1
        var shapeCount = 0
        shape.forEachIndexed { index, inside ->
            if (!inside) return@forEachIndexed
            shapeCount++
            val x = index % size
            val y = index / size
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
        if (shapeCount < argb.size * 0.01f) return null
        val boxWidth = right - left + 1
        val boxHeight = bottom - top + 1
        val isPlate = shapeCount >= boxWidth * boxHeight * 0.78f && minOf(boxWidth, boxHeight) >= size * 0.4f
        if (!isPlate) {
            // Already a bare logo: its own alpha is the glyph.
            return ByteArray(argb.size) { Color.alpha(argb[it]).toByte() }
        }
        return plateGlyph(argb, shape, shapeCount)
    }

    /** Rounded square of the 72dp viewport inside the 108dp layers, slightly inside any mask. */
    private fun visibleArea(): BooleanArray {
        val size = bitmapSize
        val inset = size / 6f + size * 0.02f
        val half = size / 2f - inset
        val radius = half * 0.45f
        val center = size / 2f
        return BooleanArray(size * size) { index ->
            val dx = (kotlin.math.abs(index % size + 0.5f - center) - (half - radius)).coerceAtLeast(0f)
            val dy = (kotlin.math.abs(index / size + 0.5f - center) - (half - radius)).coerceAtLeast(0f)
            dx <= half && dy <= half && dx * dx + dy * dy <= radius * radius
        }
    }

    private fun plateGlyph(argb: IntArray, shape: BooleanArray, shapeCount: Int): ByteArray? {
        val size = bitmapSize
        val band = edgeBand(shape, (size / 32).coerceAtLeast(2))
        val centers = clusterColors(argb, shape) ?: return null
        val bandHits = IntArray(centers.size)
        var bandCount = 0
        band.forEachIndexed { index, inBand ->
            if (!inBand) return@forEachIndexed
            bandHits[nearest(argb[index], centers)]++
            bandCount++
        }
        if (bandCount == 0) return null
        val plate = BooleanArray(centers.size) { bandHits[it] >= bandCount * PLATE_SHARE }
        if (plate.all { it } || plate.none { it }) return null

        val mask = ByteArray(argb.size)
        var solid = 0
        argb.forEachIndexed { index, pixel ->
            if (!shape[index]) return@forEachIndexed
            var plateDistance = Float.MAX_VALUE
            var glyphDistance = Float.MAX_VALUE
            centers.forEachIndexed { cluster, center ->
                val distance = distance(pixel, center)
                if (plate[cluster]) plateDistance = minOf(plateDistance, distance)
                else glyphDistance = minOf(glyphDistance, distance)
            }
            val ratio = plateDistance / (plateDistance + glyphDistance + 0.001f)
            val alpha = ((ratio - 0.38f) / 0.24f).coerceIn(0f, 1f)
            if (alpha > 0.5f) solid++
            mask[index] = (alpha * 255f).roundToInt().toByte()
        }
        val coverage = solid.toFloat() / shapeCount
        return if (coverage in 0.05f..0.62f) mask else null
    }

    /** Shape pixels within [radius] of the shape's outline or the bitmap edge. */
    private fun edgeBand(shape: BooleanArray, radius: Int): BooleanArray {
        val size = bitmapSize
        // Horizontal then vertical erosion; whatever the erosion removes is the band.
        val horizontal = BooleanArray(shape.size)
        for (y in 0 until size) {
            var run = 0
            for (x in 0 until size) {
                run = if (shape[y * size + x]) run + 1 else 0
                horizontal[y * size + x] = run > radius
            }
            run = 0
            for (x in size - 1 downTo 0) {
                run = if (shape[y * size + x]) run + 1 else 0
                if (run <= radius) horizontal[y * size + x] = false
            }
        }
        val eroded = BooleanArray(shape.size)
        for (x in 0 until size) {
            var run = 0
            for (y in 0 until size) {
                run = if (horizontal[y * size + x]) run + 1 else 0
                eroded[y * size + x] = run > radius
            }
            run = 0
            for (y in size - 1 downTo 0) {
                run = if (horizontal[y * size + x]) run + 1 else 0
                if (run <= radius) eroded[y * size + x] = false
            }
        }
        return BooleanArray(shape.size) { shape[it] && !eroded[it] }
    }

    /** k-means over the shape's colors, seeded with mutually distant colors. */
    private fun clusterColors(argb: IntArray, shape: BooleanArray): IntArray? {
        val step = 3
        val samples = ArrayList<Int>()
        var index = 0
        while (index < argb.size) {
            if (shape[index]) samples += argb[index]
            index += step
        }
        if (samples.size < CLUSTERS * 8) return null
        val centers = IntArray(CLUSTERS)
        centers[0] = samples[samples.size / 2]
        for (k in 1 until CLUSTERS) {
            centers[k] = samples.maxByOrNull { sample -> (0 until k).minOf { distance(sample, centers[it]) } }!!
        }
        val sums = Array(CLUSTERS) { LongArray(4) }
        repeat(8) {
            sums.forEach { it.fill(0) }
            samples.forEach { sample ->
                val cluster = nearest(sample, centers)
                sums[cluster][0]++
                sums[cluster][1] += Color.red(sample).toLong()
                sums[cluster][2] += Color.green(sample).toLong()
                sums[cluster][3] += Color.blue(sample).toLong()
            }
            for (k in 0 until CLUSTERS) {
                val count = sums[k][0]
                if (count > 0) {
                    centers[k] = Color.rgb((sums[k][1] / count).toInt(), (sums[k][2] / count).toInt(), (sums[k][3] / count).toInt())
                }
            }
        }
        return centers
    }

    private fun nearest(pixel: Int, centers: IntArray): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        centers.forEachIndexed { index, center ->
            val distance = distance(pixel, center)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    private fun distance(first: Int, second: Int): Float {
        val red = Color.red(first) - Color.red(second)
        val green = Color.green(first) - Color.green(second)
        val blue = Color.blue(first) - Color.blue(second)
        return sqrt((red * red + green * green + blue * blue).toFloat())
    }

    private fun luminanceMask(flatBitmap: Bitmap) {
        val opaque = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(Color.BLACK)
            drawBitmap(flatBitmap, 0f, 0f, null)
        }
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
        Canvas(alphaBitmap).drawBitmap(opaque, 0f, 0f, copyPaint)
        generateMono()
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

    private companion object {
        const val CLUSTERS = 5
        // A color counts as plate when this share of the plate's edge belongs to it.
        const val PLATE_SHARE = 0.10f
    }
}
