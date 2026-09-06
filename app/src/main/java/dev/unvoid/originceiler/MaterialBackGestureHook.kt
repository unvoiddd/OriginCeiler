package dev.unvoid.originceiler

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

class MaterialBackGestureHook {
    private data class GestureState(
        var left: Boolean = true,
        var startX: Float = 0f,
        var startY: Float = 0f,
        var touchX: Float = 0f,
        var touchY: Float = 0f,
        var animatedPull: Float = 0f,
        var animatedOffsetY: Float = 0f,
        var alpha: Float = 1f,
        var scale: Float = 1f,
        var active: Boolean = false,
        var exitAnimator: ValueAnimator? = null
    )

    private val states = Collections.synchronizedMap(WeakHashMap<View, GestureState>())

    fun install(classLoader: ClassLoader) {
        classNames.forEach { className ->
            runCatching { Class.forName(className, false, classLoader) }.getOrNull()?.let(::installClass)
        }
        if (watcherInstalled.compareAndSet(false, true)) {
            XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook(Int.MIN_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val type = param.result as? Class<*> ?: return
                    if (type.name in classNames) installClass(type)
                }
            })
        }
    }

    private fun installClass(type: Class<*>) {
        synchronized(installedClasses) {
            if (installedClasses.put(type, true) != null) return
        }
        XposedBridge.hookAllMethods(
            type,
            "a",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!enabled(view.context)) return
                    val left = param.args[0] as? Boolean ?: return
                    val x = param.args[1] as? Float ?: return
                    val y = param.args[2] as? Float ?: return
                    val state = states.getOrPut(view) { GestureState() }
                    state.exitAnimator?.cancel()
                    state.left = left
                    state.startX = x
                    state.startY = y
                    state.touchX = x
                    state.touchY = y
                    state.animatedPull = 0f
                    state.animatedOffsetY = 0f
                    state.alpha = 1f
                    state.scale = 1f
                    state.active = true
                    view.invalidate()
                }
            }
        )
        XposedBridge.hookAllMethods(
            type,
            "c",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!enabled(view.context)) return
                    val event = param.args[0] as? MotionEvent ?: return
                    val state = states[view] ?: return
                    state.touchX = event.rawX
                    state.touchY = event.rawY
                    view.postInvalidateOnAnimation()
                }
            }
        )
        XposedBridge.hookAllMethods(
            type,
            "b",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!enabled(view.context)) return
                    startExit(view)
                }
            }
        )
        XposedBridge.hookAllMethods(
            type,
            "onDraw",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!enabled(view.context)) return
                    val canvas = param.args[0] as? Canvas ?: return
                    draw(view, canvas, states[view])
                    param.setResult(null)
                }
            }
        )
        XposedBridge.log("OriginRootToolbox Material back gesture hook ready: ${type.name}")
    }

    private fun startExit(view: View) {
        val state = states[view] ?: return
        state.exitAnimator?.cancel()
        val startAlpha = state.alpha
        val startScale = state.scale
        state.exitAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Float
                state.alpha = startAlpha * (1f - value)
                state.scale = startScale + (0.82f - startScale) * value
                view.invalidate()
                if (value >= 1f) state.active = false
            }
            start()
        }
    }

    private fun draw(view: View, canvas: Canvas, state: GestureState?) {
        if (state == null || !state.active) return
        val density = view.resources.displayMetrics.density
        val pull = max(0f, if (state.left) state.touchX - state.startX else state.startX - state.touchX)
        val targetOffset = verticalOffset(state.touchY - state.startY, density)
        state.animatedPull += (pull - state.animatedPull) * 0.32f
        state.animatedOffsetY += (targetOffset - state.animatedOffsetY) * 0.22f
        if (abs(pull - state.animatedPull) > 0.25f || abs(targetOffset - state.animatedOffsetY) > 0.25f) {
            view.postInvalidateOnAnimation()
        }
        val progress = (state.animatedPull / (25f * density)).coerceIn(0f, 1f)
        if (progress <= 0.001f || state.alpha <= 0.001f) return
        val overscroll = max(0f, state.animatedPull - 50f * density)
        val extraX = (1f - exp((-overscroll / (40f * density)).toDouble())).toFloat() * 12f * density
        val extraHeight = (1f - exp((-max(0f, overscroll - 75f * density) / (60f * density)).toDouble())).toFloat() * 8f * density
        val width = lerp(0f, 51f * density, progress) + extraX
        val height = lerp(48f * density, 46f * density, progress) + extraHeight
        val nearRadius = min(height * 0.5f, lerp(6f * density, 16f * density, progress))
        val farRadius = min(height * 0.5f, lerp(6f * density, 20f * density, progress))
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val centerY = state.startY - location[1] + state.animatedOffsetY
        val edgeInset = -8f * density
        val bounds = if (state.left) {
            RectF(edgeInset, centerY - height * 0.5f, edgeInset + width, centerY + height * 0.5f)
        } else {
            RectF(view.width - edgeInset - width, centerY - height * 0.5f, view.width - edgeInset, centerY + height * 0.5f)
        }
        val radii = if (state.left) {
            floatArrayOf(nearRadius, nearRadius, farRadius, farRadius, farRadius, farRadius, nearRadius, nearRadius)
        } else {
            floatArrayOf(farRadius, farRadius, nearRadius, nearRadius, nearRadius, nearRadius, farRadius, farRadius)
        }
        val path = Path().apply { addRoundRect(bounds, radii, Path.Direction.CW) }
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dynamicColor(view.context, backgroundColorName(view.context), android.R.color.system_neutral1_800)
            alpha = (255f * state.alpha).toInt().coerceIn(0, 255)
        }
        canvas.save()
        canvas.scale(state.scale, state.scale, bounds.centerX(), bounds.centerY())
        canvas.drawPath(path, backgroundPaint)
        if (progress >= 0.33f) drawArrow(view.context, canvas, bounds.centerX(), bounds.centerY(), state.left, progress, state.alpha, density)
        canvas.restore()
    }

    private fun drawArrow(context: Context, canvas: Canvas, centerX: Float, centerY: Float, left: Boolean, progress: Float, alpha: Float, density: Float) {
        val arrowAlpha = ((progress - 0.33f) / 0.27f).coerceIn(0f, 1f) * alpha
        val halfWidth = lerp(5.6f, 7.2f, progress) * density
        val halfHeight = lerp(5.6f, 7.2f, progress) * density
        val shaft = lerp(5.6f, 6.4f, progress) * density
        val direction = if (left) -1f else 1f
        val tipX = centerX + direction * shaft * 0.5f
        val tailX = centerX - direction * shaft * 0.5f
        val path = Path().apply {
            moveTo(tailX, centerY - halfHeight)
            lineTo(tipX, centerY)
            lineTo(tailX, centerY + halfHeight)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
            color = dynamicColor(context, arrowColorName(context), android.R.color.system_neutral1_50)
            this.alpha = (255f * arrowAlpha).toInt().coerceIn(0, 255)
        }
        canvas.drawPath(path, paint)
    }

    private fun verticalOffset(delta: Float, density: Float): Float {
        val magnitude = (1f - exp((-abs(delta) / (180f * density)).toDouble())).toFloat() * 75f * density
        return sign(delta) * magnitude
    }

    private fun backgroundColorName(context: Context): String {
        return if (dark(context)) "system_secondary_container_dark" else "system_secondary_fixed_dim"
    }

    private fun arrowColorName(context: Context): String {
        return if (dark(context)) "system_on_secondary_container_dark" else "system_on_secondary_fixed"
    }

    private fun dark(context: Context): Boolean {
        return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun dynamicColor(context: Context, name: String, fallback: Int): Int {
        val id = context.resources.getIdentifier(name, "color", "android")
        return runCatching { context.getColor(if (id != 0) id else fallback) }.getOrDefault(Color.DKGRAY)
    }

    private fun enabled(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, SETTING_ENABLED, 0) == 1
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress
    }

    companion object {
        const val SETTING_ENABLED = "originroottoolbox_material_back_gesture"
        private val watcherInstalled = AtomicBoolean(false)
        private val installedClasses = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
        private val classNames = setOf(
            "com.vivo.upslide.navigation.sideslide.view.SideSlideAnimationView",
            "com.vivo.upslide.navigation.sideslide.view.SideSlideAppBarAnimationView",
            "com.vivo.upslide.navigation.sideslide.flipsideback.FlipSideSlideAnimationView"
        )
    }
}
