package dev.unvoid.originceiler

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

class MaterialStatusBatteryDrawable(
    private val density: Float,
    private val level: Float,
    private val chargeStyle: Int,
    white: Boolean
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (white) Color.WHITE else Color.BLACK
    }
    private var tint = ColorStateList.valueOf(paint.color)
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        val scale = minOf(bounds.width() / 23f, bounds.height() / 12f)
        val left = bounds.left + (bounds.width() - 23f * scale) / 2f
        val top = bounds.top + (bounds.height() - 12f * scale) / 2f
        val body = RectF(left, top + 0.5f * scale, left + 20.5f * scale, top + 11.5f * scale)
        val terminal = RectF(left + 21.5f * scale, top + 3.25f * scale, left + 23f * scale, top + 8.75f * scale)
        val resolvedColor = tint.getColorForState(state, tint.defaultColor)
        paint.color = resolvedColor
        paint.alpha = (drawableAlpha * 0.3f).toInt()
        canvas.drawRoundRect(body, 3.4f * scale, 3.4f * scale, paint)
        paint.alpha = drawableAlpha
        canvas.drawRoundRect(terminal, 1f * scale, 1f * scale, paint)
        val save = canvas.save()
        canvas.clipRect(body.left, body.top, body.left + body.width() * level, body.bottom)
        canvas.drawRoundRect(body, 3.4f * scale, 3.4f * scale, paint)
        canvas.restoreToCount(save)
        if (chargeStyle > 0) {
            val bolt = android.graphics.Path().apply {
                moveTo(left + 11.6f * scale, top + 1.8f * scale)
                lineTo(left + 7.4f * scale, top + 6.5f * scale)
                lineTo(left + 10.1f * scale, top + 6.5f * scale)
                lineTo(left + 8.8f * scale, top + 10.2f * scale)
                lineTo(left + 13.1f * scale, top + 5.3f * scale)
                lineTo(left + 10.4f * scale, top + 5.3f * scale)
                close()
            }
            paint.color = if (Color.luminance(resolvedColor) > 0.5f) Color.BLACK else Color.WHITE
            canvas.drawPath(bolt, paint)
            if (chargeStyle == 2) {
                canvas.save()
                canvas.translate(2.4f * scale, 0f)
                paint.alpha = (drawableAlpha * 0.65f).toInt()
                canvas.drawPath(bolt, paint)
                canvas.restore()
                paint.alpha = drawableAlpha
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint ?: ColorStateList.valueOf(Color.BLACK)
        invalidateSelf()
    }

    override fun isStateful(): Boolean = tint.isStateful

    override fun onStateChange(state: IntArray): Boolean {
        invalidateSelf()
        return true
    }

    override fun getIntrinsicWidth(): Int = (23f * density).toInt()

    override fun getIntrinsicHeight(): Int = (12f * density).toInt()

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
