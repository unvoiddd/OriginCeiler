package dev.unvoid.originceiler

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Array
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

class ShadeLiquidGlassHook {
    fun install(classLoader: ClassLoader) {
        installNotificationHooks(classLoader)
        installControlCenterHooks(classLoader)
        installMaterialGuards(classLoader)
        installBackgroundGuards()
        installClassLoaderWatcher()
    }

    private fun installNotificationHooks(classLoader: ClassLoader) {
        val backgroundClass = findClass(classLoader, NOTIFICATION_BACKGROUND_CLASS) ?: return
        if (notificationClasses.put(backgroundClass, true) != null) return
        hookAll(backgroundClass, "setCustomBackground", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!notificationsEnabled(view.context)) return
                val index = param.args.indexOfFirst { it is Drawable }
                if (index >= 0 && param.args[index] !is ShadeLiquidGlassDrawable) {
                    param.args[index] = ShadeLiquidGlassEngine.attach(view, GlassRole.NOTIFICATION)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (notificationsEnabled(view.context)) ensureNotificationBackground(view)
            }
        })
        listOf(
            "onAttachedToWindow",
            "onVisibilityAggregated",
            "setActualHeight",
            "setActualWidth",
            "setClipTopAmount",
            "setClipBottomAmount",
            "setBackgroundType",
            "updateBlurVisible",
            "updateBackgroundRadii",
            "onUiModeChangedForBlur"
        ).forEach { name ->
            hookAll(backgroundClass, name, object : XC_MethodHook(Int.MIN_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (notificationsEnabled(view.context)) ensureNotificationBackground(view)
                }
            })
        }
        hookAll(backgroundClass, "setRadius", object : XC_MethodHook(Int.MIN_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!notificationsEnabled(view.context)) return
                val top = (param.args.getOrNull(0) as? Number)?.toFloat() ?: return
                val bottom = (param.args.getOrNull(1) as? Number)?.toFloat() ?: top
                ShadeLiquidGlassEngine.attach(view, GlassRole.NOTIFICATION).setCornerRadii(
                    floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
                )
                ensureNotificationBackground(view)
            }
        })
        hookAll(backgroundClass, "setTint", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!notificationsEnabled(view.context)) return
                val field = findField(view.javaClass, "mBackground") ?: return
                val bg = runCatching { field.get(view) as? Drawable }.getOrNull()
                if (bg is ShadeLiquidGlassDrawable) {
                    param.setResult(null) // Block Vivo from tinting the glass black
                }
            }
        })
    }

    private fun hookSystemBlurScale(classLoader: ClassLoader) {
        val type = findClass(classLoader, "com.vivo.blur.VivoMaterialBlurDelegator") ?: return
        hookAll(type, "setMaterialBlurRadius", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.args.firstOrNull { it is View } as? View ?: return
                val scale = runCatching { Settings.Global.getInt(view.context.contentResolver, SETTING_NATIVE_BG_BLUR, 100) }.getOrDefault(100)
                if (scale != 100) {
                    val radius = param.args[1] as Float
                    param.args[1] = radius * (scale / 100f)
                }
            }
        })
        findClass(classLoader, "com.vivo.systemuiplugin.systemui.qs.ui.VivoThreeLayerMaterialParams")?.let { params ->
            hookAll(params, "getBlurRadius", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activityThreadClass = Class.forName("android.app.ActivityThread")
                    val currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication")
                    val context = currentApplicationMethod.invoke(null) as? android.content.Context ?: return
                    val scale = runCatching { Settings.Global.getInt(context.contentResolver, SETTING_NATIVE_BG_BLUR, 100) }.getOrDefault(100)
                    if (scale != 100) {
                        val radius = param.result as Float
                        param.result = radius * (scale / 100f)
                    }
                }
            })
        }
    }

    private fun ensureNotificationBackground(view: View) {
        val drawable = ShadeLiquidGlassEngine.attach(view, GlassRole.NOTIFICATION)
        readField(view, "mCornerRadii")?.let { value ->
            if (value is FloatArray && value.size >= 8) drawable.setCornerRadii(value.copyOf(8))
        }
        val field = findField(view.javaClass, "mBackground") ?: return
        if (runCatching { field.get(view) }.getOrNull() === drawable) return
        runCatching {
            val old = field.get(view) as? Drawable
            old?.callback = null
            field.set(view, drawable)
            drawable.callback = view
            view.invalidate()
        }.onFailure(XposedBridge::log)
    }

    private fun installControlCenterHooks(classLoader: ClassLoader) {
        CONTROL_CENTER_CLASSES.forEach { spec ->
            val type = findClass(classLoader, spec.name) ?: return@forEach
            installControlCenterClass(type, spec)
        }
    }

    private fun installControlCenterClass(type: Class<*>, spec: ControlCenterSpec) {
        if (controlCenterClasses.put(type, true) != null) return
        spec.methods.forEach { methodName ->
            hookAll(type, methodName, object : XC_MethodHook(Int.MIN_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val owner = param.thisObject ?: return
                    val context = ownerContext(owner) ?: return
                    if (controlCenterEnabled(context)) {
                        val active = resolveActiveState(owner, param.args)
                        applyControlCenterBackground(owner, spec, active)
                        applyConnectivityIcon(owner, param.args, active)
                    }
                }
            })
        }
    }

    private fun applyControlCenterBackground(owner: Any, spec: ControlCenterSpec, active: Boolean?) {
        val names = (spec.fields + CONTROL_CENTER_BACKGROUND_FIELDS).distinct()
        val fieldTargets = if (spec.ownerOnly) mutableListOf() else names.mapNotNull { readField(owner, it) as? View }.distinct().toMutableList()
        val targets = if (spec.deepBackground) fieldTargets.flatMap(::findMusicBackgrounds).distinct().toMutableList() else fieldTargets
        if (targets.isEmpty() && owner is View) targets += owner
        targets.filterNot {
            it.javaClass.name.contains("Icon", true) || it.javaClass.name.contains("Label", true) || it.javaClass.name.contains("Text", true)
        }.forEach { target ->
            val original = if (target is ImageView) target.drawable ?: target.background else target.background
            val drawable = ShadeLiquidGlassEngine.attach(target, GlassRole.CONTROL_CENTER)
            drawable.captureShape(original, owner.javaClass.name.contains("ConnectGroup", true))
            if (owner.javaClass.name.contains("Tile", true) && !owner.javaClass.name.contains("Group", true)) drawable.setActive(active)
            
            if (owner.javaClass.name.contains("SeekBar")) {
                val progress = (readField(owner, "mCurrentProgress") as? Number)?.toFloat()
                val max = (readField(owner, "mMaxProgress") as? Number)?.toFloat() ?: 100f
                val min = (readField(owner, "mMinProgress") as? Number)?.toFloat() ?: 0f
                val isVertical = readField(owner, "mVertical") as? Boolean ?: false
                if (progress != null && max > min) {
                    val fraction = (progress - min) / (max - min)
                    drawable.setProgressFraction(fraction, isVertical)
                }
            }
            
            clearNativeMaterial(target)
            if (target is ImageView && (spec.imageLayer || spec.deepBackground)) {
                ShadeBackgroundGuard.mutate {
                    target.background = null
                    target.setImageDrawable(drawable)
                }
            } else {
                if (target is ImageView && target.drawable !is ShadeLiquidGlassDrawable) {
                    ShadeBackgroundGuard.mutate { target.setImageDrawable(null) }
                }
                if (target.background !== drawable) ShadeBackgroundGuard.mutate { target.background = drawable }
            }
            target.invalidate()
        }
    }

    private fun resolveActiveState(owner: Any, args: kotlin.Array<out Any?>): Boolean? {
        (readField(owner, "mTileState") as? Boolean)?.let { return it }
        val state = args.firstNotNullOfOrNull { argument ->
            argument?.takeIf { it.javaClass.name.contains("QSTile\$State") }
        } ?: listOf("mState", "state").firstNotNullOfOrNull { readField(owner, it) }
        return ((state as? Number) ?: state?.let { readField(it, "state") as? Number })?.toInt()?.let { it == 2 }
    }



    private fun applyConnectivityIcon(owner: Any, args: kotlin.Array<out Any?>, active: Boolean?) {
        if (active == null) return
        val state = args.firstOrNull { it?.javaClass?.name?.contains("QSTile\$State") == true }
        val text = listOf("label", "secondaryLabel", "contentDescription").mapNotNull {
            state?.let { value -> readField(value, it)?.toString() }
        }.plus((owner as? View)?.contentDescription?.toString()).joinToString(" ").lowercase()
        if (CONNECTIVITY_LABELS.none { text.contains(it) }) return
        val icon = readField(owner, "mIcon") as? View ?: return
        fun tint(view: View) {
            if (view is ImageView) {
                if (active) view.setColorFilter(CONNECTIVITY_ACTIVE_BLUE) else view.clearColorFilter()
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) tint(view.getChildAt(index))
        }
        tint(icon)
    }

    private fun findMusicBackgrounds(root: View): List<View> {
        val matches = mutableListOf<View>()
        fun visit(view: View) {
            val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            if (name in MUSIC_BACKGROUND_IDS) matches += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return matches.ifEmpty { listOf(root) }
    }

    private fun installMaterialGuards(classLoader: ClassLoader) {
    }

    private fun installMaterialGuardClass(type: Class<*>) {
    }

    private fun installClassLoaderWatcher() {
        if (!classLoaderWatcherInstalled.compareAndSet(false, true)) return
        hookAll(ClassLoader::class.java, "loadClass", object : XC_MethodHook(Int.MIN_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                val type = param.result as? Class<*> ?: return
                CONTROL_CENTER_CLASSES_BY_NAME[type.name]?.let { installControlCenterClass(type, it) }
                if (type.name == "com.vivo.blur.VivoMaterialBlurDelegator") installMaterialGuardClass(type)
            }
        })
    }

    private fun installBackgroundGuards() {
        if (!backgroundGuardsInstalled.compareAndSet(false, true)) return
        listOf("setBackground", "setBackgroundDrawable", "setBackgroundResource", "setBackgroundColor").forEach { name ->
            hookAll(View::class.java, name, object : XC_MethodHook(Int.MAX_VALUE) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!ShadeBackgroundGuard.internal() && ShadeLiquidGlassEngine.role(view) == GlassRole.CONTROL_CENTER && controlCenterEnabled(view.context)) {
                        param.setResult(null)
                    }
                }
            })
        }
        listOf("setImageDrawable", "setImageResource", "setImageBitmap").forEach { name ->
            hookAll(ImageView::class.java, name, object : XC_MethodHook(Int.MAX_VALUE) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? ImageView ?: return
                    if (!ShadeBackgroundGuard.internal() && ShadeLiquidGlassEngine.role(view) == GlassRole.CONTROL_CENTER && controlCenterEnabled(view.context)) {
                        param.setResult(null)
                    }
                }
            })
        }
    }

    private fun clearNativeMaterial(view: View) {
        runCatching {
            val type = Class.forName("com.vivo.blur.VivoMaterialBlurDelegator", false, view.javaClass.classLoader)
            type.getMethod("clearMaterial", View::class.java).invoke(null, view)
        }
    }

    private fun notificationsEnabled(context: Context): Boolean = false

    private fun controlCenterEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, SETTING_CONTROL_CENTER, 0) == 1
    }.getOrDefault(false)

    private fun ownerContext(owner: Any): Context? {
        if (owner is View) return owner.context
        return listOf("mContext", "context", "fixedUiContext", "mFixedUiContext").firstNotNullOfOrNull {
            readField(owner, it) as? Context
        }
    }

    private fun hookAll(type: Class<*>, name: String, callback: XC_MethodHook) {
        runCatching { XposedBridge.hookAllMethods(type, name, callback) }.onFailure(XposedBridge::log)
    }

    private fun findClass(classLoader: ClassLoader, name: String): Class<*>? = runCatching {
        Class.forName(name, false, classLoader)
    }.getOrNull()

    private fun readField(owner: Any, name: String): Any? = runCatching {
        findField(owner.javaClass, name)?.get(owner)
    }.getOrNull()

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    private data class ControlCenterSpec(
        val name: String,
        val fields: List<String>,
        val methods: List<String>,
        val ownerOnly: Boolean = false,
        val imageLayer: Boolean = false,
        val deepBackground: Boolean = false
    )

    companion object {
        const val SETTING_NOTIFICATIONS = "originceiler_liquid_glass_notifications"
        const val SETTING_CONTROL_CENTER = "originceiler_liquid_glass_control_center"
        const val SETTING_BLUR = "originceiler_liquid_glass_blur"
        const val SETTING_REFRACTION = "originceiler_liquid_glass_refraction"
        const val SETTING_DISPERSION = "originceiler_liquid_glass_dispersion"
        const val SETTING_SATURATION = "originceiler_liquid_glass_saturation"
        const val SETTING_TINT = "originceiler_liquid_glass_tint"
        const val SETTING_EDGE = "originceiler_liquid_glass_edge"
        const val SETTING_QUALITY = "originceiler_liquid_glass_quality"
        const val SETTING_CC_RADIUS = "originceiler_liquid_glass_cc_radius"
        const val SETTING_NATIVE_BG_BLUR = "originceiler_native_bg_blur"
        private const val NOTIFICATION_BACKGROUND_CLASS = "com.android.systemui.statusbar.notification.row.NotificationBackgroundView"
        private val notificationClasses = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
        private val controlCenterClasses = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
        private val materialClasses = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
        private val seekProgressDrawables = Collections.synchronizedMap(WeakHashMap<View, GradientDrawable>())
        private val backgroundGuardsInstalled = AtomicBoolean(false)
        private val classLoaderWatcherInstalled = AtomicBoolean(false)
        private val CONTROL_CENTER_BACKGROUND_FIELDS = listOf("mContainerBg", "mBackgroundView", "mBg", "bg", "backgroundView", "mediaBg", "mMediaBg")
        private val MUSIC_BACKGROUND_IDS = setOf(
            "music_card_bg", "music_widget_background", "blur_view_bg", "iv_mixed_bg", "play_bg_view", "music_background_layout",
            "blur_view_bg_stub", "iv_cover_bg", "swipe_bg", "view_bg_set"
        )
        private val CONNECTIVITY_LABELS = setOf(
            "wi-fi", "wifi", 
            "интернет", "internet", 
            "мобильные", "mobile data", "передача", "сотовые", "data connection",
            "самолет", "самолёт", "airplane", "авиарежим", "flight mode"
        )
        private const val CONNECTIVITY_ACTIVE_BLUE = 0xFF0A84FF.toInt()
        private val COMMON_TILE_METHODS = listOf(
            "initialize",
            "initViews",
            "addBgView",
            "handleStateChanged",
            "onStateChanged",
            "updateBg",
            "updateBgColor",
            "updateBgImage",
            "updateBgImageDrawable",
            "resetBgColor",
            "updateResources",
            "onAttachedToWindow",
            "onVivoQSCenterThemeChanged",
            "setPressed",
            "drawableStateChanged",
            "dispatchSetPressed"
        )
        private val CONTROL_CENTER_CLASSES = listOf(
            ControlCenterSpec(
                "com.vivo.systemuiplugin.systemui.qs.tileimpl.VivoQSTileBaseViewImpl",
                listOf("mBg"),
                COMMON_TILE_METHODS
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.systemui.qs.tileimpl.VivoSuperQSTileViewImpl",
                listOf("mBg"),
                COMMON_TILE_METHODS
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.systemui.vivoqscenter.tileimpl.VivoSuperQSTileLabeledViewImpl",
                listOf("mContainerBg", "mBg"),
                COMMON_TILE_METHODS
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.systemui.vivoqscenter.tileimpl.VivoSuperCustomizeTileViewImpl",
                listOf("mBg"),
                COMMON_TILE_METHODS
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.android.systemui.qs.paneltile.VivoPanelTileViewImpl",
                listOf("mBg"),
                COMMON_TILE_METHODS + listOf("addBgView", "refreshBlur", "updateResource", "updateIconBgColor")
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.android.systemui.qs.paneltile.VivoAnimPanelTileViewImpl",
                listOf("mBg"),
                COMMON_TILE_METHODS + listOf("updateBlurAnimBg", "dragAnimUpdate", "updateAnimView")
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.systemui.vivoqscenter.view.VivoSuperQSGroupViewImpl",
                listOf("mBg"),
                COMMON_TILE_METHODS
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.systemui.vivoqscenter.view.VivoSuperQSConnectGroupViewImpl",
                listOf("mBg"),
                COMMON_TILE_METHODS
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.systemui.vivoqscenter.view.VivoSuperQSRingVibrateViewImpl",
                listOf("mBg"),
                COMMON_TILE_METHODS
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.systemui.vivoqscenter.view.VivoSuperQSDndViewImpl",
                listOf("mBg"),
                COMMON_TILE_METHODS
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.systemui.vivoqscenter.view.VivoSuperQSFocusViewImpl",
                listOf("mBg"),
                COMMON_TILE_METHODS
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.systemui.vivoqscenter.view.VivoSuperQSTileVolumeGroupViewImpl",
                listOf("mBg"),
                COMMON_TILE_METHODS
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.systemui.qs.ui.VivoQsSeekBar",
                listOf("mBackgroundView"),
                listOf("addChildrenView", "updateResources", "expandFilletRadius", "onAttachedToWindow", "copy", "setProgress", "setProgressColor")
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.systemui.qs.volume.VivoQsVolumeSeekBar",
                listOf("mBackgroundView"),
                listOf("addChildrenView", "updateResources", "expandFilletRadius", "onAttachedToWindow", "copy", "setProgress", "setProgressColor")
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.systemui.qs.music.ControlCenterMediaPlayerView",
                listOf("bg", "mBg", "backgroundView", "mBackgroundView", "mediaBg", "mMediaBg"),
                listOf("initView", "onFinishInflate", "updateBg", "updateBackground", "updateBgShape", "updateThemeColor", "onSystemColorChanged", "onVivoQSCenterThemeChanged", "onAttachedToWindow", "onVisibilityAggregated", "setPressed", "drawableStateChanged", "dispatchSetPressed"),
                imageLayer = true
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.systemui.vivoqscenter.view.VivoQs2x4MixMusicContainer",
                listOf("mMixWidgetView"),
                listOf("setMixWidgetView", "addMixWidgetView", "addAllViews", "init2x4MixWidget", "onAttachedToWindow", "updateChildBlurVisible", "onRealtimeBlurSwitchChanged", "update2x4MixWidgetVisibility", "setPressed", "drawableStateChanged", "dispatchSetPressed"),
                deepBackground = true
            ),
            ControlCenterSpec(
                "com.vivo.systemuiplugin.systemui.vivoqscenter.view.VivoQsMixMusicContainer",
                listOf("mMixWidgetView", "mMusicWidgetView", "mWidgetView", "mMusicContainer"),
                listOf("setMixWidgetView", "addMixWidgetView", "addAllViews", "initMixWidget", "onAttachedToWindow", "updateChildBlurVisible", "onRealtimeBlurSwitchChanged", "updateMixWidgetVisibility", "setPressed", "drawableStateChanged", "dispatchSetPressed"),
                deepBackground = true
            )
        )
        private val CONTROL_CENTER_CLASSES_BY_NAME = CONTROL_CENTER_CLASSES.associateBy { it.name }
    }
}

