package dev.unvoid.originceiler

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Liquid press physics ported from HyperLight 1.2.2 (LiquidGlass build): the press / drag
 * controller of its lockscreen glass buttons and the neighbour effects around them.
 *
 *  - [Spring]: step response of a damped spring (damping ratio, angular frequency).
 *  - [LiquidPress]: press squeezes the element, dragging moves it with a rubber band and
 *    stretches it along the drag, releasing springs it back (tap, long press and drag each with
 *    their own spring).
 *  - [NeighborLink]: while dragging, nearby elements are pulled along, weighted by distance.
 *  - [PressWave]: pressing / releasing sends a delayed spring wave through nearby elements.
 *  - [PressGlow]: the white radial glow over a pressed button.
 *
 * Unlike the original, the pressed element is transformed through View.setAnimationMatrix
 * ([LiquidPress.useAnimationMatrix]), so vivo's own scale / translation animations and our
 * layout translations stay untouched.
 */
object LiquidPressPhysics {
    /** HyperLight p42: 1 - e^(-ζωt)(cos ω_d t + ζω/ω_d sin ω_d t), critically damped for ζ ≥ 1. */
    class Spring(damping: Float, frequency: Float) : Interpolator {
        private val zeta = damping.coerceIn(0.2f, 1.2f)
        private val omega = frequency.coerceIn(2f, 24f)

        override fun getInterpolation(t: Float): Float {
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            val wt = t * omega
            if (zeta >= 1f) return 1f - ((wt + 1.0) * exp(-wt.toDouble())).toFloat()
            val wd = (omega * sqrt(1.0 - zeta * zeta)).toFloat()
            val decay = exp((-zeta * wt).toDouble()).toFloat()
            val phase = (t * wd).toDouble()
            return 1f - decay * (cos(phase) + (zeta * omega / wd) * sin(phase)).toFloat()
        }
    }

    val PRESS = Spring(0.85f, 8f)
    val TAP_RELEASE = Spring(0.32f, 7f)
    val LONG_RELEASE = Spring(0.5f, 8f)
    val DRAG_RETURN = Spring(0.55f, 8f)
    val STRETCH_RETURN = Spring(0.5f, 8f)
    val BLUR_OUT = DecelerateInterpolator(2.4f)

    /** Pressed element → its controller (the wave leaves elements alone while they animate). */
    val controllers: MutableMap<View, LiquidPress> = Collections.synchronizedMap(WeakHashMap())

    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * vivo drives View.setAnimationMatrix itself (shade expansion, press effects): its last matrix
     * per view, so ours is composed on top of it instead of the two overwriting each other.
     */
    val external: MutableMap<View, Matrix> = Collections.synchronizedMap(WeakHashMap())
    private val ownWrite = ThreadLocal.withInitial { false }
    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Clip / layer values vivo set on a lifted view meanwhile (method name → argument). */
    val desired: MutableMap<View, MutableMap<String, Any?>> = WeakHashMap()

    /** Views whose clipping / layers are lifted while an element over them is displaced. */
    val unclipped: MutableSet<View> = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap()))

    fun installMatrixHook() {
        if (!installed.compareAndSet(false, true)) return
        // vivo turns a hardware layer, outline clipping or child clipping back on during its own
        // press animation: while lifted, such calls are turned into "no clipping".
        val keepOpen = object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view !in unclipped) return
                // What vivo wanted is what goes back on once the element is at rest (restoring
                // the values from before, e.g. ahead of a notification expanding, clipped rows).
                val wanted = param.args[0].let { if (it is android.graphics.Rect) android.graphics.Rect(it) else it }
                synchronized(desired) { desired.getOrPut(view) { HashMap() }[param.method.name] = wanted }
                when (param.method.name) {
                    "setLayerType" -> param.args[0] = View.LAYER_TYPE_NONE
                    "setClipToOutline", "setClipChildren", "setClipToPadding" -> param.args[0] = false
                    "setClipBounds" -> param.args[0] = null
                }
            }
        }
        for (name in arrayOf("setLayerType", "setClipToOutline", "setClipBounds")) {
            de.robv.android.xposed.XposedBridge.hookAllMethods(View::class.java, name, keepOpen)
        }
        for (name in arrayOf("setClipChildren", "setClipToPadding")) {
            de.robv.android.xposed.XposedBridge.hookAllMethods(ViewGroup::class.java, name, keepOpen)
        }
        de.robv.android.xposed.XposedBridge.hookAllMethods(View::class.java, "setAnimationMatrix", object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (ownWrite.get()) return
                val view = param.thisObject as? View ?: return
                val press = controllers[view] ?: return
                val m = param.args.firstOrNull() as? Matrix
                if (m == null) external.remove(view) else external[view] = Matrix(m)
                if (press.busy()) param.args[0] = press.composed(m)
            }
        })
    }

    /** Sets [matrix] (already composed) without being taken for vivo's own. */
    fun write(view: View, matrix: Matrix?) {
        ownWrite.set(true)
        try {
            view.animationMatrix = matrix
        } finally {
            ownWrite.set(false)
        }
    }

    /** Centre of [view] in window coordinates, scaled like HyperLight (scale ≤ 0 counts as 1). */
    fun center(view: View, out: FloatArray): Boolean {
        if (!view.isAttachedToWindow) return false
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val sx = if (view.scaleX <= 0f) 1f else view.scaleX
        val sy = if (view.scaleY <= 0f) 1f else view.scaleY
        out[0] = loc[0] + view.width * sx / 2f
        out[1] = loc[1] + view.height * sy / 2f
        return true
    }
}

