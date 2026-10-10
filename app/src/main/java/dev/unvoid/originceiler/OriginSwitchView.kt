package dev.unvoid.originceiler

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.animation.PathInterpolator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView

class OriginSwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val rect = RectF()

    private var checked = false
    private var ratio = 0f
    private var animator: ValueAnimator? = null
    var onCheckedChangeListener: ((Boolean) -> Unit)? = null

    private val trackWidth = dp(42f)
    private val trackHeight = dp(20f)
    private val ringOffR = dp(9.5f)
    private val ringOnR = dp(9.5f)
    private val ringLine = dp(3f)
    private val offLine = dp(2.5f)
    private val padLeft = dp(2f)
    private val padRight = dp(1f)

    private var trackOffColor = 0x66555555.toInt()
    private var trackOnColor = 0xFF223E63.toInt()
    private var ringOffColor = 0xFF555555.toInt()
    private var ringOnColor = 0xFF2785FF.toInt()
    private var thumbOffColor = 0xFF000000.toInt()
    private var thumbOnColor = 0xFF000000.toInt()
    private val glowColor get() = (ringOnColor and 0x00FFFFFF) or 0x66000000

    fun setPalette(accent: Int, container: Int, inactive: Int, thumb: Int) {
        ringOnColor = accent
        trackOnColor = container
        ringOffColor = inactive
        trackOffColor = inactive
        thumbOffColor = thumb
        thumbOnColor = thumb
        invalidate()
    }

    init {
        isClickable = true
        isFocusable = true
        val bleed = Math.ceil(ringLine.toDouble()).toInt()
        setPadding(bleed, bleed, bleed, bleed)
        setOnClickListener {
            toggle()
        }
    }

    private fun dp(value: Float): Float {
        return resources.displayMetrics.density * value
    }

    fun isChecked(): Boolean = checked

    fun setChecked(value: Boolean, animate: Boolean = true) {
        if (checked == value) return
        checked = value
        val target = if (value) 1f else 0f
        animateTo(target, animate)
        onCheckedChangeListener?.invoke(checked)
    }

    fun toggle() {
        setChecked(!checked, true)
    }

    private fun animateTo(target: Float, animate: Boolean) {
        animator?.cancel()
        if (!animate) {
            ratio = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(ratio, target).apply {
            duration = 250L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                ratio = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = Math.ceil((padLeft + trackWidth + padRight).toDouble()).toInt()
        val height = Math.ceil(maxOf(trackHeight, ringOnR * 2f).toDouble()).toInt()
        val w = resolveSize(paddingLeft + width + paddingRight, widthMeasureSpec)
        val h = resolveSize(paddingTop + height + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = ratio.coerceIn(0f, 1f)
        val centerY = height / 2f
        val left = paddingLeft + padLeft
        val track = trackWidth
        val halfH = trackHeight / 2f

        val cx = ringOffR + left + (left + track - ringOnR - (ringOffR + left)) * t
        val ringR = ringOffR + (ringOnR - ringOffR) * t
        val ringColor = lerpColor(ringOffColor, ringOnColor, t)
        val thumbColor = lerpColor(thumbOffColor, thumbOnColor, t)

        val save = canvas.save()
        clipPath.reset()
        clipPath.addCircle(cx, centerY, ringR, Path.Direction.CW)
        canvas.clipPath(clipPath, Region.Op.DIFFERENCE)

        if (t > 0f) {
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = withAlpha(trackOnColor, (t * 2f).coerceAtMost(1f))
            val fillRight = (t.coerceAtLeast(0.5f) * track) + left
            rect.set(left, centerY - halfH, fillRight, centerY + halfH)
            canvas.drawRoundRect(rect, halfH, halfH, fillPaint)
        }

        if (t < 1f) {
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = offLine
            strokePaint.color = withAlpha(trackOffColor, 1f - t)
            val offLeft = (t.coerceAtMost(0.5f) * track) + left
            rect.set(offLeft, centerY - halfH, left + track, centerY + halfH)
            canvas.drawRoundRect(rect, halfH, halfH, strokePaint)
        }

        canvas.restoreToCount(save)

        if (t > 0.02f) {
            val maxR = minOf(centerY, height - centerY)
            val glowR = minOf(2.4f * ringR, maxR)
            if (glowR > 0f) {
                glowPaint.shader = RadialGradient(
                    cx,
                    centerY,
                    glowR,
                    intArrayOf(withAlpha(glowColor, 255f * t), withAlpha(glowColor, 0f)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, centerY, glowR, glowPaint)
                glowPaint.shader = null
            }
        }

        fillPaint.style = Paint.Style.FILL_AND_STROKE
        fillPaint.strokeWidth = ringLine
        fillPaint.color = thumbColor
        canvas.drawCircle(cx, centerY, ringR, fillPaint)

        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = ringLine
        strokePaint.color = ringColor
        canvas.drawCircle(cx, centerY, ringR, strokePaint)
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f)
        val r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f)
        val g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f)
        val b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f)
        return Color.argb(a, r, g, b)
    }

    private fun withAlpha(color: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        val a = Math.round(Color.alpha(color) * f)
        return (a shl 24) or (0x00FFFFFF and color)
    }
}

@Composable
fun OriginSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val view = LocalView.current
    val accent = OriginAccent.toArgb()
    val container = OriginAccentContainer.toArgb()
    val inactive = OriginTextDisabled.toArgb()
    val thumb = OriginBgDark.toArgb()
    val onToggle: (Boolean) -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        onCheckedChange(it)
    }
    AndroidView(
        factory = { ctx ->
            OriginSwitchView(ctx).apply {
                setChecked(checked, false)
                onCheckedChangeListener = onToggle
                isEnabled = enabled
            }
        },
        update = { view ->
            view.onCheckedChangeListener = null
            view.setPalette(accent, container, inactive, thumb)
            if (view.isChecked() != checked) {
                view.setChecked(checked, true)
            }
            view.onCheckedChangeListener = onToggle
            view.isEnabled = enabled
        },
        modifier = modifier
    )
}