private enum class GlassRole {
    NOTIFICATION,
    CONTROL_CENTER
}

private data class GlassTuning(
    val blur: Float,
    val refraction: Float,
    val dispersion: Float,
    val saturation: Float,
    val tint: Float,
    val edge: Float,
    val ccRadius: Float,
    val captureScale: Float
)

private object ShadeLiquidGlassEngine {
    private val drawables = Collections.synchronizedMap(WeakHashMap<View, ShadeLiquidGlassDrawable>())
    private val capture = ShadeScreenCapture()
    @Volatile
    private var tuningReadAt = 0L
    @Volatile
    private var cachedTuning = GlassTuning(1f, 1f, 1f, 1.2f, 1f, 1f, 0f, 0.35f)

    fun attach(view: View, role: GlassRole): ShadeLiquidGlassDrawable {
        synchronized(drawables) {
            val current = drawables[view]
            if (current != null && current.role == role) return current
            return ShadeLiquidGlassDrawable(view, role, capture).also { drawables[view] = it }
        }
    }

    fun role(view: View): GlassRole? = synchronized(drawables) { drawables[view]?.role }

    fun tuning(context: Context): GlassTuning {
        val now = SystemClock.uptimeMillis()
        if (now - tuningReadAt < 250L) return cachedTuning
        cachedTuning = runCatching {
            val resolver = context.contentResolver
            GlassTuning(
                Settings.Global.getInt(resolver, ShadeLiquidGlassHook.SETTING_BLUR, 100).coerceIn(0, 200) / 100f,
                Settings.Global.getInt(resolver, ShadeLiquidGlassHook.SETTING_REFRACTION, 100).coerceIn(0, 200) / 100f,
                Settings.Global.getInt(resolver, ShadeLiquidGlassHook.SETTING_DISPERSION, 100).coerceIn(0, 200) / 100f,
                Settings.Global.getInt(resolver, ShadeLiquidGlassHook.SETTING_SATURATION, 120).coerceIn(50, 180) / 100f,
                Settings.Global.getInt(resolver, ShadeLiquidGlassHook.SETTING_TINT, 100).coerceIn(0, 200) / 100f,
                Settings.Global.getInt(resolver, ShadeLiquidGlassHook.SETTING_EDGE, 100).coerceIn(0, 200) / 100f,
                Settings.Global.getInt(resolver, ShadeLiquidGlassHook.SETTING_CC_RADIUS, 0).toFloat(),
                0.16f + Settings.Global.getInt(resolver, ShadeLiquidGlassHook.SETTING_QUALITY, 35).coerceIn(20, 50) / 100f * 0.8f
            )
        }.getOrDefault(cachedTuning)
        tuningReadAt = now
        return cachedTuning
    }
}