/** Source of the elements around a pressed one. */
/**
 * Undoing a multiplicative scale while the owner animated it too leaves a residue (0.994): a
 * neighbour at rest within 1 % of its natural size goes back to exactly 1.
 */
internal fun snapScale(view: View) {
    if (view.scaleX != 1f && abs(view.scaleX - 1f) < 0.01f) view.scaleX = 1f
    if (view.scaleY != 1f && abs(view.scaleY - 1f) < 0.01f) view.scaleY = 1f
}

fun interface NeighborProvider {
    fun collect(source: View, out: MutableList<View>)
}

/** HyperLight sk1: neighbours follow a dragged element. */
class NeighborLink(private val provider: NeighborProvider, weightRadiusDp: Float = 64f) {
    private class Node(view: View) {
        val ref = WeakReference(view)
        var tx = 0f; var ty = 0f; var appliedTx = 0f; var appliedTy = 0f
        var sx = 1f; var sy = 1f; var appliedSx = 1f; var appliedSy = 1f
        var distance = 1f; var weight = 0f; var dirX = 0f; var dirY = 0f
        var width = 1; var height = 1
    }

    private val weightRadius = if (weightRadiusDp > 0f) weightRadiusDp else 64f
    private val maxRadius = 360f
    private val follow = 0.3f
    private val squash = true
    private val candidates = ArrayList<View>(6)
    private val nodes = ArrayList<Node>(6)
    private var density = 3f
    var captured = false
        private set
    private var settled = true
    private var recapture = false
    private var smoothIn = 0f
    private var smoothOut = 0f
    private var lastFrame = 0L
    private var sourceRef = WeakReference<View>(null)
    private val src = FloatArray(2)
    private val pos = FloatArray(2)

    fun capture(source: View?) {
        release()
        if (source == null || !LiquidPressPhysics.center(source, src)) return
        sourceRef = WeakReference(source)
        provider.collect(source, candidates)
        density = source.resources?.displayMetrics?.density ?: 3f
        val weightPx = weightRadius * density
        val maxPx = maxRadius * density
        for (candidate in candidates) {
            if (nodes.size >= 16) break
            if (candidate === source || !candidate.isAttachedToWindow || candidate.visibility != View.VISIBLE) continue
            if (nodes.any { it.ref.get() === candidate }) continue
            if (!LiquidPressPhysics.center(candidate, pos)) continue
            val dist = hypot(pos[0] - src[0], pos[1] - src[1])
            if (dist > maxPx) continue
            val node = Node(candidate)
            node.width = max(1, candidate.width)
            node.height = max(1, candidate.height)
            node.distance = max(1f, dist)
            node.weight = weightPx / (weightPx + dist)
            if (dist > 1f) {
                node.dirX = (pos[0] - src[0]) / dist
                node.dirY = (pos[1] - src[1]) / dist
            }
            nodes += node
        }
        candidates.clear()
        captured = true
        settled = true
        recapture = nodes.isEmpty()
    }

    /** Neighbours still displaced (a release animation must run). */
    fun displaced(): Boolean = nodes.any {
        abs(it.appliedTx) >= 0.05f || abs(it.appliedTy) >= 0.05f || abs(it.appliedSx - 1f) >= 0.0002f || abs(it.appliedSy - 1f) >= 0.0002f
    }

    /** Asymmetric smoothing: 25 ms towards a bigger target, 160 ms back. */
    private fun approach(current: Float, target: Float): Float {
        val d = target - current
        if (abs(d) < 0.02f) return target
        return current + d * (if (abs(target) < abs(current)) smoothOut else smoothIn)
    }

