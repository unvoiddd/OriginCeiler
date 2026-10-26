package dev.unvoid.originceiler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import de.robv.android.xposed.XposedBridge
import java.util.TimeZone

class CustomLockscreenClockHostView(context: Context) : FrameLayout(context) {

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var activeStyleId: String = ""
    private var activeFaceView: View? = null
    private var receiverRegistered = false
    private var observerRegistered = false
    private var nativeClockViews = emptyList<View>()
    private val nativeClockAlphas = LinkedHashMap<View, Float>()
    private var glassRenderer: LiquidGlassRenderer? = null

    private val fallbackShader by lazy {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (powerManager?.isInteractive == true) {
            nativeClockViews.forEach { if (it.alpha != 0f) it.alpha = 0f }
        }
        true
    }

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            post {
                val currentSetting = Settings.Global.getString(context.contentResolver, SETTING_CLOCK_STYLE)
                    ?: LockscreenClockCatalog.DEFAULT_STYLE_ID
                if (currentSetting != activeStyleId) {
                    recreateFaceIfNeeded(force = true)
                }
                updateColor()
                updateGlass()
                updateTime()
                requestLayout()
            }
        }
    }

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CLOCK_STYLE_CHANGED -> {
                    recreateFaceIfNeeded(force = true)
                    updateColor()
                    updateGlass()
                    updateTime()
                }
                else -> {
                    updateVisibility()
                    updateTime()
                    updateColor()
                    updateGlass()
                }
            }
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        registerGlobalListeners()
        recreateFaceIfNeeded(force = true)
        updateColor()
        updateGlass()
        updateTime()
        updateVisibility()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
        var cur: ViewParent? = parent
        while (cur is ViewGroup) {
            cur.clipChildren = false
            cur.clipToPadding = false
            cur = cur.parent
        }

        registerGlobalListeners()

        recreateFaceIfNeeded(force = false)
        updateColor()
        updateGlass()
        updateVisibility()
        updateTime()
    }

    private fun registerGlobalListeners() {
        if (!observerRegistered) {
            runCatching {
                val cr = context.contentResolver
                cr.registerContentObserver(Settings.Global.getUriFor(SETTING_CLOCK_STYLE), false, settingsObserver)
                cr.registerContentObserver(Settings.Global.getUriFor(SETTING_CLOCK_COLOR), false, settingsObserver)
                cr.registerContentObserver(Settings.Global.getUriFor(SETTING_CLOCK_GLASS), false, settingsObserver)
                cr.registerContentObserver(Settings.Global.getUriFor(SETTING_CLOCK_GLASS_BLUR), false, settingsObserver)
                cr.registerContentObserver(Settings.Global.getUriFor(SETTING_CLOCK_GLASS_REFRACT), false, settingsObserver)
                cr.registerContentObserver(Settings.Global.getUriFor(SETTING_CLOCK_GLASS_DISPERSION), false, settingsObserver)
                cr.registerContentObserver(Settings.Global.getUriFor(SETTING_CLOCK_GLASS_HIGHLIGHT), false, settingsObserver)
                cr.registerContentObserver(Settings.Global.getUriFor(SETTING_CLOCK_GLASS_FALLOFF), false, settingsObserver)
                cr.registerContentObserver(Settings.Global.getUriFor(SETTING_CLOCK_GLASS_LIGHT_ANGLE), false, settingsObserver)
                cr.registerContentObserver(Settings.Global.getUriFor(SETTING_CLOCK_GLASS_TINT_STRENGTH), false, settingsObserver)
                cr.registerContentObserver(Settings.Global.getUriFor(MaterialOriginOsLockscreenHook.SETTING_CUSTOM_CLOCK), false, settingsObserver)
                observerRegistered = true
            }
        }

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_WALLPAPER_CHANGED)
                addAction(ACTION_CLOCK_STYLE_CHANGED)
            }
            runCatching {
                val targetContext = context.applicationContext ?: context
                if (Build.VERSION.SDK_INT >= 33) {
                    targetContext.registerReceiver(clockReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    targetContext.registerReceiver(clockReceiver, filter)
                }
                receiverRegistered = true
            }
        }
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        nativeClockViews.forEach { view -> nativeClockAlphas[view]?.let { view.alpha = it } }
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateColor()
        updateGlass()
        updateTime()
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        positionFace()
    }

    fun isActive(): Boolean = parent != null && visibility == View.VISIBLE && powerManager?.isInteractive == true

    fun bindNativeClockViews(views: List<View>) {
        nativeClockViews.forEach { view -> nativeClockAlphas[view]?.let { view.alpha = it } }
        nativeClockViews = views.distinct()
        nativeClockViews.forEach { view -> nativeClockAlphas.putIfAbsent(view, view.alpha) }
        updateVisibility()
    }

    private fun recreateFaceIfNeeded(force: Boolean) {
        val currentSetting = Settings.Global.getString(context.contentResolver, SETTING_CLOCK_STYLE)
            ?: LockscreenClockCatalog.DEFAULT_STYLE_ID

        if (!force && currentSetting == activeStyleId && activeFaceView != null) {
            return
        }

        activeStyleId = currentSetting
        activeFaceView?.let { removeView(it) }

        val style = LockscreenClockCatalog.findStyle(context, currentSetting)
        val newFace: View = when {
            style.category == LockscreenClockCatalog.CATEGORY_PIXEL || currentSetting == "pixel.expressive" -> {
                ExpressiveLockscreenClockView(context)
            }
            style.category == LockscreenClockCatalog.CATEGORY_HYPEROS || currentSetting.startsWith("hyperos.") -> {
                HyperOsConvertedClockFace(context, currentSetting)
            }
            currentSetting == "flyme.perspective" -> {
                FlymePerspectiveClockView(context)
            }
            currentSetting == "flyme.alive.vertical" -> {
                FlymeAliveConvertedClockFace(context, isVertical = true)
            }
            currentSetting == "flyme.alive.horizontal" -> {
                FlymeAliveConvertedClockFace(context, isVertical = false)
            }
            style.category == LockscreenClockCatalog.CATEGORY_FLYME || currentSetting.startsWith("flyme.") -> {
                FlymePerspectiveClockView(context)
            }
            else -> {
                VivoConvertedClockFace(context, style)
            }
        }

        newFace.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != oldRight - oldLeft || h != oldBottom - oldTop)) {
                updateGlass()
            }
        }

        activeFaceView = newFace
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        addView(newFace, lp)

        updateColor()
        updateGlass()
        updateTime()
        requestLayout()
    }

    private fun positionFace() {
        val face = activeFaceView ?: return
        if (width <= 0 || height <= 0) return

        if (face is ExpressiveLockscreenClockView) {
            face.translationY = 0f
            return
        }

        // Align with native clock container or appropriate top margin
        var targetTop = height * 0.12f
        val nativeView = nativeClockViews.firstOrNull { it.isAttachedToWindow && it.height > 0 }
        if (nativeView is ViewGroup) {
            for (i in 0 until nativeView.childCount) {
                val child = nativeView.getChildAt(i)
                if (child.visibility == View.VISIBLE && child.top > 0) {
                    targetTop = child.top.toFloat() + child.translationY
                    break
                }
            }
        } else if (nativeView != null && nativeView.top > 0) {
            targetTop = nativeView.top.toFloat()
        }

        val oldY = face.translationY
        face.translationY = targetTop
        if (oldY != targetTop) {
            updateGlass()
        }
    }

    private fun updateVisibility() {
        val interactive = powerManager?.isInteractive == true
        visibility = if (interactive) View.VISIBLE else View.GONE
        nativeClockViews.forEach { view ->
            view.alpha = if (interactive) 0f else nativeClockAlphas[view] ?: 1f
        }
    }

    private fun updateTime() {
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val is24 = android.text.format.DateFormat.is24HourFormat(context)

        when (val face = activeFaceView) {
            is VivoConvertedClockFace -> face.update(now, zone, is24)
            is FlymePerspectiveClockView -> face.update(now, zone, is24)
            is FlymeAliveConvertedClockFace -> face.update(now, zone, is24)
            is HyperOsConvertedClockFace -> face.update(now, zone, is24)
            is NativeHyperOsFace -> runCatching { face.refresh(zone.id, is24) }
            is NativeFlymePerspectiveFace -> face.update(now, zone, is24)
            is VivoLockscreenClockFace -> face.update(now, zone, is24)
            is ExpressiveLockscreenClockView -> face.updateTime()
        }
        post { positionFace() }
    }

    private fun updateColor() {
        val rawColor = runCatching {
            Settings.Global.getString(context.contentResolver, SETTING_CLOCK_COLOR)
        }.getOrNull()
        val userColor = rawColor?.toLongOrNull()?.toInt() ?: 0
        val color = if (userColor != 0) userColor else Color.WHITE

        when (val face = activeFaceView) {
            is VivoConvertedClockFace -> face.color(color)
            is FlymePerspectiveClockView -> face.color(color)
            is FlymeAliveConvertedClockFace -> face.color(color)
            is HyperOsConvertedClockFace -> face.color(color)
            is NativeHyperOsFace -> runCatching { face.color(color) }
            is NativeFlymePerspectiveFace -> face.color(color)
            is VivoLockscreenClockFace -> face.setColor(color)
            is ExpressiveLockscreenClockView -> face.updateColor(if (userColor != 0) userColor else null)
        }
        if (userColor != 0) {
            activeFaceView?.let { applyColorRecursively(it, userColor) }
        }
    }

    private fun applyColorRecursively(view: View, color: Int) {
        if (view is TextView) {
            view.setTextColor(color)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyColorRecursively(view.getChildAt(i), color)
            }
        }
    }

    private fun getGlobalFloat(name: String, default: Float): Float {
        return runCatching {
            Settings.Global.getString(context.contentResolver, name)?.toFloatOrNull() ?: default
        }.getOrDefault(default)
    }

    private fun getTargetGlassViews(face: View): List<View> {
        val targets = when (face) {
            is HyperOsConvertedClockFace -> face.getDigitViews()
            is VivoConvertedClockFace -> face.getDigitViews()
            is FlymeAliveConvertedClockFace -> face.getDigitViews()
            is FlymePerspectiveClockView -> face.getDigitViews()
            is ExpressiveLockscreenClockView -> face.getDigitViews()
            else -> emptyList()
        }
        return if (targets.isNotEmpty()) targets else listOf(face)
    }

    private fun updateGlass() {
        runCatching { updateGlassInner() }.onFailure { XposedBridge.log(it) }
    }

    /** The digits wear the shared glass (see [ClockGlass]), dyed by the clock colour. */
    private fun updateGlassInner() {
        val glassEnabled = Settings.Global.getInt(context.contentResolver, SETTING_CLOCK_GLASS, 0) == 1
        val face = activeFaceView ?: return
        val targets = getTargetGlassViews(face)
        face.setRenderEffect(null)
        if (!glassEnabled) {
            targets.forEach { ClockGlass.clear(it); it.setRenderEffect(null) }
            return
        }
        val rawColor = runCatching { Settings.Global.getString(context.contentResolver, SETTING_CLOCK_COLOR) }.getOrNull()
        val userColor = rawColor?.toLongOrNull()?.toInt() ?: 0
        targets.forEach { ClockGlass.apply(it, userColor) }
    }

    companion object {
        const val SETTING_CLOCK_STYLE = "originroottoolbox_custom_lockscreen_clock_style"
        const val SETTING_CLOCK_COLOR = "originroottoolbox_custom_lockscreen_clock_color"
        const val SETTING_CLOCK_GLASS = "originroottoolbox_custom_lockscreen_clock_glass"
        const val SETTING_CLOCK_GLASS_BLUR = "originroottoolbox_clock_glass_blur"
        const val SETTING_CLOCK_GLASS_REFRACT = "originroottoolbox_clock_glass_refract"
        const val SETTING_CLOCK_GLASS_DISPERSION = "originroottoolbox_clock_glass_dispersion"
        const val SETTING_CLOCK_GLASS_HIGHLIGHT = "originroottoolbox_clock_glass_highlight"
        const val SETTING_CLOCK_GLASS_FALLOFF = "originroottoolbox_clock_glass_falloff"
        const val SETTING_CLOCK_GLASS_LIGHT_ANGLE = "originroottoolbox_clock_glass_light_angle"
        const val SETTING_CLOCK_GLASS_TINT_STRENGTH = "originroottoolbox_clock_glass_tint_strength"
        const val ACTION_CLOCK_STYLE_CHANGED = "dev.unvoid.originceiler.ACTION_CLOCK_STYLE_CHANGED"
    }
}