private object ShadeBackgroundGuard {
    private val mutation = ThreadLocal<Boolean>()

    fun internal(): Boolean = mutation.get() == true

    fun mutate(block: () -> Unit) {
        mutation.set(true)
        try {
            block()
        } finally {
            mutation.remove()
        }
    }
}

private class ShadeScreenCapture {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(
        0,
        2,
        2L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { task -> Thread(task, "OriginCeilerGlassCapture").apply { isDaemon = true } }
    )
    private val capturing = AtomicBoolean(false)
    private val captureGeneration = AtomicLong(0L)
    private val consumers = Collections.synchronizedMap(WeakHashMap<ShadeLiquidGlassDrawable, Boolean>())
    @Volatile
    private var frame: Bitmap? = null
    @Volatile
    private var frameScale = 0.2f
    @Volatile
    private var lastCapture = 0L
    @Volatile
    private var reflection: CaptureReflection? = null
    @Volatile
    private var reflectionAttempted = false

    fun register(drawable: ShadeLiquidGlassDrawable) {
        consumers[drawable] = true
    }

    fun bitmap(): Bitmap? = frame

    fun scale(): Float = frameScale

    fun request(view: View, requestedScale: Float) {
        if (!view.isShown || view.alpha <= 0.01f || view.width <= 0 || view.height <= 0) return
        val now = SystemClock.uptimeMillis()
        if (now - lastCapture < 16L || !capturing.compareAndSet(false, true)) return
        lastCapture = now
        val generation = captureGeneration.incrementAndGet()
        val surface = surfaceControl(view)
        val displayId = view.display?.displayId ?: 0
        val timeout = Runnable {
            if (captureGeneration.compareAndSet(generation, generation + 1L)) {
                capturing.set(false)
                invalidateConsumers()
            }
        }
        mainHandler.postDelayed(timeout, CAPTURE_TIMEOUT_MS)
        runCatching {
            worker.execute {
                val captured = runCatching { capture(displayId, surface, requestedScale) }.onFailure(XposedBridge::log).getOrNull()
                mainHandler.post {
                    mainHandler.removeCallbacks(timeout)
                    if (captureGeneration.get() == generation) {
                        if (captured != null) {
                            val old = frame
                            frame = captured.bitmap
                            frameScale = captured.scale
                            if (old !== captured.bitmap && old?.isRecycled == false) old.recycle()
                        }
                        capturing.set(false)
                        invalidateConsumers()
                    } else if (captured?.bitmap?.isRecycled == false) {
                        captured.bitmap.recycle()
                    }
                }
            }
        }.onFailure {
            mainHandler.removeCallbacks(timeout)
            captureGeneration.compareAndSet(generation, generation + 1L)
            capturing.set(false)
            invalidateConsumers()
        }
    }