    fun update(dx: Float, dy: Float) {
        if (!captured) return
        if (recapture && (dx != 0f || dy != 0f)) {
            recapture = false
            val source = sourceRef.get()
            if (source != null && nodes.isEmpty()) {
                capture(source)
                if (nodes.isNotEmpty()) recapture = false
            }
        }
        if (settled && dx == 0f && dy == 0f) return
        val now = SystemClock.uptimeMillis()
        var dt = if (lastFrame == 0L) 16L else now - lastFrame
        lastFrame = now
        dt = dt.coerceIn(1L, 40L)
        smoothIn = 1f - exp(-dt / 25f)
        smoothOut = 1f - exp(-dt / 160f)
        var moved = false
        for (node in nodes) {
            val view = node.ref.get() ?: continue
            val along = dx * node.dirX + dy * node.dirY
            val projected = (1f - follow) * node.weight * along
            val direct = follow * node.weight
            var s = if (squash) -along / node.distance * node.weight else 0f
            val limit = min(density * 6f * node.weight / max(1f, (node.width + node.height) * 0.5f), 0.06f)
            s = s.coerceIn(-limit, limit)
            val targetTx = node.dirX * projected + dx * direct
            val targetTy = node.dirY * projected + dy * direct
            val targetSx = s * node.dirX * node.dirX + 1f
            val targetSy = s * node.dirY * node.dirY + 1f
            val nx = approach(node.tx, targetTx)
            val ny = approach(node.ty, targetTy)
            val nsx = approach(node.sx, targetSx)
            val nsy = approach(node.sy, targetSy)
            if (abs(nx - node.tx) < 0.05f && abs(ny - node.ty) < 0.05f && abs(nsx - node.sx) < 0.0002f && abs(nsy - node.sy) < 0.0002f) continue
            node.tx = nx; node.ty = ny; node.sx = nsx; node.sy = nsy
            var changed = false
            if (abs(node.tx - node.appliedTx) >= 0.05f) {
                view.translationX += node.tx - node.appliedTx
                node.appliedTx = node.tx
                changed = true
            }
            if (abs(node.ty - node.appliedTy) >= 0.05f) {
                view.translationY += node.ty - node.appliedTy
                node.appliedTy = node.ty
                changed = true
            }
            if (abs(node.sx - node.appliedSx) >= 0.0002f) {
                view.scaleX = view.scaleX * node.sx / node.appliedSx
                node.appliedSx = node.sx
                changed = true
            }
            if (abs(node.sy - node.appliedSy) >= 0.0002f) {
                view.scaleY = view.scaleY * node.sy / node.appliedSy
                node.appliedSy = node.sy
                changed = true
            }
            if (changed) moved = true
        }
        if (moved) settled = false else if (dx == 0f && dy == 0f) settled = true
    }

    /** Takes back everything applied to the neighbours. */
    fun release() {
        for (node in nodes) {
            val view = node.ref.get() ?: continue
            if (abs(node.appliedTx) >= 0.05f) view.translationX -= node.appliedTx
            if (abs(node.appliedTy) >= 0.05f) view.translationY -= node.appliedTy
            if (abs(node.appliedSx - 1f) >= 0.0002f) view.scaleX /= node.appliedSx
            if (abs(node.appliedSy - 1f) >= 0.0002f) view.scaleY /= node.appliedSy
            snapScale(view)
        }
        nodes.clear()
        candidates.clear()
        captured = false
        settled = true
        recapture = false
        lastFrame = 0L
        sourceRef = WeakReference(null)
    }
}

/**
 * HyperLight tl1: a press sends a wave through nearby elements. Each neighbour is a damped spring
 * (ω 12.6, ζ 0.4) driven by the press depth as it was [delay] ago (delay grows with distance, like
 * a wave front), plus impulses on press / release. Only offsets it applied itself are undone.
 */
class PressWave(private val provider: NeighborProvider, weightRadiusDp: Float = 72f, reachDp: Float = 280f) {
    private class Node(view: View) {
        val ref = WeakReference(view)
        var x = 0f; var y = 0f; var appliedX = 0f; var appliedY = 0f
        var scale = 0f; var appliedSx = 1f; var appliedSy = 1f
        var vx = 0f; var vy = 0f; var vs = 0f
        var targetX = 0f; var targetY = 0f; var targetScale = 1f
        var weight = 0f; var strength = 1f; var dirX = 0f; var dirY = 0f; var delayMs = 8f
    }

    var gain = 1f
    private val weightRadius = if (weightRadiusDp > 0f) weightRadiusDp else 52f
    private val reach = if (reachDp > 0f) reachDp else 128f
    private val omega = 12.6f
    private val zeta = 0.4f
    private val candidates = ArrayList<View>(8)
    private val nodes = ArrayList<Node>(8)
    private val history = FloatArray(32)
    private val historyAt = LongArray(32)
    private var historyCount = 0
    private var density = 3f
    private var active = false
    private var running = false
    /** Current press depth of the source (1 - its scale). */
    var depth = 0f
    private var generation = 0
    private var lastFrame = 0L
    private var sourceRef = WeakReference<View>(null)
    private val src = FloatArray(2)
    private val pos = FloatArray(2)

