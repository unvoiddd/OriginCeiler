package com.autonavi.minimap

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.AdaptiveIconDrawable
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
import android.graphics.drawable.RippleDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.provider.Settings
import android.service.notification.StatusBarNotification
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
    private val aospVolumeDialogs = Collections.synchronizedMap(WeakHashMap<Any, Any>())
    private val settingsOriginalTopMargins = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val settingsOriginalHorizontalMargins = Collections.synchronizedMap(WeakHashMap<View, Pair<Int, Int>>())
    private val settingsOriginalPaddings = Collections.synchronizedMap(WeakHashMap<View, IntArray>())
    private val settingsSwitchStates = Collections.synchronizedMap(WeakHashMap<View, MutableState<Boolean>>())
    private val settingsComposeSwitches = Collections.synchronizedMap(WeakHashMap<View, ComposeView>())
    private var settingsSymbolTypeface: Typeface? = null
    private val aospThemedIconSize = ThreadLocal<Int?>()
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
                "com.android.settings",
                "com.android.phone",
                "com.vivo.systemuiplugin",
                "com.iqoo.powersaving",
                "com.vivo.gamecube",
                "com.bbk.theme",
                "com.vivo.pay"
            )
        ) {
            XposedBridge.log("OriginIcons loaded: ${loadPackageParam.packageName}")
        }
        if (loadPackageParam.packageName == "com.android.systemui") {
            runCatching { hookAospControlCenter(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookAospVolumeBar(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookNotificationShadeFilter(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookPersistentVivoNotificationFilter(loadPackageParam.classLoader) }
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
        }
        if (loadPackageParam.packageName in setOf("com.vivo.ai.copilot", "com.vivo.launchercopilot")) {
            runCatching { hookPowerButtonAssistant(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookPowerButtonAssistantFallback(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
            runCatching { hookBlueLmToasts(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
        }
        if (loadPackageParam.packageName == "com.vivo.upslide") {
            runCatching { hookNavigationHandle(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
        }
        if (loadPackageParam.packageName == "com.vivo.musicwidgetmix") {
            runCatching { hookMusicWidgetAllowLists(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
        }
        if (loadPackageParam.packageName == "com.google.android.googlequicksearchbox") {
            runCatching { hookPixelGoogleIdentity(loadPackageParam.classLoader) }
                .onFailure(XposedBridge::log)
        }
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

    private fun hookAospControlCenter(classLoader: ClassLoader) {
        val hostManager = Class.forName(
            "com.android.systemui.fragments.FragmentHostManager",
            false,
            classLoader
        )
        XposedHelpers.findAndHookMethod(
            hostManager,
            "create",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val application = currentApplication() ?: return
                    if (Settings.Global.getInt(
                            application.contentResolver,
                            "originicons_aosp_control_center",
                            0
                        ) != 1
                    ) return
                    val managerField = hostManager.getDeclaredField("mPlugins").apply { isAccessible = true }
                    val contextField = hostManager.getDeclaredField("mContext").apply { isAccessible = true }
                    val manager = managerField.get(param.thisObject)
                    val context = contextField.get(param.thisObject) as Context
                    val instantiate = manager.javaClass.getMethod(
                        "instantiate",
                        Context::class.java,
                        String::class.java,
                        android.os.Bundle::class.java
                    )
                    val names = listOf(
                        "com.android.systemui.qs.composefragment.QSFragmentCompose",
                        "com.android.systemui.qs.QSFragmentLegacy"
                    )
                    for (name in names) {
                        val fragment = runCatching<Any> {
                            instantiate.invoke(manager, context, name, null)
                        }.getOrNull() ?: continue
                        param.result = fragment
                        return
                    }
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
        val baseIconFactory = Class.forName(
            "com.android.launcher3.icons.BaseIconFactory",
            false,
            classLoader
        )
        val iconOptions = Class.forName(
            "com.android.launcher3.icons.BaseIconFactory\$IconOptions",
            false,
            classLoader
        )
        val iconSizeField = baseIconFactory.getDeclaredField("mIconBitmapSize").apply {
            isAccessible = true
        }
        XposedHelpers.findAndHookMethod(
            baseIconFactory,
            "createBadgedIconBitmap",
            Drawable::class.java,
            iconOptions,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val application = currentApplication() ?: return
                    if (Settings.Global.getInt(
                            application.contentResolver,
                            "originicons_native_aosp_themed_icons",
                            0
                        ) == 1
                    ) {
                        aospThemedIconSize.set(iconSizeField.getInt(param.thisObject))
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    aospThemedIconSize.remove()
                }
            }
        )
        XposedHelpers.findAndHookMethod(
            AdaptiveIconDrawable::class.java,
            "getMonochrome",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result != null) return
                    val iconSize = aospThemedIconSize.get() ?: return
                    param.result = AospMonochromeDrawable(
                        param.thisObject as AdaptiveIconDrawable,
                        iconSize
                    )
                }
            }
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
                            "originicons_native_aosp_themed_icons",
                            0
                        ) == 1
                    ) {
                        param.args[1] = (param.args[1] as Int) or 1
                    }
                }
            }
        )
    }

    private fun hookMusicWidgetAllowLists(classLoader: ClassLoader) {
        val appUtils = Class.forName(
            "com.vivo.musicwidgetmix.utils.d",
            false,
            classLoader
        )
        listOf("P", "M", "T", "W", "X").forEach { name ->
            runCatching {
                XposedHelpers.findAndHookMethod(
                    appUtils,
                    name,
                    Context::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (allowAllMediaPlayers()) param.setResult(true)
                        }
                    }
                )
            }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                appUtils,
                "H",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (allowAllMediaPlayers()) param.setResult(false)
                    }
                }
            )
        }
        listOf("M", "E").forEach { name ->
            runCatching {
                XposedHelpers.findAndHookMethod(
                    appUtils,
                    name,
                    Context::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!allowAllMediaPlayers()) return
                            activeMediaPackages(param.args.firstOrNull() as? Context).firstOrNull()?.let(param::setResult)
                        }
                    }
                )
            }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                appUtils,
                "O",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!allowAllMediaPlayers()) return
                        val packages = activeMediaPackages(param.args.firstOrNull() as? Context)
                        if (packages.isNotEmpty()) param.setResult(packages)
                    }
                }
            )
        }
        listOf("t3.v", "k4.k0").forEach { className ->
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
                                    param.setResult(object : ArrayList<Any?>(source) {
                                        override fun contains(element: Any?): Boolean = true
                                    })
                                }
                            }
                        )
                    }
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
            val listener = ComponentName(
                "com.autonavi.minimap",
                "com.autonavi.minimap.OriginIslandNotificationListener"
            )
            ArrayList(manager.getActiveSessions(listener).map { it.packageName }.distinct())
        }.getOrDefault(arrayListOf())
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
                    if (Settings.Global.getInt(application.contentResolver, "originicons_aosp_volume_bar", 0) != 1) return
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
                    if (Settings.Global.getInt(application.contentResolver, "originicons_aosp_volume_bar", 0) != 1) return
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
                    if (Settings.Global.getInt(application.contentResolver, "originicons_aosp_volume_bar", 0) != 1) return
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
                    if (sbn.packageName == "com.autonavi.minimap") return
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
                    if (Settings.Global.getInt(
                            application.contentResolver,
                            "originicons_island_enabled",
                            0
                        ) != 1
                    ) return
                    val packages = Settings.Global.getString(
                        application.contentResolver,
                        "originicons_island_packages"
                    ).orEmpty().split(',')
                    val notification = sbn.notification
                    val media = notification.category == Notification.CATEGORY_TRANSPORT ||
                        notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
                        notification.actions.orEmpty().any { action ->
                            val title = action.title?.toString().orEmpty().lowercase()
                            title.contains("play") || title.contains("pause") || title.contains("воспроиз") || title.contains("пауз")
                        }
                    val progress = notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
                        notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
                    val allMedia = Settings.Global.getInt(
                        application.contentResolver,
                        "originicons_island_all_media",
                        0
                    ) == 1
                    val allProgress = Settings.Global.getInt(
                        application.contentResolver,
                        "originicons_island_all_progress",
                        0
                    ) == 1
                    if (sbn.packageName in packages || allMedia && media || allProgress && progress) param.setResult(null)
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
            context.createPackageContext("com.autonavi.minimap", Context.CONTEXT_IGNORE_SECURITY)
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
                                    if (Settings.Global.getInt(
                                            application.contentResolver,
                                            "originicons_hide_vivo_service_error",
                                            0
                                        ) != 1
                                    ) return
                                    val entry = param.args.firstOrNull() ?: return
                                    val sbn = readField(entry, "mSbn") as? StatusBarNotification ?: return
                                    if (sbn.packageName == "com.vivo.daemonService") param.setResult(true)
                                }
                            }
                        )
                    }
                }
            )
        }
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
                                launchComponent(
                                    application,
                                    "com.parallelc.vistrigger",
                                    "com.parallelc.micts.ui.activity.MainActivity"
                                )
                            } else {
                                if (!isCircleToSearchEnabled(application)) return
                                launchCircleToSearch(application)
                            }
                            param.setResult(null)
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
                        launchComponent(
                            application,
                            "com.parallelc.vistrigger",
                            "com.parallelc.micts.ui.activity.MainActivity"
                        )
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

    private fun launchComponent(application: Context, packageName: String, className: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAssistantLaunch < 1000L) return
        lastAssistantLaunch = now
        val intent = Intent().apply {
            component = ComponentName(packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { application.startActivity(intent) }
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