    private fun invalidateConsumers() {
        synchronized(consumers) { consumers.keys.toList() }.forEach { it.invalidateSelf() }
    }

    private fun capture(displayId: Int, surface: SurfaceControl?, requestedScale: Float): CapturedFrame? {
        val api = reflection ?: initializeReflection()?.also { reflection = it } ?: return null
        val builder = api.builderConstructor.newInstance()
        val scale = requestedScale.coerceIn(0.3f, 0.6f)
        api.setFrameScale.invoke(builder, scale, scale)
        if (surface != null && surface.isValid) {
            val excluded = Array.newInstance(SurfaceControl::class.java, 1)
            Array.set(excluded, 0, surface)
            api.setExcludeLayers.invoke(builder, excluded)
        }
        api.setCaptureMode?.invoke(builder, 1)
        val args = api.build.invoke(builder)
        val listener = api.createListener.invoke(null)
        api.captureDisplay.invoke(api.windowManager, displayId, args, listener)
        val buffer = api.getBuffer.invoke(listener) ?: return null
        val bitmap = api.asBitmap.invoke(buffer) as? Bitmap ?: return null
        return CapturedFrame(bitmap, scale)
    }

    private fun initializeReflection(): CaptureReflection? {
        if (reflectionAttempted) return reflection
        reflectionAttempted = true
        return runCatching {
            val windowManagerGlobal = Class.forName("android.view.WindowManagerGlobal")
            val windowManager = windowManagerGlobal.getMethod("getWindowManagerService").invoke(null) ?: error("No WindowManager service")
            val builderClass = Class.forName("android.window.ScreenCapture\$CaptureArgs\$Builder")
            val captureArgsClass = Class.forName("android.window.ScreenCapture\$CaptureArgs")
            val listenerClass = Class.forName("android.window.ScreenCapture\$ScreenCaptureListener")
            val syncListenerClass = Class.forName("android.window.ScreenCapture\$SynchronousScreenCaptureListener")
            val bufferClass = Class.forName("android.window.ScreenCapture\$ScreenshotHardwareBuffer")
            val screenCaptureClass = Class.forName("android.window.ScreenCapture")
            val windowManagerClass = Class.forName("android.view.IWindowManager")
            val surfaceArrayClass = Array.newInstance(SurfaceControl::class.java, 0).javaClass
            val constructor = builderClass.getDeclaredConstructor().apply { isAccessible = true }
            CaptureReflection(
                windowManager,
                constructor,
                builderClass.getMethod("setFrameScale", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType),
                builderClass.getMethod("setExcludeLayers", surfaceArrayClass),
                runCatching { builderClass.getMethod("setExcludeOrIncludeLayerNames", kotlin.Array<String>::class.java) }.getOrNull(),
                runCatching { builderClass.getMethod("setCaptureMode", Int::class.javaPrimitiveType) }.getOrNull(),
                builderClass.getMethod("build"),
                windowManagerClass.getMethod("captureDisplay", Int::class.javaPrimitiveType, captureArgsClass, listenerClass),
                screenCaptureClass.getMethod("createSyncCaptureListener"),
                syncListenerClass.getMethod("getBuffer"),
                bufferClass.getMethod("asBitmap")
            )
        }.onFailure(XposedBridge::log).getOrNull()
    }