    private val frame = Runnable { step() }

    fun capture(source: View?) {
        reset()
        if (source == null || !LiquidPressPhysics.center(source, src)) return
        sourceRef = WeakReference(source)
        provider.collect(source, candidates)
        density = source.resources?.displayMetrics?.density ?: 3f
        val weightPx = weightRadius * density
        val reachPx = reach * density
        for (candidate in candidates) {
            if (nodes.size >= 20) break
            if (candidate === source) continue
            if (nodes.any { it.ref.get() === candidate }) continue
            if (!candidate.isAttachedToWindow || candidate.visibility != View.VISIBLE) continue
            if (!LiquidPressPhysics.center(candidate, pos)) continue
            val dist = hypot(pos[0] - src[0], pos[1] - src[1])
            if (dist > reachPx) continue
            val node = Node(candidate)
            node.weight = weightPx / (weightPx + max(1f, dist))
            node.delayMs = (max(1f, dist) / (density * 1.55f)).coerceIn(8f, 260f)
            if (dist > 1f) {
                node.dirX = (pos[0] - src[0]) / dist
                node.dirY = (pos[1] - src[1]) / dist
            }
            nodes += node
        }
        candidates.clear()
        active = true
        depth = 0f
        historyCount = 0
        lastFrame = 0L
    }

    /** A kick of [impulse] through every neighbour, arriving after its delay. */
    fun impulse(impulse: Float) {
        if (!active || impulse == 0f) return
        val gen = generation
        val view = sourceRef.get()
        for (node in nodes) {
            if (node.ref.get() == null) continue
            val amount = node.weight * impulse
            if (node.delayMs.toLong() <= 8L || view == null) {
                kick(node, amount)
            } else {
                val weak = WeakReference(node)
                view.postDelayed({
                    if (gen == generation && active) weak.get()?.let { kick(it, amount); schedule() }
                }, node.delayMs.toLong())
            }
        }
        schedule()
    }

    private fun kick(node: Node, amount: Float) {
        val a = amount * gain * node.strength
        node.vx += node.dirX * a * 8.5f * density
        node.vy += node.dirY * a * 8.5f * density
        node.vs -= a * 0.035f
    }

    fun schedule() {
        if (running || nodes.isEmpty()) return
        val view = sourceRef.get() ?: return
        running = true
        lastFrame = 0L
        view.postOnAnimation(frame)
    }

    private fun depthAt(time: Long): Float {
        if (historyCount == 0) return 0f
        val newest = (historyCount - 1).mod(32)
        if (historyAt[newest] <= time) return history[newest]
        var i = historyCount - 1
        while (i >= 0 && i > historyCount - 33) {
            val k = i.mod(32)
            if (historyAt[k] <= time) return history[k]
            i--
        }
        return 0f
    }

    private fun step() {
        val source = sourceRef.get()
        if (!active || source == null) {
            running = false
            return
        }
        val now = SystemClock.uptimeMillis()
        val dtMs = (if (lastFrame == 0L) 16L else now - lastFrame).coerceIn(1L, 40L)
        lastFrame = now
        history[historyCount.mod(32)] = depth
        historyAt[historyCount.mod(32)] = now
        historyCount++
        val dt = dtMs / 1000f
        val k = omega * omega
        val c = 2f * zeta * omega
        var moving = false
        for (node in nodes) {
            val view = node.ref.get() ?: continue
            // Elements pressed or animating on their own keep still.
            val own = LiquidPressPhysics.controllers[view]
            if (view.isPressed || (own != null && own.busy())) {
                if (node.appliedX != 0f || node.appliedY != 0f || node.appliedSx != 1f || node.appliedSy != 1f) undo(node, view)
                clear(node)
                continue
            }
            val d = depthAt(now - node.delayMs.toLong()) * gain * node.strength
            node.targetX = node.dirX * d * node.weight * 1.05f * 24f * density
            node.targetY = node.dirY * d * node.weight * 1.05f * 24f * density
            node.targetScale = 1f - d * node.weight * 0.78f
            node.vx += (-k * (node.x - node.targetX) - c * node.vx) * dt
            node.vy += (-k * (node.y - node.targetY) - c * node.vy) * dt
            node.vs += (-k * (node.scale - (node.targetScale - 1f)) - c * node.vs) * dt
            node.x += node.vx * dt
            node.y += node.vy * dt
            node.scale += node.vs * dt
            val eps = density * 0.0008f
            val still = abs(node.x - node.targetX) < eps && abs(node.y - node.targetY) < eps &&
                abs(node.scale - (node.targetScale - 1f)) < 0.0008f && abs(node.vx) < eps && abs(node.vy) < eps &&
                abs(node.vs) < 0.0008f && abs(node.targetX) < 0.05f && abs(node.targetY) < 0.05f && abs(node.targetScale - 1f) < 0.0002f
            if (still) clear(node) else moving = true
            if (abs(node.x - node.appliedX) >= 0.05f) {
                view.translationX += node.x - node.appliedX
                node.appliedX = node.x
            }
            if (abs(node.y - node.appliedY) >= 0.05f) {
                view.translationY += node.y - node.appliedY
                node.appliedY = node.y
            }
            val s = node.scale + 1f
            if (abs(s - node.appliedSx) >= 0.0002f) {
                view.scaleX = view.scaleX * s / node.appliedSx
                node.appliedSx = s
            }
            if (abs(s - node.appliedSy) >= 0.0002f) {
                view.scaleY = view.scaleY * s / node.appliedSy
                node.appliedSy = s
            }
        }
        if (moving || depth != 0f) source.postOnAnimation(frame) else running = false
    }

