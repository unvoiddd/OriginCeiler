package dev.unvoid.originceiler

import android.content.res.Configuration
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.AbsSeekBar
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ProgressBar
import android.widget.RadioButton
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * KEY_CONTROLS for View-based apps: every switch (Switch, SwitchCompat, OriginUI VMoveBoolButton)
 * and slider (SeekBar, OriginUI VigourSeekbar) draws Kyant's catalog LiquidToggle / LiquidSlider
 * instead of its own look. The view keeps its touch handling; only onDraw (and, for switches,
 * onMeasure to make room for the 64 dp track) is taken over, so turning the setting off restores it.
 */
object KyantGlassControls {
    private enum class Kind { SWITCH, SLIDER }

    private val kinds = Collections.synchronizedMap(HashMap<Class<*>, Kind?>())
    private val hooked = Collections.synchronizedSet(HashSet<Method>())
    private val states = Collections.synchronizedMap(WeakHashMap<View, State>())
    private val checkedGetters = Collections.synchronizedMap(HashMap<Class<*>, Method?>())
    private val draggingFields = Collections.synchronizedMap(HashMap<Class<*>, Field?>())
    @Volatile private var installed = false
    @Volatile private var listening = false

    private class State {
        var fraction = Float.NaN
        var press = 0f
        var velocity = 0f
        var time = 0L
    }

