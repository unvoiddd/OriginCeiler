package dev.unvoid.originceiler

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.view.ViewOutlineProvider
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Shake to fall": shaking the phone on the home screen turns every icon, folder, widget and
 * dock icon into a rigid body that falls with the real gravity vector. Shake again (or double tap
 * empty space, press Home, leave the launcher) and everything flies back into place.
 */
class ShakePhysicsHook {

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (!isLauncher(activity)) return
                controllerFor(activity).onResume()
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onPause", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (!isLauncher(activity)) return
                controller?.takeIf { it.activity.get() === activity }?.onPause()
            }
        })
        // Home press while already on the launcher arrives as a new intent, not a pause.
        XposedBridge.hookAllMethods(Activity::class.java, "performNewIntent", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (!isLauncher(activity)) return
                controller?.takeIf { it.activity.get() === activity }?.restore(animated = true)
            }
        })
        XposedBridge.log("OriginShake: launcher hooks installed")
    }

    private fun isLauncher(activity: Activity) =
        activity.packageName == LAUNCHER_PACKAGE && activity.javaClass.name == "com.bbk.launcher2.Launcher"

    private fun controllerFor(activity: Activity): Controller {
        controller?.let { if (it.activity.get() === activity) return it; it.release() }
        return Controller(activity).also { controller = it }
    }

    // ------------------------------------------------------------------ controller

    private class Controller(host: Activity) : SensorEventListener {
        val activity = WeakReference(host)
        private val sensorManager = host.getSystemService(SensorManager::class.java)
        private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        private var listening = false
        private var testObserverRegistered = false
        private var overlay: PhysicsOverlay? = null
        private var returning: PhysicsOverlay? = null

        // Screen-space apparent acceleration in m/s^2 (x right, y down).
        val accel = FloatArray(2).also { it[1] = SensorManager.GRAVITY_EARTH }
        private var peakCount = 0
        private var firstPeakAt = 0L
        private var lastPeakAt = 0L
        private var lastToggleAt = 0L

        fun onResume() {
            val act = activity.get() ?: return
            if (!isEnabled(act)) {
                stopSensors()
                restore(animated = false)
                return
            }
            if (!testObserverRegistered) {
                testObserverRegistered = true
                // Debug trigger: `settings put global originceiler_shake_physics_test <n>` toggles the drop.
                act.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor(SETTING_TEST), false,
                    object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            if (listening) toggle()
                        }
                    }
                )
            }
            if (!listening && accelerometer != null) {
                listening = sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        fun onPause() {
            stopSensors()
            // Swipe-up / recents gestures pause the launcher while it is still on screen,
            // so fly home with the same animation instead of snapping.
            restore(animated = true)
        }

        fun release() {
            stopSensors()
            restore(animated = false)
        }

        private fun stopSensors() {
            if (listening) sensorManager?.unregisterListener(this)
            listening = false
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        override fun onSensorChanged(event: SensorEvent) {
            val fx = event.values[0]
            val fy = event.values[1]
            val fz = event.values[2]
            // Bodies feel -f in the device frame; device y points up, screen y points down.
            accel[0] = accel[0] * 0.35f + (-fx) * 0.65f
            accel[1] = accel[1] * 0.35f + fy * 0.65f
            detectShake(sqrt(fx * fx + fy * fy + fz * fz) / SensorManager.GRAVITY_EARTH)
        }

        private fun detectShake(gForce: Float) {
            val now = SystemClock.uptimeMillis()
            if (gForce < SHAKE_THRESHOLD_G) return
            if (now - lastPeakAt < 90L) return
            if (now - firstPeakAt > SHAKE_WINDOW_MS) {
                firstPeakAt = now
                peakCount = 0
            }
            lastPeakAt = now
            peakCount++
            if (peakCount >= SHAKE_PEAKS && now - lastToggleAt > SHAKE_COOLDOWN_MS) {
                peakCount = 0
                lastToggleAt = now
                toggle()
            }
        }

        private fun toggle() {
            if (overlay != null) restore(animated = true) else drop()
        }

        private fun drop() {
            val act = activity.get() ?: return
            if (!act.hasWindowFocus() || returning != null) return
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
            val views = collectViews(act)
            if (views.isEmpty()) return
            val o = PhysicsOverlay(act, this)
            content.addView(o, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val origin = IntArray(2).also { content.getLocationOnScreen(it) }
            val res = act.resources
            val workspace = act.findViewById<View>(res.getIdentifier("workspace", "id", LAUNCHER_PACKAGE))
            o.unclip(views, content, workspace)
            views.forEach { o.addBody(it, origin) }
            o.start()
            overlay = o
            o.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        fun restore(animated: Boolean) {
            val o = overlay
            if (o == null) {
                // A fly-home already in progress must still finish instantly when asked to.
                if (!animated) returning?.let { it.detach(); returning = null }
                return
            }
            overlay = null
            if (animated) {
                returning = o
                o.flyHome {
                    o.detach()
                    if (returning === o) returning = null
                }
            } else {
                o.detach()
            }
        }

        /** Icons, folders and widgets of the visible workspace page plus the dock icons. */
        private fun collectViews(act: Activity): List<View> {
            val res = act.resources
            fun id(name: String) = res.getIdentifier(name, "id", LAUNCHER_PACKAGE)
            val result = ArrayList<View>()
            val screenWidth = res.displayMetrics.widthPixels
            val loc = IntArray(2)

            act.findViewById<ViewGroup>(id("workspace"))?.let { workspace ->
                for (i in 0 until workspace.childCount) {
                    val page = workspace.getChildAt(i) as? ViewGroup ?: continue
                    if (page.visibility != View.VISIBLE) continue
                    page.getLocationOnScreen(loc)
                    val center = loc[0] + page.width / 2
                    if (center !in 0..screenWidth) continue
                    itemContainer(page)?.let { addItems(it, result) }
                }
            }
            act.findViewById<ViewGroup>(id("hotseat"))?.let { hotseat ->
                for (i in 0 until hotseat.childCount) {
                    val child = hotseat.getChildAt(i) as? ViewGroup ?: continue
                    if (!child.javaClass.name.contains("CellLayout")) continue
                    itemContainer(child)?.let { addItems(it, result) }
                }
            }
            return result
        }

        private fun itemContainer(cellLayout: ViewGroup): ViewGroup? {
            for (i in 0 until cellLayout.childCount) {
                val child = cellLayout.getChildAt(i)
                if (child is ViewGroup && child.javaClass.name.endsWith("ShortcutAndWidgetContainer")) return child
            }
            return null
        }

        private fun addItems(container: ViewGroup, out: MutableList<View>) {
            for (i in 0 until container.childCount) {
                val v = container.getChildAt(i)
                if (v.visibility != View.VISIBLE || v.alpha < 0.05f || v.width <= 0 || v.height <= 0) continue
                out += v
            }
        }
    }

    // ------------------------------------------------------------------ physics

    private class Body(
        val view: View,
        val halfW: Float,
        val halfH: Float,
        val homeX: Float,
        val homeY: Float
    ) {
        // Original transform, restored when the pile flies home.
        val tx0 = view.translationX
        val ty0 = view.translationY
        val rot0 = view.rotation
        // Offset from the pivot to the visual center (scaled), used to rotate around the center.
        val dx = (view.width / 2f - view.pivotX) * view.scaleX
        val dy = (view.height / 2f - view.pivotY) * view.scaleY

        /** Moves the real view so its visual center sits at (x, y) rotated by angle. */
        fun apply() {
            val c = cos(angle)
            val s = sin(angle)
            val rdx = dx * c - dy * s
            val rdy = dx * s + dy * c
            view.translationX = tx0 + (x - homeX) + dx - rdx
            view.translationY = ty0 + (y - homeY) + dy - rdy
            view.rotation = rot0 + angle * 180f / PI.toFloat()
        }

        fun reset() {
            view.translationX = tx0
            view.translationY = ty0
            view.rotation = rot0
        }

        var x = homeX
        var y = homeY
        var vx = 0f
        var vy = 0f
        var angle = 0f
        var omega = 0f
        var invMass = 0f
        var invInertia = 0f
        var radius = 0f            // collision circle radius
        var offsets = FloatArray(0) // circle centers along the long local axis
        var alongX = true
        var bound = 0f              // bounding radius for the broad phase

        fun setup(density: Float) {
            val w = halfW * 2f
            val h = halfH * 2f
            val area = (w * h) / (density * density * 1000f)
            invMass = 1f / area
            invInertia = 1f / (area * (w * w + h * h) / 12f)
            val long = max(w, h)
            val short = min(w, h)
            alongX = w >= h
            if (long / short < 1.25f) {
                // Nearly square (icons with labels, square folders): one rounded body.
                radius = long / 2f * 0.86f
                offsets = floatArrayOf(0f)
            } else {
                radius = short / 2f
                val n = ceil(long / short).toInt().coerceAtLeast(2)
                val span = long / 2f - radius
                offsets = FloatArray(n) { i -> -span + 2f * span * i / (n - 1) }
            }
            bound = hypot(halfW, halfH)
        }

        fun circleX(i: Int): Float {
            val o = offsets[i]
            return if (alongX) x + cos(angle) * o else x - sin(angle) * o
        }

        fun circleY(i: Int): Float {
            val o = offsets[i]
            return if (alongX) y + sin(angle) * o else y + cos(angle) * o
        }

        fun contains(px: Float, py: Float): Boolean {
            val dx = px - x
            val dy = py - y
            val c = cos(-angle)
            val s = sin(-angle)
            val lx = dx * c - dy * s
            val ly = dx * s + dy * c
            return abs(lx) <= halfW && abs(ly) <= halfH
        }
    }

    private class PhysicsOverlay(context: Context, private val controller: Controller) : View(context) {
        private val density = resources.displayMetrics.density
        private val bodies = ArrayList<Body>()
        private var running = false
        private var lastFrame = 0L
        private var flyingHome = false
        private var lastHaptic = 0L

        private var grabbed: Body? = null
        private var grabX = 0f
        private var grabY = 0f
        private var grabLocalX = 0f
        private var grabLocalY = 0f

        private val frame = Choreographer.FrameCallback { now -> onFrame(now) }

        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (bodies.none { it.contains(e.x, e.y) }) {
                    controller.restore(animated = true)
                    return true
                }
                return false
            }
        })

        private val clipState = ArrayList<Triple<ViewGroup, BooleanArray, Any?>>()
        private var raisedView: View? = null
        private var raisedZ = 0f
        private var raisedOutline: ViewOutlineProvider? = null

        /**
         * Lets the real views leave their cells: disables clipping on every ancestor and lifts the
         * workspace above the dock so falling icons are not hidden behind the dock blur.
         */
        fun unclip(views: List<View>, root: ViewGroup, workspace: View?) {
            val seen = HashSet<ViewGroup>()
            for (v in views) {
                var p = v.parent
                while (p is ViewGroup) {
                    if (seen.add(p)) {
                        clipState += Triple(p, booleanArrayOf(p.clipChildren, p.clipToPadding, p.clipToOutline), null)
                        p.clipChildren = false
                        p.clipToPadding = false
                        p.clipToOutline = false
                    }
                    if (p === root) break
                    p = p.parent
                }
            }
            if (workspace != null) {
                raisedView = workspace
                raisedZ = workspace.translationZ
                raisedOutline = workspace.outlineProvider
                workspace.outlineProvider = null
                workspace.translationZ = raisedZ + 2f
            }
        }

        fun addBody(view: View, origin: IntArray) {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val w = view.width * view.scaleX
            val h = view.height * view.scaleY
            if (w < 2f || h < 2f) return
            val body = Body(
                view, w / 2f, h / 2f,
                loc[0] - origin[0] + w / 2f, loc[1] - origin[1] + h / 2f
            )
            body.setup(density)
            // Tiny random spin so the pile never looks scripted.
            body.omega = ((Math.random() - 0.5) * 2.0).toFloat()
            body.vy = -(Math.random() * 120.0).toFloat() * density
            bodies += body
        }

        fun start() {
            running = true
            lastFrame = 0L
            Choreographer.getInstance().postFrameCallback(frame)
        }

        fun detach() {
            running = false
            Choreographer.getInstance().removeFrameCallback(frame)
            bodies.forEach { it.reset() }
            bodies.clear()
            for ((group, flags, _) in clipState) {
                group.clipChildren = flags[0]
                group.clipToPadding = flags[1]
                group.clipToOutline = flags[2]
            }
            clipState.clear()
            raisedView?.let {
                it.translationZ = raisedZ
                it.outlineProvider = raisedOutline
            }
            raisedView = null
            (parent as? ViewGroup)?.removeView(this)
        }

        fun flyHome(onEnd: () -> Unit) {
            if (flyingHome) return
            flyingHome = true
            grabbed = null
            val startX = bodies.map { it.x }
            val startY = bodies.map { it.y }
            val startA = bodies.map { normalizeAngle(it.angle) }
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 520L
                interpolator = OvershootInterpolator(1.1f)
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    bodies.forEachIndexed { i, b ->
                        // Stagger: lower bodies start a touch later, like they are being pulled back.
                        b.x = startX[i] + (b.homeX - startX[i]) * t
                        b.y = startY[i] + (b.homeY - startY[i]) * t
                        b.angle = startA[i] * (1f - t)
                        b.apply()
                    }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
                })
                start()
            }
        }

        private fun normalizeAngle(a: Float): Float {
            var r = a % (2f * PI.toFloat())
            if (r > PI) r -= 2f * PI.toFloat()
            if (r < -PI) r += 2f * PI.toFloat()
            return r
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (flyingHome) return true
            gestures.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grabbed = bodies.lastOrNull { it.contains(event.x, event.y) }
                    grabbed?.let { b ->
                        val dx = event.x - b.x
                        val dy = event.y - b.y
                        val c = cos(-b.angle)
                        val s = sin(-b.angle)
                        grabLocalX = dx * c - dy * s
                        grabLocalY = dx * s + dy * c
                        // Bring to front.
                        bodies.remove(b); bodies.add(b)
                    }
                    grabX = event.x
                    grabY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    grabX = event.x
                    grabY = event.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> grabbed = null
            }
            return true
        }

        private fun onFrame(now: Long) {
            if (!running) return
            val dt = if (lastFrame == 0L) 1f / 120f else ((now - lastFrame) / 1e9f).coerceIn(0.002f, 0.033f)
            lastFrame = now
            if (!flyingHome) {
                val sub = 3
                repeat(sub) { step(dt / sub) }
                bodies.forEach { it.apply() }
            }
            Choreographer.getInstance().postFrameCallback(frame)
        }

        private fun step(dt: Float) {
            val scale = PX_PER_METER * density
            val gx = controller.accel[0] * scale
            val gy = controller.accel[1] * scale
            val g = grabbed

            for (b in bodies) {
                if (b === g) {
                    // Spring the grabbed point toward the finger; keeps it physical when thrown.
                    val c = cos(b.angle)
                    val s = sin(b.angle)
                    val px = b.x + grabLocalX * c - grabLocalY * s
                    val py = b.y + grabLocalX * s + grabLocalY * c
                    val k = 28f
                    b.vx = (grabX - px) * k
                    b.vy = (grabY - py) * k
                    b.omega *= 0.85f
                } else {
                    b.vx += gx * dt
                    b.vy += gy * dt
                }
                b.vx *= 0.9995f
                b.vy *= 0.9995f
                b.omega *= 0.997f
                b.x += b.vx * dt
                b.y += b.vy * dt
                b.angle += b.omega * dt
            }

            repeat(SOLVER_ITERATIONS) {
                for (i in bodies.indices) {
                    val a = bodies[i]
                    for (j in i + 1 until bodies.size) {
                        val b = bodies[j]
                        val dx = b.x - a.x
                        val dy = b.y - a.y
                        val reach = a.bound + b.bound
                        if (dx * dx + dy * dy > reach * reach) continue
                        collideBodies(a, b)
                    }
                    collideWalls(a)
                }
            }
        }

        private fun collideBodies(a: Body, b: Body) {
            for (i in a.offsets.indices) {
                val ax = a.circleX(i)
                val ay = a.circleY(i)
                for (j in b.offsets.indices) {
                    val bx = b.circleX(j)
                    val by = b.circleY(j)
                    val dx = bx - ax
                    val dy = by - ay
                    val r = a.radius + b.radius
                    val d2 = dx * dx + dy * dy
                    if (d2 >= r * r || d2 < 1e-4f) continue
                    val d = sqrt(d2)
                    val nx = dx / d
                    val ny = dy / d
                    val pen = r - d
                    val px = ax + nx * (a.radius - pen / 2f)
                    val py = ay + ny * (a.radius - pen / 2f)
                    resolve(a, b, px, py, nx, ny, pen)
                }
            }
        }

        private fun collideWalls(b: Body) {
            val w = width.toFloat()
            val h = height.toFloat()
            for (i in b.offsets.indices) {
                val cx = b.circleX(i)
                val cy = b.circleY(i)
                val r = b.radius
                if (cy + r > h) resolve(null, b, cx, h, 0f, -1f, cy + r - h)
                if (cy - r < 0f) resolve(null, b, cx, 0f, 0f, 1f, r - cy)
                if (cx + r > w) resolve(null, b, w, cy, -1f, 0f, cx + r - w)
                if (cx - r < 0f) resolve(null, b, 0f, cy, 1f, 0f, r - cx)
            }
        }

        /** Impulse resolution; n points from a to b. a == null means a static wall. */
        private fun resolve(a: Body?, b: Body, px: Float, py: Float, nx: Float, ny: Float, pen: Float) {
            val invMA = a?.invMass ?: 0f
            val invIA = a?.invInertia ?: 0f
            val invMB = b.invMass
            val invIB = b.invInertia
            val rax = if (a != null) px - a.x else 0f
            val ray = if (a != null) py - a.y else 0f
            val rbx = px - b.x
            val rby = py - b.y

            val vax = (a?.vx ?: 0f) - (a?.omega ?: 0f) * ray
            val vay = (a?.vy ?: 0f) + (a?.omega ?: 0f) * rax
            val vbx = b.vx - b.omega * rby
            val vby = b.vy + b.omega * rbx
            val rvx = vbx - vax
            val rvy = vby - vay
            val vn = rvx * nx + rvy * ny

            if (vn < 0f) {
                val raCn = rax * ny - ray * nx
                val rbCn = rbx * ny - rby * nx
                val denom = invMA + invMB + raCn * raCn * invIA + rbCn * rbCn * invIB
                if (denom > 0f) {
                    val impactSpeed = -vn / density
                    val restitution = if (impactSpeed > 220f) RESTITUTION else 0f
                    val jn = -(1f + restitution) * vn / denom
                    applyImpulse(a, b, rax, ray, rbx, rby, nx * jn, ny * jn)
                    if (impactSpeed > 900f) tick()

                    // Coulomb friction along the tangent.
                    var tx = rvx - vn * nx
                    var ty = rvy - vn * ny
                    val tl = hypot(tx, ty)
                    if (tl > 1e-3f) {
                        tx /= tl; ty /= tl
                        val raCt = rax * ty - ray * tx
                        val rbCt = rbx * ty - rby * tx
                        val denomT = invMA + invMB + raCt * raCt * invIA + rbCt * rbCt * invIB
                        if (denomT > 0f) {
                            var jt = -(rvx * tx + rvy * ty) / denomT
                            val maxF = FRICTION * jn
                            jt = jt.coerceIn(-maxF, maxF)
                            applyImpulse(a, b, rax, ray, rbx, rby, tx * jt, ty * jt)
                        }
                    }
                }
            }

            // Positional correction so stacks don't sink into each other.
            val total = invMA + invMB
            if (total > 0f) {
                val corr = max(pen - 0.5f * density, 0f) / total * 0.45f
                if (a != null) { a.x -= nx * corr * invMA; a.y -= ny * corr * invMA }
                b.x += nx * corr * invMB
                b.y += ny * corr * invMB
            }
        }

        private fun applyImpulse(a: Body?, b: Body, rax: Float, ray: Float, rbx: Float, rby: Float, jx: Float, jy: Float) {
            if (a != null) {
                a.vx -= jx * a.invMass
                a.vy -= jy * a.invMass
                a.omega -= (rax * jy - ray * jx) * a.invInertia
            }
            b.vx += jx * b.invMass
            b.vy += jy * b.invMass
            b.omega += (rbx * jy - rby * jx) * b.invInertia
        }

        private fun tick() {
            val now = SystemClock.uptimeMillis()
            if (now - lastHaptic < 70L) return
            lastHaptic = now
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    companion object {
        const val SETTING_ENABLED = "originceiler_shake_physics"
        private const val SETTING_TEST = "originceiler_shake_physics_test"
        private const val LAUNCHER_PACKAGE = "com.bbk.launcher2"

        private const val SHAKE_THRESHOLD_G = 2.1f
        private const val SHAKE_PEAKS = 3
        private const val SHAKE_WINDOW_MS = 700L
        private const val SHAKE_COOLDOWN_MS = 1200L

        // ~600 px (dp) per meter keeps a 15 cm screen fall feeling weighty but quick.
        private const val PX_PER_METER = 600f
        private const val SOLVER_ITERATIONS = 6
        private const val RESTITUTION = 0.28f
        private const val FRICTION = 0.45f

        private val installed = AtomicBoolean(false)
        private var controller: Controller? = null

        fun isEnabled(context: Context) =
            Settings.Global.getInt(context.contentResolver, SETTING_ENABLED, 0) == 1
    }
}