    private fun undo(node: Node, view: View) {
        if (abs(node.appliedX) >= 0.05f) view.translationX -= node.appliedX
        if (abs(node.appliedY) >= 0.05f) view.translationY -= node.appliedY
        if (abs(node.appliedSx - 1f) >= 0.0002f) view.scaleX /= node.appliedSx
        if (abs(node.appliedSy - 1f) >= 0.0002f) view.scaleY /= node.appliedSy
        snapScale(view)
    }

    private fun clear(node: Node) {
        node.x = 0f; node.y = 0f; node.appliedX = 0f; node.appliedY = 0f
        node.scale = 0f; node.appliedSx = 1f; node.appliedSy = 1f
        node.vx = 0f; node.vy = 0f; node.vs = 0f
        node.targetX = 0f; node.targetY = 0f; node.targetScale = 1f
    }

    fun reset() {
        generation++
        sourceRef.get()?.removeCallbacks(frame)
        for (node in nodes) {
            node.ref.get()?.let { undo(node, it) }
            clear(node)
        }
        nodes.clear()
        candidates.clear()
        active = false
        running = false
        depth = 0f
        historyCount = 0
        lastFrame = 0L
        sourceRef = WeakReference(null)
    }
}

/**
 * HyperLight gk1: the press / drag controller.
 * [pressScale] squeeze on press, [stretch] / [squash] along / across the drag, [resistance] of
 * the rubber band, [dragBlurDp] blur while dragged (0 = none).
 */
class LiquidPress(view: View) {
    private val ref = WeakReference(view)
    var pressScale = 0.88f
    var stretch = 0.32f
    var squash = 0.16f
    var resistance = 0.15f
    var dragBlurDp = 14f
    var link: NeighborLink? = null
        set(value) {
            if (field === value) return
            field?.release()
            field = value
        }
    var wave: PressWave? = null
        set(value) {
            if (field === value) return
            field?.reset()
            field = value
        }
    /**
     * Transform through View.setAnimationMatrix (our own views) instead of adding our share to
     * the view's scale / translation (vivo's views: vivo rewrites their animation matrix every
     * frame from the framework, where a hook cannot reach).
     */
    var useAnimationMatrix = true
    /** What this controller has added to a view's scale / translation. */
    private class Share { var tx = 0f; var ty = 0f; var sx = 1f; var sy = 1f }
    private val shares = WeakHashMap<View, Share>()

    /**
     * Views moved together with the element (property mode): the icon and labels on a glass
     * plate. They scale around the plate's centre, so the item squeezes as one.
     */
    var followers: List<View> = emptyList()
        set(value) {
            field = value
            val host = ref.get() ?: return
            for (f in value) {
                f.pivotX = host.left + host.width / 2f - f.left
                f.pivotY = host.top + host.height / 2f - f.top
            }
        }

    private fun applyShare(v: View, sx: Float, sy: Float) {
        val sh = shares.getOrPut(v) { Share() }
        if (abs(tx - sh.tx) >= 0.01f) { v.translationX += tx - sh.tx; sh.tx = tx }
        if (abs(ty - sh.ty) >= 0.01f) { v.translationY += ty - sh.ty; sh.ty = ty }
        if (abs(sx - sh.sx) >= 0.0001f) { v.scaleX = v.scaleX * sx / sh.sx; sh.sx = sx }
        if (abs(sy - sh.sy) >= 0.0001f) { v.scaleY = v.scaleY * sy / sh.sy; sh.sy = sy }
        if (sx == 1f && sy == 1f) snapScale(v)
    }

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var stretchX = 1f
    private var stretchY = 1f
    private var blur = 0f
    private var blurApplied = false
    private var scaleAnim: ValueAnimator? = null
    private var moveAnim: ValueAnimator? = null
    private var stretchAnim: ValueAnimator? = null
    private var blurAnim: ValueAnimator? = null
    private val matrix = Matrix()
    private var appliedBlur = -1f

