package dev.unvoid.originceiler

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * SF Symbols–style glyphs of the iOS lockscreen quick actions: `flashlight.off.fill` and
 * `camera.fill`, white on the glass plate. The drawable is the size of the 50 dp plate so a lit
 * flashlight can fill the whole plate white with a dark glyph, as iOS does.
 */
class IosGlyphDrawable(private val density: Float, val flashlight: Boolean, private val context: android.content.Context? = null, val plateDp: Float = 50f) : Drawable() {
    var torchOn = false
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { style = Paint.Style.FILL }
    private val glyph = Path()
    private val dst = RectF()

    companion object {
        private val cache = HashMap<Boolean, android.graphics.Bitmap?>()

        fun themeGlyph(context: android.content.Context, flashlight: Boolean): android.graphics.Bitmap? = synchronized(cache) {
            if (cache.containsKey(flashlight)) return cache[flashlight]
            val name = if (flashlight) "ios_lockscreen/flashlight.png" else "ios_lockscreen/camera.png"
            val bmp = runCatching { LockscreenClockRuntime.openAsset(context, name).use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
            cache[flashlight] = bmp
            bmp
        }
    }
    private var builtFor = -1f

    override fun getIntrinsicWidth() = (plateDp * density).toInt()
    override fun getIntrinsicHeight() = (plateDp * density).toInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        if (flashlight && torchOn) {
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, minOf(b.width(), b.height()) / 2f, paint)
        }
        val glyphColor = if (flashlight && torchOn) Color.rgb(28, 28, 30) else Color.WHITE
        // The iOS 26 theme's own glyphs (150 px = the 50 dp plate), tinted; drawn paths as fallback.
        val bitmap = context?.let { themeGlyph(it, flashlight) }
        if (bitmap != null) {
            paint.colorFilter = android.graphics.PorterDuffColorFilter(glyphColor, android.graphics.PorterDuff.Mode.SRC_IN)
            // The theme glyph is drawn for the plate size; it grows with the plate.
            val size = minOf(b.width(), b.height()).toFloat()
            dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
            canvas.drawBitmap(bitmap, null, dst, paint)
            paint.colorFilter = null
            return
        }
        if (builtFor != density) build()
        paint.color = glyphColor
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawPath(glyph, paint)
        canvas.restore()
    }

    /** The glyph centred on (0, 0), in px. */
    private fun build() {
        builtFor = density
        glyph.reset()
        if (flashlight) {
            // 10 × 22 units → 20 dp tall.
            val u = 20f * density / 22f
            val w = 10f * u
            val h = 22f * u
            val ox = -w / 2f
            val oy = -h / 2f
            val head = Path().apply { addRoundRect(RectF(ox, oy, ox + w, oy + 3.2f * u), 1.1f * u, 1.1f * u, Path.Direction.CW) }
            val neck = Path().apply {
                moveTo(ox, oy + 3.9f * u)
                lineTo(ox + w, oy + 3.9f * u)
                lineTo(ox + w, oy + 4.9f * u)
                lineTo(ox + 8.2f * u, oy + 8.6f * u)
                lineTo(ox + 1.8f * u, oy + 8.6f * u)
                lineTo(ox, oy + 4.9f * u)
                close()
            }
            val handle = Path().apply { addRoundRect(RectF(ox + 1.8f * u, oy + 8.2f * u, ox + 8.2f * u, oy + h), 1.5f * u, 1.5f * u, Path.Direction.CW) }
            val dot = Path().apply { addCircle(0f, oy + 12.6f * u, 1.3f * u, Path.Direction.CW) }
            glyph.op(head, Path.Op.UNION)
            glyph.op(neck, Path.Op.UNION)
            glyph.op(handle, Path.Op.UNION)
            glyph.op(dot, Path.Op.DIFFERENCE)
        } else {
            // 26 × 20 units → 24 dp wide.
            val u = 24f * density / 26f
            val w = 26f * u
            val h = 20f * u
            val ox = -w / 2f
            val oy = -h / 2f
            val body = Path().apply { addRoundRect(RectF(ox, oy + 3.4f * u, ox + w, oy + h), 3.6f * u, 3.6f * u, Path.Direction.CW) }
            val hump = Path().apply { addRoundRect(RectF(ox + 8.2f * u, oy, ox + 17.8f * u, oy + 5.4f * u), 1.8f * u, 1.8f * u, Path.Direction.CW) }
            val lensCy = oy + 11.9f * u
            val ring = Path().apply { addCircle(0f, lensCy, 5.3f * u, Path.Direction.CW) }
            val lens = Path().apply { addCircle(0f, lensCy, 3.7f * u, Path.Direction.CW) }
            glyph.op(body, Path.Op.UNION)
            glyph.op(hump, Path.Op.UNION)
            glyph.op(ring, Path.Op.DIFFERENCE)
            glyph.op(lens, Path.Op.UNION)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
