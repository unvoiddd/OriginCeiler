package dev.unvoid.originceiler

import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.view.Choreographer
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.tanh

/**
 * "Pop Out buttons": iOS-style indentation of the screen edge next to the physical
 * power / volume keys while they are pressed.
 *
 * system_server: key events are observed in interceptKeyBeforeQueueing and relayed to SystemUI.
 * SystemUI: a non-touchable overlay draws a black bezel "bulge" at the button position,
 * driven by an underdamped spring so it squishes in on press and bounces out on release.
 */
object PopOutButtonsHook {
    const val SETTING_ENABLED = "originceiler_pop_out_buttons"
    const val SETTING_DEPTH = "originceiler_pop_out_depth"
    const val SETTING_VOLUME_UP_Y = "originceiler_pop_out_volume_up_y"
    const val SETTING_VOLUME_DOWN_Y = "originceiler_pop_out_volume_down_y"
    const val SETTING_POWER_Y = "originceiler_pop_out_power_y"
    const val SETTING_LEFT_SIDE = "originceiler_pop_out_left_side"
    /** 1 while the on-screen position editor is open: all bulges are held out. */
    const val SETTING_EDIT = "originceiler_pop_out_edit"

    const val DEFAULT_DEPTH = 9f
    const val DEFAULT_VOLUME_UP_Y = 27f
    const val DEFAULT_VOLUME_DOWN_Y = 35f
    const val DEFAULT_POWER_Y = 45f

    private const val ACTION_KEY = "dev.unvoid.originceiler.POP_OUT_KEY"
    private const val EXTRA_KEY = "key"
    private const val EXTRA_DOWN = "down"
    private const val SENDER_PERMISSION = "android.permission.INJECT_EVENTS"

    private const val KEY_VOLUME_UP = 0
    private const val KEY_VOLUME_DOWN = 1
    private const val KEY_POWER = 2

    // Hidden framework constants.
    private const val TYPE_SECURE_SYSTEM_OVERLAY = 2015
    private val USER_ALL: UserHandle by lazy {
        UserHandle::class.java.getField("ALL").get(null) as UserHandle
    }

    private const val EDIT_TIMEOUT_MS = 5 * 60_000L

    private val systemInstalled = AtomicBoolean(false)
    private val systemUiInstalled = AtomicBoolean(false)

    // ---------------------------------------------------------------- system_server

