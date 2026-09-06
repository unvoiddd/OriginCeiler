package dev.unvoid.originceiler

import android.content.Intent
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.core.graphics.PathParser
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class MaterialOriginOsLockscreenHook {
    fun install(classLoader: ClassLoader) {
        synchronized(installedLoaders) {
            if (installedLoaders.put(classLoader, true) != null) return
        }
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
            KEYGUARD_VIEW_CLASS -> installKeyguardView(type)
            TIME_CONTAINER_CLASS -> installTimeContainer(type)
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
        listOf("updateLeftToolView", "updateRightToolView").forEach { methodName ->
            XposedBridge.hookAllMethods(type, methodName, object : XC_MethodHook(Int.MIN_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = (param.thisObject as? View)?.context ?: return
                    if (!enabled(context)) return
                    styleQuickTools(param.thisObject)
                }
            })
        }
        listOf("updateShortcutBackground", "updateLeftQuickToolVisible", "updateQuickToolViewAnim").forEach { methodName ->
            XposedBridge.hookAllMethods(type, methodName, object : XC_MethodHook(Int.MIN_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (enabled(view.context)) styleQuickTools(param.thisObject)
                }
            })
        }
        XposedBridge.hookAllMethods(type, "onConfigurationChanged", object : XC_MethodHook(Int.MIN_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (enabled(view.context)) styleQuickTools(param.thisObject)
            }
        })
    }

    private fun installQuickToolWrapper(type: Class<*>) {
        XposedBridge.hookAllMethods(type, "bind", object : XC_MethodHook(Int.MIN_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                val context = findField(param.thisObject, "context") as? Context ?: return
                if (!enabled(context)) return
                findField(param.thisObject, "quickToolsView")?.let(::styleQuickTools)
            }
        })
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
        (findField(owner, "frameLeft") as? View)?.background = null
        (findField(owner, "frameRight") as? View)?.background = null
        (findField(owner, "leftToolsTips") as? View)?.visibility = View.INVISIBLE
        (findField(owner, "rightToolsTips") as? View)?.visibility = View.INVISIBLE
        styleQuickButton(left, true)
        styleQuickButton(right, false)
        if ((left.width == 0 || right.width == 0) && pendingQuickStyles.put(owner, true) == null) {
            root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    view.removeOnLayoutChangeListener(this)
                    pendingQuickStyles.remove(owner)
                    if (enabled(context)) styleQuickTools(owner)
                }
            })
        }
    }

    private fun styleQuickButton(view: ImageView, flashlight: Boolean) {
        val context = view.context
        val colors = MaterialOriginOsLockscreenPalette.resolve(context)
        val drawable = ExpressiveAffordanceDrawable(if (flashlight) FLASHLIGHT_PATH else CAMERA_PATH)
        drawable.setTintList(ColorStateList.valueOf(colors.foreground))
        view.setImageDrawable(drawable)
        view.imageTintList = ColorStateList.valueOf(colors.foreground)
        val background = quickButtonBackgrounds[view] ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }.also { quickButtonBackgrounds[view] = it }
        background.setColor(colors.background)
        view.background = background
        view.backgroundTintList = ColorStateList.valueOf(colors.background)
        quickButtonColors[view] = colors
        val size = minOf(view.width.takeIf { it > 0 } ?: view.layoutParams?.width ?: 0, view.height.takeIf { it > 0 } ?: view.layoutParams?.height ?: 0)
        val padding = if (size > 0) (size * 0.28f).toInt() else (12f * context.resources.displayMetrics.density).toInt()
        view.setPadding(padding, padding, padding, padding)
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.visibility = View.VISIBLE
        view.alpha = 1f
        if (pressedButtons[view] != true) {
            view.scaleX = QUICK_BUTTON_BASE_SCALE
            view.scaleY = QUICK_BUTTON_BASE_SCALE
        }
        view.translationY = -6f * context.resources.displayMetrics.density
        view.isClickable = true
        view.isLongClickable = false
        view.setOnClickListener(null)
        view.setOnTouchListener { button, event -> handleQuickButtonTouch(button, event, flashlight) }
        ensureQuickButtonEnforcer(view)
    }

    private fun ensureQuickButtonEnforcer(view: ImageView) {
        if (quickButtonEnforcers.containsKey(view)) return
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (view.isAttachedToWindow && enabled(view.context)) {
                val colors = MaterialOriginOsLockscreenPalette.resolve(view.context)
                val background = quickButtonBackgrounds[view]
                if (
                    quickButtonColors[view] != colors ||
                    background == null ||
                    view.background !== background ||
                    view.backgroundTintList?.defaultColor != colors.background ||
                    view.imageTintList?.defaultColor != colors.foreground
                ) {
                    val target = background ?: GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        quickButtonBackgrounds[view] = this
                    }
                    target.setColor(colors.background)
                    view.background = target
                    view.backgroundTintList = ColorStateList.valueOf(colors.background)
                    view.drawable?.setTintList(ColorStateList.valueOf(colors.foreground))
                    view.imageTintList = ColorStateList.valueOf(colors.foreground)
                    quickButtonColors[view] = colors
                }
                val targetScale = if (pressedButtons[view] == true) QUICK_BUTTON_PRESSED_SCALE else QUICK_BUTTON_BASE_SCALE
                if (kotlin.math.abs(view.scaleX - targetScale) > 0.12f || pressedButtons[view] != true) view.scaleX = targetScale
                if (kotlin.math.abs(view.scaleY - targetScale) > 0.12f || pressedButtons[view] != true) view.scaleY = targetScale
                val targetTranslation = -6f * view.resources.displayMetrics.density
                if (kotlin.math.abs(view.translationY - targetTranslation) > 0.5f) view.translationY = targetTranslation
            }
            true
        }
        quickButtonEnforcers[view] = listener
        view.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun handleQuickButtonTouch(view: View, event: MotionEvent, flashlight: Boolean): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.parent?.requestDisallowInterceptTouchEvent(true)
                view.animate().cancel()
                view.animate().scaleX(QUICK_BUTTON_PRESSED_SCALE).scaleY(QUICK_BUTTON_PRESSED_SCALE).setDuration(110L).setInterpolator(pressInterpolator).start()
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                pressedButtons[view] = true
            }
            MotionEvent.ACTION_MOVE -> {
                val inside = event.x >= 0f && event.y >= 0f && event.x < view.width && event.y < view.height
                if (pressedButtons[view] != inside) {
                    pressedButtons[view] = inside
                    animateQuickButton(view, inside)
                }
            }
            MotionEvent.ACTION_UP -> {
                val inside = pressedButtons.remove(view) == true && event.x >= 0f && event.y >= 0f && event.x < view.width && event.y < view.height
                animateQuickButton(view, false)
                view.parent?.requestDisallowInterceptTouchEvent(false)
                if (inside) {
                    if (flashlight) toggleFlashlight(view.context) else launchCamera(view.context)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedButtons.remove(view)
                animateQuickButton(view, false)
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun animateQuickButton(view: View, pressed: Boolean) {
        val scale = if (pressed) QUICK_BUTTON_PRESSED_SCALE else QUICK_BUTTON_BASE_SCALE
        view.animate().cancel()
        view.animate().scaleX(scale).scaleY(scale).setDuration(if (pressed) 110L else 150L).setInterpolator(pressInterpolator).start()
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

    private fun systemColor(context: Context, name: String, fallback: Int): Int {
        val id = context.resources.getIdentifier(name, "color", "android")
        return if (id != 0) runCatching { context.getColor(id) }.getOrDefault(fallback) else fallback
    }

    private fun installKeyguardView(type: Class<*>) {
        XposedBridge.hookAllMethods(type, "onFinishInflate", object : XC_MethodHook(Int.MIN_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                val root = param.thisObject as? View ?: return
                if (!enabled(root.context)) return
                val parent = findField(param.thisObject, "mMainContents") as? ViewGroup ?: return
                val previous = synchronized(clockViews) { clockViews[root] }
                if (previous?.parent === parent) return
                (previous?.parent as? ViewGroup)?.removeView(previous)
                val clock = ExpressiveLockscreenClockView(root.context)
                clock.bindNativeClockViews(
                    listOfNotNull(
                        findField(param.thisObject, "mTimeContainer") as? View,
                        findField(param.thisObject, "mTimeContainerDeep") as? View
                    )
                )
                val layoutParams = RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                parent.addView(clock, layoutParams)
                synchronized(clockViews) { clockViews[root] = clock }
            }
        })
    }

    private fun installTimeContainer(type: Class<*>) {
        XposedBridge.hookAllMethods(type, "dispatchDraw", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val container = param.thisObject as? View ?: return
                if (!enabled(container.context)) return
                val root = keyguardAncestor(container) ?: return
                val clock = synchronized(clockViews) { clockViews[root] } ?: return
                if (clock.isActive()) param.result = null
            }
        })
    }

    private fun keyguardAncestor(view: View): View? {
        var current: View? = view
        while (current != null) {
            if (current.javaClass.name == KEYGUARD_VIEW_CLASS) return current
            current = current.parent as? View
        }
        return null
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

    private class ExpressiveAffordanceDrawable(pathData: String) : Drawable() {
        private val source = PathParser.createPathFromPathData(pathData)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private var drawableAlpha = 255
        private var tintList: ColorStateList? = null
        private var tintMode = PorterDuff.Mode.SRC_IN

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.isEmpty) return
            val size = min(bounds.width(), bounds.height()).toFloat()
            val scale = size / 24f
            val save = canvas.save()
            canvas.translate(bounds.exactCenterX() - size / 2f, bounds.exactCenterY() - size / 2f)
            canvas.scale(scale, scale)
            paint.alpha = drawableAlpha
            canvas.drawPath(source, paint)
            canvas.restoreToCount(save)
        }

        override fun onBoundsChange(bounds: Rect) {
            invalidateSelf()
        }

        override fun setAlpha(alpha: Int) {
            drawableAlpha = alpha.coerceIn(0, 255)
            invalidateSelf()
        }

        override fun getAlpha(): Int = drawableAlpha

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun setTintList(tint: ColorStateList?) {
            tintList = tint
            updateTint()
        }

        override fun setTintMode(tintMode: PorterDuff.Mode?) {
            this.tintMode = tintMode ?: PorterDuff.Mode.SRC_IN
            updateTint()
        }

        override fun isStateful(): Boolean = tintList?.isStateful == true

        override fun onStateChange(state: IntArray): Boolean {
            updateTint()
            return true
        }

        private fun updateTint() {
            val color = tintList?.getColorForState(state, tintList?.defaultColor ?: Color.WHITE)
            paint.colorFilter = color?.let { PorterDuffColorFilter(it, tintMode) }
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = 24

        override fun getIntrinsicHeight(): Int = 24
    }

    companion object {
        const val SETTING_ENABLED = "originroottoolbox_material_originos_lockscreen"

        private val classNames = setOf(
            QUICK_TOOL_CLASS,
            QUICK_TOOL_WRAPPER_CLASS,
            KEYGUARD_VIEW_CLASS,
            TIME_CONTAINER_CLASS
        )
        private val installedLoaders = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())
        private val installedClasses = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
        private val clockViews = Collections.synchronizedMap(WeakHashMap<View, ExpressiveLockscreenClockView>())
        private val pressedButtons = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
        private val pendingQuickStyles = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
        private val quickButtonEnforcers = Collections.synchronizedMap(WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>())
        private val quickButtonBackgrounds = Collections.synchronizedMap(WeakHashMap<View, GradientDrawable>())
        private val quickButtonColors = Collections.synchronizedMap(WeakHashMap<View, MaterialOriginOsLockscreenPalette.Colors>())
        private val classWatcherInstalled = AtomicBoolean(false)
        private val pressInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        @Volatile private var cameraManager: CameraManager? = null
        @Volatile private var torchCameraId: String? = null
        @Volatile private var torchEnabled = false
        @Volatile private var torchCallbackRegistered = false
        private const val QUICK_TOOL_CLASS = "com.vivo.systemuiplugin.keyguard.tools.QuickToolView"
        private const val QUICK_TOOL_WRAPPER_CLASS = "com.vivo.systemuiplugin.keyguard.legacy.keyguard.views.area.QuickToolAreaWrapper"
        private const val QUICK_BUTTON_BASE_SCALE = 0.76f
        private const val QUICK_BUTTON_PRESSED_SCALE = 0.83f
        private const val KEYGUARD_VIEW_CLASS = "com.vivo.systemuiplugin.keyguard.legacy.keyguard.views.keyguardstyleos5.KeyguardOsView"
        private const val TIME_CONTAINER_CLASS = "com.vivo.systemuiplugin.keyguard.time.container.TimeContainer"
        private const val CAMERA_PATH = "M12,17.5C13.25,17.5 14.313,17.063 15.188,16.188C16.063,15.313 16.5,14.25 16.5,13C16.5,11.75 16.063,10.688 15.188,9.813C14.313,8.938 13.25,8.5 12,8.5C10.75,8.5 9.688,8.938 8.813,9.813C7.938,10.688 7.5,11.75 7.5,13C7.5,14.25 7.938,15.313 8.813,16.188C9.688,17.063 10.75,17.5 12,17.5ZM12,15.5C11.3,15.5 10.708,15.258 10.225,14.775C9.742,14.292 9.5,13.7 9.5,13C9.5,12.3 9.742,11.708 10.225,11.225C10.708,10.742 11.3,10.5 12,10.5C12.7,10.5 13.292,10.742 13.775,11.225C14.258,11.708 14.5,12.3 14.5,13C14.5,13.7 14.258,14.292 13.775,14.775C13.292,15.258 12.7,15.5 12,15.5ZM4,21C3.45,21 2.979,20.804 2.588,20.413C2.196,20.021 2,19.55 2,19V7C2,6.45 2.196,5.979 2.588,5.588C2.979,5.196 3.45,5 4,5H7.15L8.4,3.65C8.583,3.45 8.804,3.292 9.063,3.175C9.321,3.058 9.592,3 9.875,3H14.125C14.408,3 14.679,3.058 14.938,3.175C15.196,3.292 15.417,3.45 15.6,3.65L16.85,5H20C20.55,5 21.021,5.196 21.413,5.588C21.804,5.979 22,6.45 22,7V19C22,19.55 21.804,20.021 21.413,20.413C21.021,20.804 20.55,21 20,21H4Z"
        private const val FLASHLIGHT_PATH = "M6,5V4C6,3.45 6.196,2.979 6.588,2.588C6.979,2.196 7.45,2 8,2H16C16.55,2 17.021,2.196 17.413,2.588C17.804,2.979 18,3.45 18,4V5H6ZM13.063,18.563C13.354,18.271 13.5,17.917 13.5,17.5C13.5,17.083 13.354,16.729 13.063,16.438C12.771,16.146 12.417,16 12,16C11.583,16 11.229,16.146 10.938,16.438C10.646,16.729 10.5,17.083 10.5,17.5C10.5,17.917 10.646,18.271 10.938,18.563C11.229,18.854 11.583,19 12,19C12.417,19 12.771,18.854 13.063,18.563ZM8,20V11L6.325,8.5C6.208,8.333 6.125,8.158 6.075,7.975C6.025,7.792 6,7.6 6,7.4V7H18V7.4C18,7.6 17.975,7.792 17.925,7.975C17.875,8.158 17.792,8.333 17.675,8.5L16,11V20C16,20.55 15.804,21.021 15.413,21.413C15.021,21.804 14.55,22 14,22H10C9.45,22 8.979,21.804 8.587,21.413C8.196,20.021 8,20.55 8,20Z"
    }
}