    /**
     * A squeezed / stretched element may draw past its parents: lift their clipping while it is
     * displaced, or the glass is cut off by invisible frames.
     */

    /** One clipping ancestor's state before we lifted it. */
    private class Clip(val view: View, val children: Boolean, val padding: Boolean, val outline: Boolean,
                       val bounds: android.graphics.Rect?, val layer: Int)

    private val clips = ArrayList<Clip>(6)

    /**
     * Everything that would cut a squeezed / stretched element off while it is displaced: the
     * element's and its parents' clip bounds, outline clipping, child clipping and hardware layers
     * (a layer is the size of its view: vivo's tile view clips its plate and icon with it).
     * Restored once the element is back at rest.
     */
    /** Lift parents' clipping while displaced (off for notification rows: vivo animates it). */
    var liftClipping = true

    private fun unclip(view: View) {
        if (!liftClipping || clips.isNotEmpty()) return
        var v: View? = view
        var depth = 0
        while (v != null && depth <= 5) {
            val g = v as? ViewGroup
            clips += Clip(v, g?.clipChildren ?: false, g?.clipToPadding ?: false, v.clipToOutline, v.clipBounds, v.layerType)
            if (v.clipBounds != null) v.clipBounds = null
            if (depth > 0 && v.clipToOutline) v.clipToOutline = false
            if (v.layerType != View.LAYER_TYPE_NONE) v.setLayerType(View.LAYER_TYPE_NONE, null)
            if (g != null && depth > 0) {
                g.clipChildren = false
                g.clipToPadding = false
            }
            LiquidPressPhysics.unclipped += v
            v = v.parent as? View
            depth++
        }
    }

    private fun restoreClipIfIdle() {
        if (busy() || stretchX != 1f || stretchY != 1f || blurAnim != null || clips.isEmpty()) return
        for (c in clips) LiquidPressPhysics.unclipped -= c.view
        for (c in clips) {
            val wanted = synchronized(LiquidPressPhysics.desired) { LiquidPressPhysics.desired.remove(c.view) } ?: emptyMap()
            val g = c.view as? ViewGroup
            if (g != null) {
                g.clipChildren = wanted["setClipChildren"] as? Boolean ?: c.children
                g.clipToPadding = wanted["setClipToPadding"] as? Boolean ?: c.padding
            }
            val outline = wanted["setClipToOutline"] as? Boolean ?: c.outline
            if (c.view.clipToOutline != outline) c.view.clipToOutline = outline
            val bounds = if (wanted.containsKey("setClipBounds")) wanted["setClipBounds"] as? android.graphics.Rect else c.bounds
            if (bounds != c.view.clipBounds) c.view.clipBounds = bounds
            val layer = wanted["setLayerType"] as? Int ?: c.layer
            if (layer != c.view.layerType) c.view.setLayerType(layer, null)
        }
        clips.clear()
    }

    init {
        LiquidPressPhysics.controllers[view] = this
    }

    /** Our transform on top of [external] (vivo's own animation matrix, if any). */
    fun composed(external: Matrix?): Matrix {
        val view = ref.get()
        val w = view?.width ?: 0
        val h = view?.height ?: 0
        matrix.reset()
        matrix.setScale(scale * stretchX, scale * stretchY, w / 2f, h / 2f)
        matrix.postTranslate(tx, ty)
        if (external != null) matrix.preConcat(external)
        return Matrix(matrix)
    }

    /** Animating or displaced (the wave keeps its hands off). */
    fun busy() = scaleAnim != null || moveAnim != null || stretchAnim != null || scale != 1f || tx != 0f || ty != 0f

    private fun apply() {
        val view = ref.get()
        link?.let { if (it.captured) it.update(tx, ty) }
        wave?.let {
            it.depth = 1f - scale
            it.schedule()
        }
        view ?: return
        val sx = scale * stretchX
        val sy = scale * stretchY
        if (useAnimationMatrix) {
            val ext = LiquidPressPhysics.external[view]
            if (sx == 1f && sy == 1f && tx == 0f && ty == 0f) {
                // Back to whatever vivo had set.
                if (view.animationMatrix != ext) LiquidPressPhysics.write(view, ext)
            } else {
                LiquidPressPhysics.write(view, composed(ext))
            }
        } else {
            // Only our share of the views' own scale / translation.
            applyShare(view, sx, sy)
            for (f in followers) applyShare(f, sx, sy)
        }
        if (blur > 0f) {
            setBlur(view, blur)
            blurApplied = true
        } else if (blurApplied) {
            setBlur(view, 0f)
        }
    }