    fun installSystemServer(classLoader: ClassLoader) {
        if (!systemInstalled.compareAndSet(false, true)) return
        val thread = HandlerThread("OriginPopOutKeys").apply { start() }
        val handler = Handler(thread.looper)
        val lastDown = BooleanArray(3)
        var enabledCache = false
        var enabledCheckedAt = 0L
        var logBudget = 20

        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val event = param.args.firstOrNull() as? KeyEvent ?: return
                val key = when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> KEY_VOLUME_UP
                    KeyEvent.KEYCODE_VOLUME_DOWN -> KEY_VOLUME_DOWN
                    KeyEvent.KEYCODE_POWER -> KEY_POWER
                    else -> return
                }
                val down = when (event.action) {
                    KeyEvent.ACTION_DOWN -> true
                    KeyEvent.ACTION_UP -> false
                    else -> return
                }
                if (down && event.repeatCount > 0) return
                handler.post {
                    if (lastDown[key] == down) return@post
                    lastDown[key] = down
                    val context = systemContext() ?: return@post
                    val now = SystemClock.uptimeMillis()
                    if (now - enabledCheckedAt > 1500L) {
                        enabledCache = runCatching {
                            Settings.Global.getInt(context.contentResolver, SETTING_ENABLED, 0) == 1
                        }.getOrDefault(false)
                        enabledCheckedAt = now
                    }
                    if (!enabledCache && down) return@post
                    if (down && logBudget > 0) {
                        logBudget--
                        XposedBridge.log("OriginPopOut: key $key down -> SystemUI")
                    }
                    val intent = Intent(ACTION_KEY)
                        .setPackage("com.android.systemui")
                        .putExtra(EXTRA_KEY, key)
                        .putExtra(EXTRA_DOWN, down)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                    runCatching { context.sendBroadcastAsUser(intent, USER_ALL) }
                        .onFailure { XposedBridge.log("OriginPopOut: broadcast failed: $it") }
                }
            }
        }

        // Hook every layer a key passes through: the native entry in InputManagerService,
        // the WM callback and the policy itself. Duplicate events are dropped by lastDown.
        val hooked = listOf(
            "com.android.server.input.InputManagerService",
            "com.android.server.wm.InputManagerCallback",
            "com.android.server.policy.PhoneWindowManager"
        ).filter { name ->
            val cls = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: return@filter false
            runCatching { XposedBridge.hookAllMethods(cls, "interceptKeyBeforeQueueing", callback) }
                .getOrNull().orEmpty().isNotEmpty()
        }.ifEmpty { null }
        XposedBridge.log("OriginPopOut: system_server key hooks: ${hooked ?: "none"}")
    }

    private fun systemContext(): Context? = runCatching {
        val atClass = Class.forName("android.app.ActivityThread")
        val thread = atClass.getMethod("currentActivityThread").invoke(null)
        atClass.getMethod("getSystemContext").invoke(thread) as Context
    }.getOrNull()

    // ---------------------------------------------------------------- SystemUI

    fun installSystemUi() {
        if (!systemUiInstalled.compareAndSet(false, true)) return
        XposedBridge.hookAllMethods(Instrumentation::class.java, "callApplicationOnCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.args.firstOrNull() as? Application ?: return
                if (app.packageName != "com.android.systemui") return
                if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName() != "com.android.systemui") return
                runCatching { Controller(app).start() }
                    .onFailure { XposedBridge.log("OriginPopOut: SystemUI init failed: $it") }
            }
        })
    }

    private class Controller(private val context: Context) {
        private val handler = Handler(Looper.getMainLooper())
        private val windowManager = context.getSystemService(WindowManager::class.java)
        private var view: BulgeView? = null
        private var enabled = false

        fun start() {
            enabled = readEnabled()
            val filter = IntentFilter(ACTION_KEY)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (!enabled) return
                    val key = intent.getIntExtra(EXTRA_KEY, -1)
                    if (key !in 0..2) return
                    ensureView().onKey(key, intent.getBooleanExtra(EXTRA_DOWN, false))
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, SENDER_PERMISSION, handler, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter, SENDER_PERMISSION, handler)
            }

            val resolver = context.contentResolver
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    enabled = readEnabled()
                    if (!enabled) {
                        view?.reset()
                        return
                    }
                    val v = ensureView()
                    v.reloadSettings()
                    val editing = Settings.Global.getInt(context.contentResolver, SETTING_EDIT, 0) == 1
                    if (editing != v.editing) v.setEditing(editing)
                    if (editing) {
                        // Safety net: never keep the bulges out forever if the editor dies.
                        handler.removeCallbacks(editTimeout)
                        handler.postDelayed(editTimeout, EDIT_TIMEOUT_MS)
                        return
                    }
                    handler.removeCallbacks(editTimeout)
                    // Live calibration preview when a position/depth slider moves in the app.
                    when (uri?.lastPathSegment) {
                        SETTING_VOLUME_UP_Y -> v.preview(KEY_VOLUME_UP)
                        SETTING_VOLUME_DOWN_Y -> v.preview(KEY_VOLUME_DOWN)
                        SETTING_POWER_Y -> v.preview(KEY_POWER)
                        SETTING_DEPTH, SETTING_LEFT_SIDE -> {
                            v.preview(KEY_VOLUME_UP); v.preview(KEY_VOLUME_DOWN); v.preview(KEY_POWER)
                        }
                    }
                }
            }
            listOf(SETTING_ENABLED, SETTING_EDIT, SETTING_DEPTH, SETTING_VOLUME_UP_Y, SETTING_VOLUME_DOWN_Y, SETTING_POWER_Y, SETTING_LEFT_SIDE)
                .forEach { resolver.registerContentObserver(Settings.Global.getUriFor(it), false, observer) }
            XposedBridge.log("OriginPopOut: SystemUI overlay ready (enabled=$enabled)")
        }

        private val editTimeout = Runnable { view?.setEditing(false) }

        private fun readEnabled(): Boolean =
            Settings.Global.getInt(context.contentResolver, SETTING_ENABLED, 0) == 1

        private fun ensureView(): BulgeView {
            view?.let { return it }
            val v = BulgeView(context)
            v.visibility = View.GONE
            @Suppress("DEPRECATION")
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                title = "OriginPopOutButtons"
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
                if (Build.VERSION.SDK_INT >= 31) alpha = 1f
            }
            windowManager.addView(v, params)
            view = v
            return v
        }
    }

    /** One spring per key; depth is in px, 0 = flush with the bezel. */
    private class KeySpring {
        var value = 0f
        var velocity = 0f
        var target = 0f
        var pressedAt = 0L
        var releasePending = false
        val isResting get() = target == 0f && abs(value) < 0.05f && abs(velocity) < 0.5f
    }

    private class BulgeView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val path = Path()
        private val springs = Array(3) { KeySpring() }
        private val centers = FloatArray(3)
        private var depthDp = DEFAULT_DEPTH
        private var leftSide = false
        private var lastFrameNanos = 0L
        private var running = false
        var editing = false
            private set
        private val frameCallback = Choreographer.FrameCallback { now -> onFrame(now) }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
            reloadSettings()
        }

        fun reloadSettings() {
            val r = context.contentResolver
            fun f(key: String, def: Float) =
                runCatching { Settings.Global.getString(r, key)?.toFloatOrNull() }.getOrNull() ?: def
            depthDp = f(SETTING_DEPTH, DEFAULT_DEPTH).coerceIn(2f, 30f)
            centers[KEY_VOLUME_UP] = f(SETTING_VOLUME_UP_Y, DEFAULT_VOLUME_UP_Y).coerceIn(0f, 100f) / 100f
            centers[KEY_VOLUME_DOWN] = f(SETTING_VOLUME_DOWN_Y, DEFAULT_VOLUME_DOWN_Y).coerceIn(0f, 100f) / 100f
            centers[KEY_POWER] = f(SETTING_POWER_Y, DEFAULT_POWER_Y).coerceIn(0f, 100f) / 100f
            leftSide = Settings.Global.getInt(r, SETTING_LEFT_SIDE, 0) == 1
            invalidate()
        }

        fun setEditing(on: Boolean) {
            editing = on
            for (s in springs) {
                s.releasePending = false
                s.target = if (on) 1f else 0f
                if (on) s.velocity += 4f
            }
            kick()
        }

        fun onKey(key: Int, down: Boolean) {
            if (editing) return
            val s = springs[key]
            val now = SystemClock.uptimeMillis()
            if (down) {
                s.target = 1f
                s.pressedAt = now
                s.releasePending = false
                // A small initial kick makes the press feel snappy, like the button hitting the glass.
                s.velocity += 4f
            } else {
                // Guarantee a visible squish even for very short taps.
                if (now - s.pressedAt < MIN_PRESS_MS) {
                    s.releasePending = true
                } else {
                    s.target = 0f
                }
            }
            kick()
        }

        fun preview(key: Int) {
            if (editing) return
            onKey(key, true)
            postDelayed({ onKey(key, false) }, 260L)
        }

        fun reset() {
            editing = false
            springs.forEach { it.value = 0f; it.velocity = 0f; it.target = 0f; it.releasePending = false }
            running = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            visibility = GONE
        }

        private fun kick() {
            if (visibility != VISIBLE) visibility = VISIBLE
            if (!running) {
                running = true
                lastFrameNanos = 0L
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }

        private fun onFrame(nowNanos: Long) {
            val dt = if (lastFrameNanos == 0L) 1f / 120f else ((nowNanos - lastFrameNanos) / 1e9f).coerceIn(0.001f, 0.034f)
            lastFrameNanos = nowNanos
            val nowMs = SystemClock.uptimeMillis()
            var allResting = true
            for (s in springs) {
                if (s.releasePending && nowMs - s.pressedAt >= MIN_PRESS_MS) {
                    s.releasePending = false
                    s.target = 0f
                }
                // Press: stiff and slightly bouncy. Release: softer with a jelly overshoot.
                val pressing = s.target > 0f
                val stiffness = if (pressing) 900f else 520f
                val damping = if (pressing) 0.62f else 0.38f
                val c = 2f * damping * kotlin.math.sqrt(stiffness)
                // Semi-implicit Euler with sub-steps for stability at high refresh rates.
                val steps = 4
                val h = dt / steps
                repeat(steps) {
                    val accel = -stiffness * (s.value - s.target) - c * s.velocity
                    s.velocity += accel * h
                    s.value += s.velocity * h
                }
                if (s.isResting) {
                    s.value = 0f; s.velocity = 0f
                } else {
                    allResting = false
                }
            }
            invalidate()
            if (allResting) {
                running = false
                visibility = GONE
            } else {
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val rotation = display?.rotation ?: Surface.ROTATION_0
            // Long side of the natural (portrait) orientation; buttons are mapped from it.
            val natural = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) w else h
            val depthPx = depthDp * density

            for (key in 0..2) {
                val s = springs[key]
                if (s.value <= 0.01f) continue
                val amount = s.value
                val buttonLen = (if (key == KEY_POWER) 24f else 20f) * density * (0.85f + 0.15f * amount.coerceAtMost(1.2f))
                val center = centers[key] * natural
                val depth = depthPx * amount
                val softness = (6f + 4f * amount.coerceIn(0f, 1f)) * density
                buildBulge(center, buttonLen, depth, softness, natural)
                canvas.save()
                mapToRotation(canvas, rotation, w, h)
                canvas.drawPath(path, paint)
                canvas.restore()
            }
        }

        /**
         * Builds the bulge in "edge space": x is the distance inward from the button edge,
         * y runs along the edge in natural orientation. Profile = tanh plateau, which gives the
         * flared, liquid-looking neck where the bulge meets the bezel.
         */
        private fun buildBulge(center: Float, halfLen: Float, depth: Float, softness: Float, length: Float) {
            path.rewind()
            val a = center - halfLen
            val b = center + halfLen
            val start = (a - softness * 3.2f).coerceAtLeast(0f)
            val end = (b + softness * 3.2f).coerceAtMost(length)
            val steps = 48
            path.moveTo(-1f, start)
            for (i in 0..steps) {
                val y = start + (end - start) * i / steps
                val profile = 0.5f * (tanh((y - a) / softness) - tanh((y - b) / softness))
                // Slight rounding on top so the tip looks like a pill, not a flat plateau.
                val dome = 1f - 0.12f * sq((y - center) / (halfLen + softness))
                val x = depth * profile.coerceAtLeast(0f) * dome.coerceAtLeast(0f)
                path.lineTo(x, y)
            }
            path.lineTo(-1f, end)
            path.close()
        }

        /** Maps edge space (natural right/left edge) onto the current screen rotation. */
        private fun mapToRotation(canvas: Canvas, rotation: Int, w: Float, h: Float) {
            // Natural-portrait canvas: right edge at x = nw, y downwards.
            when (rotation) {
                Surface.ROTATION_0 -> {}
                Surface.ROTATION_90 -> { canvas.translate(0f, h); canvas.rotate(-90f) }
                Surface.ROTATION_180 -> { canvas.translate(w, h); canvas.rotate(180f) }
                Surface.ROTATION_270 -> { canvas.translate(w, 0f); canvas.rotate(90f) }
            }
            val naturalWidth = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) h else w
            if (leftSide) {
                // Left edge: x grows to the right already.
                return
            }
            canvas.translate(naturalWidth, 0f)
            canvas.scale(-1f, 1f)
        }

        private fun sq(v: Float) = v * v

        companion object {
            private const val MIN_PRESS_MS = 110L
        }
    }
}
