package dev.unvoid.originceiler

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.os.SystemClock
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import android.graphics.RectF
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reworks the stock lockscreen quick tool buttons (flashlight / camera) on OriginOS into
 * iOS-style round glass buttons:
 *  - vivo's round plate becomes the same Kyant liquid glass as the shade (refracting the lock
 *    wallpaper), with vivo's own icon,
 *  - places them like iOS: inset from the screen corners,
 *  - replaces the native "swipe up" gesture with a press-and-hold (0.4s) toggle.
 *
 * Everything is done by hooking the ORIGINAL Vivo views (ShortcutIcon inside QuickToolView),
 * no custom overlay window is added.
 */
class LockscreenQuickToolGlassHook {

    fun install(classLoader: ClassLoader) {
        synchronized(installedLoaders) {
            if (installedLoaders.put(classLoader, true) != null) return
        }
        runCatching { KyantGlassTouch.install() }
        runCatching { LiquidPressPhysics.installMatrixHook() }
        classNames.forEach { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()?.let(::installClass)
        }
        if (classWatcherInstalled.compareAndSet(false, true)) {
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
        when (type.name) {
            QUICK_TOOL_CLASS -> installQuickTools(type)
            QUICK_TOOL_WRAPPER_CLASS -> installQuickToolWrapper(type)
        }
    }

    private fun installQuickTools(type: Class<*>) {
        type.declaredConstructors.forEach { constructor ->
            XposedBridge.hookMethod(constructor, object : XC_MethodHook(Int.MIN_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (enabled(view.context)) styleQuickTools(param.thisObject)
                }
            })
        }
        listOf(
            "updateLeftToolView",
            "updateRightToolView",
            "updateShortcutBackground",
            "updateLeftQuickToolVisible",
            "updateQuickToolViewAnim",
            "onConfigurationChanged"
        ).forEach { methodName ->
            XposedBridge.hookAllMethods(type, methodName, object : XC_MethodHook(Int.MIN_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (enabled(view.context)) styleQuickTools(param.thisObject)
                }
            })
        }
    }

    private fun installQuickToolWrapper(type: Class<*>) {
        XposedBridge.hookAllMethods(type, "bind", object : XC_MethodHook(Int.MIN_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                val context = findField(param.thisObject, "context") as? Context ?: return
                if (!enabled(context)) return
                findField(param.thisObject, "quickToolsView")?.let(::styleQuickTools)
            }
        })
        // Kill the native "swipe up" pipeline so the press-and-hold gesture owns the buttons.
        listOf(
            "handleTouchDown",
            "handleTouchMove",
            "handleTouchUp",
            "handleTouchCancel",
            "handleTouchMoveQuickTool",
            "handleTouchUpQuickTool"
        ).forEach { methodName ->
            XposedBridge.hookAllMethods(type, methodName, object : XC_MethodHook(Int.MAX_VALUE) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = findField(param.thisObject, "context") as? Context ?: return
                    if (enabled(context)) param.result = null
                }
            })
        }
    }

    private fun styleQuickTools(owner: Any) {
        val root = owner as? View ?: return
        val context = root.context
        if (!enabled(context)) return
        val left = findField(owner, "leftShortcut") as? ImageView ?: return
        val right = findField(owner, "rightShortcut") as? ImageView ?: return
        // The "swipe up to turn on/off" tips no longer match the new gesture.
        (findField(owner, "leftToolsTips") as? View)?.visibility = View.INVISIBLE
        (findField(owner, "rightToolsTips") as? View)?.visibility = View.INVISIBLE
        KyantGlass.ensureConfig(context)
        allowOverflow(root)
        applyButton(left, true)
        applyButton(right, false)
        if ((left.width == 0 || right.width == 0) && pendingStyles.put(owner, true) == null) {
            root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or_: Int, ob: Int) {
                    v.removeOnLayoutChangeListener(this)
                    pendingStyles.remove(owner)
                    if (enabled(context)) styleQuickTools(owner)
                }
            })
        }
    }

    private fun allowOverflow(view: View) {
        var current: View? = view
        var guard = 0
        while (current != null && guard < 4) {
            (current as? ViewGroup)?.let {
                it.clipChildren = false
                it.clipToPadding = false
            }
            current = current.parent as? View
            guard++
        }
    }

    private fun applyButton(view: ImageView, flashlight: Boolean) {
        val glass = glassByView.getOrPut(view) {
            KyantGlass.GlassDrawable(
                view, "",
                backdrop = { v -> ScreenBackdropSource.getLockBackdrop(v.context) },
                shape = ::plateShape,
                radius = { _, w, h -> minOf(w, h) / 2f }
            )
        }
        KyantGlass.track(view)
        if (view.background !== glass) view.background = glass
        sides[view] = flashlight
        place(view)

        view.isClickable = true
        view.isLongClickable = false
        view.setOnClickListener(null)
        view.setOnLongClickListener(null)
        // HyperLight ex0.k: the press controller (no drag blur), its neighbour link and the
        // shared press wave; ex0.a: the glow in the parent's overlay.
        presses.getOrPut(view) {
            LiquidPress(view).apply {
                dragBlurDp = 0f
                link = NeighborLink(linkNeighbors, 64f)
                wave = sharedWave
            }
        }
        (view.parent as? ViewGroup)?.let { parent ->
            if (glows[view]?.parentGroup !== parent) glows[view] = PressGlow(parent, view, ::iconCenter)
        }
        view.setOnTouchListener { button, event -> handleTouch(button, event, flashlight) }

        ensureEnforcer(view)
    }

    private fun ensureEnforcer(view: View) {
        if (enforcers.containsKey(view)) return
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (view.isAttachedToWindow && enabled(view.context)) {
                val glass = glassByView[view]
                if (glass != null && view.background !== glass) view.background = glass
                place(view)
            }
            true
        }
        enforcers[view] = listener
        view.viewTreeObserver.addOnPreDrawListener(listener)
    }

    /**
     * HyperLight dx0 (LockScreenBlurButtonHook): down squeezes the button and lights the glow,
     * moving past 8 dp drags it on a rubber band (glow off), and releasing after the long-press
     * timeout with the finger still on the button runs the shortcut.
     */
    private fun handleTouch(view: View, event: MotionEvent, flashlight: Boolean): Boolean {
        val press = presses[view] ?: return false
        val state = touchStates.getOrPut(view) { TouchState() }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                state.downX = event.rawX
                state.downY = event.rawY
                state.downAt = SystemClock.uptimeMillis()
                state.dragging = false
                view.parent?.requestDisallowInterceptTouchEvent(true)
                view.isPressed = true
                glows[view]?.set(true)
                press.press()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - state.downX
                val dy = event.rawY - state.downY
                val slop = view.resources.displayMetrics.density * DRAG_SLOP_DP
                if (!state.dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    state.dragging = true
                    glows[view]?.set(false)
                }
                if (state.dragging) press.drag(dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                val held = SystemClock.uptimeMillis() - state.downAt
                val dragged = state.dragging
                state.dragging = false
                view.isPressed = false
                glows[view]?.set(false)
                val long = held >= ViewConfiguration.getLongPressTimeout()
                val w = view.width
                val h = view.height
                val inside = w <= 0 || h <= 0 ||
                    hypot(event.x - w / 2.0, event.y - h / 2.0) <= maxOf(w, h) / 2.0
                when {
                    dragged -> press.releaseDrag()
                    long -> press.releaseLong()
                    else -> press.releaseTap()
                }
                if (long && inside) fire(view, flashlight)
            }
            MotionEvent.ACTION_CANCEL -> {
                state.dragging = false
                view.isPressed = false
                glows[view]?.set(false)
                press.releaseDrag()
            }
        }
        return true
    }

    /** HyperLight vy0: one launch per 800 ms, with the long-press haptic. */
    private fun fire(view: View, flashlight: Boolean) {
        if (!firing.compareAndSet(false, true)) return
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        try {
            if (flashlight) toggleFlashlight(view.context) else launchCamera(view.context)
        } finally {
            mainHandler.postDelayed({ firing.set(false) }, FIRE_GUARD_MILLIS)
        }
    }

    private class TouchState {
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        var dragging = false
    }

    /** HyperLight l30 #18: the other shortcut and the notifications follow a dragged button. */
    private val linkNeighbors = NeighborProvider { source, out ->
        synchronized(sides) { sides.keys.toList() }.forEach { if (it !== source && it.isAttachedToWindow) out += it }
        collectNotifications(source, out)
    }

    /** HyperLight l30 #19: the press wave runs through the notifications. */
    private val waveNeighbors = NeighborProvider { source, out -> collectNotifications(source, out) }

    /** HyperLight ex0.m: one wave shared by both buttons (strength 0.9). */
    private val sharedWave by lazy { PressWave(waveNeighbors, 72f, 280f).apply { gain = 0.9f } }

    private var stackRef = java.lang.ref.WeakReference<ViewGroup>(null)

    /** Rows of the notification stack (NotificationRow / MediaHeaderView), as HyperLight ex0.f. */
    private fun collectNotifications(source: View, out: MutableList<View>) {
        val stack = stackRef.get()?.takeIf { it.isAttachedToWindow }
            ?: (findStack(source.rootView, 0)?.also { stackRef = java.lang.ref.WeakReference(it) } ?: return)
        collectRows(stack, source, out)
    }

    private fun findStack(view: View?, depth: Int): ViewGroup? {
        if (view !is ViewGroup || depth > 12) return null
        if (view.javaClass.name.contains("NotificationStackScrollLayout")) return view
        for (i in 0 until view.childCount) findStack(view.getChildAt(i), depth + 1)?.let { return it }
        return null
    }

    private fun collectRows(group: View, source: View, out: MutableList<View>) {
        if (group !is ViewGroup) return
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child === source || !child.isAttachedToWindow || child.visibility != View.VISIBLE) continue
            val name = child.javaClass.name
            if (name.contains("NotificationRow") || name.contains("MediaHeaderView")) out += child else collectRows(child, source, out)
        }
    }

    private val screenLoc = IntArray(2)

    /**
     * iOS placement: the plate centre [INSET_X_DP] from its screen side and [INSET_Y_DP] above the
     * bottom, whatever vivo's layout. Done with translation from the laid-out position, so the
     * touch area moves along.
     */
    private fun place(view: View) {
        if (view.width == 0 || !view.isAttachedToWindow) return
        val left = sides[view] ?: return
        val metrics = view.resources.displayMetrics
        val density = metrics.density
        view.getLocationOnScreen(screenLoc)
        iconCenter(view, iconPoint)
        val baseX = screenLoc[0] - view.translationX + iconPoint[0]
        val baseY = screenLoc[1] - view.translationY + iconPoint[1]
        val screenW = view.rootView.width.takeIf { it > 0 } ?: metrics.widthPixels
        val screenH = view.rootView.height.takeIf { it > 0 } ?: metrics.heightPixels
        val targetX = if (left) INSET_X_DP * density else screenW - INSET_X_DP * density
        val targetY = screenH - INSET_Y_DP * density
        val tx = targetX - baseX
        val ty = targetY - baseY
        if (kotlin.math.abs(view.translationX - tx) > 0.5f) view.translationX = tx
        if (kotlin.math.abs(view.translationY - ty) > 0.5f) view.translationY = ty
    }

    private val iconPoint = FloatArray(2)
    private val shapePoint = FloatArray(2)
    private val iconRect = RectF()

    /**
     * Where vivo actually draws the icon inside the view (padding and image matrix applied):
     * the camera glyph sits off the view centre, so the plate centres on the glyph instead.
     */
    private fun iconCenter(view: View, out: FloatArray) {
        out[0] = view.width / 2f
        out[1] = view.height / 2f
        val image = view as? ImageView ?: return
        val drawable = image.drawable ?: return
        val b = drawable.bounds
        if (b.isEmpty) return
        iconRect.set(b)
        image.imageMatrix.mapRect(iconRect)
        out[0] = image.paddingLeft + iconRect.centerX()
        out[1] = image.paddingTop + iconRect.centerY()
    }

    /** HyperLight's blur button: a circle [PLATE_DP] across, centred on the icon. */
    private fun plateShape(view: View, rect: RectF): Float {
        val d = KyantGlass.config[KyantGlass.KEY_SHORTCUT_SIZE].coerceIn(36, 100) * view.resources.displayMetrics.density
        iconCenter(view, shapePoint)
        val cx = shapePoint[0]
        val cy = shapePoint[1]
        rect.set(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f)
        return d / 2f
    }

    private fun toggleFlashlight(context: Context) {
        runCatching {
            val manager = cameraManager ?: context.getSystemService(CameraManager::class.java).also {
                cameraManager = it
                initializeTorch(it)
            }
            val id = torchCameraId ?: findTorchCamera(manager).also { torchCameraId = it } ?: return
            manager.setTorchMode(id, !torchEnabled)
        }.onFailure(XposedBridge::log)
    }

    private fun initializeTorch(manager: CameraManager) {
        if (torchCallbackRegistered) return
        torchCallbackRegistered = true
        torchCameraId = findTorchCamera(manager)
        manager.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == torchCameraId) torchEnabled = enabled
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun findTorchCamera(manager: CameraManager): String? {
        return manager.cameraIdList.firstOrNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    private fun launchCamera(context: Context) {
        val secure = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        runCatching { context.startActivity(secure) }.recoverCatching {
            context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
        }.onFailure(XposedBridge::log)
    }

    private fun findField(owner: Any, name: String): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            if (field != null) return runCatching { field.get(owner) }.getOrNull()
            type = type.superclass
        }
        return null
    }

    private fun enabled(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, SETTING_ENABLED, 0) == 1
    }

    companion object {
        const val SETTING_ENABLED = "originroottoolbox_lockscreen_quicktool_glass"

        private const val QUICK_TOOL_CLASS = "com.vivo.systemuiplugin.keyguard.tools.QuickToolView"
        private const val QUICK_TOOL_WRAPPER_CLASS = "com.vivo.systemuiplugin.keyguard.legacy.keyguard.views.area.QuickToolAreaWrapper"

        private const val INSET_X_DP = 64f
        private const val INSET_Y_DP = 80f

        private const val DRAG_SLOP_DP = 8f
        private const val FIRE_GUARD_MILLIS = 800L

        private val classNames = setOf(QUICK_TOOL_CLASS, QUICK_TOOL_WRAPPER_CLASS)
        private val installedLoaders = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())
        private val installedClasses = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
        private val pendingStyles = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
        private val enforcers = Collections.synchronizedMap(WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>())
        private val glassByView = Collections.synchronizedMap(WeakHashMap<View, KyantGlass.GlassDrawable>())
        /** true = left (flashlight) button. */
        private val sides = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
        private val presses = Collections.synchronizedMap(WeakHashMap<View, LiquidPress>())
        private val glows = Collections.synchronizedMap(WeakHashMap<View, PressGlow>())
        private val touchStates = Collections.synchronizedMap(WeakHashMap<View, TouchState>())
        private val firing = AtomicBoolean(false)
        private val classWatcherInstalled = AtomicBoolean(false)
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile private var cameraManager: CameraManager? = null
        @Volatile private var torchCameraId: String? = null
        @Volatile private var torchEnabled = false
        @Volatile private var torchCallbackRegistered = false
    }
}
