package dev.unvoid.originceiler

import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * The buttons behind a notification swiped halfway (settings, delete) as [KyantGlass] drops.
 *
 * vivo draws each button (VivoNotificationMenuItemView, a square of mIconSize) as a circle inset
 * by mIconPadding in dispatchDraw: an outer shadow, a material blur region and a light rim.
 * With the switch on those are left out and the background is glass shaped like a drop: once the
 * card uncovers a button the drop runs off the card's edge, stretched, and springs into a circle
 * with a wobble while its icon swells in; covered again, it shrinks back into the card.
 */
object KyantGlassNotificationMenu {
    private const val ITEM = "com.vivo.systemui.statusbar.notification.row.VivoNotificationMenuItemView"

    private val installed = AtomicBoolean(false)
    private val drops = Collections.synchronizedMap(WeakHashMap<View, Drop>())
    private val listenerAdded = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        val cls = runCatching { Class.forName(ITEM, false, classLoader) }.getOrNull() ?: return
        if (!installed.compareAndSet(false, true)) return
        val padding = field(cls, "mIconPadding")
        val paint = field(cls, "mPaint")
        val highlight = field(cls, "mHighLight")
        XposedBridge.hookAllMethods(cls, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val item = param.thisObject as? View ?: return
                KyantGlass.ensureConfig(item.context)
                if (listenerAdded.compareAndSet(false, true)) KyantGlass.onConfigChanged { refresh() }
                val drop = drops.getOrPut(item) { Drop(item, padding) }
                drop.attach(on())
            }
        })
        XposedBridge.hookAllMethods(cls, "onDetachedFromWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                drops[param.thisObject as? View ?: return]?.detach()
            }
        })
        // vivo's own plate: no shadow circle (a transparent paint it takes as already made) and no
        // light rim; the material region is dropped below.
        XposedBridge.hookAllMethods(cls, "dispatchDraw", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val item = param.thisObject as? View ?: return
                if (!on()) return
                runCatching {
                    val p = paint?.get(item) as? Paint
                    if (p == null) paint?.set(item, Paint().apply { color = 0 }) else if (p.alpha != 0) p.alpha = 0
                }
                runCatching { highlight?.get(item)?.let { hideHighlight(it) } }
            }
        })
        val material = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view.javaClass.name != ITEM || !on()) return
                param.result = when ((param.method as? Method)?.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        }
        View::class.java.declaredMethods
            .filter { it.name.startsWith("setMaterial") && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .forEach { runCatching { XposedBridge.hookMethod(it, material) } }
        XposedBridge.log("OriginKyantGlass: notification menu drops installed")
    }

    private fun on() = KyantGlass.config.on(KyantGlass.KEY_NOTIFICATION_BUTTONS)

    private fun refresh() {
        val enabled = on()
        synchronized(drops) { drops.values.toList() }.forEach { it.attach(enabled) }
    }

    private var hideRim: Method? = null

    /** ViewEffect.setBorderHLAlpha(int, int): both rim layers transparent. */
    private fun hideHighlight(effect: Any) {
        val m = hideRim ?: effect.javaClass.methods.firstOrNull {
            it.name == "setBorderHLAlpha" && it.parameterTypes.size == 2
        }?.also { hideRim = it } ?: return
        m.invoke(effect, 0, 0)
        m.invoke(effect, 1, 0)
    }

    private fun field(cls: Class<*>, name: String): Field? =
        runCatching { cls.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    // ---------------------------------------------------------------- one drop

    /**
     * One button. Its glass is water running off the swiped card: the card's right edge is a
     * wall, the drop (the circle vivo would draw: bounds inset by mIconPadding) sits where the
     * button is, and while the wall still cuts the circle the two are joined by concave fillets,
     * the meniscus. As soon as any of the drop shows, its corners are bent into the card ")"; the
     * fillets shrink as more of it comes out ":)" and reach zero exactly when the whole circle is
     * out, where it is free "()" with a little wobble. The rim runs along the whole outline, and a
     * thin strip drawn over the card at the joint hides the card's own edge rim there, so the
     * highlight flows from the card into the drop without a cut.
     */
    private class Drop(item: View, private val paddingField: Field?) {
        private val ref = java.lang.ref.WeakReference(item)
        private val goo = GooDrawable(joint = false)
        private val joint = GooDrawable(joint = true)
        private var jointRow: java.lang.ref.WeakReference<View>? = null

        /** Wobble after the drop frees itself: squash, spring driven. */
        private var wobble = 0f
        private var velocity = 0f
        private var lastFrame = 0L
        private var running = false
        private var free = false
        /** Card's right edge in the button's coordinates (NaN: unknown / not swiped). */
        private var edge = Float.NaN
        /** The button's origin in the row's coordinates. */
        private var originX = 0f
        private var originY = 0f
        private var observer: ViewTreeObserver? = null
        private val preDraw = ViewTreeObserver.OnPreDrawListener {
            ref.get()?.let { track(it) }
            true
        }
        private val tick = Runnable { step() }

        // Shape of the current frame (button coordinates).
        private val path = android.graphics.Path()
        private val rim = android.graphics.Path()
        private val shapeBounds = RectF()
        private var shapeVisible = false
        private var cornerFactor = 1f
        private var lens = 1f

        fun attach(enabled: Boolean) {
            val item = ref.get() ?: return
            if (enabled) {
                KyantGlass.track(item)
                if (item.background !== goo) item.background = goo
                val vto = item.viewTreeObserver
                if (observer !== vto && vto.isAlive) {
                    detachObserver()
                    vto.addOnPreDrawListener(preDraw)
                    observer = vto
                }
                track(item)
            } else {
                detachObserver()
                removeJoint()
                if (item.background === goo) item.background = null
                icon(item)?.apply { scaleX = 1f; scaleY = 1f; alpha = 1f }
            }
            item.invalidate()
        }

        fun detach() {
            detachObserver()
            removeJoint()
            wobble = 0f; velocity = 0f; running = false; free = false
        }

        private fun detachObserver() {
            observer?.let { if (it.isAlive) it.removeOnPreDrawListener(preDraw) }
            observer = null
        }

        private fun ensureJoint(row: View) {
            if (jointRow?.get() === row) return
            removeJoint()
            runCatching { row.overlay.add(joint) }
            jointRow = java.lang.ref.WeakReference(row)
        }

        private fun removeJoint() {
            jointRow?.get()?.let { row -> runCatching { row.overlay.remove(joint) } }
            jointRow = null
        }

        /** Every frame: where the card edge is; the drop frees itself once fully out. */
        private fun track(item: View) {
            val row = rowOf(item)
            val e = if (row != null && item.isShown && item.alpha > 0.05f) cardEdge(item, row) else Float.NaN
            if (row != null) {
                ensureJoint(row)
                joint.setBounds(0, 0, row.width, row.height)
            }
            if (e != edge && !(e.isNaN() && edge.isNaN())) {
                edge = e
                item.invalidate()
                joint.invalidateSelf()
            }
            val r = radius(item)
            val nowFree = !e.isNaN() && item.width / 2f - e >= r
            if (nowFree && !free) {
                // Let go of the card: a little jelly wobble.
                velocity += 7f
                startSpring(item)
            }
            free = nowFree
            applyIcon(item, r)
        }

        private fun cardEdge(item: View, row: View): Float {
            val translation = rowTranslation(row) ?: return Float.NaN
            if (translation >= -1f) return Float.NaN
            var x = item.left + item.translationX
            var y = item.top + item.translationY
            var p = item.parent as? View
            while (p != null && p !== row) {
                x += p.left + p.translationX
                y += p.top + p.translationY
                p = p.parent as? View
            }
            originX = x
            originY = y
            return row.width + translation - x
        }

        private fun startSpring(item: View) {
            if (running) return
            running = true
            lastFrame = android.os.SystemClock.uptimeMillis()
            item.postOnAnimation(tick)
        }

        private fun step() {
            val item = ref.get() ?: run { running = false; return }
            val now = android.os.SystemClock.uptimeMillis()
            var dt = ((now - lastFrame).coerceIn(1L, 48L)) / 1000f
            lastFrame = now
            val stiffness = 260f
            val damping = 2f * 0.3f * sqrt(stiffness)
            while (dt > 0f) {
                val h = minOf(dt, 0.004f)
                velocity += (-stiffness * wobble - damping * velocity) * h
                wobble += velocity * h
                dt -= h
            }
            if (kotlin.math.abs(wobble) < 0.001f && kotlin.math.abs(velocity) < 0.01f) {
                wobble = 0f
                velocity = 0f
                running = false
            }
            applyIcon(item, radius(item))
            item.invalidate()
            joint.invalidateSelf()
            if (running) item.postOnAnimation(tick)
        }

        /** The icon swells in as the drop comes out, full once it is free. */
        private fun applyIcon(item: View, r: Float) {
            val image = icon(item) ?: return
            val e = edge
            val out = if (e.isNaN()) 0f else ((item.width / 2f - e + r) / (2f * r)).coerceIn(0f, 1f)
            val q = ((out - 0.35f) / 0.65f).coerceIn(0f, 1f) * (1f + wobble * 0.08f)
            image.scaleX = q
            image.scaleY = q
            image.alpha = q.coerceIn(0f, 1f)
        }

        private fun icon(item: View): View? = (item as? ViewGroup)?.takeIf { it.childCount > 0 }?.getChildAt(0)

        private fun radius(item: View): Float {
            val pad = runCatching { paddingField?.getInt(item) }.getOrNull()?.toFloat() ?: (item.width * 0.15f)
            return ((minOf(item.width, item.height) - pad * 2f) / 2f).coerceAtLeast(1f)
        }

        /** Builds [path] (filled) and [rim] (the outline, without the joint on the wall). */
        private fun build(item: View) {
            path.rewind()
            rim.rewind()
            shapeVisible = false
            val e = edge
            if (e.isNaN()) return
            val r = radius(item)
            val cx = item.width / 2f
            val cy = item.height / 2f
            val dx = cx - e
            if (dx <= -r + 0.5f) return
            shapeVisible = true
            if (dx >= r) {
                // Free: round, squashed by the wobble.
                val rx = r * (1f + wobble * 0.18f)
                val ry = r * (1f - wobble * 0.13f)
                path.addOval(cx - rx, cy - ry, cx + rx, cy + ry, android.graphics.Path.Direction.CW)
                rim.addOval(cx - rx, cy - ry, cx + rx, cy + ry, android.graphics.Path.Direction.CW)
                cornerFactor = 1f
                lens = 1f
                return
            }
            // Meniscus: fillets as large as the drop allows early on, gone exactly when it is out.
            val t = ((r - dx) / (r * 0.75f)).coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            val rf = r * 0.6f * smooth
            val fx = e + rf
            val yfSq = (r + rf) * (r + rf) - (cx - fx) * (cx - fx)
            val yf = if (yfSq > 0f) sqrt(yfSq) else 0f
            val deg = (180.0 / Math.PI).toFloat()
            if (rf < 0.5f || yf <= rf + 0.5f) {
                // Circle cut by the card edge.
                val phi = kotlin.math.acos((-dx / r).coerceIn(-1f, 1f)) * deg
                val oval = RectF(cx - r, cy - r, cx + r, cy + r)
                path.arcTo(oval, -phi, 2f * phi, true)
                path.close()
                rim.arcTo(oval, -phi, 2f * phi, true)
            } else {
                val theta = kotlin.math.atan2(yf, cx - fx) * deg
                val phi1 = kotlin.math.atan2(-yf, fx - cx) * deg
                val top = RectF(fx - rf, cy - yf - rf, fx + rf, cy - yf + rf)
                val drop = RectF(cx - r, cy - r, cx + r, cy + r)
                val bottom = RectF(fx - rf, cy + yf - rf, fx + rf, cy + yf + rf)
                for (p in arrayOf(path, rim)) {
                    p.moveTo(e, cy - yf)
                    p.arcTo(top, 180f, theta - 180f, false)
                    p.arcTo(drop, phi1, -2f * phi1, false)
                    p.arcTo(bottom, -theta, theta - 180f, false)
                }
                path.close()
            }
            // Attached, the glass is a soft-cornered block (no lens); round and lensed once free.
            cornerFactor = 1f - 0.85f * smooth
            lens = 1f - smooth
        }

        // ------------------------------------------------------------ drawing

        /**
         * [joint] false: the button's background, the whole drop under its icon. [joint] true:
         * drawn in the row's overlay above the card, a thin strip of the same glass over the
         * card edge where the drop hangs on it, and the rim of the whole outline.
         */
        private inner class GooDrawable(private val joint: Boolean) : android.graphics.drawable.Drawable() {
            private val renderer = KyantGlass.Renderer()
            private val radii = FloatArray(4)
            private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

            override fun draw(canvas: android.graphics.Canvas) {
                val item = ref.get() ?: return
                if (!on()) return
                if (!joint) build(item)
                if (!shapeVisible) return
                KyantGlassHook.dropVivoEffect(item)
                val density = item.resources.displayMetrics.density
                val save = canvas.save()
                if (joint) canvas.translate(originX, originY)
                path.computeBounds(shapeBounds, true)
                val e = edge
                val attached = !free && !e.isNaN()
                // The joint only covers the card's edge rim, and only while the drop hangs on.
                if (!joint || attached) {
                    canvas.save()
                    if (joint) canvas.clipRect(e - 4f * density, shapeBounds.top - 1f, e + 3f * density, shapeBounds.bottom + 1f)
                    canvas.clipPath(path)
                    radii.fill(minOf(shapeBounds.width(), shapeBounds.height()) / 2f * cornerFactor)
                    val backdrop = KyantGlassHook.ShadeBackdrop.get(item)
                    renderer.draw(canvas, item, shapeBounds, radii, backdrop, highlight = false, dim = true,
                        lensScale = lens, withTouchGlow = false)
                    canvas.restore()
                    if (backdrop == null) item.postInvalidateDelayed(120L)
                }
                if (joint) {
                    // Never over the card itself, save the joint strip.
                    canvas.clipRect(if (e.isNaN()) -1e6f else e - 4f * density, -1e6f, 1e6f, 1e6f)
                    val hl = KyantGlass.config[KyantGlass.KEY_HIGHLIGHT].coerceIn(0, 100) / 100f
                    sheenPaint.strokeWidth = 5f * density
                    sheenPaint.color = android.graphics.Color.argb((hl * 0.10f * 255).toInt().coerceIn(0, 255), 255, 255, 255)
                    canvas.save()
                    canvas.clipPath(path)
                    canvas.drawPath(rim, sheenPaint)
                    canvas.restore()
                    rimPaint.strokeWidth = maxOf(0.75f * density, 1.5f)
                    rimPaint.color = android.graphics.Color.argb((hl * 0.6f * 255).toInt().coerceIn(0, 255), 255, 255, 255)
                    canvas.drawPath(rim, rimPaint)
                }
                canvas.restoreToCount(save)
            }

            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    // ---------------------------------------------------------------- the swiped row

    private fun rowOf(item: View): View? {
        var v = item.parent as? View
        var depth = 0
        while (v != null && depth < 4) {
            if (v.javaClass.simpleName == "ExpandableNotificationRow") return v
            v = v.parent as? View
            depth++
        }
        return null
    }

    private var getTranslation: Method? = null

    /** How far the card is swiped (negative: to the left). */
    private fun rowTranslation(row: View): Float? {
        val m = getTranslation ?: runCatching { row.javaClass.getMethod("getTranslation") }.getOrNull()?.also { getTranslation = it }
        m?.let { method -> runCatching { (method.invoke(row) as? Float) }.getOrNull()?.let { return it } }
        // Fallback: the content's own translation.
        val group = row as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.javaClass.simpleName == "NotificationContentView") return child.translationX
        }
        return null
    }
}
