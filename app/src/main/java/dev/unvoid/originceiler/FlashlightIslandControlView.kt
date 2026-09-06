package dev.unvoid.originceiler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class FlashlightIslandControlView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val cameraId = flashCameraId(context)
    private val maximum = maximumStrength(context).coerceAtLeast(1)
    private var strength = Settings.Global.getInt(context.contentResolver, SETTING_STRENGTH, maximum).coerceIn(1, maximum)
    private var enabled = Settings.Global.getInt(context.contentResolver, SETTING_ENABLED, 0) == 1
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val materialIcon: Drawable? = runCatching {
        context.createPackageContext("dev.unvoid.originceiler", 0).getDrawable(R.drawable.ic_flashlight_material3)?.mutate()
    }.getOrNull()
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
            resolveSize((312f * density).roundToInt(), widthMeasureSpec),
            resolveSize((92f * density).roundToInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerY = height * 0.57f
        val iconSize = 42f * density
        val iconLeft = 19f * density
        val iconTop = centerY - iconSize / 2f
        materialIcon?.let { icon ->
            icon.setTint(if (enabled) 0xffffd95c.toInt() else 0xfff1f1f5.toInt())
            icon.setBounds(iconLeft.roundToInt(), iconTop.roundToInt(), (iconLeft + iconSize).roundToInt(), (iconTop + iconSize).roundToInt())
            icon.draw(canvas)
        }
        val trackStart = 88f * density
        val trackEnd = width - 22f * density
        val trackHalf = 8f * density
        val fraction = strength.toFloat() / maximum
        val thumbX = trackStart + (trackEnd - trackStart) * fraction
        fill.color = 0xff48484f.toInt()
        canvas.drawRoundRect(RectF(trackStart, centerY - trackHalf, trackEnd, centerY + trackHalf), trackHalf, trackHalf, fill)
        if (enabled) {
            fill.color = 0xffffd95c.toInt()
            canvas.drawRoundRect(
                RectF(trackStart, centerY - trackHalf, (thumbX - 7f * density).coerceAtLeast(trackStart), centerY + trackHalf),
                trackHalf,
                trackHalf,
                fill
            )
        }
        fill.color = 0xff202126.toInt()
        canvas.drawRoundRect(
            RectF(thumbX - 6.5f * density, centerY - 24f * density, thumbX + 6.5f * density, centerY + 24f * density),
            6.5f * density,
            6.5f * density,
            fill
        )
        fill.color = if (enabled) 0xfffff3c2.toInt() else 0xffdedee5.toInt()
        canvas.drawRoundRect(
            RectF(thumbX - 3f * density, centerY - 20f * density, thumbX + 3f * density, centerY + 20f * density),
            3f * density,
            3f * density,
            fill
        )
        textPaint.alpha = if (enabled) 235 else 145
        canvas.drawText(if (enabled) "${(fraction * 100).roundToInt()}%" else "ВЫКЛ", trackEnd, 17f * density, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val trackStart = 88f * density
        val trackEnd = width - 22f * density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (event.x < trackStart - 10f * density) {
                    toggle()
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                } else {
                    setFromPosition(event.x, trackStart, trackEnd)
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.x >= trackStart - 14f * density) setFromPosition(event.x, trackStart, trackEnd)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun setFromPosition(x: Float, start: Float, end: Float) {
        val fraction = ((x - start) / (end - start)).coerceIn(0f, 1f)
        strength = (1 + fraction * (maximum - 1)).roundToInt().coerceIn(1, maximum)
        enabled = true
        applyTorch()
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