    /** HyperLight ux0: quantised to 1.5 px so a slow drag does not rebuild the effect each frame. */
    private fun setBlur(view: View, radius: Float) {
        val r = if (radius > 0f) ceil(radius / 1.5f) * 1.5f else 0f
        if (r == appliedBlur) return
        appliedBlur = r
        view.setRenderEffect(if (r > 0f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null)
    }

    private fun clearBlur() {
        val view = ref.get() ?: return
        blur = 0f
        if (!blurApplied) return
        blurApplied = false
        setBlur(view, 0f)
    }

    private fun cancelMoves() {
        moveAnim?.let { moveAnim = null; it.cancel() }
        stretchAnim?.let { stretchAnim = null; it.cancel() }
    }

    private fun animateScale(from: Float, to: Float, duration: Long, interpolator: Interpolator): ValueAnimator =
        ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { scale = it.animatedValue as Float; apply() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (scaleAnim === animation) scaleAnim = null
                    restoreClipIfIdle()
                }
            })
        }

    /** Finger down. */
    fun press() {
        scaleAnim?.let { scaleAnim = null; it.cancel() }
        blurAnim?.let { blurAnim = null; it.cancel() }
        blur = 0f
        val view = ref.get()
        view?.let(::unclip)
        link?.capture(view)
        wave?.let {
            it.capture(view)
            it.impulse(0.45f)
        }
        apply()
        scaleAnim = animateScale(scale, pressScale, 380L, LiquidPressPhysics.PRESS).also { it.start() }
    }

    /** Finger moved by ([dx], [dy]) since down (past the touch slop). */
    fun drag(dx: Float, dy: Float) {
        val view = ref.get() ?: return
        val w = max(1f, view.width.toFloat())
        val h = max(1f, view.height.toFloat())
        var mx = (1f - 1f / (abs(dx) * resistance / w + 1f)) * w
        if (dx < 0f) mx = -mx
        var my = (1f - 1f / (abs(dy) * resistance / h + 1f)) * h
        if (dy < 0f) my = -my
        cancelMoves()
        tx = mx
        ty = my
        val fx = (mx / w).coerceIn(-1f, 1f)
        val fy = (my / h).coerceIn(-1f, 1f)
        val horizontal = abs(fx) >= abs(fy)
        val d = if (horizontal) fx * fx else fy * fy
        stretchX = if (horizontal) 1f + stretch * d else 1f - squash * d
        stretchY = if (horizontal) 1f - squash * d else 1f + stretch * d
        val pull = min(1f, hypot(mx, my) / max(w, h))
        blurAnim?.let { blurAnim = null; it.cancel() }
        blur = dragBlurDp * view.resources.displayMetrics.density * pull
        apply()
    }

    /** Short tap released: squeeze fully, then bounce back. */
    fun releaseTap() {
        scaleAnim?.let { scaleAnim = null; it.cancel() }
        scale = pressScale
        apply()
        wave?.impulse(1f)
        scaleAnim = animateScale(pressScale, 1f, 520L, LiquidPressPhysics.TAP_RELEASE).also { it.start() }
        fadeBlur()
    }

    /** Long press released. */
    fun releaseLong() {
        scaleAnim?.let { scaleAnim = null; it.cancel() }
        wave?.impulse(0.55f)
        scaleAnim = animateScale(scale, 1f, 420L, LiquidPressPhysics.LONG_RELEASE).also { it.start() }
        fadeBlur()
    }

    /** Drag ended or cancelled: displacement and stretch spring back, then the long release. */
    fun releaseDrag() {
        cancelMoves()
        val neighbors = link?.displaced() == true
        if (abs(tx) > 0.01f || abs(ty) > 0.01f || neighbors) {
            val x0 = tx
            val y0 = ty
            moveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (neighbors) 520L else 460L
                interpolator = LiquidPressPhysics.DRAG_RETURN
                addUpdateListener {
                    val v = it.animatedValue as Float
                    tx = x0 * (1f - v)
                    ty = y0 * (1f - v)
                    apply()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (moveAnim !== animation) return
                        moveAnim = null
                        tx = 0f
                        ty = 0f
                        apply()
                        if (neighbors) link?.release()
                        if (blurAnim == null) clearBlur()
                        restoreClipIfIdle()
                    }
                })
                start()
            }
        }
        if (abs(stretchX - 1f) > 0.001f || abs(stretchY - 1f) > 0.001f) {
            val sx0 = stretchX
            val sy0 = stretchY
            stretchAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420L
                interpolator = LiquidPressPhysics.STRETCH_RETURN
                addUpdateListener {
                    val v = it.animatedValue as Float
                    stretchX = LiquidPressPhysics.lerp(sx0, 1f, v)
                    stretchY = LiquidPressPhysics.lerp(sy0, 1f, v)
                    apply()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (stretchAnim !== animation) return
                        stretchAnim = null
                        stretchX = 1f
                        stretchY = 1f
                        apply()
                        restoreClipIfIdle()
                    }
                })
                start()
            }
        }
        releaseLong()
    }

    private fun fadeBlur() {
        blurAnim?.let { blurAnim = null; it.cancel() }
        if (blur <= 0.01f) {
            blur = 0f
            apply()
            clearBlur()
            return
        }
        blurAnim = ValueAnimator.ofFloat(blur, 0f).apply {
            duration = 240L
            interpolator = LiquidPressPhysics.BLUR_OUT
            addUpdateListener { blur = it.animatedValue as Float; apply() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (blurAnim !== animation) return
                    blurAnim = null
                    blur = 0f
                    apply()
                    clearBlur()
                }
            })
            start()
        }
    }

    /** Everything back to rest at once. */
    fun reset() {
        scaleAnim?.let { scaleAnim = null; it.cancel() }
        cancelMoves()
        blurAnim?.let { blurAnim = null; it.cancel() }
        scale = 1f; tx = 0f; ty = 0f; stretchX = 1f; stretchY = 1f; blur = 0f
        apply()
        link?.release()
        wave?.reset()
        clearBlur()
        restoreClipIfIdle()
    }
}