    fun install() {
        if (installed) return
        installed = true
        XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                kindOf(view.javaClass) ?: return
                KyantGlass.ensureConfig(view.context)
                listenForSetting()
                KyantGlass.track(view)
                states.getOrPut(view) { State() }
            }
        })
    }

    private fun listenForSetting() {
        if (listening) return
        listening = true
        // The switch width depends on the setting: measure again, not only redraw.
        KyantGlass.onConfigChanged {
            synchronized(states) { states.keys.toList() }.forEach { it.requestLayout(); it.invalidate() }
        }
    }

    private fun kindOf(cls: Class<*>): Kind? {
        if (kinds.containsKey(cls)) return kinds[cls]
        val name = cls.simpleName
        val kind = when {
            ViewGroup::class.java.isAssignableFrom(cls) -> null
            CheckBox::class.java.isAssignableFrom(cls) || RadioButton::class.java.isAssignableFrom(cls) -> null
            CompoundButton::class.java.isAssignableFrom(cls) && name.contains("Switch", true) -> Kind.SWITCH
            name.endsWith("MoveBoolButton") -> Kind.SWITCH
            AbsSeekBar::class.java.isAssignableFrom(cls) -> Kind.SLIDER
            ProgressBar::class.java.isAssignableFrom(cls) && name.contains("seekbar", true) -> Kind.SLIDER
            else -> null
        }
        kinds[cls] = kind
        if (kind != null) {
            runCatching { hookNearest(cls, "onDraw", Canvas::class.java, hook = drawHook) }.onFailure(XposedBridge::log)
            if (kind == Kind.SWITCH) {
                runCatching { hookNearest(cls, "onMeasure", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, hook = measureHook) }
                    .onFailure(XposedBridge::log)
            }
        }
        return kind
    }

    /** Hooks the most derived declaration, so nothing a subclass draws after super survives. */
    private fun hookNearest(cls: Class<*>, name: String, vararg params: Class<*>, hook: XC_MethodHook) {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            val m = runCatching { c.getDeclaredMethod(name, *params) }.getOrNull()
            if (m != null) {
                if (hooked.add(m)) XposedBridge.hookMethod(m, hook)
                return
            }
            c = c.superclass
        }
    }

    private fun active(view: View): Kind? {
        val kind = kinds[view.javaClass] ?: return null
        return if (KyantGlass.config.on(KyantGlass.KEY_CONTROLS)) kind else null
    }

    private val drawHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val view = param.thisObject as? View ?: return
            val kind = active(view) ?: return
            val canvas = param.args[0] as? Canvas ?: return
            runCatching {
                when (kind) {
                    Kind.SWITCH -> drawToggle(view, canvas)
                    Kind.SLIDER -> drawSlider(view as ProgressBar, canvas)
                }
                param.result = null
            }.onFailure(XposedBridge::log)
        }
    }

    private val measureHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val view = param.thisObject as? View ?: return
            active(view) ?: return
            val density = view.resources.displayMetrics.density
            // OriginUI switches pad themselves heavily: size to the track alone, not the padding.
            val w = View.resolveSize(max(view.measuredWidth, (TOGGLE_W * density).toInt()), param.args[0] as Int)
            val h = View.resolveSize(max(view.measuredHeight, (TOGGLE_H * density).toInt()), param.args[1] as Int)
            if (w == view.measuredWidth && h == view.measuredHeight) return
            runCatching {
                View::class.java.getDeclaredMethod("setMeasuredDimension", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(view, w, h)
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    private const val TOGGLE_W = 64f
    private const val TOGGLE_H = 28f
    private const val THUMB_W = 40f
    private const val THUMB_H = 24f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rect = RectF()
    private val thumb = RectF()

    private fun isDark(view: View) =
        (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun trackColor(dark: Boolean) = if (dark) Color.argb(92, 0x78, 0x78, 0x80) else Color.argb(51, 0x78, 0x78, 0x78)

    /** Moves the state towards its targets; true while something is still moving. */
    private fun step(state: State, target: Float, pressed: Boolean, rate: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = if (state.time == 0L || state.fraction.isNaN()) 0f else ((now - state.time).coerceIn(0L, 48L)) / 1000f
        state.time = now
        if (state.fraction.isNaN()) state.fraction = target
        val before = state.fraction
        state.fraction += (target - state.fraction) * (1f - exp(-dt * rate))
        if (abs(target - state.fraction) < 0.001f) state.fraction = target
        val v = if (dt > 0f) (state.fraction - before) / dt else 0f
        state.velocity += (v - state.velocity) * (1f - exp(-dt * 20f))
        if (abs(state.velocity) < 0.01f && state.fraction == target) state.velocity = 0f
        val pressTarget = if (pressed) 1f else 0f
        state.press += (pressTarget - state.press) * (1f - exp(-dt * 22f))
        if (abs(pressTarget - state.press) < 0.002f) state.press = pressTarget
        return state.fraction != target || state.press != pressTarget || state.velocity != 0f
    }

    private fun drawToggle(view: View, canvas: Canvas) {
        val state = states.getOrPut(view) { State() }
        val density = view.resources.displayMetrics.density
        val dark = isDark(view)
        val accent = if (dark) 0xFF30D158.toInt() else 0xFF34C759.toInt()
        // VLoadingMoveBoolButton lays its switch out far wider than itself: only what the parent shows counts.
        val parentWidth = (view.parent as? View)?.width?.minus(view.left)?.takeIf { it > 0 } ?: view.width
        val cw = min(view.width, parentWidth).toFloat()
        val ch = view.height.toFloat()
        val u = min(1f, min(cw / (TOGGLE_W * density), ch / (TOGGLE_H * density))) * density
        if (u <= 0f) return
        val moving = step(state, if (readChecked(view)) 1f else 0f, view.isPressed, 16f)
        val f = state.fraction
        val p = state.press
        val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
        // Hug the end the switch sits against when the view is wider than the track.
        val left = if (rtl) 0f else cw - TOGGLE_W * u
        val cy = ch / 2f
        val alpha = if (view.isEnabled) 255 else 102

        canvas.saveLayerAlpha(null, alpha)
        rect.set(left, cy - TOGGLE_H * u / 2f, left + TOGGLE_W * u, cy + TOGGLE_H * u / 2f)
        val track = lerpColor(trackColor(dark), accent, f)
        fill.maskFilter = null
        fill.color = track
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)

        val travel = 2f * u + 20f * u * f
        val cx = if (rtl) rect.right - travel - THUMB_W * u / 2f else rect.left + travel + THUMB_W * u / 2f
        drawThumb(canvas, cx, cy, u, p, state.velocity / 50f) { sx, sy ->
            canvas.save()
            canvas.scale(lerp(2f / 3f, 0.75f, p) * sx, lerp(0f, 0.75f, p) * sy, cx, cy)
            fill.color = track
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
            canvas.restore()
        }
        canvas.restore()
        if (moving) view.postInvalidateOnAnimation()
    }

    private fun drawSlider(view: ProgressBar, canvas: Canvas) {
        val state = states.getOrPut(view) { State() }
        val density = view.resources.displayMetrics.density
        val dark = isDark(view)
        val accent = if (dark) 0xFF0091FF.toInt() else 0xFF0088FF.toInt()
        val cw = (view.width - view.paddingLeft - view.paddingRight).toFloat()
        val ch = (view.height - view.paddingTop - view.paddingBottom).toFloat()
        val u = min(1f, ch / (THUMB_H * density)).coerceAtLeast(0.6f) * density
        val min = view.min
        val span = (view.max - min).coerceAtLeast(1)
        val target = ((view.progress - min).toFloat() / span).coerceIn(0f, 1f)
        val dragging = view.isPressed || readDragging(view)
        val moving = step(state, target, dragging, if (dragging) 60f else 24f)
        val f = state.fraction
        val p = state.press
        val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val thumbW = THUMB_W * u
        // Kyant lets the thumb hang a quarter of its width past each end of the track.
        val left = view.paddingLeft + thumbW / 4f
        val right = view.paddingLeft + cw - thumbW / 4f
        if (right <= left) return
        val cy = view.paddingTop + ch / 2f
        val trackH = 6f * u
        val alpha = if (view.isEnabled) 255 else 102

        canvas.saveLayerAlpha(null, alpha)
        val drawTrack = {
            rect.set(left, cy - trackH / 2f, right, cy + trackH / 2f)
            fill.color = trackColor(dark)
            canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, fill)
            val w = (right - left) * f
            if (w > 0f) {
                if (rtl) rect.set(right - w, rect.top, right, rect.bottom) else rect.set(left, rect.top, left + w, rect.bottom)
                fill.color = accent
                canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, fill)
            }
        }
        fill.maskFilter = null
        drawTrack()
        val pos = (right - left) * f
        val cx = (if (rtl) right - pos else left + pos).coerceIn(left + thumbW / 4f, right - thumbW / 4f)
        drawThumb(canvas, cx, cy, u, p, state.velocity / 10f) { sx, sy ->
            canvas.save()
            canvas.scale(lerp(2f / 3f, 1f, p) * sx, lerp(0f, 1f, p) * sy, cx, cy)
            drawTrack()
            canvas.restore()
        }
        canvas.restore()
        if (moving) view.postInvalidateOnAnimation()
    }

    /**
     * The glass capsule: soft shadow, then (clipped to the capsule) what lies under it magnified
     * like the lens, the white surface that fades while held, inner shadow and ambient rim.
     */
    private inline fun drawThumb(canvas: Canvas, cx: Float, cy: Float, u: Float, p: Float, velocity: Float, backdrop: (Float, Float) -> Unit) {
        val scale = lerp(1f, 1.5f, p)
        val sx = scale / (1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f))
        val sy = scale * (1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f))
        val w = THUMB_W * u * sx
        val h = THUMB_H * u * sy
        thumb.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val r = h / 2f

        fill.color = Color.argb(13, 0, 0, 0)
        fill.maskFilter = BlurMaskFilter(4f * u, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(thumb, r, r, fill)
        fill.maskFilter = null

        canvas.save()
        path.reset()
        path.addRoundRect(thumb, r, r, Path.Direction.CW)
        canvas.clipPath(path)
        // Magnification of the lens: what is under the thumb, seen 1 / scale larger.
        if (p > 0f) backdrop(1f, 1f)
        fill.color = Color.argb(((1f - p) * 255f).toInt(), 255, 255, 255)
        canvas.drawRoundRect(thumb, r, r, fill)
        if (p > 0.01f) {
            stroke.strokeWidth = 8f * u * p
            stroke.color = Color.argb((38f * p).toInt(), 0, 0, 0)
            stroke.maskFilter = BlurMaskFilter(4f * u * p, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(thumb, r, r, stroke)
            stroke.maskFilter = null
        }
        canvas.restore()
        if (p > 0.01f) {
            stroke.strokeWidth = u
            stroke.color = Color.argb((140f * p).toInt(), 255, 255, 255)
            thumb.inset(u / 2f, u / 2f)
            canvas.drawRoundRect(thumb, r - u / 2f, r - u / 2f, stroke)
        }
    }

    // ---------------------------------------------------------------- reading the view

    private fun readChecked(view: View): Boolean {
        if (view is CompoundButton) return view.isChecked
        val getter = checkedGetters.getOrPut(view.javaClass) {
            view.javaClass.methods.firstOrNull { (it.name == "isChecked" || it.name == "getChecked") && it.parameterCount == 0 }
        } ?: return false
        return runCatching { getter.invoke(view) as? Boolean }.getOrNull() ?: false
    }

    /** OriginUI seekbars track the drag themselves without setPressed. */
    private fun readDragging(view: View): Boolean {
        if (view is AbsSeekBar) return false
        val field = draggingFields.getOrPut(view.javaClass) {
            var c: Class<*>? = view.javaClass
            var found: Field? = null
            while (c != null && found == null && c != ProgressBar::class.java) {
                found = runCatching { c.getDeclaredField("mIsDragging") }.getOrNull()
                    ?: runCatching { c.getDeclaredField("isTouchMoving") }.getOrNull()
                c = c.superclass
            }
            found?.apply { isAccessible = true }
        } ?: return false
        return runCatching { field.getBoolean(view) }.getOrDefault(false)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f).toInt(),
            (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        )
    }
}
