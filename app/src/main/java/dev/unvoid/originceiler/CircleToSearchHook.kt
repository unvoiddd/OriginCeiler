package dev.unvoid.originceiler

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Resources
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.TypedValue
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import java.util.Collections
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class CircleToSearchHook : IXposedHookLoadPackage {
    private var lastAssistantLaunch = 0L
    private var navigationDownX = 0f
    private var navigationDownY = 0f
    private var navigationLongPress: Runnable? = null
    private var pixelSearchHost: AppWidgetHost? = null
    private val pixelHomeBars = Collections.synchronizedMap(WeakHashMap<View, View>())
    private val launcherIconViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val launcherIconBaseScaleX = Collections.synchronizedMap(WeakHashMap<View, Float>())
    private val launcherIconBaseScaleY = Collections.synchronizedMap(WeakHashMap<View, Float>())
    private val launcherIconObserverInstalled = AtomicBoolean(false)
    private val launcherIconInternalScale = ThreadLocal<Boolean>()
    private val aospVolumeDialogs = Collections.synchronizedMap(WeakHashMap<Any, Any>())
    private val settingsOriginalTopMargins = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val settingsOriginalHorizontalMargins = Collections.synchronizedMap(WeakHashMap<View, Pair<Int, Int>>())
    private val settingsOriginalPaddings = Collections.synchronizedMap(WeakHashMap<View, IntArray>())
    private val settingsSwitchStates = Collections.synchronizedMap(WeakHashMap<View, MutableState<Boolean>>())
    private val settingsComposeSwitches = Collections.synchronizedMap(WeakHashMap<View, ComposeView>())
    private val iosNotificationClockSizes = Collections.synchronizedMap(WeakHashMap<View, Float>())
    private val iosNotificationClassHooked = AtomicBoolean(false)
    private val lockscreenClockMirrorHooked = AtomicBoolean(false)
    private val originPlayerClassLoadingHooked = AtomicBoolean(false)
    private val originPlayerMixCardHooked = AtomicBoolean(false)
    private val originPlayerMetadataHooked = AtomicBoolean(false)
    private val originPlayerDisplayCallbacks = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
    private val originPlayerRemoteHosts = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
    private val originPlayerTitleViews = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
    private val originPlayerTrackNames = Collections.synchronizedMap(HashMap<String, String>())
    private val originPlayerArtistNames = Collections.synchronizedMap(HashMap<String, String>())
    private val originPlayerAlbumNames = Collections.synchronizedMap(HashMap<String, String>())
    private val originPlayerRelayedMetadata = Collections.synchronizedMap(HashMap<String, String>())
    private var originPlayerService = WeakReference<Any>(null)
    private val iosNotificationClockMirrors = Collections.synchronizedMap(WeakHashMap<ViewGroup, ImageView>())
    private var lockscreenClockView = WeakReference<View>(null)
    private var lockscreenClockBitmap: Bitmap? = null
    private var lockscreenClockCaptureTime = 0L
    private val lockscreenClockCaptureActive = ThreadLocal<Boolean>()
    private var notificationShadeExpanded = false
    private var notificationShadeFraction = 0f
    private var notificationShadeTracking = false
    @Volatile
    private var iosClockGlassClassLoader: ClassLoader? = null
    private var settingsSymbolTypeface: Typeface? = null
    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) {
        Handler(Looper.getMainLooper())
    }

    override fun handleLoadPackage(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        if (loadPackageParam.packageName in setOf(
                "com.android.systemui",
                "com.bbk.launcher2",
                "com.vivo.launchercopilot",
                "com.vivo.ai.copilot",
                "com.vivo.upslide",
                "com.vivo.musicwidgetmix",
                "com.vivo.musicmixcard",
                "com.google.android.inputmethod.latin",
                "com.android.settings",
                "com.android.phone",
                "com.vivo.systemuiplugin",
                "com.iqoo.powersaving",
                "com.vivo.gamecube",
                "com.bbk.theme",
                "com.vivo.pay",
                "com.vivo.fingerprintui",
                "com.vivo.faceui"
            )
        ) {
            XposedBridge.log("OriginIcons loaded: ${loadPackageParam.packageName}")
        }
        if (loadPackageParam.packageName == "com.android.systemui") {
            runCatching { MaterialOriginOsLockscreenHook().install(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { MaterialOriginOsHook().install(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { MaterialOriginOsStatusFontHook().install() }
                .onFailure(XposedBridge::log)
            runCatching { ShadeLiquidGlassHook().install(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { OriginIslandLiveUpdatesHook().install(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookIosLikeNotificationCenter(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookAospVolumeBar(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookNotificationShadeFilter(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookPersistentVivoNotificationFilter(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
        }
        if (loadPackageParam.packageName in setOf("com.vivo.fingerprintui", "com.vivo.faceui")) {
            runCatching { MaterialOriginOsUdfpsHook().install(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
        }
        if (loadPackageParam.packageName == "android") {
            runCatching { hookPrivateDnsWithVpn(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
        }
        if (loadPackageParam.packageName == "com.android.settings") {
            runCatching { hookSettingsContainerSpacing(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
        }
        if (loadPackageParam.packageName in setOf(
                "com.android.phone",
                "com.vivo.systemuiplugin",
                "com.iqoo.powersaving",
                "com.vivo.gamecube",
                "com.bbk.theme",
                "com.vivo.ai.copilot",
                "com.vivo.pay"
            )
        ) {
            if (loadPackageParam.packageName == "com.vivo.systemuiplugin") {
                runCatching { MaterialOriginOsLockscreenHook().install(loadPackageParam.classLoader) }
                    .onFailure(XposedBridge::log)
                runCatching { MaterialOriginOsStatusFontHook().install() }
                    .onFailure(XposedBridge::log)
                runCatching { ShadeLiquidGlassHook().install(loadPackageParam.classLoader) }
                    .onFailure(XposedBridge::log)
                runCatching { OriginIslandLiveUpdatesHook().install(loadPackageParam.classLoader) }
                    .onFailure(XposedBridge::log)
                runCatching { hookIosLikeNotificationCenter(loadPackageParam.classLoader) }
                    .onFailure(XposedBridge::log)
            }
            runCatching { hookExternalSettingsTheme(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
        }
        if (loadPackageParam.packageName == "com.bbk.launcher2") {
            runCatching { hookCircleToSearch(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookPixelHomeLayout() }
                .onFailure(XposedBridge::log)
            runCatching { hookPixelHomeTranslations() }
                .onFailure(XposedBridge::log)
            runCatching { hookPixelHomeDockLayout() }
                .onFailure(XposedBridge::log)
            runCatching { hookPixelHomeWidgetSync() }
                .onFailure(XposedBridge::log)
            runCatching { hookLauncherIconSize() }
                .onFailure(XposedBridge::log)
            runCatching { hookNativeAospThemedIcons(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookMonetIconRasterizers(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookMonetDynamicIcons(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookMonetRedrawnIcons(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookMonetHotseatIcons(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
        }
        if (loadPackageParam.packageName in setOf("com.vivo.ai.copilot", "com.vivo.launchercopilot")) {
            if (loadPackageParam.packageName == "com.vivo.ai.copilot") {
                runCatching { hookPowerButtonWakeupService(loadPackageParam.classLoader) }
                    .onFailure(XposedBridge::log)
            }
            runCatching { hookPowerButtonAssistant(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookPowerButtonAssistantFallback(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookBlueLmToasts(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
        }
        val processName = runCatching { loadPackageParam.javaClass.getField("processName").get(loadPackageParam) as? String }.getOrNull()
        if (loadPackageParam.packageName == "com.vivo.upslide" || processName == "com.vivo.upslide") {
            runCatching { hookNavigationHandle(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { MaterialBackGestureHook().install(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
        }
        if (loadPackageParam.packageName == "com.vivo.musicwidgetmix") {
            runCatching { hookMusicWidgetAllowLists(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
        }
        if (loadPackageParam.packageName in setOf("com.android.systemui", "com.vivo.systemuiplugin", "com.vivo.musicmixcard")) {
            runCatching { hookOriginPlayerClassLoading(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { MaterialOriginPlayerHook().install(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
        }
        if (loadPackageParam.packageName == "com.google.android.inputmethod.latin") {
            runCatching { GboardBlurHook().install() }
                .onFailure(XposedBridge::log)
        }
    }

    private fun hookIosLikeNotificationCenter(classLoader: ClassLoader) {
        listOf("setScaleX", "setScaleY").forEach { methodName ->
            XposedBridge.hookAllMethods(
                View::class.java,
                methodName,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        if (view.javaClass.name != "com.vivo.systemuiplugin.android.systemui.statusbar.policy.Clock") return
                        if (!isCenteredNotificationClockEnabled(view)) return
                        val requested = param.args.firstOrNull() as? Float ?: return
                        if (requested < 1f) param.args[0] = 1f
                    }
                }
            )
        }
        XposedBridge.hookAllMethods(
            ClassLoader::class.java,
            "loadClass",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val loadedClass = param.result as? Class<*> ?: return
                    if (loadedClass.name != "com.vivo.systemuiplugin.systemui.statusbar.header.VivoQSHeader") return
                    iosClockGlassClassLoader = loadedClass.classLoader
                    installIosLikeNotificationHeaderHooks(loadedClass)
                }
            }
        )
    }

    private fun installIosLikeNotificationHeaderHooks(headerClass: Class<*>) {
        if (!iosNotificationClassHooked.compareAndSet(false, true)) return
        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val header = param.thisObject as? ViewGroup ?: return
                applyIosLikeNotificationHeaderIfNeeded(header)
            }
        }
        XposedBridge.hookAllMethods(headerClass, "onLayout", callback)
        XposedBridge.hookAllMethods(headerClass, "setStandAloneControlCenterNotificationPage", callback)
        XposedBridge.hookAllMethods(headerClass, "updateResources", callback)
        XposedBridge.log("OriginRootToolbox iOS notification header hook ready")
    }

    private fun applyIosLikeNotificationHeaderIfNeeded(header: ViewGroup) {
        if (!isCenteredNotificationClockEnabled(header)) return
        applyIosLikeNotificationHeader(header)
    }

    private fun applyIosLikeNotificationHeader(header: ViewGroup) {
        val clock = readField(header, "mHeaderClock") as? TextView ?: return
        val date = readField(header, "mRightDateView") as? TextView ?: return
        val originalSize = iosNotificationClockSizes[clock] ?: clock.textSize.also {
            iosNotificationClockSizes[clock] = it
        }
        clock.gravity = Gravity.CENTER
        date.gravity = Gravity.CENTER
        clock.includeFontPadding = false
        date.includeFontPadding = false
        clock.visibility = View.VISIBLE
        date.visibility = View.VISIBLE
        val scale = notificationClockScale(clock)
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalSize * scale)
        clock.scaleX = 1f
        clock.scaleY = 1f
        if (isCenteredClockGlassEnabled(clock)) applyIosClockLiquidGlass(clock)
        applyLockscreenClockMirror(header, clock, date)
        val clockHeight = (clock.paint.fontMetrics.bottom - clock.paint.fontMetrics.top).toInt()
        val dateHeight = maxOf(date.measuredHeight, (date.paint.fontMetrics.bottom - date.paint.fontMetrics.top).toInt())
        val minimumHeaderHeight = maxOf(dp(header.context, 78), clockHeight + dateHeight + dp(header.context, 28))
        header.minimumHeight = minimumHeaderHeight
        val headerLayout = header.layoutParams
        var headerChanged = false
        if (headerLayout != null && headerLayout.height in 1 until minimumHeaderHeight) {
            headerLayout.height = minimumHeaderHeight
            header.layoutParams = headerLayout
            headerChanged = true
        }
        clock.translationX = 0f
        clock.translationY = 0f
        date.translationX = 0f
        date.translationY = 0f
        val clockChanged = centerNotificationHeaderLayout(clock, View.NO_ID, date.id, View.NO_ID, 0)
        val dateChanged = centerNotificationHeaderLayout(date, 0, View.NO_ID, clock.id, View.NO_ID)
        val clockSpacingChanged = updateVerticalMargins(clock, dp(header.context, 4), dp(header.context, 8))
        val dateSpacingChanged = updateVerticalMargins(date, dp(header.context, 8), dp(header.context, 4))
        if (headerChanged || clockChanged || dateChanged || clockSpacingChanged || dateSpacingChanged) header.requestLayout()
    }

    private fun applyIosClockLiquidGlass(clock: TextView) {
        runCatching {
            clock.background = null
            clock.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            val classLoader = clock.javaClass.classLoader
            iosClockGlassClassLoader = classLoader
            val delegatorClass = Class.forName(
                "com.vivo.systemuiplugin.keyguard.blur.VivoMaterialBlurDelegator",
                false,
                classLoader
            )
            delegatorClass.declaredMethods.first {
                it.name == "setMaterial" && it.parameterTypes.size == 3
            }.apply { isAccessible = true }.invoke(null, clock, 0, 1)
            val glassApplierClass = Class.forName(
                "com.vivo.systemuiplugin.keyguard.time.material.applier.GlassApplier",
                false,
                classLoader
            )
            val glassApplier = glassApplierClass.getDeclaredField("INSTANCE").apply {
                isAccessible = true
            }.get(null)
            glassApplierClass.declaredMethods.first {
                it.name == "setGlassViewColor" && it.parameterTypes.size == 9
            }.apply { isAccessible = true }.invoke(
                glassApplier,
                clock,
                0.04f,
                Color.rgb(238, 245, 255),
                0.32f,
                1,
                1f,
                null,
                1f,
                false
            )
            val delegator = delegatorClass.getDeclaredField("INSTANCE").apply {
                isAccessible = true
            }.get(null)
            delegatorClass.declaredMethods.first {
                it.name == "setLiquidGlassRimLightIfNeeded" && it.parameterTypes.size == 3
            }.apply { isAccessible = true }.invoke(delegator, clock, 3, null)
            clock.invalidate()
            XposedBridge.log("OriginRootToolbox notification clock glyph glass applied")
        }.onFailure {
            XposedBridge.log("OriginRootToolbox notification clock glyph glass failed: ${it.message}")
        }
    }

    private fun installLockscreenClockMirrorHooks(timeViewClass: Class<*>) {
        if (!lockscreenClockMirrorHooked.compareAndSet(false, true)) return
        XposedBridge.hookAllMethods(
            timeViewClass,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    lockscreenClockView = WeakReference(view)
                    captureLockscreenClock(view)
                }
            }
        )
        XposedBridge.hookAllMethods(
            timeViewClass,
            "dispatchDraw",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (lockscreenClockCaptureActive.get() == true) return
                    val view = param.thisObject as? View ?: return
                    lockscreenClockView = WeakReference(view)
                    if (SystemClock.uptimeMillis() - lockscreenClockCaptureTime > 750L) captureLockscreenClock(view)
                }
            }
        )
    }

    private fun captureLockscreenClock(view: View) {
        if (view.width <= 0 || view.height <= 0 || lockscreenClockCaptureActive.get() == true) return
        view.post {
            if (view.width <= 0 || view.height <= 0 || lockscreenClockCaptureActive.get() == true) return@post
            runCatching {
                lockscreenClockCaptureActive.set(true)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                lockscreenClockBitmap = bitmap
                lockscreenClockCaptureTime = SystemClock.uptimeMillis()
                synchronized(iosNotificationClockMirrors) {
                    iosNotificationClockMirrors.values.toList()
                }.forEach {
                    it.setImageBitmap(bitmap)
                    it.invalidate()
                }
            }.onFailure {
                XposedBridge.log("OriginRootToolbox lockscreen clock capture failed: ${it.message}")
            }
            lockscreenClockCaptureActive.remove()
        }
    }

    private fun applyLockscreenClockMirror(header: ViewGroup, clock: TextView, date: TextView) {
        val enabled = isLockscreenClockMirrorEnabled(header) && isNotificationPage(header)
        if (!enabled) {
            iosNotificationClockMirrors.remove(header)?.let { (it.parent as? ViewGroup)?.removeView(it) }
            clock.visibility = View.VISIBLE
            date.visibility = View.VISIBLE
            return
        }
        clock.visibility = View.INVISIBLE
        date.visibility = View.INVISIBLE
        val panel = findNotificationPanel(header) ?: return
        val existing = iosNotificationClockMirrors[header]
        if (existing != null && existing.parent === panel) {
            existing.setImageBitmap(lockscreenClockBitmap)
            existing.visibility = View.VISIBLE
            return
        }
        if (existing != null) (existing.parent as? ViewGroup)?.removeView(existing)
        val mirror = ImageView(header.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageBitmap(lockscreenClockBitmap)
            scaleX = notificationClockScale(header)
            scaleY = notificationClockScale(header)
        }
        panel.addView(
            mirror,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(header.context, 300), Gravity.TOP).apply {
                topMargin = dp(header.context, 28)
            }
        )
        iosNotificationClockMirrors[header] = mirror
        lockscreenClockView.get()?.let(::captureLockscreenClock)
    }

    private fun findNotificationPanel(view: View): ViewGroup? {
        val root = view.rootView as? ViewGroup ?: return null
        val id = view.resources.getIdentifier("notification_panel", "id", "com.android.systemui")
        return if (id == 0) null else root.findViewById(id)
    }

    private fun isCenteredNotificationClockEnabled(view: View): Boolean {
        val resolver = view.context.contentResolver
        val legacy = Settings.Global.getInt(resolver, "originroottoolbox_ios_notification_center", 0)
        return Settings.Global.getInt(resolver, "originroottoolbox_centered_notification_clock", legacy) == 1
    }

    private fun isCenteredClockGlassEnabled(view: View): Boolean {
        return Settings.Global.getInt(view.context.contentResolver, "originroottoolbox_centered_clock_glass", 1) == 1
    }


    private fun notificationClockScale(view: View): Float {
        return Settings.Global.getInt(
            view.context.contentResolver,
            "originroottoolbox_notification_clock_size",
            128
        ).coerceIn(80, 200) / 100f
    }

    private fun isLockscreenClockMirrorEnabled(view: View): Boolean {
        return isCenteredNotificationClockEnabled(view) && Settings.Global.getInt(
            view.context.contentResolver,
            "originroottoolbox_lockscreen_clock_notification_center",
            0
        ) == 1
    }

    private fun isNotificationPage(header: ViewGroup): Boolean {
        val root = header.rootView as? ViewGroup ?: return false
        val panelId = header.resources.getIdentifier("notification_panel", "id", "com.android.systemui")
        return panelId != 0 && root.findViewById<View>(panelId) != null
    }

    private fun centerNotificationHeaderLayout(
        view: View,
        topToTop: Int,
        topToBottom: Int,
        bottomToTop: Int,
        bottomToBottom: Int
    ): Boolean {
        val layout = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
        var changed = false
        fun setField(name: String, value: Int) {
            var type: Class<*>? = layout.javaClass
            while (type != null) {
                val field = runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
                if (field != null) {
                    if (field.getInt(layout) != value) {
                        field.setInt(layout, value)
                        changed = true
                    }
                    return
                }
                type = type.superclass
            }
        }
        setField("startToStart", 0)
        setField("startToEnd", View.NO_ID)
        setField("endToEnd", 0)
        setField("endToStart", View.NO_ID)
        setField("topToTop", topToTop)
        setField("topToBottom", topToBottom)
        setField("bottomToTop", bottomToTop)
        setField("bottomToBottom", bottomToBottom)
        setField("verticalChainStyle", 2)
        if (layout.marginStart != 0) {
            layout.marginStart = 0
            changed = true
        }
        if (changed) view.layoutParams = layout
        return changed
    }

    private fun updateVerticalMargins(view: View, top: Int, bottom: Int): Boolean {
        val layout = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
        if (layout.topMargin == top && layout.bottomMargin == bottom) return false
        layout.topMargin = top
        layout.bottomMargin = bottom
        view.layoutParams = layout
        return true
    }

    private fun hookPixelGoogleIdentity(classLoader: ClassLoader) {
        val application = currentApplication() ?: return
        if (Settings.Global.getInt(application.contentResolver, "originicons_pixel_google_identity", 0) != 1) return
        val identity = mapOf(
            "BRAND" to "google",
            "MANUFACTURER" to "Google",
            "DEVICE" to "komodo",
            "PRODUCT" to "komodo",
            "MODEL" to "Pixel 9 Pro XL",
            "FINGERPRINT" to "google/komodo/komodo:16/BP2A.250605.031.A2/13901833:user/release-keys"
        )
        identity.forEach { (name, value) ->
            runCatching {
                Build::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
            }
        }
        val properties = Class.forName("android.os.SystemProperties", false, classLoader)
        XposedBridge.hookAllMethods(
            properties,
            "get",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val key = param.args.firstOrNull() as? String ?: return
                    val value = when (key) {
                        "ro.product.brand", "ro.product.system.brand", "ro.product.vendor.brand" -> "google"
                        "ro.product.manufacturer", "ro.product.system.manufacturer", "ro.product.vendor.manufacturer" -> "Google"
                        "ro.product.model", "ro.product.system.model", "ro.product.vendor.model" -> "Pixel 9 Pro XL"
                        "ro.product.device", "ro.product.system.device", "ro.product.vendor.device" -> "komodo"
                        "ro.build.fingerprint" -> "google/komodo/komodo:16/BP2A.250605.031.A2/13901833:user/release-keys"
                        else -> null
                    }
                    if (value != null) param.result = value
                }
            }
        )
    }

    private fun hookNativeAospThemedIcons(classLoader: ClassLoader) {
        val bitmapInfo = Class.forName(
            "com.android.launcher3.icons.BitmapInfo",
            false,
            classLoader
        )
        XposedHelpers.findAndHookMethod(
            bitmapInfo,
            "newIcon",
            Context::class.java,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val application = currentApplication() ?: return
                    if (Settings.Global.getInt(
                            application.contentResolver,
                            "originicons_themed_launcher_icons",
                            0
                        ) != 1
                    ) return
                    val flags = param.args[1] as Int
                    param.args[1] = if (MonetThemedIconFactory.moodCubeIconStyle(application).themed) {
                        flags or 1
                    } else {
                        flags and 1.inv()
                    }
                }
            }
        )
    }

    private fun hookMonetIconRasterizers(classLoader: ClassLoader) {
        val candidates = listOf(
            "x8.a",
            "y8.a",
            "z8.a",
            "w8.a",
            "com.bbk.launcher2.launcherIcon.LauncherIconImpl",
            "com.bbk.launcher2.launcherIcon.data.iconcache.IconManager"
        )
        candidates.forEach { className ->
            runCatching {
                val target = Class.forName(className, false, classLoader)
                target.declaredMethods.forEach { method ->
                    if (method.returnType != Bitmap::class.java) return@forEach
                    val types = method.parameterTypes
                    if (types.size < 3) return@forEach
                    val contextIndex = types.indexOfFirst { Context::class.java.isAssignableFrom(it) }
                    val drawableIndex = types.indexOfFirst { Drawable::class.java.isAssignableFrom(it) }
                    val packageIndex = types.indexOfFirst { it == String::class.java }
                    if (drawableIndex < 0) return@forEach
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook(Int.MAX_VALUE) {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val application = currentApplication() ?: return
                            if (Settings.Global.getInt(application.contentResolver, "originicons_themed_launcher_icons", 0) != 1) return
                            val original = param.args.getOrNull(drawableIndex) as? Drawable ?: return
                            val context = if (contextIndex >= 0) param.args.getOrNull(contextIndex) as? Context ?: application else application
                            val bitmap = param.result as? Bitmap ?: return
                            val style = MonetThemedIconFactory.moodCubeIconStyle(context)
                            if (!style.themed) return
                            val packageName = if (packageIndex >= 0) param.args.getOrNull(packageIndex) as? String else null
                            val scale = Settings.Global.getInt(application.contentResolver, "originicons_monet_icon_scale", 100) / 100f
                            MonetThemedIconFactory.renderAospThemedLive(context, original, packageName, bitmap.width, true, scale)
                                ?.let { MonetThemedIconFactory.applyMoodTint(it, style.tint) ?: it }
                                ?.let { param.result = it }
                        }
                    })
                }
            }.onFailure(XposedBridge::log)
        }
    }

    private fun hookMonetDynamicIcons(classLoader: ClassLoader) {
        listOf(
            "a8.b",
            "b8.b",
            "b8.c",
            "b8.d",
            "b8.f",
            "com.bbk.launcher2.launcherIcon.change.dynamicicon.CalendarDynamicIcon"
        ).forEach { className ->
            runCatching {
                val target = Class.forName(className, false, classLoader)
                target.declaredMethods.forEach { method ->
                    if (method.returnType != Bitmap::class.java || method.parameterTypes.firstOrNull() != Context::class.java || java.lang.reflect.Modifier.isAbstract(method.modifiers)) return@forEach
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook(Int.MAX_VALUE) {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val application = currentApplication() ?: return
                            if (Settings.Global.getInt(application.contentResolver, "originicons_themed_launcher_icons", 0) != 1) return
                            val context = param.args.firstOrNull() as? Context ?: application
                            val bitmap = param.result as? Bitmap ?: return
                            val style = MonetThemedIconFactory.moodCubeIconStyle(context)
                            if (!style.themed) return
                            MonetThemedIconFactory.recolorVivoThemeIcon(context, bitmap)
                                ?.let { MonetThemedIconFactory.applyMoodTint(it, style.tint) ?: it }
                                ?.let { param.result = it }
                        }
                    })
                }
            }.onFailure(XposedBridge::log)
        }
    }

    private fun hookMonetRedrawnIcons(classLoader: ClassLoader) {
        listOf(
            "com.vivo.content.IconRedrawManger",
            "com.example.iconredrawmanager.IconRedrawManager"
        ).forEach { className ->
            runCatching {
                val target = Class.forName(className, false, classLoader)
                target.declaredMethods.forEach { method ->
                    if (method.returnType != Bitmap::class.java || java.lang.reflect.Modifier.isAbstract(method.modifiers)) return@forEach
                    if (listOf("create", "get", "redraw").none(method.name::startsWith)) return@forEach
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook(Int.MAX_VALUE) {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val application = currentApplication() ?: return
                            if (Settings.Global.getInt(application.contentResolver, "originicons_themed_launcher_icons", 0) != 1) return
                            val bitmap = param.result as? Bitmap ?: return
                            val style = MonetThemedIconFactory.moodCubeIconStyle(application)
                            if (!style.themed) return
                            MonetThemedIconFactory.recolorVivoThemeIcon(application, bitmap)
                                ?.let { MonetThemedIconFactory.applyMoodTint(it, style.tint) ?: it }
                                ?.let { param.result = it }
                        }
                    })
                }
            }.onFailure(XposedBridge::log)
        }
    }

    private fun hookMonetHotseatIcons(classLoader: ClassLoader) {
        listOf(
            "com.bbk.launcher2.launcherIcon.data.iconcache.IconManager",
            "x8.a",
            "y8.a"
        ).forEach { className ->
            runCatching {
                val target = Class.forName(className, false, classLoader)
                target.declaredMethods.filter { method ->
                    method.returnType == Bitmap::class.java &&
                        method.name in setOf("getExploreHotseatIcon", "getExploreIcon")
                }.forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook(Int.MAX_VALUE) {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val application = currentApplication() ?: return
                            if (Settings.Global.getInt(application.contentResolver, "originicons_themed_launcher_icons", 0) != 1) return
                            val style = MonetThemedIconFactory.moodCubeIconStyle(application)
                            if (!style.themed) return
                            val bitmap = param.result as? Bitmap ?: return
                            MonetThemedIconFactory.recolorVivoThemeIcon(application, bitmap)
                                ?.let { MonetThemedIconFactory.applyMoodTint(it, style.tint) ?: it }
                                ?.let { param.result = it }
                        }
                    })
                }
            }.onFailure(XposedBridge::log)
        }
    }

    private fun hookMusicWidgetAllowLists(classLoader: ClassLoader) {
        hookOriginPlayerMetadata(classLoader)
        val musicListUtils = Class.forName("com.vivo.musicwidgetmix.utils.MusicListUtils", false, classLoader)
        setOf("d", "e", "i").forEach { name ->
            XposedBridge.hookAllMethods(musicListUtils, name, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!allowAllMediaPlayers()) return
                    val context = param.args.firstOrNull { it is Context } as? Context
                    param.result = permissiveMediaPackageList(param.result as? List<*>, context)
                }
            })
        }
        val appUtils = Class.forName(
            "com.vivo.musicwidgetmix.utils.d",
            false,
            classLoader
        )
        appUtils.declaredMethods.filter {
            it.returnType == Boolean::class.javaPrimitiveType && it.parameterTypes.contentEquals(arrayOf(Context::class.java, String::class.java))
        }.map { it.name }.distinct().forEach { name ->
                XposedBridge.hookAllMethods(appUtils, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!allowAllMediaPlayers()) return
                        if (param.args.size == 2 && param.args[0] is Context && param.args[1] is String) {
                            val context = param.args[0] as Context
                            val packageName = param.args[1] as String
                            if (isEligibleMediaPackage(context, packageName)) param.result = name != "W"
                        }
                    }
                })
        }
        setOf("A", "H", "J").forEach { name ->
                XposedBridge.hookAllMethods(appUtils, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (allowAllMediaPlayers() && param.args.size == 1 && param.args[0] is Context) param.result = false
                    }
                })
        }
        setOf("F", "G", "I", "P", "q", "r", "v", "w").forEach { name ->
                XposedBridge.hookAllMethods(appUtils, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!allowAllMediaPlayers()) return
                        if (param.args.size == 1 && param.args[0] is Context && param.result is List<*>) {
                            param.result = permissiveMediaPackageList(param.result as? List<*>, param.args[0] as? Context)
                        }
                    }
                })
        }
        listOf("t3.v", "k4.k0", "k4.m0").forEach { className ->
            runCatching {
                val type = Class.forName(className, false, classLoader)
                type.declaredMethods
                    .filter { it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) }
                    .map { it.name }
                    .distinct()
                    .forEach { methodName ->
                        XposedBridge.hookAllMethods(
                            type,
                            methodName,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!allowAllMediaPlayers()) return
                                    val source = param.result as? List<*> ?: return
                                    val context = currentApplication()
                                    param.setResult(object : ArrayList<Any?>(source) {
                                        override fun contains(element: Any?): Boolean {
                                            if (element is String && isExcludedOriginPlayerPackage(element)) return false
                                            return super.contains(element) ||
                                                (element is String && isEligibleMediaPackage(context, element))
                                        }
                                    })
                                }
                            }
                        )
                    }
            }
        }
    }

    private fun hookOriginPlayerMetadata(classLoader: ClassLoader) {
        if (!originPlayerMetadataHooked.compareAndSet(false, true)) return
        val controller = Class.forName("com.vivo.musicwidgetmix.controller.a3", false, classLoader)
        mapOf(
            "v" to originPlayerTrackNames,
            "e" to originPlayerArtistNames,
            "d" to originPlayerAlbumNames
        ).forEach { (methodName, cache) ->
            XposedBridge.hookAllMethods(controller, methodName, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!allowAllMediaPlayers()) return
                    val packageName = readField(param.thisObject, "f8948b") as? String ?: return
                    val current = (param.result as? String).orEmpty()
                    val metadata = activeOriginPlayerMetadata(currentApplication(), packageName)
                    val resolved = when (methodName) {
                        "v" -> metadata?.let {
                            it.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                                ?.takeIf(String::isNotBlank)
                                ?: it.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf(String::isNotBlank)
                                ?: it.description?.title?.toString()?.takeIf(String::isNotBlank)
                        }
                        "e" -> metadata?.let {
                            it.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                                ?.takeIf(String::isNotBlank)
                                ?: it.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf(String::isNotBlank)
                                ?: it.description?.subtitle?.toString()?.takeIf(String::isNotBlank)
                        }
                        else -> metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)?.takeIf(String::isNotBlank)
                    }
                    val value = if (methodName == "v") {
                        resolved ?: validOriginPlayerTrack(currentApplication(), packageName, current) ?: cache[packageName]
                    } else {
                        resolved ?: current.takeIf(String::isNotBlank) ?: cache[packageName]
                    }
                    if (!value.isNullOrBlank()) {
                        cache[packageName] = value
                        param.result = value
                    }
                }
            })
        }
        val service = Class.forName("com.vivo.musicwidgetmix.service.MusicWidgetMixService", false, classLoader)
        XposedBridge.hookAllMethods(service, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (allowAllMediaPlayers()) originPlayerService = WeakReference(param.thisObject)
            }
        })
        val mediaCallback = Class.forName("k4.p\$a", false, classLoader)
        XposedBridge.hookAllMethods(mediaCallback, "onMetadataChanged", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!allowAllMediaPlayers()) return
                val outer = readField(param.thisObject, "a") ?: return
                val packageName = readField(outer, "b") as? String ?: return
                val controller = readField(outer, "d") as? android.media.session.MediaController ?: return
                if (!isEligibleMediaController(currentApplication(), controller)) {
                    removeOriginPlayerImmediately(packageName)
                    return
                }
                val metadata = param.args.firstOrNull() as? android.media.MediaMetadata ?: return
                relayOriginPlayerMetadata(packageName, metadata)
            }
        })
        XposedBridge.hookAllMethods(mediaCallback, "onPlaybackStateChanged", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!allowAllMediaPlayers()) return
                val outer = readField(param.thisObject, "a") ?: return
                val packageName = readField(outer, "b") as? String ?: return
                val controller = readField(outer, "d") as? android.media.session.MediaController ?: return
                if (!isEligibleMediaController(currentApplication(), controller)) {
                    removeOriginPlayerImmediately(packageName)
                    return
                }
                val metadata = controller.metadata ?: return
                relayOriginPlayerMetadata(packageName, metadata)
            }
        })
        XposedBridge.hookAllMethods(mediaCallback, "onSessionDestroyed", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!allowAllMediaPlayers()) return
                val outer = readField(param.thisObject, "a") ?: return
                val packageName = readField(outer, "b") as? String ?: return
                removeOriginPlayerImmediately(packageName)
            }
        })
        mapOf(
            "E0" to Pair("f9287q", originPlayerTrackNames),
            "r0" to Pair("f9288r", originPlayerArtistNames)
        ).forEach { (methodName, source) ->
            XposedBridge.hookAllMethods(service, methodName, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!allowAllMediaPlayers()) return
                    val packageName = readField(param.thisObject, "f9278h") as? String ?: return
                    val current = (param.result as? String).orEmpty()
                    val previous = readField(param.thisObject, source.first) as? String
                    val metadata = activeOriginPlayerMetadata(currentApplication(), packageName)
                    val active = if (methodName == "E0") {
                        metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)?.takeIf(String::isNotBlank)
                            ?: metadata?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf(String::isNotBlank)
                            ?: metadata?.description?.title?.toString()?.takeIf(String::isNotBlank)
                    } else {
                        metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)?.takeIf(String::isNotBlank)
                            ?: metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf(String::isNotBlank)
                            ?: metadata?.description?.subtitle?.toString()?.takeIf(String::isNotBlank)
                    }
                    val value = if (methodName == "E0") {
                        active
                            ?: validOriginPlayerTrack(currentApplication(), packageName, current)
                            ?: validOriginPlayerTrack(currentApplication(), packageName, previous)
                            ?: source.second[packageName]
                    } else {
                        active ?: current.takeIf(String::isNotBlank) ?: previous?.takeIf(String::isNotBlank) ?: source.second[packageName]
                    }
                    if (!value.isNullOrBlank()) {
                        source.second[packageName] = value
                        param.result = value
                    }
                }
            })
        }
        XposedBridge.hookAllMethods(service, "X0", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!allowAllMediaPlayers() || param.args.getOrNull(0) != "EVENT_SONG_INFO_CHANGED") return
                val bundle = param.args.getOrNull(1) as? Bundle ?: return
                val packageName = readField(param.thisObject, "f9278h") as? String ?: return
                val track = bundle.getString("trackName").orEmpty()
                val artist = bundle.getString("artistName").orEmpty()
                val metadata = activeOriginPlayerMetadata(currentApplication(), packageName)
                val resolvedTrack = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)?.takeIf(String::isNotBlank)
                    ?: metadata?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf(String::isNotBlank)
                    ?: metadata?.description?.title?.toString()?.takeIf(String::isNotBlank)
                    ?: track.takeIf(String::isNotBlank)
                    ?: (readField(param.thisObject, "f9287q") as? String)?.takeIf(String::isNotBlank)
                    ?: originPlayerTrackNames[packageName]
                val resolvedArtist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)?.takeIf(String::isNotBlank)
                    ?: metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf(String::isNotBlank)
                    ?: metadata?.description?.subtitle?.toString()?.takeIf(String::isNotBlank)
                    ?: artist.takeIf(String::isNotBlank)
                    ?: (readField(param.thisObject, "f9288r") as? String)?.takeIf(String::isNotBlank)
                    ?: originPlayerArtistNames[packageName]
                if (!resolvedTrack.isNullOrBlank()) {
                    originPlayerTrackNames[packageName] = resolvedTrack
                    bundle.putString("trackName", resolvedTrack)
                }
                if (!resolvedArtist.isNullOrBlank()) {
                    originPlayerArtistNames[packageName] = resolvedArtist
                    bundle.putString("artistName", resolvedArtist)
                }
            }
        })
        listOf(
            "com.vivo.musicwidgetmix.view.steep.island.IslandMusicWidget\$g" to "mPackageName",
            "com.vivo.musicwidgetmix.view.steep.lockview.LockScreenMusicWidgetV1\$a" to "packageName"
        ).forEach { (className, packageField) ->
            val callback = Class.forName(className, false, classLoader)
            installOriginPlayerDisplayCallback(callback, packageField)
        }
    }

    private fun relayOriginPlayerMetadata(packageName: String, metadata: android.media.MediaMetadata) {
        val track = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)?.takeIf(String::isNotBlank)
            ?: metadata.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf(String::isNotBlank)
            ?: metadata.description?.title?.toString()?.takeIf(String::isNotBlank)
            ?: return
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)?.takeIf(String::isNotBlank)
            ?: metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf(String::isNotBlank)
            ?: metadata.description?.subtitle?.toString()?.takeIf(String::isNotBlank)
            ?: ""
        val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val mediaId = metadata.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val service = originPlayerService.get() ?: return
        val identity = "$track\u001f$artist\u001f$album\u001f$mediaId"
        if (originPlayerRelayedMetadata[packageName] == identity) return
        originPlayerRelayedMetadata[packageName] = identity
        originPlayerTrackNames[packageName] = track
        originPlayerArtistNames[packageName] = artist
        originPlayerAlbumNames[packageName] = album
        val event = Bundle().apply {
            putString("musicId", "$packageName:${track.hashCode()}:${artist.hashCode()}")
            putString("trackName", track)
            putString("artistName", artist)
            putString("albumName", album)
            putString("packageName", packageName)
            putInt("musicType", 1)
            putInt("supportEvent", 0)
        }
        mainHandler.post {
            runCatching {
                service.javaClass.getDeclaredMethod("X0", String::class.java, Bundle::class.java)
                    .apply { isAccessible = true }
                    .invoke(service, "EVENT_SONG_INFO_CHANGED", event)
            }.onFailure(XposedBridge::log)
        }
    }

    private fun installOriginPlayerDisplayCallback(callback: Class<*>, packageField: String) {
        synchronized(originPlayerDisplayCallbacks) {
            if (originPlayerDisplayCallbacks.put(callback, true) != null) return
        }
        XposedBridge.hookAllMethods(callback, "g", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!allowAllMediaPlayers() || param.args.size < 3) return
                val outer = readField(param.thisObject, "this\$0") ?: return
                val packageName = readField(outer, packageField) as? String ?: return
                val incomingTrack = (param.args[1] as? String).orEmpty()
                val incomingArtist = (param.args[2] as? String).orEmpty()
                val context = readField(outer, "mContext") as? Context
                val metadata = activeOriginPlayerMetadata(context, packageName)
                val validIncomingTrack = validOriginPlayerTrack(context, packageName, incomingTrack)
                val metadataTrack = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                    ?.takeIf(String::isNotBlank)
                    ?: metadata?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf(String::isNotBlank)
                    ?: metadata?.description?.title?.toString()?.takeIf(String::isNotBlank)
                val metadataArtist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                    ?.takeIf(String::isNotBlank)
                    ?: metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf(String::isNotBlank)
                    ?: metadata?.description?.subtitle?.toString()?.takeIf(String::isNotBlank)
                val outerTrack = ((readField(outer, "mTrackName") ?: readField(outer, "trackName")) as? String)
                    ?.takeIf(String::isNotBlank)
                val outerArtist = ((readField(outer, "mArtistName") ?: readField(outer, "artistName")) as? String)
                    ?.takeIf(String::isNotBlank)
                val resolvedTrack = originPlayerAppLabel(context, packageName) ?: validIncomingTrack ?: metadataTrack
                    ?: originPlayerTrackNames[packageName] ?: outerTrack
                val resolvedArtist = incomingArtist.takeIf(String::isNotBlank) ?: metadataArtist
                    ?: originPlayerArtistNames[packageName] ?: outerArtist
                if (!resolvedTrack.isNullOrBlank()) {
                    originPlayerTrackNames[packageName] = resolvedTrack
                    param.args[1] = resolvedTrack
                }
                if (!resolvedArtist.isNullOrBlank()) {
                    originPlayerArtistNames[packageName] = resolvedArtist
                    param.args[2] = resolvedArtist
                }
                if (!resolvedTrack.isNullOrBlank()) {
                    param.args[0] = "$packageName:${resolvedTrack.hashCode()}:${resolvedArtist.orEmpty().hashCode()}"
                }
            }
        })
    }

    private fun activeOriginPlayerMetadata(context: Context?, packageName: String): android.media.MediaMetadata? {
        if (context == null) return null
        return runCatching {
            val manager = context.getSystemService(android.media.session.MediaSessionManager::class.java)
            val sessions = runCatching { manager.getActiveSessions(null) }.getOrElse {
                manager.getActiveSessions(ComponentName(context, android.service.notification.NotificationListenerService::class.java))
            }
            sessions.firstOrNull {
                it.packageName == packageName && isEligibleMediaController(context, it)
            }?.metadata
        }.getOrNull()
    }

    private fun isEligibleMediaPackage(context: Context?, packageName: String): Boolean {
        if (context == null || packageName.isBlank() || isExcludedOriginPlayerPackage(packageName)) return false
        return runCatching {
            val manager = context.getSystemService(android.media.session.MediaSessionManager::class.java)
            val sessions = runCatching { manager.getActiveSessions(null) }.getOrElse {
                manager.getActiveSessions(ComponentName(context, android.service.notification.NotificationListenerService::class.java))
            }
            sessions.any { it.packageName == packageName && isEligibleMediaController(context, it) }
        }.getOrDefault(false)
    }

    private fun isEligibleMediaController(context: Context?, controller: android.media.session.MediaController): Boolean {
        if (isExcludedOriginPlayerPackage(controller.packageName)) return false
        val playbackState = controller.playbackState ?: return false
        val active = playbackState.state in setOf(
            android.media.session.PlaybackState.STATE_PLAYING,
            android.media.session.PlaybackState.STATE_BUFFERING,
            android.media.session.PlaybackState.STATE_CONNECTING,
            android.media.session.PlaybackState.STATE_SKIPPING_TO_NEXT,
            android.media.session.PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            android.media.session.PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM
        )
        val paused = playbackState.state == android.media.session.PlaybackState.STATE_PAUSED &&
            SystemClock.elapsedRealtime() - playbackState.lastPositionUpdateTime < 30L * 60L * 1000L
        if (!active && !paused) return false
        val contentType = activeAudioContentType(context, controller.packageName)
        if (contentType == android.media.AudioAttributes.CONTENT_TYPE_MOVIE) return false
        if (contentType == android.media.AudioAttributes.CONTENT_TYPE_MUSIC) return true
        val metadata = controller.metadata ?: return false
        return metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty().isNotBlank() ||
            metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty().isNotBlank() ||
            metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM).orEmpty().isNotBlank() ||
            metadata.getString(android.media.MediaMetadata.METADATA_KEY_GENRE).orEmpty().isNotBlank() ||
            metadata.getLong(android.media.MediaMetadata.METADATA_KEY_TRACK_NUMBER) > 0L
    }

    private fun activeAudioContentType(context: Context?, packageName: String): Int? {
        if (context == null) return null
        return runCatching {
            val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
            val manager = context.getSystemService(android.media.AudioManager::class.java)
            manager.activePlaybackConfigurations.firstOrNull { configuration ->
                runCatching {
                    configuration.javaClass.getDeclaredMethod("getClientUid")
                        .apply { isAccessible = true }
                        .invoke(configuration) as Int
                }.getOrNull() == uid
            }?.audioAttributes?.contentType
        }.getOrNull()
    }

    private fun isExcludedOriginPlayerPackage(packageName: String): Boolean {
        return packageName in setOf("com.vivo.gallery", "com.android.gallery3d")
    }

    private fun removeOriginPlayerImmediately(packageName: String) {
        originPlayerTrackNames.remove(packageName)
        originPlayerArtistNames.remove(packageName)
        originPlayerAlbumNames.remove(packageName)
        originPlayerRelayedMetadata.remove(packageName)
        val service = originPlayerService.get() ?: return
        mainHandler.post {
            listOf("hideMusicCard", "hideLandMusicCard").forEach { methodName ->
                runCatching {
                    service.javaClass.methods.firstOrNull {
                        it.name == methodName && it.parameterTypes.contentEquals(arrayOf(String::class.java))
                    }?.invoke(service, packageName)
                }
            }
        }
    }

    private fun hookOriginPlayerClassLoading(classLoader: ClassLoader) {
        runCatching {
            installMusicMixCardAllowLists(Class.forName("com.vivo.musicmixcard.constants.WhitelistManager", false, classLoader))
        }
        listOf(
            "com.vivo.island.music.RemoteMusicManager",
            "com.vivo.systemuiplugin.keyguard.legacy.keyguard.presenter.remotewidget.KeyguardMusicWidgetManager"
        ).forEach { className ->
            runCatching { installOriginPlayerRemoteHost(Class.forName(className, false, classLoader)) }
        }
        runCatching {
            installOriginPlayerTitleView(Class.forName("com.vivo.musicwidgetmix.view.steep.MusicAnimTextView", false, classLoader))
        }
        if (!originPlayerClassLoadingHooked.compareAndSet(false, true)) return
        XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val loadedClass = param.result as? Class<*> ?: return
                if (loadedClass.name == "com.vivo.musicmixcard.constants.WhitelistManager") {
                    installMusicMixCardAllowLists(loadedClass)
                }
                when (loadedClass.name) {
                    "com.vivo.musicwidgetmix.view.steep.island.IslandMusicWidget\$g" ->
                        installOriginPlayerDisplayCallback(loadedClass, "mPackageName")
                    "com.vivo.musicwidgetmix.view.steep.lockview.LockScreenMusicWidgetV1\$a" ->
                        installOriginPlayerDisplayCallback(loadedClass, "packageName")
                    "com.vivo.island.music.RemoteMusicManager",
                    "com.vivo.systemuiplugin.keyguard.legacy.keyguard.presenter.remotewidget.KeyguardMusicWidgetManager" ->
                        installOriginPlayerRemoteHost(loadedClass)
                    "com.vivo.musicwidgetmix.view.steep.MusicAnimTextView" ->
                        installOriginPlayerTitleView(loadedClass)
                }
            }
        })
    }

    private fun installOriginPlayerRemoteHost(type: Class<*>) {
        synchronized(originPlayerRemoteHosts) {
            if (originPlayerRemoteHosts.put(type, true) != null) return
        }
        XposedBridge.hookAllMethods(type, "init", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!allowAllMediaPlayers()) return
                val context = readField(param.thisObject, "mWidgetContext") as? Context ?: return
                val loader = context.classLoader
                runCatching {
                    installOriginPlayerTitleView(
                        Class.forName("com.vivo.musicwidgetmix.view.steep.MusicAnimTextView", false, loader)
                    )
                }
                runCatching {
                    installOriginPlayerDisplayCallback(
                        Class.forName("com.vivo.musicwidgetmix.view.steep.island.IslandMusicWidget\$g", false, loader),
                        "mPackageName"
                    )
                }
                runCatching {
                    installOriginPlayerDisplayCallback(
                        Class.forName("com.vivo.musicwidgetmix.view.steep.lockview.LockScreenMusicWidgetV1\$a", false, loader),
                        "packageName"
                    )
                }
            }
        })
    }

    private fun installOriginPlayerTitleView(type: Class<*>) {
        synchronized(originPlayerTitleViews) {
            if (originPlayerTitleViews.put(type, true) != null) return
        }
        XposedBridge.hookAllMethods(type, "setText", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!allowAllMediaPlayers() || param.args.isEmpty()) return
                val view = param.thisObject as? View ?: return
                val resourceName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
                if (resourceName != "track_name") return
                val host = generateSequence(view.parent) { (it as? View)?.parent }
                    .filterIsInstance<View>()
                    .firstOrNull {
                        it.javaClass.name == "com.vivo.musicwidgetmix.view.steep.island.IslandMusicWidget" ||
                            it.javaClass.name == "com.vivo.musicwidgetmix.view.steep.lockview.LockScreenMusicWidgetV1"
                    }
                if (host != null) {
                    val packageName = ((readField(host, "mPackageName") ?: readField(host, "packageName")) as? String)
                        ?.takeIf(String::isNotBlank)
                        ?: activeMediaPackage(view.context).takeIf(String::isNotBlank)
                    val label = packageName?.let { originPlayerAppLabel(view.context, it) }
                    if (!label.isNullOrBlank()) param.args[0] = label
                    return
                }
                val packageName = activeMediaPackage(view.context).takeIf(String::isNotBlank) ?: return
                val metadata = activeOriginPlayerMetadata(view.context, packageName)
                val incoming = (param.args[0] as? String).orEmpty()
                val incomingTitle = validOriginPlayerTrack(view.context, packageName, incoming)
                val metadataTitle = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                    ?.takeIf(String::isNotBlank)
                    ?: metadata?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf(String::isNotBlank)
                    ?: metadata?.description?.title?.toString()?.takeIf(String::isNotBlank)
                val title = metadataTitle ?: incomingTitle ?: originPlayerTrackNames[packageName]
                if (!title.isNullOrBlank()) originPlayerTrackNames[packageName] = title
                if (!title.isNullOrBlank()) param.args[0] = title
            }
        })
    }

    private fun validOriginPlayerTrack(context: Context?, packageName: String, value: String?): String? {
        val track = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        val appLabel = context?.let {
            runCatching {
                val info = it.packageManager.getApplicationInfo(packageName, 0)
                it.packageManager.getApplicationLabel(info).toString().trim()
            }.getOrNull()
        }
        return track.takeUnless { !appLabel.isNullOrBlank() && it.equals(appLabel, true) }
    }

    private fun originPlayerAppLabel(context: Context?, packageName: String): String? {
        if (context == null || packageName.isBlank()) return null
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString().trim().takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private fun installMusicMixCardAllowLists(type: Class<*>) {
        if (!originPlayerMixCardHooked.compareAndSet(false, true)) return
        setOf("getIsLandWhitelist", "getMusicWhitelist", "getSupportWhitelist", "getThirdMusicWhitelist").forEach { name ->
            XposedBridge.hookAllMethods(type, name, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!allowAllMediaPlayers()) return
                    param.result = permissiveMediaPackageList(param.result as? List<*>, currentApplication())
                }
            })
        }
    }

    private fun permissiveMediaPackageList(source: List<*>?, context: Context?): ArrayList<Any?> {
        val values = ArrayList<Any?>()
        source?.forEach { if (!values.contains(it)) values.add(it) }
        activeMediaPackages(context).forEach { if (!values.contains(it)) values.add(it) }
        return object : ArrayList<Any?>(values) {
            override fun contains(element: Any?): Boolean {
                if (element is String && isExcludedOriginPlayerPackage(element)) return false
                return super.contains(element) ||
                    (element is String && isEligibleMediaPackage(context, element))
            }
        }
    }

    private fun allowAllMediaPlayers(): Boolean {
        val application = currentApplication() ?: return false
        return Settings.Global.getInt(
            application.contentResolver,
            "originicons_allow_all_media_players",
            0
        ) == 1
    }

    private fun activeMediaPackages(context: Context?): ArrayList<String> {
        if (context == null) return arrayListOf()
        return runCatching {
            val manager = context.getSystemService(android.media.session.MediaSessionManager::class.java)
            val sessions = runCatching { manager.getActiveSessions(null) }.getOrElse {
                manager.getActiveSessions(ComponentName(context, android.service.notification.NotificationListenerService::class.java))
            }
            ArrayList(sessions.filter { isEligibleMediaController(context, it) }.sortedByDescending {
                when (it.playbackState?.state) {
                    android.media.session.PlaybackState.STATE_PLAYING -> 3
                    android.media.session.PlaybackState.STATE_BUFFERING,
                    android.media.session.PlaybackState.STATE_CONNECTING -> 2
                    android.media.session.PlaybackState.STATE_PAUSED -> 1
                    else -> 0
                }
            }.map { it.packageName }.distinct())
        }.getOrDefault(arrayListOf())
    }

    private fun activeMediaPackage(context: Context?): String {
        return activeMediaPackages(context).firstOrNull() ?: "com.android.bbkmusic"
    }

    private fun hookAospVolumeBar(classLoader: ClassLoader) {
        val factory = Class.forName(
            "com.android.systemui.volume.VolumeDialogComponent_Factory",
            false,
            classLoader
        )
        XposedBridge.hookAllMethods(
            factory,
            "get",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val application = currentApplication() ?: return
                    if (!aospVolumeEnabled(application)) return
                    val component = param.result ?: return
                    if (component.javaClass.name != "com.android.systemui.volume.VolumeDialogComponent") return
                    val provider = readField(param.thisObject, "volumeDialogProvider") ?: return
                    val aospDialog = provider.javaClass.methods.firstOrNull {
                        it.name == "get" && it.parameterCount == 0
                    }?.invoke(provider) ?: return
                    aospVolumeDialogs[component] = aospDialog
                    activateAospVolumeDialog(component, aospDialog)
                }
            }
        )
        val callback = Class.forName(
            "com.android.systemui.volume.VolumeDialogComponent\$\$ExternalSyntheticLambda1",
            false,
            classLoader
        )
        XposedBridge.hookAllMethods(
            callback,
            "accept",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val application = currentApplication() ?: return
                    if (!aospVolumeEnabled(application)) return
                    if ((readField(param.thisObject, "\$r8\$classId") as? Int) != 0) return
                    val component = readField(param.thisObject, "f\$0") ?: return
                    val aospDialog = aospVolumeDialogs[component] ?: return
                    if (readField(component, "mDialog") !== aospDialog) {
                        activateAospVolumeDialog(component, aospDialog)
                    }
                    param.setResult(null)
                }
            }
        )
        val dialog = Class.forName(
            "com.android.systemui.volume.VolumeDialogImpl",
            false,
            classLoader
        )
        XposedBridge.hookAllMethods(
            dialog,
            "updateVolumeRowTintH",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val application = currentApplication() ?: return
                    if (!aospVolumeEnabled(application)) return
                    val row = param.args.firstOrNull() ?: return
                    val tint = ColorStateList.valueOf(resolveSystemAccent(application))
                    (readField(row, "sliderProgressSolid") as? Drawable)?.setTintList(tint)
                    (readField(row, "slider") as? SeekBar)?.apply {
                        progressTintList = tint
                        thumbTintList = tint
                    }
                    (readField(row, "number") as? TextView)?.setTextColor(tint)
                }
            }
        )
    }

    private fun aospVolumeEnabled(application: Application): Boolean {
        return Settings.Global.getInt(application.contentResolver, "originicons_aosp_volume_bar", 0) == 1 ||
            Settings.Global.getInt(application.contentResolver, MaterialOriginOsHook.SETTING_VOLUME, 0) == 1
    }

    private fun resolveSystemAccent(context: Context): Int {
        val customization = Settings.Secure.getString(
            context.contentResolver,
            "theme_customization_overlay_packages"
        ).orEmpty()
        val encoded = Regex("\\\"android\\.theme\\.customization\\.accent_color\\\"\\s*:\\s*\\\"([0-9a-fA-F]+)\\\"")
            .find(customization)
            ?.groupValues
            ?.getOrNull(1)
        if (!encoded.isNullOrBlank()) {
            val normalized = if (encoded.length <= 6) "ff$encoded" else encoded.takeLast(8)
            normalized.toLongOrNull(16)?.let { return it.toInt() }
        }
        val dark = context.resources.configuration.uiMode and 0x30 == 0x20
        val name = if (dark) "system_accent1_200" else "system_accent1_600"
        val id = context.resources.getIdentifier(name, "color", "android")
        return if (id != 0) context.getColor(id) else 0xff567cf8.toInt()
    }

    private fun activateAospVolumeDialog(component: Any, aospDialog: Any) {
        val current = readField(component, "mDialog")
        if (current !== aospDialog) {
            current?.javaClass?.methods?.firstOrNull {
                it.name == "destroy" && it.parameterCount == 0
            }?.invoke(current)
        }
        writeField(component, "mDialog", aospDialog)
        val callback = readField(component, "mVolumeDialogCallback") ?: return
        aospDialog.javaClass.methods.firstOrNull {
            it.name == "init" && it.parameterCount == 2
        }?.invoke(aospDialog, 2020, callback)
    }

    private fun readField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            runCatching {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(instance)
            }
            type = type.superclass
        }
        return null
    }

    private fun writeField(instance: Any, name: String, value: Any?) {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val written = runCatching {
                type.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
            }.isSuccess
            if (written) return
            type = type.superclass
        }
    }

    private fun hookNotificationShadeFilter(classLoader: ClassLoader) {
        val notificationListener = Class.forName(
            "com.android.systemui.statusbar.NotificationListener",
            false,
            classLoader
        )
        XposedBridge.hookAllMethods(
            notificationListener,
            "onNotificationPosted",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val sbn = param.args.filterIsInstance<StatusBarNotification>().firstOrNull() ?: return
                    val application = currentApplication() ?: return
                    if (sbn.packageName == "com.vivo.daemonService" && Settings.Global.getInt(
                            application.contentResolver,
                            "originicons_hide_vivo_service_error",
                            0
                        ) == 1
                    ) {
                        param.setResult(null)
                        return
                    }
                }
            }
        )
    }

    private fun hookBlueLmToasts(classLoader: ClassLoader) {
        val toastClass = Class.forName("android.widget.Toast", false, classLoader)
        XposedBridge.hookAllMethods(
            toastClass,
            "show",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.setResult(null)
                }
            }
        )
    }

    private fun hookPrivateDnsWithVpn(classLoader: ClassLoader) {
        val vpnClass = Class.forName("com.android.server.connectivity.Vpn", false, classLoader)
        listOf("updateState", "agentConnect", "agentDisconnect", "startNewNetworkAgent", "unregisterNetworkAgent").forEach { methodName ->
            XposedBridge.hookAllMethods(
                vpnClass,
                methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = currentApplication() ?: contextFromController(param.thisObject) ?: return
                        schedulePrivateDnsSync(context)
                    }
                }
            )
        }
    }

    private fun hookSettingsContainerSpacing(classLoader: ClassLoader) {
        hookAdaptiveFragmentLifecycle(classLoader)
        hookAdaptiveSwitchColors(classLoader)
        hookWifiNetworkIcons(classLoader)
        XposedBridge.hookAllMethods(
            CompoundButton::class.java,
            "setChecked",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val button = param.thisObject as? CompoundButton ?: return
                    settingsSwitchStates[button]?.value = button.isChecked
                }
            }
        )
        XposedBridge.hookAllMethods(
            Activity::class.java,
            "onPostResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!isSettingsAdaptiveThemeEnabled(activity)) return
                    scheduleSettingsTheme(activity)
                }
            }
        )
        runCatching {
            val listContentClass = Class.forName("com.originui.widget.listitem.VListContent", false, classLoader)
            XposedBridge.hookAllMethods(
                listContentClass,
                "showCardListItemSelector",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        if (isSettingsAdaptiveThemeEnabled(view.context)) styleOriginListCard(view, view.context)
                    }
                }
            )
        }
        runCatching {
            val tintUtils = Class.forName("com.vivo.settings.utils.colortheme.VivoColorTintUtils", false, classLoader)
            XposedBridge.hookAllMethods(
                tintUtils,
                "setBackgroundColorPickTintMode",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.args.firstOrNull() as? Activity ?: return
                        if (isSettingsAdaptiveThemeEnabled(activity)) {
                            scheduleSettingsTheme(activity)
                        }
                    }
                }
            )
        }
        XposedBridge.hookAllMethods(
            Resources::class.java,
            "getColor",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val resources = param.thisObject as? Resources ?: return
                    val id = param.args.firstOrNull() as? Int ?: return
                    val application = currentApplication() ?: return
                    if (!isSettingsAdaptiveThemeEnabled(application)) return
                    val name = runCatching { resources.getResourceEntryName(id).lowercase() }.getOrNull() ?: return
                    val cardResource =
                        (name.contains("card") && (name.contains("background") || name.contains("_bg"))) ||
                            (name.contains("preference") && (name.contains("background") || name.contains("_bg"))) ||
                            name.contains("list_item_selector_background") ||
                            name.contains("focus_mode_rules_pressed_bg")
                    if (!cardResource) return
                    val card = settingsCardColor(application)
                    param.result = card
                }
            }
        )
        val adapterClass = Class.forName("androidx.recyclerview.widget.RecyclerView\$Adapter", false, classLoader)
        XposedBridge.hookAllMethods(
            adapterClass,
            "bindViewHolder",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val application = currentApplication() ?: return
                    val holder = param.args.firstOrNull() ?: return
                    val position = param.args.filterIsInstance<Int>().firstOrNull() ?: return
                    val view = readField(holder, "itemView") as? View ?: return
                    val layout = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                    val originalTopMargin = settingsOriginalTopMargins[view] ?: layout.topMargin.also {
                        settingsOriginalTopMargins[view] = it
                    }
                    val originalHorizontalMargins = settingsOriginalHorizontalMargins[view] ?: (layout.leftMargin to layout.rightMargin).also {
                        settingsOriginalHorizontalMargins[view] = it
                    }
                    val enabled = Settings.Global.getInt(
                        application.contentResolver,
                        "originroottoolbox_settings_adaptive_theme",
                        0
                    ) == 1
                    val gap = Settings.Global.getInt(
                        application.contentResolver,
                        "originroottoolbox_settings_container_gap",
                        8
                    )
                    layout.topMargin = if (enabled && position > 0) originalTopMargin + dp(application, gap.coerceIn(0, 32)) else originalTopMargin
                    layout.leftMargin = originalHorizontalMargins.first
                    layout.rightMargin = originalHorizontalMargins.second
                    view.layoutParams = layout
                    if (enabled) styleSettingsItem(view, view.context)
                    view.requestLayout()
                }
            }
        )
        XposedBridge.log("OriginIcons Settings RecyclerView spacing hook ready")
    }

    private fun hookExternalSettingsTheme(classLoader: ClassLoader) {
        hookAdaptiveFragmentLifecycle(classLoader)
        hookAdaptiveSwitchColors(classLoader)
        XposedBridge.hookAllMethods(
            Activity::class.java,
            "onPostResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!isSettingsAdaptiveThemeEnabled(activity)) return
                    scheduleSettingsTheme(activity)
                }
            }
        )
        XposedBridge.hookAllMethods(
            Resources::class.java,
            "getColor",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val resources = param.thisObject as? Resources ?: return
                    val id = param.args.firstOrNull() as? Int ?: return
                    val application = currentApplication() ?: return
                    if (!isSettingsAdaptiveThemeEnabled(application)) return
                    val color = adaptiveCardResourceColor(resources, id, application) ?: return
                    param.result = color
                }
            }
        )
        runCatching {
            val listContentClass = Class.forName("com.originui.widget.listitem.VListContent", false, classLoader)
            XposedBridge.hookAllMethods(
                listContentClass,
                "showCardListItemSelector",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        if (isSettingsAdaptiveThemeEnabled(view.context)) styleOriginListCard(view, view.context)
                    }
                }
            )
        }
    }

    private fun hookAdaptiveFragmentLifecycle(classLoader: ClassLoader) {
        runCatching {
            val fragmentClass = Class.forName("androidx.fragment.app.Fragment", false, classLoader)
            XposedBridge.hookAllMethods(
                fragmentClass,
                "performResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val fragment = param.thisObject ?: return
                        val activity = runCatching {
                            fragment.javaClass.methods.firstOrNull {
                                it.name == "getActivity" && it.parameterCount == 0
                            }?.invoke(fragment) as? Activity
                        }.getOrNull() ?: return
                        if (!isSettingsAdaptiveThemeEnabled(activity)) return
                        scheduleSettingsTheme(activity)
                    }
                }
            )
        }
    }

    private fun hookAdaptiveSwitchColors(classLoader: ClassLoader) {
        listOf(
            "com.originui.widget.components.switches.VMoveBoolButton",
            "com.originui.widget.components.switches.VLoadingMoveBoolButton",
            "com.vivo.common.widget.components.switches.VMoveBoolButton"
        ).forEach { className ->
            runCatching {
                val switchClass = Class.forName(className, false, classLoader)
                listOf("onAttachedToWindow", "setChecked").forEach { methodName ->
                    XposedBridge.hookAllMethods(
                        switchClass,
                        methodName,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val view = param.thisObject as? View ?: return
                                if (isSettingsAdaptiveThemeEnabled(view.context)) styleAdaptiveSwitch(view)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun hookWifiNetworkIcons(classLoader: ClassLoader) {
        listOf(
            "com.vivo.settings.wifi.VivoWifiEntryPreference",
            "com.vivo.settings.wifi.VivoLongPressWifiEntryPreference",
            "com.vivo.settings.wifi.VivoConnectedWifiEntryPreference"
        ).forEach { className ->
            runCatching {
                val preferenceClass = Class.forName(className, false, classLoader)
                XposedBridge.hookAllMethods(
                    preferenceClass,
                    "onBindViewHolder",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val holder = param.args.firstOrNull() ?: return
                            val itemView = readField(holder, "itemView") as? View ?: return
                            if (!isSettingsAdaptiveThemeEnabled(itemView.context)) return
                            itemView.post { styleWifiNetworkIcon(itemView, itemView.context) }
                        }
                    }
                )
            }
        }
    }

    private fun adaptiveCardResourceColor(resources: Resources, id: Int, context: Context): Int? {
        val name = runCatching { resources.getResourceEntryName(id).lowercase() }.getOrNull() ?: return null
        val cardResource =
            (name.contains("card") && (name.contains("background") || name.contains("_bg"))) ||
                (name.contains("preference") && (name.contains("background") || name.contains("_bg"))) ||
                name.contains("list_item_selector_background") ||
                name.contains("focus_mode_rules_pressed_bg")
        return if (cardResource) settingsCardColor(context) else null
    }

    private fun styleSettingsActivity(activity: Activity) {
        val background = settingsBackgroundColor(activity)
        activity.window.statusBarColor = background
        activity.window.navigationBarColor = background
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.setBackgroundColor(background)
        traverseViews(content) { view ->
            val name = viewResourceName(view)
            if (view.javaClass.name.contains("RecyclerView")) view.setBackgroundColor(Color.TRANSPARENT)
            if (name.contains("search_bar", true) || name.contains("search_container", true)) {
                view.background = roundedShape(settingsSearchColor(activity), dp(activity, 28).toFloat())
            }
        }
    }

    private fun styleSettingsActivityNative(activity: Activity) {
        val background = settingsBackgroundColor(activity)
        activity.window.statusBarColor = background
        activity.window.navigationBarColor = background
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.setBackgroundColor(background)
        val screenWidth = content.width.coerceAtLeast(1)
        val screenHeight = content.height.coerceAtLeast(1)
        traverseViews(content) { view ->
            val name = viewResourceName(view)
            val className = view.javaClass.name.lowercase()
            if (view.javaClass.name == "com.originui.widget.listitem.VListContent") {
                styleOriginListCard(view, activity)
                styleSettingsText(view, activity)
                styleSettingsLeadingIcon(view, activity)
                styleSettingsTrailingArrow(view, activity)
            }
            if (view.javaClass.name.contains("RecyclerView")) {
                view.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                val original = settingsOriginalPaddings[view] ?: intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom).also {
                    settingsOriginalPaddings[view] = it
                }
                view.setPadding(
                    original[0],
                    original[1],
                    original[2],
                    original[3]
                )
            } else if (
                view is ViewGroup &&
                view.background != null &&
                view.width >= screenWidth * 0.8f &&
                view.height >= screenHeight * 0.35f &&
                !className.contains("card") &&
                !className.contains("switch") &&
                !name.contains("card", true) &&
                !name.contains("search", true)
            ) {
                view.setBackgroundColor(background)
            }
            if (name.contains("search_bar", true) || name.contains("search_container", true)) {
                view.backgroundTintList = ColorStateList.valueOf(settingsSearchColor(activity))
            }
            if (view is CompoundButton || className.contains("switch") || className.contains("boolbutton")) {
                styleAdaptiveSwitch(view)
            }
        }
        styleSettingsCardContainers(content, activity)
    }

    private fun scheduleSettingsTheme(activity: Activity) {
        val decor = activity.window.decorView
        listOf(0L, 180L, 520L, 1100L).forEach { delay ->
            decor.postDelayed({
                if (!activity.isFinishing && !activity.isDestroyed && isSettingsAdaptiveThemeEnabled(activity)) {
                    styleSettingsActivityNative(activity)
                }
            }, delay)
        }
    }

    private fun styleSettingsItem(root: View, context: Context) {
        styleSettingsCardContainers(root, context, true)
        styleSettingsText(root, context)
        styleSettingsLeadingIcon(root, context)
        styleSettingsTrailingArrow(root, context)
    }

    private fun styleSettingsCardContainers(root: View, context: Context, allowRootFallback: Boolean = false) {
        val card = settingsCardColor(context)
        val pressed = blendSettingsColor(card, if (isDarkTheme(context)) Color.WHITE else Color.BLACK, 0.08f)
        val tint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(pressed, pressed, card)
        )
        traverseViews(root) { view ->
            if (view.javaClass.name == "com.originui.widget.listitem.VListContent") {
                styleOriginListCard(view, context)
                return@traverseViews
            }
            val className = view.javaClass.name.lowercase()
            val resourceName = viewResourceName(view).lowercase()
            if (className.contains("cardview") || resourceName.contains("card_container") || resourceName.contains("card_layout")) {
                val applied = runCatching {
                    val method = view.javaClass.methods.firstOrNull {
                        it.name == "setCardBackgroundColor" && it.parameterCount == 1
                    } ?: return@runCatching false
                    if (method.parameterTypes[0] == ColorStateList::class.java) method.invoke(view, ColorStateList.valueOf(card))
                    else method.invoke(view, card)
                    true
                }.getOrDefault(false)
                if (!applied && view.background != null) view.backgroundTintList = tint
                return@traverseViews
            }
            if (!allowRootFallback || view !== root) return@traverseViews
            if (view !is ViewGroup || view.background == null) return@traverseViews
            val name = viewResourceName(view).lowercase()
            if (
                name.contains("icon") ||
                name.contains("arrow") ||
                name.contains("switch") ||
                name.contains("checkbox") ||
                name.contains("radio") ||
                className.contains("switch")
            ) return@traverseViews
            view.backgroundTintList = tint
        }
    }

    private fun styleOriginListCard(view: View, context: Context) {
        val drawable = readField(view, "mVListItemSelectorDrawable") ?: view.background ?: return
        val card = settingsCardColor(context)
        val pressed = blendSettingsColor(card, if (isDarkTheme(context)) Color.WHITE else Color.BLACK, 0.08f)
        runCatching {
            drawable.javaClass.methods.firstOrNull {
                it.name == "setBackgroundDrawableColor" && it.parameterCount == 1
            }?.invoke(drawable, ColorStateList.valueOf(card))
        }
        runCatching {
            drawable.javaClass.methods.firstOrNull {
                it.name == "setColor" && it.parameterCount == 1
            }?.invoke(drawable, ColorStateList.valueOf(pressed))
        }
        if (view.background !== drawable) view.background = drawable as? Drawable
        view.invalidate()
    }

    private fun styleAdaptiveSwitch(view: View) {
        val context = view.context
        val primary = resolveSystemAccent(context)
        val onPrimary = if (colorLuminance(primary) > 0.52f) Color.BLACK else Color.WHITE
        val off = blendSettingsColor(
            primary,
            if (isDarkTheme(context)) Color.BLACK else settingsBackgroundColor(context),
            if (isDarkTheme(context)) 0.34f else 0.28f
        )
        val applied = invokeSettingsMethod(
            view,
            "setSwitchColors",
            ColorStateList.valueOf(off),
            ColorStateList.valueOf(primary),
            ColorStateList.valueOf(off),
            ColorStateList.valueOf(primary),
            ColorStateList.valueOf(off),
            ColorStateList.valueOf(onPrimary)
        )
        if (applied) {
            view.invalidate()
            return
        }
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
        val track = ColorStateList(states, intArrayOf(primary, off))
        val thumb = ColorStateList(states, intArrayOf(onPrimary, off))
        invokeSettingsMethod(view, "setTrackTintList", track)
        invokeSettingsMethod(view, "setThumbTintList", thumb)
        view.invalidate()
    }

    private fun invokeSettingsMethod(instance: Any, name: String, vararg arguments: Any?): Boolean {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val method = type.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == arguments.size
            }
            if (method != null) {
                return runCatching {
                    method.isAccessible = true
                    method.invoke(instance, *arguments)
                    true
                }.getOrDefault(false)
            }
            type = type.superclass
        }
        return false
    }

    private fun styleWifiNetworkIcon(root: View, context: Context) {
        val icon = root.findViewById<ImageView>(android.R.id.icon) ?: findSettingsLeadingImageView(root) ?: return
        val typeface = resolveSettingsSymbolTypeface(context) ?: return
        val palette = settingsIconPalette(context, 1)
        val size = dp(context, 40)
        val padding = dp(context, 6)
        icon.layoutParams = icon.layoutParams?.apply {
            width = size
            height = size
        }
        icon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.first)
        }
        icon.setPadding(padding, padding, padding, padding)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        icon.imageTintList = null
        icon.setImageDrawable(MaterialSymbolDrawable(materialSymbolGlyph("wifi"), typeface, palette.second))
        icon.visibility = View.VISIBLE
    }

    private fun isSettingsAdaptiveThemeEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            "originroottoolbox_settings_adaptive_theme",
            0
        ) == 1
    }

    private fun replaceSettingsSwitch(root: View, context: Context) {
        val original = findSettingsSwitch(root) ?: return
        if (settingsComposeSwitches.containsKey(original)) return
        val parent = original.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(original)
        if (index < 0) return
        val checked = readSettingsSwitchChecked(original) ?: false
        val state = mutableStateOf(checked)
        val composeView = ComposeView(context).apply {
            id = original.id
            contentDescription = original.contentDescription
            isEnabled = original.isEnabled
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                SettingsExpressiveSwitch(
                    checked = state.value,
                    enabled = isEnabled
                ) { desired ->
                    val handled = original.performClick()
                    if (!handled) writeSettingsSwitchChecked(original, desired)
                    post { state.value = readSettingsSwitchChecked(original) ?: desired }
                }
            }
        }
        val params = original.layoutParams.apply {
            width = dp(context, 52)
            height = dp(context, 48)
        }
        settingsSwitchStates[original] = state
        settingsComposeSwitches[original] = composeView
        parent.removeViewAt(index)
        parent.addView(composeView, index, params)
    }

    private fun findSettingsSwitch(root: View): View? {
        var result: View? = null
        traverseViews(root) { view ->
            if (result != null || view is ComposeView) return@traverseViews
            val name = viewResourceName(view)
            val className = view.javaClass.simpleName
            if (
                view is CompoundButton ||
                className.contains("Switch", true) ||
                className.contains("BoolButton", true) ||
                name == "switchWidget" ||
                name == "switch_btn" ||
                name == "sud_items_switch"
            ) result = view
        }
        return result
    }

    private fun readSettingsSwitchChecked(view: View): Boolean? {
        if (view is CompoundButton) return view.isChecked
        return view.javaClass.methods.firstOrNull {
            (it.name == "isChecked" || it.name == "getChecked") && it.parameterTypes.isEmpty()
        }?.let { runCatching { it.invoke(view) as? Boolean }.getOrNull() }
    }

    private fun writeSettingsSwitchChecked(view: View, checked: Boolean) {
        if (view is CompoundButton) {
            view.isChecked = checked
            return
        }
        view.javaClass.methods.firstOrNull {
            it.name == "setChecked" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }?.let { runCatching { it.invoke(view, checked) } }
    }

    private fun isVivoAccountCard(root: View, title: String, position: Int): Boolean {
        val normalized = title.lowercase()
        if (normalized.contains("vivo") || normalized.contains("vivo account") || normalized.contains("vivo账号")) return true
        val icon = root.findViewById<ImageView>(android.R.id.icon) ?: findSettingsLeadingImageView(root)
        return position == 0 && icon?.drawable is BitmapDrawable
    }

    private fun applySettingsRoundedClip(view: View, radius: Float, pill: Boolean) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                val width = target.width.coerceAtLeast(1)
                val height = target.height.coerceAtLeast(1)
                outline.setRoundRect(0, 0, width, height, if (pill) height / 2f else radius)
            }
        }
        view.invalidateOutline()
    }

    private fun styleSettingsNativeControls(root: View, context: Context) {
        val primary = resolveSystemAccent(context)
        val inactive = if (isDarkTheme(context)) systemColor(context, "system_neutral2_600", 0xff625b58.toInt())
        else systemColor(context, "system_neutral2_300", 0xffc9bfbb.toInt())
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
        val colors = intArrayOf(primary, inactive)
        val stateList = ColorStateList(states, colors)
        traverseViews(root) { view ->
            if (view is CompoundButton) view.buttonTintList = stateList
            if (view is SeekBar) {
                view.progressTintList = ColorStateList.valueOf(primary)
                view.thumbTintList = ColorStateList.valueOf(primary)
                view.progressBackgroundTintList = ColorStateList.valueOf(inactive)
            }
            runCatching {
                view.javaClass.methods.firstOrNull { it.name == "setThumbTintList" && it.parameterTypes.contentEquals(arrayOf(ColorStateList::class.java)) }
                    ?.invoke(view, stateList)
            }
            runCatching {
                view.javaClass.methods.firstOrNull { it.name == "setTrackTintList" && it.parameterTypes.contentEquals(arrayOf(ColorStateList::class.java)) }
                    ?.invoke(view, stateList)
            }
        }
    }

    private fun styleSettingsText(root: View, context: Context) {
        val title = root.findViewById<TextView>(android.R.id.title)
        val summary = root.findViewById<TextView>(android.R.id.summary)
        title?.apply {
            setTextColor(settingsOnSurfaceColor(context))
        }
        summary?.apply {
            setTextColor(settingsOnSurfaceVariantColor(context))
        }
    }

    private fun styleSettingsLeadingIcon(root: View, context: Context) {
        val title = settingsItemTitle(root) ?: return
        val icon = root.findViewById<ImageView>(android.R.id.icon) ?: findSettingsLeadingImageView(root) ?: return
        val originalDrawable = icon.drawable
        val resourceName = viewResourceName(icon).lowercase()
        if (
            originalDrawable is AnimationDrawable ||
            originalDrawable is Animatable ||
            resourceName.contains("progress") ||
            resourceName.contains("loading") ||
            resourceName.contains("spinner")
        ) return
        val mappedSpec = settingsIconSpec(title)
        if (mappedSpec == null && originalDrawable is BitmapDrawable) return
        val iconSpec = mappedSpec ?: ("tune" to ((title.hashCode() and Int.MAX_VALUE) % 3))
        val typeface = resolveSettingsSymbolTypeface(context) ?: return
        val palette = settingsIconPalette(context, iconSpec.second)
        val size = dp(context, 40)
        val padding = dp(context, 6)
        icon.layoutParams = icon.layoutParams?.apply {
            width = size
            height = size
        }
        icon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.first)
        }
        icon.setPadding(padding, padding, padding, padding)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        icon.imageTintList = null
        icon.setImageDrawable(MaterialSymbolDrawable(materialSymbolGlyph(iconSpec.first), typeface, palette.second))
    }

    private fun styleSettingsTrailingArrow(root: View, context: Context) {
        val targetNames = setOf("arrow", "arrow_blue_type", "arrow_right_type", "submenuarrow")
        val accent = settingsPrimaryContainerColor(context)
        val onAccent = settingsOnPrimaryContainerColor(context)
        traverseViews(root) { view ->
            val resourceName = viewResourceName(view)
            if (resourceName !in targetNames) return@traverseViews
            val size = dp(context, 30)
            val padding = dp(context, 6)
            view.layoutParams = view.layoutParams?.apply {
                width = size
                height = size
            }
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
            view.setPadding(padding, padding, padding, padding)
            if (view is ImageView) {
                val typeface = resolveSettingsSymbolTypeface(context)
                view.imageTintList = null
                if (typeface != null) view.setImageDrawable(MaterialSymbolDrawable(materialSymbolGlyph("chevron_right"), typeface, onAccent))
            }
        }
    }

    private fun settingsItemTitle(root: View): String? {
        root.findViewById<TextView>(android.R.id.title)?.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        var selected: TextView? = null
        traverseViews(root) { view ->
            if (view is TextView && view.text?.isNotBlank() == true && (selected == null || view.textSize > selected!!.textSize)) selected = view
        }
        return selected?.text?.toString()
    }

    private fun settingsIconSpec(title: String): Pair<String, Int>? {
        val value = title.lowercase()
        return when {
            value.contains("wi-fi") || value.contains("wifi") || value.contains("wlan") -> "wifi" to 1
            value.contains("bluetooth") || value.contains("蓝牙") -> "bluetooth" to 1
            value.contains("авиареж") || value.contains("airplane") || value.contains("flight mode") || value.contains("飞行模式") -> "flight" to 0
            value.contains("мобильн") || value.contains("mobile network") || value.contains("cellular") || value.contains("移动网络") -> "signal_cellular_alt" to 2
            value.contains("подключение к устрой") || value.contains("connected device") || value.contains("device connection") || value.contains("设备连接") -> "devices" to 1
            value.contains("главный экран") || value.contains("обои") || value.contains("wallpaper") || value.contains("home screen") || value.contains("桌面") || value.contains("壁纸") -> "palette" to 0
            value.contains("экран и яркость") || value.contains("display") || value.contains("brightness") || value.contains("显示") || value.contains("亮度") -> "brightness_6" to 0
            value.contains("звук") || value.contains("sound") || value.contains("vibration") || value.contains("声音") -> "volume_up" to 2
            value.contains("уведомлен") || value.contains("notification") || value.contains("通知") -> "notifications" to 2
            value.contains("приложен") || value.contains("apps") || value.contains("应用") -> "apps" to 1
            value.contains("батар") || value.contains("battery") || value.contains("电池") -> "battery_full" to 2
            value.contains("хранилищ") || value.contains("storage") || value.contains("存储") -> "storage" to 1
            value.contains("безопас") || value.contains("privacy") || value.contains("security") || value.contains("安全") || value.contains("隐私") -> "shield_lock" to 2
            value.contains("местополож") || value.contains("location") || value.contains("定位") -> "location_on" to 0
            value.contains("парол") || value.contains("аккаунт") || value.contains("account") || value.contains("password") || value.contains("账号") -> "account_circle" to 0
            value.contains("цифровое благополуч") || value.contains("digital wellbeing") || value.contains("健康使用") -> "timer" to 2
            value.contains("специальные возмож") || value.contains("accessibility") || value.contains("无障碍") -> "accessibility_new" to 1
            value.contains("экстрен") || value.contains("emergency") || value.contains("紧急") -> "emergency" to 0
            value.contains("система") || value == "system" || value.contains("系统") -> "settings" to 1
            value.contains("о телефоне") || value.contains("about phone") || value.contains("关于手机") -> "info" to 0
            value.contains("\u043a\u043e\u043d\u0444\u0438\u0434\u0435\u043d\u0446") -> "privacy_tip" to 2
            value.contains("\u0433\u0435\u043e\u043b\u043e\u043a\u0430\u0446") -> "location_on" to 0
            value.contains("\u043e\u0437\u0443") || value.contains("ram") || value.contains("\u043e\u043f\u0435\u0440\u0430\u0442\u0438\u0432\u043d") || value.contains("\u043f\u0430\u043c\u044f\u0442\u044c") -> "memory" to 1
            value.contains("\u043a\u043e\u0448\u0435\u043b\u0435\u043a") || value.contains("\u043a\u043e\u0448\u0435\u043b\u0451\u043a") || value.contains("wallet") || value.contains("\u043f\u043b\u0430\u0442\u0435\u0436") || value.contains("payment") -> "account_balance_wallet" to 0
            value.contains("blue ai") || value.contains("bluelm") || value.contains("blue copilot") || value.contains("jovi") -> "smart_toy" to 1
            value.contains("nfc") -> "nfc" to 1
            value.contains("точка доступа") || value.contains("hotspot") -> "wifi_tethering" to 2
            value.contains("разработчик") || value.contains("developer") -> "code" to 1
            value.contains("сеть и интернет") || value.contains("network and internet") || value.contains("network & internet") || value.contains("网络和互联网") -> "language" to 1
            value.contains("обновлен") || value.contains("update") || value.contains("系统升级") -> "update" to 0
            value.contains("резерв") || value.contains("backup") || value.contains("备份") -> "backup" to 2
            value.contains("сброс") || value.contains("reset") || value.contains("重置") -> "restart_alt" to 0
            value.contains("системное управление") || value.contains("system management") -> "settings" to 1
            value.contains("динамическ") || value.contains("dynamic effect") || value.contains("动态效果") -> "animation" to 0
            value.contains("игров") || value.contains("game mode") || value.contains("游戏") -> "sports_esports" to 2
            value.contains("отпечат") || value.contains("fingerprint") || value.contains("指纹") -> "fingerprint" to 1
            value.contains("распознавание лица") || value.contains("face unlock") || value.contains("面部") -> "face" to 0
            value.contains("блокиров") || value.contains("screen lock") || value.contains("锁屏") -> "screen_lock_portrait" to 2
            value.contains("sim") || value.contains("сим-карт") -> "sim_card" to 1
            value.contains("передача данных") || value.contains("data usage") || value.contains("流量") -> "data_usage" to 2
            value.contains("трансляц") || value.contains("cast") || value.contains("投屏") -> "cast" to 0
            value.contains("темн") || value.contains("dark mode") || value.contains("深色") -> "dark_mode" to 1
            else -> null
        }
    }

    private fun findSettingsLeadingImageView(root: View): ImageView? {
        var preferred: ImageView? = null
        var fallback: ImageView? = null
        traverseViews(root) { view ->
            if (view !is ImageView) return@traverseViews
            val name = viewResourceName(view)
            val excluded = name.contains("arrow", true) || name.contains("chevron", true) || name.contains("switch", true) || name.contains("radio", true) || name.contains("check", true)
            if (excluded) return@traverseViews
            if (preferred == null && (name == "icon" || name.contains("preference_icon", true) || name.contains("left_icon", true) || name.contains("title_icon", true))) preferred = view
            if (fallback == null) fallback = view
        }
        return preferred ?: fallback
    }

    private fun materialSymbolGlyph(name: String): String {
        val codepoint = when (name) {
            "wifi" -> 0xe63e
            "bluetooth" -> 0xe1a7
            "flight" -> 0xe539
            "signal_cellular_alt" -> 0xe202
            "devices" -> 0xe326
            "palette" -> 0xe40a
            "brightness_6" -> 0xe3ab
            "volume_up" -> 0xe050
            "notifications" -> 0xe7f5
            "apps" -> 0xe5c3
            "battery_full" -> 0xe1a5
            "storage" -> 0xe1db
            "shield_lock" -> 0xf686
            "location_on" -> 0xf1db
            "account_circle" -> 0xf20b
            "timer" -> 0xe425
            "accessibility_new" -> 0xe92c
            "emergency" -> 0xe1eb
            "settings" -> 0xe8b8
            "info" -> 0xe88e
            "nfc" -> 0xe1bb
            "wifi_tethering" -> 0xe1e2
            "code" -> 0xe86f
            "language" -> 0xea07
            "update" -> 0xe923
            "backup" -> 0xe864
            "restart_alt" -> 0xf053
            "animation" -> 0xe71c
            "sports_esports" -> 0xea28
            "fingerprint" -> 0xe90d
            "face" -> 0xf008
            "screen_lock_portrait" -> 0xf2be
            "sim_card" -> 0xe32b
            "data_usage" -> 0xeff2
            "cast" -> 0xe307
            "privacy_tip" -> 0xf0dc
            "memory" -> 0xe322
            "account_balance_wallet" -> 0xe850
            "smart_toy" -> 0xf06c
            "dark_mode" -> 0xe51c
            "chevron_right" -> 0xe5cc
            else -> 0xe429
        }
        return String(Character.toChars(codepoint))
    }

    private fun viewResourceName(view: View): String {
        if (view.id == View.NO_ID) return ""
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
    }

    private fun resolveSettingsSymbolTypeface(context: Context): Typeface? {
        settingsSymbolTypeface?.let { return it }
        val moduleContext = runCatching {
            context.createPackageContext("dev.unvoid.originceiler", Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        return runCatching { Typeface.createFromAsset(moduleContext.assets, "MaterialSymbolsRounded.ttf") }
            .getOrNull()
            ?.also { settingsSymbolTypeface = it }
    }

    private fun settingsIconPalette(context: Context, palette: Int): Pair<Int, Int> {
        val dark = isDarkTheme(context)
        val backgroundTone = if (dark) 200 else 100
        val foregroundTone = if (dark) 800 else 700
        val prefix = "system_accent${palette.coerceIn(0, 2) + 1}_"
        val background = systemColor(context, "$prefix$backgroundTone", resolveSystemAccent(context))
        val foreground = systemColor(context, "$prefix$foregroundTone", if (colorLuminance(background) > 0.52f) Color.BLACK else Color.WHITE)
        return background to foreground
    }

    private fun settingsBackgroundColor(context: Context): Int {
        val accent = resolveSystemAccent(context)
        return if (isDarkTheme(context)) {
            val accentTone = systemColor(context, "system_accent1_900", blendSettingsColor(accent, Color.BLACK, 0.76f))
            val neutralTone = systemColor(context, "system_neutral2_900", 0xff202124.toInt())
            blendSettingsColor(accentTone, neutralTone, 0.58f)
        } else {
            val accentTone = systemColor(context, "system_accent1_100", blendSettingsColor(accent, Color.WHITE, 0.84f))
            val neutralTone = systemColor(context, "system_neutral2_50", 0xfff7f7fa.toInt())
            blendSettingsColor(accentTone, neutralTone, 0.54f)
        }
    }

    private fun settingsCardColor(context: Context): Int {
        val accent = resolveSystemAccent(context)
        val base = if (isDarkTheme(context)) {
            val accentTone = systemColor(context, "system_accent1_800", blendSettingsColor(accent, Color.BLACK, 0.58f))
            val neutralTone = systemColor(context, "system_neutral2_800", 0xff34353a.toInt())
            blendSettingsColor(accentTone, neutralTone, 0.52f)
        } else {
            val accentTone = systemColor(context, "system_accent1_50", blendSettingsColor(accent, Color.WHITE, 0.9f))
            val neutralTone = systemColor(context, "system_neutral2_0", Color.WHITE)
            blendSettingsColor(accentTone, neutralTone, 0.5f)
        }
        return blendSettingsColor(base, if (isDarkTheme(context)) Color.WHITE else Color.BLACK, 0.08f)
    }

    private fun settingsSearchColor(context: Context): Int {
        val accent = resolveSystemAccent(context)
        return if (isDarkTheme(context)) {
            val accentTone = systemColor(context, "system_accent1_950", blendSettingsColor(accent, Color.BLACK, 0.84f))
            val neutralTone = systemColor(context, "system_neutral2_900", 0xff202124.toInt())
            blendSettingsColor(accentTone, neutralTone, 0.7f)
        } else {
            val accentTone = systemColor(context, "system_accent1_200", blendSettingsColor(accent, Color.WHITE, 0.72f))
            val neutralTone = systemColor(context, "system_neutral2_100", 0xffececf1.toInt())
            blendSettingsColor(accentTone, neutralTone, 0.62f)
        }
    }

    private fun blendSettingsColor(first: Int, second: Int, secondAmount: Float): Int {
        val amount = secondAmount.coerceIn(0f, 1f)
        val inverse = 1f - amount
        return Color.rgb(
            (Color.red(first) * inverse + Color.red(second) * amount).toInt(),
            (Color.green(first) * inverse + Color.green(second) * amount).toInt(),
            (Color.blue(first) * inverse + Color.blue(second) * amount).toInt()
        )
    }

    private fun settingsOnSurfaceColor(context: Context): Int {
        return if (isDarkTheme(context)) systemColor(context, "system_neutral1_50", Color.WHITE)
        else systemColor(context, "system_neutral1_900", Color.BLACK)
    }

    private fun settingsOnSurfaceVariantColor(context: Context): Int {
        return if (isDarkTheme(context)) systemColor(context, "system_neutral2_200", 0xffd4c7c2.toInt())
        else systemColor(context, "system_neutral2_700", 0xff5b514e.toInt())
    }

    private fun settingsPrimaryContainerColor(context: Context): Int {
        return if (isDarkTheme(context)) systemColor(context, "system_accent1_700", resolveSystemAccent(context))
        else systemColor(context, "system_accent1_100", resolveSystemAccent(context))
    }

    private fun settingsOnPrimaryContainerColor(context: Context): Int {
        val container = settingsPrimaryContainerColor(context)
        return if (colorLuminance(container) > 0.52f) Color.BLACK else Color.WHITE
    }

    private fun systemColor(context: Context, name: String, fallback: Int): Int {
        val id = context.resources.getIdentifier(name, "color", "android")
        return if (id != 0) runCatching { context.getColor(id) }.getOrDefault(fallback) else fallback
    }

    private fun isDarkTheme(context: Context): Boolean = context.resources.configuration.uiMode and 0x30 == 0x20

    private fun roundedShape(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private class MaterialSymbolDrawable(
        private val symbol: String,
        typeface: Typeface,
        color: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = Typeface.create(typeface, 500, false)
            this.color = color
            textAlign = Paint.Align.CENTER
            fontFeatureSettings = "liga"
        }

        override fun draw(canvas: Canvas) {
            paint.textSize = minOf(bounds.width(), bounds.height()) * 0.88f
            val metrics = paint.fontMetrics
            val baseline = bounds.exactCenterY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(symbol, bounds.exactCenterX(), baseline, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = 24
        override fun getIntrinsicHeight(): Int = 24
    }

    private fun traverseViews(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) traverseViews(view.getChildAt(index), action)
        }
    }

    private fun colorLuminance(color: Int): Float {
        return (Color.red(color) * 0.2126f + Color.green(color) * 0.7152f + Color.blue(color) * 0.0722f) / 255f
    }

    private fun schedulePrivateDnsSync(context: Context) {
        mainHandler.postDelayed({ syncPrivateDnsWithVpn(context) }, 400L)
        mainHandler.postDelayed({ syncPrivateDnsWithVpn(context) }, 1800L)
    }

    private fun syncPrivateDnsWithVpn(context: Context) {
        if (Settings.Global.getInt(context.contentResolver, "originicons_disable_private_dns_vpn", 0) != 1) return
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpnActive = connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        val resolver = context.contentResolver
        if (vpnActive) {
            if (Settings.Global.getInt(resolver, "originroottoolbox_dns_backup_active", 0) != 1) {
                Settings.Global.putString(resolver, "originroottoolbox_dns_mode_backup", Settings.Global.getString(resolver, "private_dns_mode") ?: "__null__")
                Settings.Global.putString(resolver, "originroottoolbox_dns_specifier_backup", Settings.Global.getString(resolver, "private_dns_specifier") ?: "__null__")
                Settings.Global.putInt(resolver, "originroottoolbox_dns_backup_active", 1)
            }
            Settings.Global.putString(resolver, "private_dns_mode", "off")
        } else if (Settings.Global.getInt(resolver, "originroottoolbox_dns_backup_active", 0) == 1) {
            val mode = Settings.Global.getString(resolver, "originroottoolbox_dns_mode_backup")
            val specifier = Settings.Global.getString(resolver, "originroottoolbox_dns_specifier_backup")
            Settings.Global.putString(resolver, "private_dns_mode", mode.takeUnless { it == "__null__" })
            Settings.Global.putString(resolver, "private_dns_specifier", specifier.takeUnless { it == "__null__" })
            Settings.Global.putString(resolver, "originroottoolbox_dns_mode_backup", null)
            Settings.Global.putString(resolver, "originroottoolbox_dns_specifier_backup", null)
            Settings.Global.putString(resolver, "originroottoolbox_dns_backup_active", null)
        }
    }

    private fun hookPersistentVivoNotificationFilter(classLoader: ClassLoader) {
        val hooked = mutableSetOf<Class<*>>()
        val pipelineClass = Class.forName(
            "com.android.systemui.statusbar.notification.collection.NotifPipeline",
            false,
            classLoader
        )
        listOf("addFinalizeFilter", "addPreGroupFilter").forEach { methodName ->
            XposedBridge.hookAllMethods(
                pipelineClass,
                methodName,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val filterClass = param.args.firstOrNull()?.javaClass ?: return
                        if (!hooked.add(filterClass)) return
                        XposedBridge.hookAllMethods(
                            filterClass,
                            "shouldFilterOut",
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val application = currentApplication() ?: return
                                    val entry = param.args.firstOrNull() ?: return
                                    val sbn = readField(entry, "mSbn") as? StatusBarNotification ?: return
                                    val hideVivoError = sbn.packageName == "com.vivo.daemonService" && Settings.Global.getInt(
                                        application.contentResolver,
                                        "originicons_hide_vivo_service_error",
                                        0
                                    ) == 1
                                    if (hideVivoError || shouldHideOriginalMediaNotification(sbn, application)) param.setResult(true)
                                }
                            }
                        )
                    }
                }
            )
        }
    }

    private fun shouldHideOriginalMediaNotification(sbn: StatusBarNotification, context: Context): Boolean {
        if (Settings.Global.getInt(context.contentResolver, "originicons_allow_all_media_players", 0) != 1) return false
        if (sbn.packageName in setOf(
                "com.vivo.musicwidgetmix",
                "com.vivo.musicmixcard",
                "com.android.systemui",
                "com.vivo.systemuiplugin",
                "dev.unvoid.originceiler"
            )) return false
        val notification = sbn.notification
        return notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
    }

    private fun hookNavigationHandle(classLoader: ClassLoader) {
        val viewClass = Class.forName("android.view.View", false, classLoader)
        XposedBridge.hookAllMethods(
            viewClass,
            "dispatchTouchEvent",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (view !== view.rootView) return
                    val layoutParams = view.layoutParams as? WindowManager.LayoutParams ?: return
                    if (layoutParams.title?.toString() != "SideSlideGestureBar-Bottom") return
                    val event = param.args.firstOrNull() as? MotionEvent ?: return
                    val application = currentApplication() ?: return
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            cancelNavigationLongPress()
                            val width = application.resources.displayMetrics.widthPixels.toFloat()
                            val height = application.resources.displayMetrics.heightPixels.toFloat()
                            val bottomZone = 96f * application.resources.displayMetrics.density
                            if (event.rawX !in width * 0.25f..width * 0.75f) return
                            if (event.rawY < height - bottomZone) return
                            navigationDownX = event.rawX
                            navigationDownY = event.rawY
                            navigationLongPress = Runnable {
                                if (!isCircleToSearchEnabled(application)) return@Runnable
                                launchCircleToSearch(application)
                            }.also { mainHandler.postDelayed(it, 450L) }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val slop = ViewConfiguration.get(application).scaledTouchSlop * 2f
                            if (hypot(event.rawX - navigationDownX, event.rawY - navigationDownY) > slop) {
                                cancelNavigationLongPress()
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelNavigationLongPress()
                    }
                }
            }
        )
    }

    private fun cancelNavigationLongPress() {
        navigationLongPress?.let(mainHandler::removeCallbacks)
        navigationLongPress = null
    }

    private fun hookCircleToSearch(classLoader: ClassLoader) {
        val featureUtils = Class.forName(
            "com.android.quickstep.utils.FeatureUtils",
            false,
            classLoader
        )
        XposedBridge.hookAllMethods(
            featureUtils,
            "isOversea",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val application = currentApplication() ?: return
                    if (Settings.Global.getInt(
                            application.contentResolver,
                            "originicons_circle_to_search",
                            0
                    ) == 1
                    ) {
                        param.setResult(true)
                    }
                }
            }
        )
    }

    private fun hookPixelHomeLayout() {
        XposedBridge.hookAllMethods(
            Activity::class.java,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.packageName != "com.bbk.launcher2") return
                    val application = currentApplication() ?: return
                    if (Settings.Global.getInt(application.contentResolver, "originicons_pixel_home_layout", 0) != 1) return
                    mainHandler.post { applyPixelHomeLayout(activity) }
                }
            }
        )
    }

    private fun hookLauncherIconSize() {
        XposedBridge.hookAllMethods(
            View::class.java,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!isWorkspaceLauncherIcon(view)) return
                    launcherIconViews[view] = true
                    launcherIconBaseScaleX.putIfAbsent(view, 1f)
                    launcherIconBaseScaleY.putIfAbsent(view, 1f)
                    disableLauncherIconClipping(view)
                    installLauncherIconSizeObserver(view)
                    applyLauncherIconSize(view)
                }
            }
        )
        listOf("setScaleX", "setScaleY").forEach { methodName ->
            XposedBridge.hookAllMethods(
                View::class.java,
                methodName,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (launcherIconInternalScale.get() == true) return
                        val view = param.thisObject as? View ?: return
                        if (!launcherIconViews.containsKey(view) || !isWorkspaceLauncherIcon(view)) return
                        val requested = param.args.firstOrNull() as? Float ?: return
                        if (methodName == "setScaleX") launcherIconBaseScaleX[view] = requested
                        else launcherIconBaseScaleY[view] = requested
                        param.args[0] = requested * launcherIconScale(view)
                    }
                }
            )
        }
        XposedBridge.log("OriginRootToolbox launcher icon size hook ready")
    }

    private fun installLauncherIconSizeObserver(view: View) {
        if (!launcherIconObserverInstalled.compareAndSet(false, true)) return
        view.context.applicationContext.contentResolver.registerContentObserver(
            Settings.Global.getUriFor("originroottoolbox_launcher_icon_size"),
            false,
            object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    synchronized(launcherIconViews) {
                        launcherIconViews.keys.toList()
                    }.forEach { icon ->
                        if (icon.isAttachedToWindow && isWorkspaceLauncherIcon(icon)) {
                            disableLauncherIconClipping(icon)
                            applyLauncherIconSize(icon)
                        }
                    }
                }
            }
        )
    }

    private fun applyLauncherIconSize(view: View) {
        val factor = launcherIconScale(view)
        launcherIconInternalScale.set(true)
        try {
            view.scaleX = (launcherIconBaseScaleX[view] ?: 1f) * factor
            view.scaleY = (launcherIconBaseScaleY[view] ?: 1f) * factor
            view.invalidate()
        } finally {
            launcherIconInternalScale.remove()
        }
    }

    private fun launcherIconScale(view: View): Float {
        return Settings.Global.getInt(
            view.context.contentResolver,
            "originroottoolbox_launcher_icon_size",
            100
        ).coerceIn(70, 150) / 100f
    }

    private fun isWorkspaceLauncherIcon(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        var iconClass = false
        while (type != null) {
            val name = type.name
            if (name == "com.bbk.launcher2.launcherIcon.ui.icon.ComponentIcon" ||
                name == "com.bbk.launcher2.launcherIcon.ui.icon.ItemIcon" ||
                name == "com.bbk.launcher2.launcherIcon.ui.icon.AppIcon"
            ) {
                iconClass = true
                break
            }
            type = type.superclass
        }
        if (!iconClass) return false
        var parent = view.parent
        repeat(12) {
            val parentView = parent as? View ?: return false
            val name = parentView.javaClass.name
            val resource = runCatching {
                parentView.resources.getResourceEntryName(parentView.id)
            }.getOrNull().orEmpty()
            if (name.contains("CellLayout", true) ||
                name.contains("ShortcutAndWidget", true) ||
                name.contains("Workspace", true) ||
                name.contains("Hotseat", true) ||
                resource.contains("workspace", true) ||
                resource.contains("hotseat", true)
            ) return true
            parent = parentView.parent
        }
        return false
    }

    private fun disableLauncherIconClipping(view: View) {
        view.clipToOutline = false
        var parent = view.parent
        repeat(12) {
            val group = parent as? ViewGroup ?: return
            group.clipChildren = false
            group.clipToPadding = false
            group.clipToOutline = false
            parent = group.parent
        }
    }

    private fun hookPixelHomeTranslations() {
        XposedBridge.hookAllMethods(
            View::class.java,
            "setTranslationY",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!isPixelHomeSurface(view)) return
                    val application = currentApplication() ?: return
                    if (Settings.Global.getInt(application.contentResolver, "originicons_pixel_home_layout", 0) != 1) return
                    val original = param.args.firstOrNull() as? Float ?: return
                    param.args[0] = original - pixelHomeOffset(view, application)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val source = param.thisObject as? View ?: return
                    syncPixelHomeBar(source) { bar ->
                        val application = currentApplication() ?: return@syncPixelHomeBar
                        bar.translationY = source.translationY + pixelHomeOffset(source, application)
                    }
                }
            }
        )
    }

    private fun hookPixelHomeDockLayout() {
        XposedBridge.hookAllMethods(
            View::class.java,
            "layout",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!isPixelDockSurface(view)) return
                    val application = currentApplication() ?: return
                    if (Settings.Global.getInt(application.contentResolver, "originicons_pixel_home_layout", 0) != 1) return
                    val offset = dp(application, 28)
                    param.args[1] = (param.args[1] as Int) - offset
                    param.args[3] = (param.args[3] as Int) - offset
                }
            }
        )
    }

    private fun hookPixelHomeWidgetSync() {
        XposedBridge.hookAllMethods(
            View::class.java,
            "setScaleX",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val source = param.thisObject as? View ?: return
                    syncPixelHomeBar(source) { it.scaleX = source.scaleX }
                }
            }
        )
        XposedBridge.hookAllMethods(
            View::class.java,
            "setScaleY",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val source = param.thisObject as? View ?: return
                    syncPixelHomeBar(source) { it.scaleY = source.scaleY }
                }
            }
        )
        XposedBridge.hookAllMethods(
            View::class.java,
            "setVisibility",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val source = param.thisObject as? View ?: return
                    syncPixelHomeBar(source) { it.visibility = source.visibility }
                }
            }
        )
    }

    private fun syncPixelHomeBar(source: View, block: (View) -> Unit) {
        pixelHomeBars[source]?.let(block)
    }

    private fun applyPixelHomeLayout(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (root.findViewWithTag<View>("originicons_pixel_search") != null) return
        findLauncherSurface(root, "workspace")?.translationY = 0f
        findLauncherSurface(root, "hotseat")?.translationY = 0f
        findLauncherSurface(root, "dock")?.translationY = 0f
        findPageIndicator(activity, root)?.translationY = 0f
        val host = findPixelLayerHost(root) ?: findFrameHost(root) ?: return
        val bar = createGoogleSearchWidget(activity) ?: return
        bar.tag = "originicons_pixel_search"
        val layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 52)).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(activity, 22)
                rightMargin = dp(activity, 22)
                bottomMargin = dp(activity, 18)
            }
        val folderIndex = (0 until host.childCount).firstOrNull { index ->
            host.getChildAt(index).javaClass.name.contains("folder", true)
        } ?: host.childCount
        host.addView(bar, folderIndex, layoutParams)
        findLauncherSurface(root, "hotseat")?.let { surface ->
            pixelHomeBars[surface] = bar
            bar.alpha = 1f
            bar.scaleX = surface.scaleX
            bar.scaleY = surface.scaleY
            bar.visibility = surface.visibility
            bar.translationY = surface.translationY + pixelHomeOffset(surface, activity)
        }
    }

    private fun createGoogleSearchWidget(activity: Activity): View = PixelSearchOverlay(activity)

    private fun findLauncherSurface(root: ViewGroup, token: String): View? {
        if (root.javaClass.name.contains(token, true)) return root
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child.javaClass.name.contains(token, true)) return child
            if (child is ViewGroup) findLauncherSurface(child, token)?.let { return it }
        }
        return null
    }

    private fun findPageIndicator(activity: Activity, root: ViewGroup): View? {
        listOf("page_indicator", "page_indicator_view", "workspace_indicator", "indicator").forEach { name ->
            val id = activity.resources.getIdentifier(name, "id", activity.packageName)
            if (id != 0) root.findViewById<View>(id)?.let { return it }
        }
        return findViewByResourceToken(root, "indicator")
            ?: findLauncherSurface(root, "simpleindicator")
            ?: findLauncherSurface(root, "pageindicator")
    }

    private fun isPixelHomeSurface(view: View): Boolean {
        val name = view.javaClass.name
        val resourceName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
        return name.contains("workspace", true) || name.contains("hotseat", true) || name.contains("dock", true) || name.contains("simpleindicator", true) || name.contains("pageindicator", true) || resourceName.contains("indicator", true)
    }

    private fun isPixelDockSurface(view: View): Boolean {
        val name = view.javaClass.name
        return name.contains("hotseat", true)
    }

    private fun pixelHomeOffset(view: View, context: Context): Float {
        val name = view.javaClass.name
        val resourceName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
        return if (name.contains("simpleindicator", true) || name.contains("pageindicator", true) || resourceName.contains("indicator", true)) {
            dp(context, 27).toFloat()
        } else if (name.contains("workspace", true)) {
            dp(context, 22).toFloat()
        } else if (name.contains("hotseat", true) || name.contains("dock", true)) {
            0f
        } else {
            dp(context, 52).toFloat()
        }
    }

    private fun findViewByResourceToken(root: ViewGroup, token: String): View? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            val resourceName = runCatching { child.resources.getResourceEntryName(child.id) }.getOrNull().orEmpty()
            if (resourceName.contains(token, true)) return child
            if (child is ViewGroup) findViewByResourceToken(child, token)?.let { return it }
        }
        return null
    }

    private fun findFrameHost(root: ViewGroup): FrameLayout? {
        if (root is FrameLayout) return root
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child is ViewGroup) findFrameHost(child)?.let { return it }
        }
        return null
    }

    private fun findPixelLayerHost(root: ViewGroup): FrameLayout? {
        val hotseat = findLauncherSurface(root, "hotseat") ?: return null
        var parent = hotseat.parent
        while (parent is ViewGroup) {
            if (parent is FrameLayout && (0 until parent.childCount).any { index ->
                    parent.getChildAt(index).javaClass.name.contains("folder", true)
                }) {
                return parent
            }
            parent = parent.parent
        }
        return null
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun hookPowerButtonAssistant(classLoader: ClassLoader) {
        val panelWindowController = Class.forName(
            "com.vivo.ai.copilot.floating.controller.PanelWindowController",
            false,
            classLoader
        )
        panelWindowController.declaredMethods
            .filter { method -> method.parameterTypes.any { it == String::class.java } && method.returnType == java.lang.Void.TYPE }
            .map { it.name }
            .distinct()
            .forEach { methodName ->
                XposedBridge.hookAllMethods(
                    panelWindowController,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val action = param.args.filterIsInstance<String>().firstOrNull() ?: return
                            if (methodName != "F" && !action.contains("WAKEUP_AGENT", true) && !action.contains("POWER", true)) return
                            val application = currentApplication()
                                ?: contextFromController(param.thisObject)
                                ?: return
                            if (action.contains("POWER", true)) {
                                if (!isPowerButtonAssistantEnabled(application)) return
                                if (!launchComponent(
                                    application,
                                    "com.parallelc.vistrigger",
                                    "com.parallelc.micts.ui.activity.MainActivity"
                                )) return
                                param.setResult(null)
                                mainHandler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 350L)
                            } else {
                                if (!isCircleToSearchEnabled(application)) return
                                launchCircleToSearch(application)
                                param.setResult(null)
                            }
                        }
                    }
                )
            }
    }

    private fun hookPowerButtonWakeupService(classLoader: ClassLoader) {
        val wakeupService = Class.forName(
            "com.vivo.ai.copilot.framework.wakeup.WakeupService",
            false,
            classLoader
        )
        wakeupService.declaredMethods
            .filter { method -> method.parameterTypes.any { it == Intent::class.java } }
            .forEach { method ->
                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val intent = param.args.filterIsInstance<Intent>().firstOrNull() ?: return
                            if (intent.action != "com.vivo.intent.action.WAKEUP_AGENT_BY_POWER") return
                            val service = param.thisObject as? Service ?: return
                            if (!isPowerButtonAssistantEnabled(service)) return
                            if (!launchComponent(service, "com.parallelc.vistrigger", "com.parallelc.micts.ui.activity.MainActivity")) return
                            service.stopSelf()
                            param.result = when (method.returnType) {
                                java.lang.Integer.TYPE -> Service.START_NOT_STICKY
                                java.lang.Boolean.TYPE -> false
                                java.lang.Long.TYPE -> 0L
                                java.lang.Float.TYPE -> 0f
                                java.lang.Double.TYPE -> 0.0
                                else -> null
                            }
                        }
                    }
                )
            }
    }

    private fun hookPowerButtonAssistantFallback(classLoader: ClassLoader) {
        val actionState = Class.forName("repackage_name.st0", false, classLoader)
        listOf(
            "com.vivo.ai.copilot.newchat.view.PanelChatView",
            "com.vivo.ai.copilot.newchat.view.SimplePanelChatView"
        ).forEach { className ->
            val panel = Class.forName(className, false, classLoader)
            XposedBridge.hookAllMethods(
                panel,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val action = runCatching {
                            actionState.getDeclaredField("e").apply { isAccessible = true }.get(null) as? String
                        }.getOrNull() ?: return
                        if (!action.contains("POWER", true)) return
                        val view = param.thisObject as? View ?: return
                        val application = view.context.applicationContext
                        if (!isPowerButtonAssistantEnabled(application)) return
                        if (!launchComponent(
                            application,
                            "com.parallelc.vistrigger",
                            "com.parallelc.micts.ui.activity.MainActivity"
                        )) return
                        view.visibility = View.GONE
                        param.setResult(null)
                        mainHandler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 250L)
                    }
                }
            )
        }
    }

    private fun isCircleToSearchEnabled(application: Context) =
        Settings.Global.getInt(
            application.contentResolver,
            "originicons_circle_to_search",
            0
        ) == 1

    private fun isPowerButtonAssistantEnabled(application: Context) =
        Settings.Global.getInt(
            application.contentResolver,
            "originicons_power_button_gemini",
            0
        ) == 1

    private fun launchComponent(application: Context, packageName: String, className: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAssistantLaunch < 1000L) return true
        lastAssistantLaunch = now
        val intent = Intent().apply {
            component = ComponentName(packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return runCatching {
            application.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun launchCircleToSearch(application: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAssistantLaunch < 1000L) return
        lastAssistantLaunch = now
        val intent = Intent().apply {
            component = ComponentName("com.parallelc.micts", "com.parallelc.micts.ui.activity.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val options = foregroundTaskOptions()
        runCatching {
            if (options == null) application.startActivity(intent) else application.startActivity(intent, options)
        }
    }

    private fun foregroundTaskOptions(): Bundle? = runCatching {
        val service = Class.forName("android.app.ActivityTaskManager")
            .getMethod("getService")
            .invoke(null)
        val tasksMethod = service.javaClass.methods.firstOrNull {
            it.name == "getTasks" && it.parameterTypes.isNotEmpty()
        } ?: return null
        var intCount = 0
        val arguments = arrayOfNulls<Any>(tasksMethod.parameterCount)
        tasksMethod.parameterTypes.forEachIndexed { index, type ->
            arguments[index] = when (type) {
                Int::class.javaPrimitiveType -> if (intCount++ == 0) 12 else 0
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }
        val tasks = tasksMethod.invoke(service, *arguments) as? List<*> ?: return null
        var taskId: Int? = null
        for (item in tasks) {
            if (item == null) continue
            val component = runCatching {
                item.javaClass.getField("topActivity").get(item) as? ComponentName
            }.getOrNull() ?: continue
            if (component.packageName in setOf(
                    "com.android.systemui",
                    "com.bbk.launcher2",
                    "com.vivo.upslide",
                    "com.parallelc.micts"
                )
            ) continue
            taskId = runCatching { item.javaClass.getField("taskId").getInt(item) }.getOrNull()
            if (taskId != null) break
        }
        val resolvedTaskId = taskId ?: return null
        ActivityOptions.makeBasic().also { options ->
            ActivityOptions::class.java.getMethod("setLaunchTaskId", Int::class.javaPrimitiveType)
                .invoke(options, resolvedTaskId)
        }.toBundle()
    }.getOrNull()

    private fun contextFromController(instance: Any): Context? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            listOf("r", "mContext", "context").forEach { fieldName ->
                val value = runCatching {
                    type.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance) as? Context
                }.getOrNull()
                if (value != null) return value
            }
            type.declaredFields.firstOrNull { Context::class.java.isAssignableFrom(it.type) }?.let { field ->
                val value = runCatching { field.apply { isAccessible = true }.get(instance) as? Context }.getOrNull()
                if (value != null) return value
            }
            type = type.superclass
        }
        return null
    }

    private fun currentApplication(): Application? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getDeclaredMethod("currentApplication").invoke(null) as Application
    }.getOrNull()
}