/**
 * HyperLight ex0.a + bx0: a white radial glow (70 % → 40 % → 0) laid over a pressed button in its
 * parent's overlay. Fades in to 0.7 and swells to 1.5× while held, fades out with a little
 * overshoot when released.
 */
class PressGlow(private val parent: ViewGroup, private val button: View, private val center: (View, FloatArray) -> Unit) {
    val parentGroup: ViewGroup get() = parent
    private class GlowView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cachedW = -1
        private var cachedH = -1

        override fun onDraw(canvas: Canvas) {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            if (w != cachedW || h != cachedH) {
                cachedW = w
                cachedH = h
                paint.shader = RadialGradient(w / 2f, h / 2f, min(w, h) / 2f, COLORS, STOPS, Shader.TileMode.CLAMP)
            }
            canvas.drawCircle(w / 2f, h / 2f, min(w, h) / 2f, paint)
        }

        companion object {
            val COLORS = intArrayOf(0xB3FFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF)
            val STOPS = floatArrayOf(0f, 0.5f, 1f)
        }
    }

    private var glow: GlowView? = null
    private var sizeScale = 1f
    private var scaleAnim: ValueAnimator? = null
    private val point = FloatArray(2)

    fun set(pressed: Boolean) {
        val view = glow ?: GlowView(parent.context).apply {
            isClickable = false
            isFocusable = false
            alpha = 0f
            translationZ = 100f
            this@PressGlow.parent.overlay.add(this)
            glow = this
        }
        layout(view, if (pressed) 1f else sizeScale)
        view.bringToFront()
        scaleAnim?.cancel()
        view.animate().cancel()
        view.animate()
            .alpha(if (pressed) 0.7f else 0f)
            .setDuration(if (pressed) 400L else 220L)
            .setInterpolator(if (pressed) DECELERATE else OVERSHOOT)
            .start()
        val from = if (pressed) 1f else sizeScale
        val to = if (pressed) 1.5f else 1f
        scaleAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = if (pressed) 700L else 220L
            interpolator = if (pressed) LinearInterpolator() else OVERSHOOT
            addUpdateListener {
                sizeScale = it.animatedValue as Float
                layout(view, sizeScale)
            }
            start()
        }
    }

    private fun layout(view: View, scale: Float) {
        val size = Math.round(max(button.width, button.height) * scale)
        if (size <= 0) return
        center(button, point)
        var cx = button.left + button.translationX + point[0]
        var cy = button.top + button.translationY + point[1]
        val p = button.parent
        if (p is View && p !== parent) {
            cx += p.left
            cy += p.top
        }
        val l = Math.round(cx - size / 2f)
        val t = Math.round(cy - size / 2f)
        view.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
        view.layout(l, t, l + size, t + size)
    }

    companion object {
        private val DECELERATE = DecelerateInterpolator(1.5f)
        private val OVERSHOOT = OvershootInterpolator(0.5f)
    }
}
