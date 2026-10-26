package dev.unvoid.originceiler

import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Finger interaction for [KyantGlass] surfaces, iOS 26 style:
 *  - touch glow: a light spot under the finger on the pressed glass, and a rim glow on the glass
 *    surfaces around it, fading in on press and out on release;
 *  - elastic glass: the pressed element swells a little and stretches after the finger with a
 *    rubber-band limit, then springs back. Applied through View.setAnimationMatrix, which is
 *    independent of the view's own scale / translation, so vivo's animations keep working.
 *
 * Touches are read at each window's root view (one hook on View.dispatchTouchEvent).
 */
object KyantGlassTouch {
    private val installed = AtomicBoolean(false)

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        runCatching { LiquidPressPhysics.installMatrixHook() }
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // Our own CANCEL handing the gesture back: not a touch to react to.
                if (synthesizing) return
                val root = param.thisObject as? View ?: return
                val event = param.args.firstOrNull() as? MotionEvent ?: return
                // Only the window root sees every event of the window. A parent that takes the
                // gesture over (list scroll, pager swipe) cancels it for the stretched view: let go.
                if (root.parent is View) {
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) elastic?.let { e ->
                        if (e.isTarget(root)) {
                            e.cancel()
                            elastic = null
                        }
                    }
                    return
                }
                // DecorView passes the event on to ViewGroup.dispatchTouchEvent: count it once.
                if (lastEvent === event && lastEventTime == event.eventTime) return
                lastEvent = event
                lastEventTime = event.eventTime
                // A held, dragged plate owns the gesture: the window never sees the rest of it.
                if (runCatching { onRootTouch(root, event) }.getOrDefault(false)) param.result = true
            }
        }
        // ViewRootImpl hands every touch of a window to its root through View.dispatchPointerEvent
        // (final): unlike dispatchTouchEvent, no window root (vivo's NotificationShadeWindowView
        // routes shade touches itself) can bypass it. dispatchTouchEvent stays hooked for the
        // CANCEL a scrolling parent sends to the stretched element.
        XposedBridge.hookAllMethods(View::class.java, "dispatchPointerEvent", hook)
        XposedBridge.hookAllMethods(android.view.ViewGroup::class.java, "dispatchTouchEvent", hook)
        XposedBridge.hookAllMethods(View::class.java, "dispatchTouchEvent", hook)
    }

    private var lastEvent: MotionEvent? = null
    private var lastEventTime = 0L

    // ---------------------------------------------------------------- touch state

    private var touchRoot: WeakReference<View>? = null
    @Volatile private var rawX = 0f
    @Volatile private var rawY = 0f
    private var downX = 0f
    private var downY = 0f
    @Volatile private var pressed = false
    private var pressedAt = 0L
    private var releasedAt = 0L

    private const val GLOW_IN_MS = 110f
    private const val GLOW_OUT_MS = 420f

    /** True while we dispatch our own CANCEL into a window. */
    private var synthesizing = false

    /** Returns true when the event is consumed (the dragged plate holds the gesture). */
    private fun onRootTouch(root: View, event: MotionEvent): Boolean {
        val cfg = KyantGlass.config
        val glowOn = cfg[KyantGlass.KEY_TOUCH_GLOW] > 0 || cfg[KyantGlass.KEY_NEIGHBOR_GLOW] > 0
        val elasticOn = cfg[KyantGlass.KEY_ELASTIC] > 0
        if (!glowOn && !elasticOn) return false
        var consume = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchRoot = WeakReference(root)
                rawX = event.rawX; rawY = event.rawY
                downX = rawX; downY = rawY
                pressed = true
                pressedAt = SystemClock.uptimeMillis()
                if (elasticOn) startElastic(root)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pressed || touchRoot?.get() !== root) return false
                rawX = event.rawX; rawY = event.rawY
                consume = moveElastic(root, event, rawX - downX, rawY - downY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                consume = elastic?.captured == true
                // The stretched element is released whatever window ends the gesture.
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) elastic?.cancel() else elastic?.release()
                elastic = null
                if (touchRoot?.get() !== root) return consume
                pressed = false
                releasedAt = SystemClock.uptimeMillis()
            }
            else -> return elastic?.captured == true
        }
        if (glowOn) invalidateSurfaces(root)
        return consume
    }

    private fun invalidateSurfaces(root: View) {
        synchronized(surfaces) { surfaces.keys.toList() }.forEach { v ->
            if (v.isAttachedToWindow && v.rootView === root) v.invalidate()
        }
    }

    // ---------------------------------------------------------------- glow

    /** What [glowFor] fills in: spot centre in local coordinates, radius and strengths. */
    class Glow {
        var x = 0f
        var y = 0f
        var radius = 0f
        var fill = 0f
        var rim = 0f
    }

    /** Every surface drawn with glass lately, so a touch can light it up. */
    private val surfaces = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val loc = IntArray(2)

    /**
     * Fills [out] for a glass surface in [bounds] (canvas coordinates of [view]); false when the
     * finger lights nothing here. Keeps the surface redrawing while the glow fades.
     */
    fun glowFor(view: View, bounds: RectF, cfg: KyantGlass.Config, density: Float, out: Glow): Boolean {
        surfaces[view] = true
        val root = touchRoot?.get() ?: return false
        if (view.rootView !== root) return false
        val now = SystemClock.uptimeMillis()
        val envelope = if (pressed) {
            ((now - pressedAt) / GLOW_IN_MS).coerceIn(0f, 1f)
        } else {
            1f - ((now - releasedAt) / GLOW_OUT_MS).coerceIn(0f, 1f)
        }
        if (envelope <= 0f) return false
        val touchStrength = cfg[KyantGlass.KEY_TOUCH_GLOW].coerceIn(0, 100) / 100f
        val neighborStrength = cfg[KyantGlass.KEY_NEIGHBOR_GLOW].coerceIn(0, 100) / 100f
        view.getLocationOnScreen(loc)
        val lx = rawX - loc[0] - bounds.left
        val ly = rawY - loc[1] - bounds.top
        val w = bounds.width()
        val h = bounds.height()
        var inside = lx >= 0f && ly >= 0f && lx <= w && ly <= h
        // While a plate is dragged its light stays on it, following the finger inside it; the
        // surfaces around it do not light up.
        val active = elastic
        if (active != null && active.dragging && pressed) {
            if (!active.owns(view)) return false
            inside = true
        }
        out.x = if (inside) lx.coerceIn(0f, w) else lx
        out.y = if (inside) ly.coerceIn(0f, h) else ly
        if (inside) {
            if (touchStrength <= 0f) return false
            val scale = cfg[KyantGlass.KEY_TOUCH_GLOW_RADIUS].coerceIn(10, 300) / 100f
            out.radius = maxOf(w, h) * 0.6f * scale
            // Eased so it swells, not blinks.
            val e = envelope * envelope * (3f - 2f * envelope)
            out.fill = 0.30f * touchStrength * e
            out.rim = 0.85f * touchStrength * e
        } else {
            if (neighborStrength <= 0f) return false
            val reach = cfg[KyantGlass.KEY_NEIGHBOR_RADIUS].coerceIn(10, 400) * density
            val dx = maxOf(-lx, 0f, lx - w)
            val dy = maxOf(-ly, 0f, ly - h)
            val d = hypot(dx, dy)
            if (d >= reach) return false
            val falloff = 1f - d / reach
            out.radius = reach
            out.fill = 0f
            out.rim = 0.7f * neighborStrength * falloff * falloff * envelope
        }
        val fading = !pressed || now - pressedAt < GLOW_IN_MS
        if (fading) view.postInvalidateOnAnimation()
        return out.rim > 0.003f || out.fill > 0.003f
    }

    // ---------------------------------------------------------------- elastic

    /** Glass hosts that may stretch → which view stretches for them. */
    private val hosts = Collections.synchronizedMap(WeakHashMap<View, (View) -> View?>())

    fun registerHost(host: View, target: (View) -> View?) {
        if (!hosts.containsKey(host)) hosts[host] = target
    }

    /** Siblings drawn over a glass plate (inside its bounds, after it): the plate's content. */
    private fun overlaying(host: View): List<View> {
        val parent = host.parent as? android.view.ViewGroup ?: return emptyList()
        val out = ArrayList<View>(3)
        val tol = 4 * host.resources.displayMetrics.density
        var after = false
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            if (c === host) { after = true; continue }
            if (!after || c.visibility != View.VISIBLE) continue
            if (c.left >= host.left - tol && c.top >= host.top - tol && c.right <= host.right + tol && c.bottom <= host.bottom + tol) out += c
        }
        return out
    }

    /**
     * The whole item a glass plate belongs to: the highest ancestor covering the same area
     * (vivo tiles: plate → effect frame → tile view, which holds the icon and its own hardware
     * layer). Stretching that moves the icon with the glass and is not clipped by the layer.
     */
    fun itemTarget(host: View): View {
        var v = host
        var depth = 0
        val tol = 4 * host.resources.displayMetrics.density
        while (depth < 4) {
            val p = v.parent as? View ?: break
            if (kotlin.math.abs(p.width - host.width) > tol || kotlin.math.abs(p.height - host.height) > tol) break
            v = p
            depth++
        }
        return v
    }

    /** Default stretch target: the nearest clickable view among the host and its parents. */
    fun clickableTarget(host: View): View {
        var v: View? = host
        var depth = 0
        while (v != null && depth < 4) {
            if (v.isClickable || v.isLongClickable) return v
            v = v.parent as? View
            depth++
        }
        return host
    }

    /** The element being pressed, with HyperLight's press controller ([LiquidPress]). */
    private class Active(view: View, val press: LiquidPress) {
        private val ref = WeakReference(view)
        val downAt = SystemClock.uptimeMillis()
        var dragging = false
        /** Plates (tiles, cards, keys) keep the gesture; notification rows still scroll / swipe. */
        var focus = false
        /** The window no longer sees this gesture (it was handed a CANCEL when dragging began). */
        var captured = false
        var hostRef: WeakReference<View>? = null

        /** The pressed surface: the plate or the stretched view. */
        fun owns(view: View) = ref.get() === view || hostRef?.get() === view
        fun isTarget(view: View) = ref.get() === view
        fun view() = ref.get()

        fun release() {
            val view = ref.get()
            when {
                dragging -> press.releaseDrag()
                SystemClock.uptimeMillis() - downAt >= android.view.ViewConfiguration.getLongPressTimeout() -> press.releaseLong()
                else -> press.releaseTap()
            }
            view?.let { animating[it] = press }
        }

        fun cancel() {
            press.releaseDrag()
            ref.get()?.let { animating[it] = press }
        }
    }

    private var elastic: Active? = null
    /** Elements this module transforms (backdrop sampling follows their animation matrix). */
    private val animating = Collections.synchronizedMap(WeakHashMap<View, LiquidPress>())
    private val presses = Collections.synchronizedMap(WeakHashMap<View, LiquidPress>())
    private const val DRAG_SLOP_DP = 8f
    /** Temporary: touch diagnostics into the dump file (vivo hides app logs). */
    private val debug get() = KyantGlass.config[KyantGlass.KEY_DEBUG_DUMP] == 1

    private fun startElastic(root: View) {
        // A press whose UP never reached us (window changed meanwhile) must not stay squeezed.
        elastic?.cancel()
        elastic = null
        val x = rawX
        val y = rawY
        var best: View? = null
        var bestArea = Float.MAX_VALUE
        synchronized(hosts) { hosts.keys.toList() }.forEach { host ->
            if (!host.isAttachedToWindow || !host.isShown || host.rootView !== root) return@forEach
            host.getLocationOnScreen(loc)
            val w = host.width * host.scaleX
            val h = host.height * host.scaleY
            if (x < loc[0] || y < loc[1] || x > loc[0] + w || y > loc[1] + h) return@forEach
            val area = w * h
            if (area < bestArea) {
                bestArea = area
                best = host
            }
        }
        val host = best
        if (debug) KyantGlassDebug.note(root.context, "down ${x.toInt()},${y.toInt()} root=${root.javaClass.simpleName} hosts=${hosts.size} host=${host?.javaClass?.simpleName}")
        host ?: return
        val target = runCatching { hosts[host]?.invoke(host) }.getOrNull()
        if (debug) KyantGlassDebug.note(root.context, "  target=${target?.javaClass?.simpleName} ${target?.width}x${target?.height} layer=${target?.layerType}")
        target ?: return
        if (target.width == 0 || target.height == 0) return
        // vivo animates its tiles' and cards' own scale, translation and animation matrix every
        // frame: the glass plate (which vivo leaves alone) moves instead, and with it everything
        // laid over it (icon, labels). Notification rows keep the animation matrix.
        val matrixMode = target !== host
        val press = presses.getOrPut(target) {
            LiquidPress(target).apply {
                useAnimationMatrix = matrixMode
                // Notification rows: the stack animates their clipping itself (expand / collapse).
                liftClipping = !matrixMode
            }
        }
        if (!matrixMode) press.followers = overlaying(host)
        tune(press, target)
        animating[target] = press
        press.press()
        elastic = Active(target, press).also {
            it.focus = !matrixMode
            it.hostRef = WeakReference(host)
        }
        if (debug) target.postDelayed({
            KyantGlassDebug.note(root.context, "  +300ms s=${target.scaleX}x${target.scaleY} t=${target.translationX},${target.translationY} matrix=${target.animationMatrix}")
            var a: View? = host
            var d = 0
            while (a != null && d < 6) {
                KyantGlassDebug.note(root.context, "    ${a.javaClass.simpleName} ${a.width}x${a.height} layer=${a.layerType} clipCh=${(a as? android.view.ViewGroup)?.clipChildren} clipOut=${a.clipToOutline} clipB=${a.clipBounds} s=${a.scaleX} t=${a.translationX},${a.translationY}")
                a = a.parent as? View
                d++
            }
        }, 300)
        if (debug) target.postDelayed({
            KyantGlassDebug.note(root.context, "  +900ms s=${target.scaleX}x${target.scaleY} t=${target.translationX},${target.translationY}")
        }, 900)
    }

    /**
     * HyperLight's button constants at 100 %; the setting scales them. Big cards squeeze less
     * (0.88 is made for round buttons: a full-width card would visibly jump).
     */
    private fun tune(press: LiquidPress, view: View) {
        val s = KyantGlass.config[KyantGlass.KEY_ELASTIC].coerceIn(0, 100) / 100f
        val shortDp = minOf(view.width, view.height) / view.resources.displayMetrics.density
        val depth = when {
            shortDp <= 96f -> 0.12f
            shortDp >= 300f -> 0.03f
            else -> 0.12f - (shortDp - 96f) / (300f - 96f) * 0.09f
        }
        press.pressScale = 1f - depth * s
        press.resistance = 0.15f * s
        press.stretch = 0.32f * s
        press.squash = 0.16f * s
        press.dragBlurDp = 0f
    }

    private fun moveElastic(root: View, event: MotionEvent, dx: Float, dy: Float): Boolean {
        val e = elastic ?: return false
        val view = e.view() ?: return false
        // A held plate owns the gesture: no parent may take it over (shade scroll, pager, collapse).
        // Re-asserted on every move, before the event is dispatched (a DOWN resets the flag).
        if (e.focus && !e.captured) view.parent?.requestDisallowInterceptTouchEvent(true)
        if (!e.dragging) {
            val slop = view.resources.displayMetrics.density * DRAG_SLOP_DP
            if (kotlin.math.abs(dx) <= slop && kotlin.math.abs(dy) <= slop) return false
            e.dragging = true
            // Dragging a plate never presses it: the window gets a CANCEL (no click, no long
            // press, nothing scrolls) and none of the rest of the gesture.
            if (e.focus) {
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                synthesizing = true
                try {
                    root.dispatchTouchEvent(cancel)
                } finally {
                    synthesizing = false
                    cancel.recycle()
                }
                e.captured = true
            }
        }
        e.press.drag(dx, dy)
        return e.captured
    }

    // ---------------------------------------------------------------- transforms

    private val tmp = Matrix()

    /**
     * View → window transform including animation matrices (View.transformMatrixToGlobal leaves
     * them out), so the glass of a stretched view samples the backdrop where it really is.
     * False when no ancestor is stretched: the caller uses the framework transform.
     */
    fun transformToGlobal(view: View, out: Matrix): Boolean {
        if (animating.isEmpty() && LiquidPressPhysics.controllers.isEmpty()) return false
        var any = false
        var v: View? = view
        while (v != null) {
            if (v.animationMatrix != null) { any = true; break }
            v = v.parent as? View
        }
        if (!any) return false
        out.reset()
        v = view
        while (v != null) {
            v.animationMatrix?.let { out.postConcat(it) }
            val m = v.matrix
            if (!m.isIdentity) out.postConcat(m)
            val parent = v.parent as? View
            out.postTranslate(v.left.toFloat() - (parent?.scrollX ?: 0), v.top.toFloat() - (parent?.scrollY ?: 0))
            v = parent
        }
        tmp.reset()
        return true
    }
}