    private fun surfaceControl(view: View): SurfaceControl? = runCatching {
        val getRoot = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }
        val root = getRoot.invoke(view) ?: return@runCatching null
        val getSurface = root.javaClass.getDeclaredMethod("getSurfaceControl").apply { isAccessible = true }
        getSurface.invoke(root) as? SurfaceControl
    }.getOrNull()

    private data class CaptureReflection(
        val windowManager: Any,
        val builderConstructor: java.lang.reflect.Constructor<*>,
        val setFrameScale: Method,
        val setExcludeLayers: Method,
        val setLayerNames: Method?,
        val setCaptureMode: Method?,
        val build: Method,
        val captureDisplay: Method,
        val createListener: Method,
        val getBuffer: Method,
        val asBitmap: Method
    )

    private data class CapturedFrame(val bitmap: Bitmap, val scale: Float)

    companion object {
        private const val CAPTURE_TIMEOUT_MS = 80L
    }
}

private class ShadeLiquidGlassDrawable(
    private val host: View,
    val role: GlassRole,
    private val capture: ShadeScreenCapture
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = host.resources.displayMetrics.density
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private val location = IntArray(2)
    private var drawableAlpha = 255
    private var cornerRadii: FloatArray? = null
    private var lastBitmap: Bitmap? = null
    private var shader: RuntimeShader? = null
    private var bitmapShader: BitmapShader? = null
    private var shaderFailed = false
    private var shapeCaptured = false
    private var active = false
    private val cachedOutline = Outline()
    
    var progressFraction: Float = -1f
    var isVerticalProgress: Boolean = false

    fun setProgressFraction(fraction: Float, vertical: Boolean) {
        progressFraction = fraction
        isVerticalProgress = vertical
        invalidateSelf()
    }

    init {
        capture.register(this)
    }

    fun setCornerRadii(value: FloatArray) {
        cornerRadii = value
        shapeCaptured = true
        invalidateSelf()
    }

    fun captureShape(source: Drawable?, roundedRectangle: Boolean) {
        if (shapeCaptured || source == null && !roundedRectangle) return
        val outlineRadius = source?.let {
            runCatching {
                val outline = Outline()
                it.getOutline(outline)
                outline.radius
            }.getOrNull()
        } ?: 0f
        val density = host.resources.displayMetrics.density
        val radius = when {
            roundedRectangle && outlineRadius > 0f -> min(outlineRadius, 26f * density)
            outlineRadius > 0f -> outlineRadius
            roundedRectangle -> 26f * density
            else -> 0f
        }
        if (radius > 0f) {
            cornerRadii = FloatArray(8) { radius }
            shapeCaptured = true
            invalidateSelf()
        }
    }

    fun setActive(value: Boolean?) {
        if (value == null || active == value) return
        active = value
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val tuning = ShadeLiquidGlassEngine.tuning(host.context)
        capture.request(host, tuning.captureScale)
        val bitmap = capture.bitmap()
        val radii = resolvedRadii(tuning)
        path.reset()
        path.addRoundRect(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            radii,
            Path.Direction.CW
        )
        if (bitmap == null || bitmap.isRecycled || !drawRuntimeGlass(canvas, bitmap, tuning)) drawFallback(canvas, radii)
        if (active) {
            activePaint.color = Color.argb((drawableAlpha * 0.92f).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawPath(path, activePaint)
        }
        
        if (progressFraction >= 0f) {
            val pBounds = RectF(bounds)
            if (isVerticalProgress) {
                pBounds.top = pBounds.bottom - pBounds.height() * progressFraction
            } else {
                pBounds.right = pBounds.left + pBounds.width() * progressFraction
            }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawRect(pBounds, progressPaint)
            canvas.restore()
        }

        drawStroke(canvas, tuning)
    }

    private fun drawRuntimeGlass(canvas: Canvas, bitmap: Bitmap, tuning: GlassTuning): Boolean {
        if (shaderFailed) return false
        return runCatching {
            val runtime = shader ?: RuntimeShader(AGSL).also { shader = it }
            if (lastBitmap !== bitmap || bitmapShader == null) {
                bitmapShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                bitmapShader!!.setFilterMode(1)
                runtime.setInputShader("content", bitmapShader!!)
                lastBitmap = bitmap
            }
            host.getLocationOnScreen(location)
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            val radius = resolvedRadii(tuning).maxOrNull() ?: min(width, height) * 0.28f
            val density = host.resources.displayMetrics.density
            val dark = host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val shortSide = min(width, height)
            val roleTint = if (role == GlassRole.NOTIFICATION) 0.10f else 0.13f
            runtime.setFloatUniform("size", width, height)
            runtime.setFloatUniform("localOrigin", bounds.left.toFloat(), bounds.top.toFloat())
            runtime.setFloatUniform("screenOrigin", (location[0] + bounds.left).toFloat(), (location[1] + bounds.top).toFloat())
            runtime.setFloatUniform("captureScale", capture.scale())
            runtime.setFloatUniform("cornerRadius", radius)
            runtime.setFloatUniform("blurRadius", 14f * density * tuning.blur)
            runtime.setFloatUniform("bevel", min(28f * density, shortSide * 0.32f))
            runtime.setFloatUniform("refractPx", 14f * density * tuning.refraction)
            runtime.setFloatUniform("dispersion", 0.075f * tuning.dispersion)
            runtime.setFloatUniform("lightDir", -0.58f, -0.81f)
            runtime.setFloatUniform("specStrength", 0.72f * tuning.edge)
            runtime.setFloatUniform("innerShadow", 0.34f * tuning.edge)
            runtime.setFloatUniform("tintColor", if (dark) 0.08f else 0.94f, if (dark) 0.095f else 0.97f, if (dark) 0.12f else 1f, (roleTint * tuning.tint).coerceIn(0f, 0.45f))
            runtime.setFloatUniform("satFactor", tuning.saturation)
            runtime.setFloatUniform("dimAmount", if (dark) 0.045f else 0.018f)
            runtime.setFloatUniform("drawableAlpha", drawableAlpha / 255f)
            paint.shader = runtime
            canvas.drawPath(path, paint)
            true
        }.onFailure {
            shaderFailed = true
            XposedBridge.log(it)
        }.getOrDefault(false)
    }

    private fun drawFallback(canvas: Canvas, radii: FloatArray) {
        val dark = host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val base = if (dark) Color.argb((150 * drawableAlpha / 255f).toInt(), 28, 30, 35) else Color.argb((165 * drawableAlpha / 255f).toInt(), 232, 237, 244)
        val shine = if (dark) Color.argb((42 * drawableAlpha / 255f).toInt(), 255, 255, 255) else Color.argb((96 * drawableAlpha / 255f).toInt(), 255, 255, 255)
        fallbackPaint.shader = LinearGradient(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            intArrayOf(shine, base, base),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fallbackPaint)
    }

    private fun drawStroke(canvas: Canvas, tuning: GlassTuning) {
        val alpha = (drawableAlpha * 0.72f * tuning.edge).toInt().coerceIn(0, 255)
        strokePaint.shader = LinearGradient(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            intArrayOf(Color.argb(alpha, 255, 255, 255), Color.argb(alpha / 9, 255, 255, 255), Color.argb(alpha / 2, 255, 255, 255)),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.clipPath(path)
        canvas.drawPath(path, strokePaint)
        canvas.restore()
    }

    private fun resolvedRadii(tuning: GlassTuning): FloatArray {
        val density = host.resources.displayMetrics.density
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val shortSide = min(width, height)
        
        val isContainer = host.javaClass.simpleName.let { name ->
            name.contains("Group") || name.contains("Focus") || name.contains("Dnd") || 
            name.contains("RingVibrate") || name.contains("SeekBar") || 
            name.contains("Music") || name.contains("Container")
        }
        
        if (!isContainer && shortSide > 0f && max(width, height) <= shortSide * 1.15f && (role != GlassRole.CONTROL_CENTER || shortSide < 90f * density)) {
            val r = shortSide * 0.5f
            return FloatArray(8) { r }
        }

        val custom = cornerRadii
        if (role == GlassRole.NOTIFICATION && custom != null && custom.size >= 8) return custom
        
        val radius = when {
            shortSide <= 0f -> 0f
            role == GlassRole.NOTIFICATION -> min(24f * density, shortSide * 0.34f)
            else -> min(27f * density, shortSide * 0.5f)
        }
        return FloatArray(8) { radius }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        this.drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        fallbackPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private val AGSL = """
            uniform shader content;
            uniform float2 size;
            uniform float2 localOrigin;
            uniform float2 screenOrigin;
            uniform float captureScale;
            uniform float cornerRadius;
            uniform float blurRadius;
            uniform float bevel;
            uniform float refractPx;
            uniform float dispersion;
            uniform float2 lightDir;
            uniform float specStrength;
            uniform float innerShadow;
            uniform float4 tintColor;
            uniform float satFactor;
            uniform float dimAmount;
            uniform float drawableAlpha;

            float sdRoundedBox(float2 p, float2 halfSize, float radius) {
                float2 q = abs(p) - halfSize + radius;
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
            }

            half4 sampleBlur(float2 point, float radius, float2 fragCoord) {
                if (radius <= 0.5) return content.eval(point);
                half4 color = half4(0.0);
                float totalWeight = 0.0;
                float c = -0.7373688;
                float s = 0.6754903;
                float randAngle = fract(sin(dot(fragCoord, float2(12.9898, 78.233))) * 43758.5453) * 6.2831853;
                float2 dir = float2(cos(randAngle), sin(randAngle));
                for (int i = 0; i < 32; i++) {
                    float t = (float(i) + 0.5) / 32.0;
                    float r = radius * sqrt(t);
                    float weight = 1.0 - t;
                    color += content.eval(point + dir * r) * half(weight);
                    totalWeight += weight;
                    float2 newDir = float2(dir.x * c - dir.y * s, dir.x * s + dir.y * c);
                    dir = newDir;
                }
                return color / half(totalWeight);
            }

            half4 main(float2 fragCoord) {
                float2 local = fragCoord - localOrigin;
                float2 halfSize = size * 0.5;
                float2 centered = local - halfSize;
                float d = sdRoundedBox(centered, halfSize, cornerRadius);
                float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);
                if (cov <= 0.004) return half4(0.0);
                float2 n = float2(
                    sdRoundedBox(centered + float2(1.0, 0.0), halfSize, cornerRadius) - sdRoundedBox(centered - float2(1.0, 0.0), halfSize, cornerRadius),
                    sdRoundedBox(centered + float2(0.0, 1.0), halfSize, cornerRadius) - sdRoundedBox(centered - float2(0.0, 1.0), halfSize, cornerRadius)
                );
                float nLen = length(n);
                n = nLen > 0.0001 ? n / nLen : float2(0.0, -1.0);
                float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
                float slope = (1.0 - t) * (1.0 - t);
                float2 offset = -n * (slope * refractPx);
                float2 capturePoint = (screenOrigin + local) * captureScale;
                float2 offsetScaled = offset * captureScale;
                float blur = blurRadius * captureScale;
                half3 col = half3(
                    sampleBlur(capturePoint + offsetScaled * (1.0 - dispersion * slope), blur, fragCoord).r,
                    sampleBlur(capturePoint + offsetScaled, blur, fragCoord).g,
                    sampleBlur(capturePoint + offsetScaled * (1.0 + dispersion * slope), blur, fragCoord).b
                );
                half lum = dot(col, half3(0.2126, 0.7152, 0.0722));
                if (satFactor <= 1.0) {
                    col = mix(half3(lum), col, half(satFactor));
                } else {
                    half satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
                    half room = 1.0 - smoothstep(0.2, 0.85, satNow);
                    half highlightRoom = 1.0 - smoothstep(0.75, 0.98, lum);
                    half amount = 1.0 + half(satFactor - 1.0) * mix(0.3, 1.0, room * highlightRoom);
                    col = clamp(mix(half3(lum), col, amount), half3(0.0), half3(1.0));
                }
                col = mix(col, half3(tintColor.rgb), half(tintColor.a));
                col *= half(1.0 - dimAmount);
                float facing = dot(n, -lightDir);
                float facingPos = max(facing, 0.0);
                float facingNeg = max(-facing, 0.0);
                float bandWidth = clamp(bevel * 0.3, 2.0, 9.0);
                float rim = clamp(1.0 - (-d - 0.5) / bandWidth, 0.0, 1.0) * cov;
                float lobe = 0.55 * pow(facingPos, 5.0) + 0.18 * pow(facingNeg, 5.0) + 0.05;
                float hair = clamp(1.0 - abs(d + 1.0) / 1.5, 0.0, 1.0);
                float spec = (rim * lobe + hair * (0.22 + 0.35 * facingPos)) * specStrength;
                col += half3(spec);
                float shadowWidth = clamp(bevel, 4.0, 28.0);
                float shadowBand = pow(clamp(1.0 + d / shadowWidth, 0.0, 1.0), 1.5);
                float inner = shadowBand * facingNeg * innerShadow;
                col *= half(1.0 - 0.45 * inner);
                col = clamp(col, half3(0.0), half3(1.0));
                return half4(col * half(cov), half(cov * drawableAlpha));
            }
        """.trimIndent()
    }
}
