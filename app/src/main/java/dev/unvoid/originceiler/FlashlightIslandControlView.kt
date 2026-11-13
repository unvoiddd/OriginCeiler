package dev.unvoid.originceiler

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

class FlashlightIslandControlView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val cameraId = flashCameraId(context)
    private val maximum = maximumStrength(context).coerceAtLeast(1)
    private var strength = Settings.Global.getInt(context.contentResolver, SETTING_STRENGTH, maximum).coerceIn(1, maximum)
    private var enabled = Settings.Global.getInt(context.contentResolver, SETTING_ENABLED, 0) == 1
    private var beamWidth = Settings.Global.getFloat(context.contentResolver, SETTING_BEAM_WIDTH, 0.5f).coerceIn(0f, 1f)
    private var shownLevel = if (enabled) strength.toFloat() / maximum else 0f
    private var shownWidth = beamWidth
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val beamPath = Path()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downStrength = 0f
    private var downWidth = 0f
    private var dragging = false
    private var callbackRegistered = false
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, active: Boolean) {
            if (id != cameraId) return
            enabled = active
            if (active && Build.VERSION.SDK_INT >= 33) {
                strength = runCatching { cameraManager.getTorchStrengthLevel(id) }.getOrDefault(strength).coerceIn(1, maximum)
            }
            invalidate()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Фонарик, яркость"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!callbackRegistered) {
            runCatching { cameraManager.registerTorchCallback(torchCallback, callbackHandler) }
            callbackRegistered = true
        }
    }

    override fun onDetachedFromWindow() {
        if (callbackRegistered) runCatching { cameraManager.unregisterTorchCallback(torchCallback) }
        callbackRegistered = false
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((164f * density).roundToInt(), widthMeasureSpec),
            resolveSize((196f * density).roundToInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val targetLevel = if (enabled) strength.toFloat() / maximum else 0f
        shownLevel += (targetLevel - shownLevel) * 0.22f
        shownWidth += (beamWidth - shownWidth) * 0.22f
        val animating = abs(targetLevel - shownLevel) > 0.002f || abs(beamWidth - shownWidth) > 0.002f
        if (!animating) {
            shownLevel = targetLevel
            shownWidth = beamWidth
        }

        val dp = density
        val cx = width / 2f
        val torchBottom = height - 24f * dp
        val bodyHalf = 7.5f * dp
        val headHalf = 12f * dp
        val bodyTop = torchBottom - 27f * dp
        val headTop = bodyTop - 12f * dp

        if (shownLevel > 0.005f) drawBeam(canvas, cx, headTop + 1f * dp, headHalf - 1.5f * dp, shownLevel)
        drawTorch(canvas, cx, headTop, bodyTop, torchBottom, headHalf, bodyHalf)

        if (animating) postInvalidateOnAnimation()
    }

    private fun drawTorch(canvas: Canvas, cx: Float, headTop: Float, bodyTop: Float, bottom: Float, headHalf: Float, bodyHalf: Float) {
        val dp = density
        val corner = 2.5f * dp
        fill.shader = null
        fill.maskFilter = null
        fill.color = if (enabled) Color.WHITE else 0xff8e8e93.toInt()
        beamPath.reset()
        beamPath.moveTo(cx - headHalf + corner, headTop)
        beamPath.lineTo(cx + headHalf - corner, headTop)
        beamPath.quadTo(cx + headHalf, headTop, cx + headHalf, headTop + corner)
        beamPath.lineTo(cx + headHalf, headTop + 3f * dp)
        beamPath.lineTo(cx + bodyHalf, bodyTop)
        beamPath.lineTo(cx + bodyHalf, bottom - corner)
        beamPath.quadTo(cx + bodyHalf, bottom, cx + bodyHalf - corner, bottom)
        beamPath.lineTo(cx - bodyHalf + corner, bottom)
        beamPath.quadTo(cx - bodyHalf, bottom, cx - bodyHalf, bottom - corner)
        beamPath.lineTo(cx - bodyHalf, bodyTop)
        beamPath.lineTo(cx - headHalf, headTop + 3f * dp)
        beamPath.lineTo(cx - headHalf, headTop + corner)
        beamPath.quadTo(cx - headHalf, headTop, cx - headHalf + corner, headTop)
        beamPath.close()
        canvas.drawPath(beamPath, fill)
        fill.color = Color.BLACK
        canvas.drawCircle(cx, bodyTop + 7f * dp, 2f * dp, fill)
    }

    private fun drawBeam(canvas: Canvas, cx: Float, originY: Float, originHalf: Float, level: Float) {
        val dp = density
        val alpha = (0.3f + 0.7f * level).coerceIn(0f, 1f) * (level / 0.08f).coerceAtMost(1f)
        // Brighter light throws further: the spot climbs towards the top of the card as the level grows.
        val reach = (originY - 30f * dp) * (0.42f + 0.58f * level)
        val topY = originY - reach
        val spread = originHalf + 8f * dp + (width / 2f - 26f * dp) * shownWidth
        val spotHalfH = (spread * 0.3f).coerceAtLeast(5f * dp)

        fill.maskFilter = null
        fill.shader = RadialGradient(
            cx, originY, 44f * dp,
            intArrayOf(white(0.75f * alpha), white(0.18f * alpha), white(0f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, originY, 44f * dp, fill)

        beamPath.reset()
        beamPath.moveTo(cx - originHalf, originY)
        beamPath.lineTo(cx - spread, topY)
        beamPath.arcTo(RectF(cx - spread, topY - spotHalfH, cx + spread, topY + spotHalfH), 180f, 180f, false)
        beamPath.lineTo(cx + originHalf, originY)
        beamPath.close()
        fill.maskFilter = BlurMaskFilter(5f * dp, BlurMaskFilter.Blur.NORMAL)
        fill.shader = LinearGradient(
            0f, originY, 0f, topY,
            intArrayOf(white(0.95f * alpha), white(0.42f * alpha), white(0.2f * alpha)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(beamPath, fill)

        val coreSpread = originHalf * 0.6f + (spread - originHalf) * 0.45f
        beamPath.reset()
        beamPath.moveTo(cx - originHalf * 0.6f, originY)
        beamPath.lineTo(cx - coreSpread, topY + spotHalfH * 0.4f)
        beamPath.lineTo(cx + coreSpread, topY + spotHalfH * 0.4f)
        beamPath.lineTo(cx + originHalf * 0.6f, originY)
        beamPath.close()
        fill.maskFilter = BlurMaskFilter(8f * dp, BlurMaskFilter.Blur.NORMAL)
        fill.shader = LinearGradient(
            0f, originY, 0f, topY,
            intArrayOf(white(0.85f * alpha * level), white(0f)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(beamPath, fill)

        val spot = RectF(cx - spread, topY - spotHalfH, cx + spread, topY + spotHalfH)
        fill.maskFilter = BlurMaskFilter(3f * dp, BlurMaskFilter.Blur.NORMAL)
        fill.shader = null
        fill.color = white(0.32f * alpha)
        canvas.drawOval(spot, fill)
        fill.maskFilter = null

        stroke.maskFilter = BlurMaskFilter(1.2f * dp, BlurMaskFilter.Blur.SOLID)
        stroke.strokeWidth = 2.2f * dp
        stroke.shader = LinearGradient(
            0f, topY - spotHalfH, 0f, topY + spotHalfH,
            intArrayOf(white(alpha), white(0.55f * alpha)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(spot, stroke)
        stroke.shader = null
        stroke.maskFilter = null

        if (level > 0.55f) {
            val rays = ((level - 0.55f) / 0.45f).coerceIn(0f, 1f)
            stroke.strokeWidth = 1.6f * dp
            stroke.color = white(0.9f * rays)
            for (index in 0 until 7) {
                val angle = Math.toRadians(207.0 + index * 21.0)
                val inner = 17f * dp
                val outer = inner + (if (index % 2 == 0) 11f else 7f) * dp * rays
                canvas.drawLine(
                    cx + cos(angle).toFloat() * inner, originY + 4f * dp + sin(angle).toFloat() * inner,
                    cx + cos(angle).toFloat() * outer, originY + 4f * dp + sin(angle).toFloat() * outer,
                    stroke
                )
            }
        }
    }

    private fun white(alpha: Float): Int = Color.argb((255 * alpha.coerceIn(0f, 1f)).roundToInt(), 255, 255, 255)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                downStrength = if (enabled) strength.toFloat() / maximum else 0f
                downWidth = beamWidth
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                if (dragging) {
                    beamWidth = (downWidth + dx / (width * 0.8f)).coerceIn(0f, 1f)
                    val level = (downStrength - dy / (height * 0.75f)).coerceIn(0f, 1f)
                    if (level <= 0.02f) {
                        if (enabled) {
                            enabled = false
                            applyTorch()
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        invalidate()
                    } else {
                        val next = (1 + level * (maximum - 1)).roundToInt().coerceIn(1, maximum)
                        if (!enabled || next != strength) {
                            strength = next
                            enabled = true
                            applyTorch()
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        } else {
                            invalidate()
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (dragging) {
                        Settings.Global.putFloat(context.contentResolver, SETTING_BEAM_WIDTH, beamWidth)
                    } else {
                        toggle()
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    performClick()
                }
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun toggle() {
        enabled = !enabled
        applyTorch()
    }

    private fun applyTorch() {
        val id = cameraId ?: return
        runCatching {
            if (!enabled) {
                cameraManager.setTorchMode(id, false)
            } else if (Build.VERSION.SDK_INT >= 33 && maximum > 1) {
                cameraManager.turnOnTorchWithStrengthLevel(id, strength)
            } else {
                cameraManager.setTorchMode(id, true)
            }
            Settings.Global.putInt(context.contentResolver, SETTING_ENABLED, if (enabled) 1 else 0)
            Settings.Global.putInt(context.contentResolver, SETTING_STRENGTH, strength)
            invalidate()
        }
    }

    companion object {
        const val SETTING_ENABLED = "originroottoolbox_flashlight_enabled"
        const val SETTING_STRENGTH = "originroottoolbox_flashlight_strength"
        const val SETTING_BEAM_WIDTH = "originroottoolbox_flashlight_beam_width"

        fun flashCameraId(context: Context): String? {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return runCatching {
                manager.cameraIdList.firstOrNull { id ->
                    val characteristics = manager.getCameraCharacteristics(id)
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }
            }.getOrNull()
        }

        fun maximumStrength(context: Context): Int {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = flashCameraId(context) ?: return 1
            if (Build.VERSION.SDK_INT < 33) return 1
            return runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            }.getOrDefault(1)
        }
    }
}
