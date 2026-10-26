package dev.unvoid.originceiler

import android.app.Application
import android.app.Instrumentation
import android.content.ContentResolver
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Port of LiquidVivo 1.7.5 (Sleep-jay/LiquidVivo, MIT) to the classic Xposed API.
 *
 * OriginOS routes every SystemUI material through hidden View facades (setMaterial*,
 * setMaterialCustomized, setMaterialMaskFusion), BlurMaterial tokens and the private
 * BlurDrawableController stack. Each call is classified into a scene (shade / volume /
 * keyguard / popup / island / other) and its blur radius and mask opacity are scaled by
 * that scene's settings. "Liquid Glass" mode additionally thins the masks, boosts
 * saturation, forces real-time blur on control-center tiles and turns the sidebar's solid
 * cards into glass. Settings live in Settings.Global (prefix [PREFIX]) and apply live: the
 * last material call per view is replayed whenever a value changes.
 */
object LiquidVivoGlassHook {
    const val PREFIX = "originceiler_lv_"
    const val KEY_ENABLE = PREFIX + "enable"
    const val KEY_LIQUID_GLASS = PREFIX + "liquid_glass"
    const val KEY_GLASS_OPACITY = PREFIX + "glass_opacity"
    const val KEY_GLASS_RADIUS = PREFIX + "glass_radius"
    const val KEY_ISLAND = PREFIX + "island"

    const val DEFAULT_GLASS_OPACITY = 40
    const val DEFAULT_GLASS_RADIUS = 8
    const val DEFAULT_SCALE = 100

    fun radiusKey(scene: Scene) = PREFIX + "radius_" + scene.suffix
    fun alphaKey(scene: Scene) = PREFIX + "alpha_" + scene.suffix

    enum class Scene(val suffix: String, val hasRadius: Boolean, val hasAlpha: Boolean) {
        SHADE("shade", true, false),
        VOLUME("volume", true, true),
        KEYGUARD("keyguard", true, false),
        POPUP("popup", false, true),
        ISLAND("island", true, true),
        OTHER("other", true, true),
    }

    private const val PKG_SYSTEMUI = "com.android.systemui"
    private const val PKG_PLUGIN = "com.vivo.systemuiplugin"
    private const val PKG_UPSLIDE = "com.vivo.upslide"
    private const val PKG_CARD = "com.vivo.card"
    private const val WINDOW_TYPE_VOLUME_OVERLAY = 2020
    private const val MAX_PARENT_DEPTH = 24

    // ---------------------------------------------------------------- config

    @Volatile private var enabled = false
    @Volatile private var liquidGlass = true
    @Volatile private var glassOpacity = DEFAULT_GLASS_OPACITY / 100f
    @Volatile private var glassRadiusPx = DEFAULT_GLASS_RADIUS
    @Volatile private var islandEnabled = true
    private val radiusScales = FloatArray(Scene.entries.size) { 1f }
    private val alphaScales = FloatArray(Scene.entries.size) { 1f }

    @Volatile private var upslideProcess = false
    @Volatile private var cardProcess = false
    private var processPackage = ""

    private val installed = AtomicBoolean(false)
    private val observerInstalled = AtomicBoolean(false)
    private val pluginLoaders = Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())

    private fun reload(cr: ContentResolver) {
        fun int(key: String, def: Int) = runCatching { Settings.Global.getInt(cr, key, def) }.getOrDefault(def)
        enabled = int(KEY_ENABLE, 0) == 1
        // Superseded by the Kyant glass (Liquid Glass page): only an explicit old opt-in keeps it.
        liquidGlass = int(KEY_LIQUID_GLASS, 0) == 1
        glassOpacity = int(KEY_GLASS_OPACITY, DEFAULT_GLASS_OPACITY).coerceIn(0, 100) / 100f
        glassRadiusPx = int(KEY_GLASS_RADIUS, DEFAULT_GLASS_RADIUS).coerceIn(0, 32)
        islandEnabled = int(KEY_ISLAND, 1) == 1
        for (scene in Scene.entries) {
            radiusScales[scene.ordinal] = int(radiusKey(scene), DEFAULT_SCALE).coerceIn(0, 200) / 100f
            alphaScales[scene.ordinal] = int(alphaKey(scene), DEFAULT_SCALE).coerceIn(0, 200) / 100f
        }
    }

    private fun radiusScaleOf(scene: Scene) = radiusScales[scene.ordinal]
    private fun alphaScaleOf(scene: Scene) = alphaScales[scene.ordinal]

    /** Mask opacity factor: with Liquid Glass, masks are thinned by the glass opacity. */
    private fun overlayScaleOf(scene: Scene): Float {
        val sceneScale = alphaScales[scene.ordinal]
        if (!liquidGlass) return sceneScale
        if (radiusScales[scene.ordinal] <= 0f) return 0f
        return sceneScale * glassOpacity
    }

    private fun glassSaturation(original: Float): Float {
        if (!liquidGlass || original <= 0f) return original
        if (original <= 2f) return (original * 1.5f).coerceAtMost(2f)
        return 0f
    }

    /** Active QS tiles ship blurRadius = 0 (solid colour); give them a real blur. */
    private fun glassRadius(original: Float, scene: Scene): Float {
        val scaled = original * radiusScaleOf(scene)
        if (!liquidGlass) return scaled
        if (radiusScaleOf(scene) <= 0f) return 0f
        if (scaled < 0.08f) return 0.22f * radiusScaleOf(scene)
        return scaled
    }

    private fun sidebarGlassRadius(scene: Scene): Float =
        (glassRadiusPx / 16f * radiusScaleOf(scene)).coerceIn(0f, 2f)

    // ---------------------------------------------------------------- install

    fun install(classLoader: ClassLoader, packageName: String) {
        if (!installed.compareAndSet(false, true)) return
        processPackage = packageName
        upslideProcess = packageName == PKG_UPSLIDE
        cardProcess = packageName == PKG_CARD
        hookConfigLoading()
        val mounted = ArrayList<String>()
        runCatching { hookViewSurface(mounted) }.onFailure { log("view surface: $it") }
        runCatching { hookSidebarGlass(mounted) }.onFailure { log("sidebar: $it") }
        runCatching { if (hookBlurMaterialTokens(classLoader)) mounted += "BlurMaterial.token" }
        if (packageName == PKG_SYSTEMUI) {
            runCatching { hookBlurDrawableStack(classLoader, mounted) }
        }
        // QS / island code lives in com.vivo.systemuiplugin: its own process, and also a plugin
        // class loader inside SystemUI. Cover both.
        installPluginHooks(classLoader, mounted)
        if (packageName == PKG_SYSTEMUI) watchPluginClassLoader()
        log("installed in $packageName: ${mounted.size} surfaces $mounted")
    }

    /** Config needs a ContentResolver, which exists only once the Application is created. */
    private fun hookConfigLoading() {
        XposedBridge.hookAllMethods(Instrumentation::class.java, "callApplicationOnCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.args.firstOrNull() as? Application ?: return
                if (!observerInstalled.compareAndSet(false, true)) return
                val cr = app.contentResolver
                reload(cr)
                val handler = Handler(Looper.getMainLooper())
                cr.registerContentObserver(Settings.Global.CONTENT_URI, true, object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        if (uri?.lastPathSegment?.startsWith(PREFIX) != true) return
                        val wasEnabled = enabled
                        reload(cr)
                        if (processPackage == PKG_SYSTEMUI) reapplyDrawableStack()
                        replayMaterialSnapshots(force = wasEnabled && !enabled)
                        IslandCapsule.onConfigChanged(uri.lastPathSegment)
                    }
                })
                log("config loaded in ${app.packageName}: enable=$enabled glass=$liquidGlass")
            }
        })
    }

    private fun watchPluginClassLoader() {
        XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val cls = param.result as? Class<*> ?: return
                if (cls.name != "com.vivo.systemuiplugin.systemui.qs.utils.VivoEffectHelper" &&
                    cls.name != "com.vivo.island.animation.ContainerAnimatorCommon"
                ) return
                val loader = cls.classLoader ?: return
                installPluginHooks(loader, ArrayList())
            }
        })
    }

    private fun installPluginHooks(loader: ClassLoader, mounted: MutableList<String>) {
        synchronized(pluginLoaders) { if (!pluginLoaders.add(loader)) return }
        runCatching { hookAmbientLightVibrancy(loader, mounted) }
        runCatching { hookAddBlurBgRetry(loader, mounted) }
        runCatching { hookColorSupportBlur(loader, mounted) }
        runCatching { hookSupportBlur(loader, mounted) }
        runCatching { hookQsMaterialParams(loader, mounted) }
        runCatching { IslandCapsule.install(loader) }
    }

    private fun log(msg: String) = XposedBridge.log("OriginLiquidVivo: $msg")

    private fun before(block: (XC_MethodHook.MethodHookParam) -> Unit) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) = block(param)
    }

    private fun after(block: (XC_MethodHook.MethodHookParam) -> Unit) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) = block(param)
    }

    // ---------------------------------------------------------------- live replay

    private class MaterialCall(val method: Method, val args: Array<Any?>)

    private val materialSnapshots =
        Collections.synchronizedMap(WeakHashMap<View, MutableMap<Method, MaterialCall>>())

    private fun snapshot(view: View?, method: Method, args: Array<Any?>) {
        if (view == null) return
        synchronized(materialSnapshots) {
            materialSnapshots.getOrPut(view) { HashMap() }[method] = MaterialCall(method, args.copyOf())
        }
    }

    /** Re-invokes the last material call per view so new settings show without reopening. */
    private fun replayMaterialSnapshots(force: Boolean = false) {
        if (!enabled && !force) return
        val calls = synchronized(materialSnapshots) {
            materialSnapshots.entries.flatMap { (view, byMethod) -> byMethod.values.map { it to view } }
        }
        for ((call, view) in calls) runCatching { call.method.invoke(view, *call.args) }
    }

    // ---------------------------------------------------------------- scene classification

    private val SHADE_ANCHORS = setOf(
        "NotificationShade", "ShadeLayout", "NotificationStackScrollLayout",
        "PhoneStatusBarView", "VivoPanelViewHolder", "GlanceableHubContainer",
        "QSPanel", "QSContainerImpl", "QsFrame", "VivoQSCenter", "VivoQsStatusContainer",
        "QsPagedPage", "NonInterceptingScrollView", "PageIndicator",
        "QuickStatusBarHeader", "VivoQuickStatusBarHeader",
        "VivoPanelTileViewImpl", "NotificationClearAllImageView",
        "VivoSplitQsStatusBarContainer",
    )
    private val POPUP_ANCHORS = setOf("VCustomRoundRectLayout", "VListPopupWindow", "VSpinner")
    private val anchorClassCache = ConcurrentHashMap<Class<*>, Scene>()
    private val threadScene = ThreadLocal<Scene>()

    private fun classifyScene(receiver: Any?): Scene {
        if (receiver !is View) return Scene.OTHER
        var classHit: Scene? = null
        var v: View? = receiver
        var depth = 0
        while (v != null && depth < MAX_PARENT_DEPTH) {
            matchAnchor(v.javaClass)?.let { classHit = it; break }
            v = v.parent as? View
            depth++
        }
        when (classHit) {
            Scene.ISLAND, Scene.VOLUME, Scene.KEYGUARD, Scene.SHADE -> return classHit!!
            else -> Unit
        }
        if (windowTypeOf(receiver) == WINDOW_TYPE_VOLUME_OVERLAY) return Scene.VOLUME
        if (idSceneOf(receiver) == Scene.VOLUME) return Scene.VOLUME
        if (classHit == Scene.POPUP) return Scene.POPUP
        if (isNotificationSurface(receiver)) return Scene.SHADE
        if (upslideProcess || cardProcess) return Scene.SHADE
        return Scene.OTHER
    }

    private fun matchAnchor(cls: Class<*>): Scene? {
        anchorClassCache[cls]?.let { return it }
        val s = cls.simpleName
        val hit = when {
            s == "DynamicCapsuleContainer" -> Scene.ISLAND
            s == "VivoVolumeSeekbar" || s.startsWith("VolumeDialog") -> Scene.VOLUME
            s.startsWith("Keyguard") || s == "BouncerBlurView" || s == "ChargeBlurView" ||
                s == "BaseRealtimeBlurView" || s == "BlurRenderView" -> Scene.KEYGUARD
            s in SHADE_ANCHORS -> Scene.SHADE
            s.contains("QSTile") || s.contains("QsTile") || s.contains("QSCenter") || s.contains("QsCenter") ||
                s.contains("SuperQS") || s.endsWith("MoveBoolButton") || s.contains("Seekbar") ||
                s.contains("SeekBar") || s.startsWith("VivoEffect") || s.endsWith("DetailLayout") ||
                s.contains("MixMusic") || s.contains("FakeWifi") || s.contains("FakeBluetooth") ||
                s.contains("QSIOT") || s.contains("IOT") || s.contains("ConnectGroup") ||
                s.contains("MediaPlayer") || s.contains("FocusMode") ||
                s.contains("Sidebar", true) || s.contains("SidePanel", true) ||
                s.contains("SmallWindow", true) || s.contains("SmallApp", true) ||
                s.contains("QuickTool", true) || s.contains("FloatPanel", true) ||
                s.contains("FloatingPanel", true) || s.contains("VivoPanel", true) -> Scene.SHADE
            s in POPUP_ANCHORS -> Scene.POPUP
            else -> null
        }
        val name = cls.name
        if (!name.startsWith("android.") && !name.startsWith("androidx.") && !name.startsWith("java.")) {
            hit?.let { anchorClassCache[cls] = it }
        }
        return hit
    }

    private var getViewRootImplMethod: Method? = null
    private var windowAttributesField: Field? = null
    @Volatile private var windowTypeProbeFailed = false

    private fun windowTypeOf(view: View): Int? {
        if (windowTypeProbeFailed) return null
        return runCatching {
            val m = getViewRootImplMethod ?: View::class.java.getMethod("getViewRootImpl").also {
                it.isAccessible = true; getViewRootImplMethod = it
            }
            val root = m.invoke(view) ?: return null
            val f = windowAttributesField ?: root.javaClass.getField("mWindowAttributes").also { windowAttributesField = it }
            (f.get(root) as? WindowManager.LayoutParams)?.type
        }.onFailure { windowTypeProbeFailed = true }.getOrNull()
    }

    private val idNameSceneCache = Collections.synchronizedMap(HashMap<Int, Scene?>())

    private fun idSceneOf(view: View): Scene? {
        val id = view.id
        if (id == 0 || id == View.NO_ID) return null
        if (idNameSceneCache.containsKey(id)) return idNameSceneCache[id]
        val name = runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
        val hit = if (name == "volume_dialog") Scene.VOLUME else null
        idNameSceneCache[id] = hit
        return hit
    }

    private val surfaceClassCache = Collections.synchronizedMap(HashMap<Class<*>, Boolean>())

    /** Heads-up / lockscreen / shade notification cards: their alpha is row animation alpha. */
    private fun isNotificationSurface(receiver: Any?): Boolean {
        if (receiver == null) return false
        val cls = receiver.javaClass
        surfaceClassCache[cls]?.let { return it }
        var c: Class<*>? = cls
        var hit = false
        while (c != null && c != Any::class.java) {
            if (c.simpleName in setOf(
                    "NotificationBackgroundView", "NotificationHeaderView", "VivoNotificationBlockMenu",
                    "ExpandableNotificationRow", "ActivatableNotificationView"
                )
            ) { hit = true; break }
            c = c.superclass
        }
        surfaceClassCache[cls] = hit
        return hit
    }

    private fun isNativeQsGlass(view: Any?): Boolean =
        (view as? View)?.javaClass?.simpleName?.contains("VivoSuperQSTileBg", ignoreCase = true) == true

    private fun maskScale(original: Float, scene: Scene, notif: Boolean): Float {
        if (notif && !liquidGlass) return (original * (0.5f + 0.5f * alphaScaleOf(scene))).coerceIn(0f, 1f)
        return glassMask(original, scene)
    }

    /** Thin masks sink below the ROM's "alpha ≤ 0.1 disables blur" gate; keep them at 0.12. */
    private fun glassMask(original: Float, scene: Scene): Float {
        val scaled = (original * overlayScaleOf(scene)).coerceIn(0f, 1f)
        if (liquidGlass && original > 0f && overlayScaleOf(scene) > 0f) return scaled.coerceAtLeast(0.12f)
        return scaled
    }

    // ---------------------------------------------------------------- View material facades

    private fun hookViewSurface(mounted: MutableList<String>) {
        val viewClass = View::class.java
        val facadeAlpha = hookFloat(viewClass, "setMaterialAlpha") { v, scene ->
            if (liquidGlass) v else (v * overlayScaleOf(scene)).coerceIn(0f, 1f)
        }
        if (facadeAlpha) mounted += "View.setMaterialAlpha"
        // Runtime radius frames include the collapse animation: keep the fade to zero intact.
        val facadeRadius = hookFloat(viewClass, "setMaterialBlurRadius") { v, scene -> v * radiusScaleOf(scene) }
        if (facadeRadius) mounted += "View.setMaterialBlurRadius"
        val c10 = hookCustomized(viewClass, "setMaterialCustomized", 10)
        val c13 = hookCustomized(viewClass, "setMaterialCustomized", 13)
        val c14 = hookCustomized(viewClass, "setMaterialCustomized", 14)
        if (c10 || c13 || c14) mounted += "View.setMaterialCustomized"
        if (hookMaskFusion(viewClass, 9) or hookMaskFusion(viewClass, 12)) mounted += "View.setMaterialMaskFusion"
        var tags = 0
        for (name in arrayOf("setMaterial", "setMaterialForce")) for (n in 2..3) if (hookMaterialTagger(viewClass, name, n)) tags++
        if (tags > 0) mounted += "View.setMaterial*$tags"

        // Older ROMs without facades: hook the low-level setters instead (never both).
        val candidates = buildList {
            add(viewClass)
            runCatching { Class.forName("android.view.VivoViewImpl") }.getOrNull()?.let { add(it) }
        }
        if (!facadeAlpha) for (c in candidates) for (n in arrayOf("setBlurAlpha", "setWindowBlurAlpha")) {
            hookFloat(c, n) { v, scene -> if (liquidGlass) v else (v * overlayScaleOf(scene)).coerceIn(0f, 1f) }
        }
        if (!facadeRadius) for (c in candidates) for (n in arrayOf("setBlurRadius", "setWindowBlurRadius")) {
            hookFloat(c, n) { v, scene -> v * radiusScaleOf(scene) }
        }
        if (!c10 && !c13 && !c14) for (c in candidates) for (n in arrayOf("setBlurCustomized", "setWindowBlurCustomized")) {
            for (count in intArrayOf(10, 13, 14)) hookCustomized(c, n, count)
        }

        // Liquid Glass: replace the static screenshot blur with real-time backdrop blur.
        runCatching {
            val m = viewClass.getDeclaredMethod("setRecorderBlurredBackground", java.lang.Boolean.TYPE)
            XposedBridge.hookMethod(m, before { p ->
                if (enabled && liquidGlass && p.args[0] == true) p.args[0] = false
            })
            mounted += "View.setRecorderBlurredBackground"
        }
    }

    private fun hookFloat(cls: Class<*>, name: String, transform: (Float, Scene) -> Float): Boolean = runCatching {
        val method = cls.getDeclaredMethod(name, java.lang.Float.TYPE)
        XposedBridge.hookMethod(method, before { p ->
            if (!enabled) return@before
            if (isNotificationSurface(p.thisObject)) return@before
            val value = p.args[0] as? Float ?: return@before
            snapshot(p.thisObject as? View, method, p.args)
            val scene = classifyScene(p.thisObject)
            p.args[0] = if (liquidGlass && isSidebarGlassHost(p.thisObject as? View)) sidebarGlassRadius(scene)
            else transform(value, scene)
        })
        true
    }.getOrDefault(false)

    /**
     * setMaterialCustomized: 10 args = (radius, saturation, c1, o1, f1, c2, o2, f2, finalOpacity, finalFusion);
     * 13 inserts (c3, o3, f3) before finalOpacity (now [11]); 14 adds materialPositionType.
     */
    private fun hookCustomized(cls: Class<*>, name: String, argCount: Int): Boolean = runCatching {
        val f = java.lang.Float.TYPE
        val i = Integer.TYPE
        val base = arrayOf<Class<*>>(f, f, i, f, i, i, f, i, f, i)
        val params = when (argCount) {
            10 -> base
            13 -> base.copyOfRange(0, 8) + arrayOf<Class<*>>(i, f, i, f, i)
            else -> base.copyOfRange(0, 8) + arrayOf<Class<*>>(i, f, i, f, i, i)
        }
        val method = cls.getDeclaredMethod(name, *params)
        val finalIndex = if (argCount == 10) 8 else 11
        val opacityIndexes = if (argCount == 10) intArrayOf(3, 6) else intArrayOf(3, 6, 9)
        XposedBridge.hookMethod(method, before { p ->
            if (!enabled || isNativeQsGlass(p.thisObject)) return@before
            val notif = isNotificationSurface(p.thisObject)
            val scene = classifyScene(p.thisObject)
            val args = p.args
            snapshot(p.thisObject as? View, method, args)
            (args[0] as? Float)?.let {
                args[0] = if (liquidGlass && isSidebarGlassHost(p.thisObject as? View)) sidebarGlassRadius(scene)
                else glassRadius(it, scene)
            }
            (args[1] as? Float)?.let { args[1] = glassSaturation(it) }
            for (idx in opacityIndexes) (args[idx] as? Float)?.let { args[idx] = maskScale(it, scene, notif) }
            (args[finalIndex] as? Float)?.let {
                args[finalIndex] = if (notif || liquidGlass) it else (it * alphaScaleOf(scene)).coerceIn(0f, 1f)
            }
        })
        true
    }.getOrDefault(false)

    /** setMaterialMaskFusion: 9 args → opacities [2][5][7]; 12 args → [2][5][8][10]. */
    private fun hookMaskFusion(cls: Class<*>, argCount: Int): Boolean = runCatching {
        val f = java.lang.Float.TYPE
        val i = Integer.TYPE
        val base = arrayOf<Class<*>>(f, i, f, i, i, f, i)
        val params = if (argCount == 9) base + arrayOf<Class<*>>(f, i) else base + arrayOf<Class<*>>(i, f, i, f, i)
        val method = cls.getDeclaredMethod("setMaterialMaskFusion", *params)
        val opacityIndexes = if (argCount == 9) intArrayOf(2, 5, 7) else intArrayOf(2, 5, 8, 10)
        XposedBridge.hookMethod(method, before { p ->
            if (!enabled || isNativeQsGlass(p.thisObject)) return@before
            val scene = classifyScene(p.thisObject)
            snapshot(p.thisObject as? View, method, p.args)
            (p.args[0] as? Float)?.let { p.args[0] = glassSaturation(it) }
            for (idx in opacityIndexes) (p.args[idx] as? Float)?.let { p.args[idx] = glassMask(it, scene) }
        })
        true
    }.getOrDefault(false)

    /** Token materials resolve inside setMaterial*: remember the receiver's scene for that call. */
    private fun hookMaterialTagger(cls: Class<*>, name: String, argCount: Int): Boolean = runCatching {
        val method = cls.getDeclaredMethod(name, *Array(argCount) { Integer.TYPE })
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (enabled) threadScene.set(classifyScene(param.thisObject))
            }

            override fun afterHookedMethod(param: MethodHookParam) = threadScene.remove()
        })
        true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- BlurMaterial tokens

    private val geometryFields = HashMap<String, Field?>()

    private fun hookBlurMaterialTokens(cl: ClassLoader): Boolean = runCatching {
        val geometryClass = Class.forName("android.view.blur.IVivoBlurRender\$BlurGeometry", false, cl)
        val blurMaterial = Class.forName("android.view.blur.BlurMaterial", false, cl)
        val resolve = blurMaterial.getDeclaredMethod("resolveBlurGeometryByStyle", geometryClass, Integer.TYPE)
        XposedBridge.hookMethod(resolve, after { p ->
            if (!enabled) return@after
            val scene = threadScene.get() ?: Scene.OTHER
            val geometry = p.args.getOrNull(0) ?: return@after
            scaleFloatField(geometry, "mBlurRadius", radiusScaleOf(scene), clamp01 = false)
            if (liquidGlass) runCatching {
                val field = geometry.javaClass.getField("mBlurRadius")
                val min = 0.22f * radiusScaleOf(scene)
                if (field.getFloat(geometry) < 0.08f && min > 0f) field.setFloat(geometry, min)
            }
            for (n in arrayOf("mMaskOpacity_1", "mMaskOpacity_2", "mMaskOpacity_3")) {
                scaleFloatField(geometry, n, overlayScaleOf(scene), clamp01 = true)
                if (liquidGlass) floorMaskField(geometry, n)
            }
            if (!liquidGlass) scaleFloatField(geometry, "mFinalOpacity", alphaScaleOf(scene), clamp01 = true)
        })
        true
    }.getOrDefault(false)

    private fun geometryField(target: Any, name: String): Field? = synchronized(geometryFields) {
        if (geometryFields.containsKey(name)) geometryFields[name]
        else runCatching { target.javaClass.getField(name) }.getOrNull().also { geometryFields[name] = it }
    }

    private fun scaleFloatField(target: Any, name: String, scale: Float, clamp01: Boolean) {
        runCatching {
            val field = geometryField(target, name) ?: return
            val scaled = field.getFloat(target) * scale
            field.setFloat(target, if (clamp01) scaled.coerceIn(0f, 1f) else scaled)
        }
    }

    private fun floorMaskField(target: Any, name: String) {
        runCatching {
            val field = geometryField(target, name) ?: return
            val v = field.getFloat(target)
            if (v > 0f && v < 0.12f) field.setFloat(target, 0.12f)
        }
    }

    // ---------------------------------------------------------------- SystemUI drawable stack

    private class DrawableOriginal(val blurRadius: Int, val paintAlphas: Map<String, Int>)

    private val drawableOriginals = Collections.synchronizedMap(WeakHashMap<Any, DrawableOriginal>())
    @Volatile private var aggregator: Any? = null
    private val PAINT_FIELDS = arrayOf("mPaint", "mDisableBlurPaint", "mFallbackPaint")

    /** Window-level shade / lockscreen backdrop (VivoBackgroundBlurDrawable), written by fields on O6. */
    private fun hookBlurDrawableStack(cl: ClassLoader, mounted: MutableList<String>) {
        val controller = Class.forName("com.vivo.blur.BlurDrawableController", false, cl)
        val getDrawable = controller.getDeclaredMethod("getDrawable", android.content.Context::class.java)
        XposedBridge.hookMethod(getDrawable, after { p ->
            val result = p.result ?: return@after
            if (!enabled) return@after
            scaleDrawable(result)
            aggregator = runCatching { controller.getField("blurRegionAggregator").get(null) }.getOrNull()
        })
        mounted += "BlurDrawableController.getDrawable"
    }

    private fun reapplyDrawableStack() {
        val agg = aggregator ?: return
        runCatching {
            val drawables = agg.javaClass.getField("mDrawables").get(agg) as? Collection<*> ?: return
            for (d in drawables) if (d != null) scaleDrawable(d)
        }
    }

    private fun scaleDrawable(drawable: Any) {
        val cls = drawable.javaClass
        val originals = drawableOriginals.getOrPut(drawable) {
            DrawableOriginal(
                runCatching { cls.getField("mBlurRadius").getInt(drawable) }.getOrDefault(0),
                PAINT_FIELDS.associateWith { n ->
                    runCatching { (cls.getField(n).get(drawable) as? Paint)?.alpha ?: -1 }.getOrDefault(-1)
                }
            )
        }
        // Disabled: restore what the ROM wrote.
        val radiusScale = if (enabled) radiusScaleOf(Scene.SHADE) else 1f
        val overlay = if (enabled) overlayScaleOf(Scene.SHADE) else 1f
        runCatching { cls.getField("mBlurRadius").setInt(drawable, (originals.blurRadius * radiusScale).toInt().coerceAtLeast(0)) }
        for ((n, origAlpha) in originals.paintAlphas) {
            if (origAlpha < 0) continue
            runCatching { (cls.getField(n).get(drawable) as? Paint)?.alpha = (origAlpha * overlay).toInt().coerceIn(0, 255) }
        }
    }

    // ---------------------------------------------------------------- control center tiles (plugin)

    /** Disables the tiles' ambient-light "oil film" tint so only the real-time blur remains. */
    private fun hookAmbientLightVibrancy(cl: ClassLoader, mounted: MutableList<String>) {
        val cls = Class.forName("com.vivo.systemlighteffect.sdk.ViewEffect", false, cl)
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.name != "enableAmbientLightVibrancy") continue
            XposedBridge.hookMethod(m, before { p ->
                if (enabled && liquidGlass && p.args.firstOrNull() is Boolean) p.args[0] = false
            })
            n++
        }
        if (n > 0) mounted += "ViewEffect.enableAmbientLightVibrancy*$n"
    }

    /** addBlurBg bails out while a tile is detached; run it again on attach. */
    private fun hookAddBlurBgRetry(cl: ClassLoader, mounted: MutableList<String>) {
        val cls = Class.forName("com.vivo.systemuiplugin.systemui.qs.utils.VivoEffectHelper", false, cl)
        val method = cls.getDeclaredMethod("addBlurBg")
        val viewField = runCatching { cls.getDeclaredField("mView").apply { isAccessible = true } }.getOrNull()
        val paramsField = runCatching { cls.getDeclaredField("mMaterialParams").apply { isAccessible = true } }.getOrNull()
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled || !liquidGlass || paramsField == null) return
                val params = paramsField.get(param.thisObject) ?: return
                runCatching {
                    val field = params.javaClass.getField("blurRadius")
                    val v = field.getFloat(params)
                    val patched = glassRadius(v, Scene.SHADE)
                    if (patched != v) field.setFloat(params, patched)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (!enabled || !liquidGlass || viewField == null) return
                val helper = param.thisObject ?: return
                val view = viewField.get(helper) as? View ?: return
                if (view.isAttachedToWindow) return
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        v.removeOnAttachStateChangeListener(this)
                        runCatching { method.invoke(helper) }
                    }

                    override fun onViewDetachedFromWindow(v: View) = Unit
                })
            }
        })
        mounted += "VivoEffectHelper.addBlurBg"
    }

    /** Active tile colours are flagged "no blur" → solid. Force the material path. */
    private fun hookColorSupportBlur(cl: ClassLoader, mounted: MutableList<String>) {
        val cls = Class.forName("com.vivo.systemuiplugin.systemui.qs.utils.QsUtils", false, cl)
        val m = cls.getDeclaredMethod("colorSupportBlur", android.content.Context::class.java, Integer.TYPE)
        XposedBridge.hookMethod(m, before { p -> if (enabled && liquidGlass) p.result = true })
        mounted += "QsUtils.colorSupportBlur"
    }

    private fun hookSupportBlur(cl: ClassLoader, mounted: MutableList<String>) {
        val cls = Class.forName("com.vivo.systemuiplugin.systemui.qs.utils.VivoEffectHelper", false, cl)
        for (name in arrayOf("supportBlur", "supportBlurIgnoreFlag")) {
            val m = runCatching { cls.getDeclaredMethod(name) }.getOrNull() ?: continue
            XposedBridge.hookMethod(m, before { p -> if (enabled && liquidGlass) p.result = true })
            mounted += "VivoEffectHelper.$name"
        }
        runCatching {
            val clear = cls.getDeclaredMethod("clearBlurBg")
            XposedBridge.hookMethod(clear, before { p -> if (enabled && liquidGlass) p.result = null })
            mounted += "VivoEffectHelper.clearBlurBg"
        }
    }

    /** Factory-made QS material params ship blurRadius = 0 for active tiles; give them a radius. */
    private fun hookQsMaterialParams(cl: ClassLoader, mounted: MutableList<String>) {
        var n = 0
        for (className in arrayOf(
            "com.vivo.systemuiplugin.systemui.qs.ui.VivoThreeLayerMaterialParamsKt",
            "com.vivo.systemuiplugin.systemui.qs.utils.QsUtils"
        )) {
            val cls = runCatching { Class.forName(className, false, cl) }.getOrNull() ?: continue
            for (m in cls.declaredMethods) {
                if (!m.name.startsWith("getQs") || !m.name.contains("MaterialParam") || m.parameterCount != 0) continue
                XposedBridge.hookMethod(m, after { p ->
                    val result = p.result ?: return@after
                    if (!enabled || !liquidGlass) return@after
                    runCatching {
                        val field = result.javaClass.getField("blurRadius")
                        field.setFloat(result, glassRadius(field.getFloat(result), Scene.SHADE))
                    }
                })
                n++
            }
        }
        if (n > 0) mounted += "QsMaterialParam*$n"
    }

    // ---------------------------------------------------------------- sidebar glass

    /** Sidebar and its expanded cards use solid ColorDrawables; swap them for real-time glass. */
    private fun hookSidebarGlass(mounted: MutableList<String>) {
        val viewClass = View::class.java
        val setBg = viewClass.getDeclaredMethod("setBackground", Drawable::class.java)
        XposedBridge.hookMethod(setBg, after { p ->
            val view = p.thisObject as? View ?: return@after
            if (!enabled || !liquidGlass) return@after
            if (!isSidebarSolidCard(view, p.args[0] as? Drawable)) return@after
            clearSolidFill(view)
            applyGlassMaterial(view)
        })
        val setColor = viewClass.getDeclaredMethod("setBackgroundColor", Integer.TYPE)
        XposedBridge.hookMethod(setColor, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!enabled || !liquidGlass || !isSidebarGlassHost(view)) return
                param.args[0] = 0
                applyGlassMaterial(view)
            }
        })
        val addView = ViewGroup::class.java.getDeclaredMethod(
            "addView", View::class.java, Integer.TYPE, ViewGroup.LayoutParams::class.java
        )
        XposedBridge.hookMethod(addView, after { p ->
            if (!enabled || !liquidGlass) return@after
            val parent = p.thisObject as? View ?: return@after
            if (!isSidebarGlassHost(parent)) return@after
            applyGlassMaterial(parent)
            (p.args[0] as? View)?.let { child ->
                if (isSidebarSolidCard(child, child.background)) {
                    clearSolidFill(child)
                    applyGlassMaterial(child)
                }
            }
        })
        mounted += "Sidebar glass"
    }

    private fun isSidebarGlassHost(view: View?): Boolean {
        if (view == null) return false
        var v: View? = view
        var depth = 0
        while (v != null && depth < 12) {
            if (isSidebarHostName(v.javaClass.simpleName)) return true
            if (cardProcess && isVivoCardSurface(v)) return true
            if (upslideProcess && isLikelyUpslideSurface(v)) return true
            v = v.parent as? View
            depth++
        }
        return false
    }

    private fun isVivoCardSurface(view: View): Boolean {
        val id = view.id
        if (id == 0 || id == View.NO_ID) return false
        val entry = runCatching { view.resources.getResourceEntryName(id) }.getOrNull() ?: return false
        return entry in setOf(
            "allbox_container", "sidedock_allbox", "sidedock_allbox_bg", "scroll_container",
            "hotseat_container", "hotseat_app_area_container", "hotseat_app_area"
        )
    }

    private fun isLikelyUpslideSurface(view: View): Boolean {
        val name = view.javaClass.simpleName.lowercase(Locale.ROOT)
        if (name.contains("gesture") || name.contains("icon") || name.contains("image") ||
            name.contains("text") || name.contains("button")
        ) return false
        val lp = view.layoutParams
        val width = if (view.width > 0) view.width else maxOf(lp?.width ?: 0, 0)
        val height = if (view.height > 0) view.height else maxOf(lp?.height ?: 0, 0)
        return view is ViewGroup && (width >= 220 || height >= 220)
    }

    private fun isSidebarHostName(simple: String): Boolean {
        val n = simple.lowercase(Locale.ROOT)
        return n.contains("sidebar") || n.contains("sidepanel") || n.contains("smallwindow") ||
            n.contains("smallapp") || n.contains("quicktool") || n.contains("floatpanel") ||
            n.contains("floatingpanel") || n.contains("miniapp")
    }

    private fun isSidebarSolidCard(view: View, incoming: Drawable?): Boolean {
        if (!isSidebarGlassHost(view)) return false
        val simple = view.javaClass.simpleName
        if (simple == "VivoSuperQSTileBg" || simple.startsWith("VivoEffect")) return false
        return incoming is ColorDrawable || view.background is ColorDrawable
    }

    private fun clearSolidFill(view: View) {
        val bg = view.background as? ColorDrawable ?: return
        bg.alpha = 0
        runCatching { bg.color = 0x00FFFFFF }
    }

    private fun applyGlassMaterial(view: View) {
        val apply = Runnable {
            val radius = sidebarGlassRadius(Scene.SHADE)
            val mask = overlayScaleOf(Scene.SHADE).coerceIn(0.12f, 0.45f)
            invokeCustomized13(view, radius, mask) || invokeCustomized10(view, radius, mask)
        }
        if (view.isAttachedToWindow) {
            runCatching { apply.run() }
            return
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                runCatching { apply.run() }
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    private fun invokeCustomized13(view: View, radius: Float, mask: Float): Boolean {
        val f = java.lang.Float.TYPE
        val i = Integer.TYPE
        val args = arrayOf<Any?>(
            radius, 0f, Color.WHITE, mask, 0, Color.WHITE, (mask * 0.5f).coerceIn(0f, 1f), 0,
            Color.TRANSPARENT, 0f, 0, 1f, 0,
        )
        if (runCatching {
                View::class.java.getDeclaredMethod("setMaterialCustomized", f, f, i, f, i, i, f, i, i, f, i, f, i)
                    .invoke(view, *args)
            }.isSuccess
        ) return true
        return runCatching {
            View::class.java.getDeclaredMethod("setMaterialCustomized", f, f, i, f, i, i, f, i, i, f, i, f, i, i)
                .invoke(view, *args, 0)
        }.isSuccess
    }

    private fun invokeCustomized10(view: View, radius: Float, mask: Float): Boolean {
        val f = java.lang.Float.TYPE
        val i = Integer.TYPE
        return runCatching {
            View::class.java.getDeclaredMethod("setMaterialCustomized", f, f, i, f, i, i, f, i, f, i).invoke(
                view, radius, 0f, Color.WHITE, mask, 0, Color.WHITE, (mask * 0.5f).coerceIn(0f, 1f), 0, 1f, 0
            )
        }.isSuccess
    }

    // ---------------------------------------------------------------- Origin Island capsule

    /**
     * The collapsed island capsule clears its material and paints solid black. When the ROM's
     * real-time blur is available (and not in power saving), promote it to the same material as
     * the expanded island and drop the black fill.
     */
    private object IslandCapsule {
        @Volatile private var blurActive = false
        private val installedLoaders = Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
        private var configGetInstance: Method? = null
        private var configSInstance: Field? = null
        private var configBlurSwitch: Method? = null
        private var configLowPower: Method? = null
        private var containerField: Field? = null
        private var blurVisibleField: Field? = null
        private var updateMethod: Method? = null
        private var animatorRef = java.lang.ref.WeakReference<Any>(null)
        private var containerRef = java.lang.ref.WeakReference<View>(null)

        fun install(cl: ClassLoader) {
            synchronized(installedLoaders) { if (!installedLoaders.add(cl)) return }
            val anim = Class.forName("com.vivo.island.animation.ContainerAnimatorCommon", false, cl)
            val configs = Class.forName("com.vivo.island.animation.IslandAnimConfigsCommon", false, cl)
            val flagsOk = runCatching {
                configs.getField("SUPPORT_REAL_TIME_BLUR").getBoolean(null) &&
                    configs.getField("DEBUG_ENABLE_ISLAND_BLUR").getBoolean(null)
            }.getOrDefault(false)
            if (!flagsOk) return
            val configUtils = Class.forName("com.vivo.island.animation.IslandAnimationConfigUtils", false, cl)
            configGetInstance = configUtils.getDeclaredMethod("getInstance", android.content.Context::class.java)
            configSInstance = configUtils.getDeclaredField("sInstance").apply { isAccessible = true }
            configBlurSwitch = configUtils.getDeclaredMethod("isRealtimeBlurSwitchEnable")
            configLowPower = configUtils.getDeclaredMethod("isLowPowerModeOn")
            containerField = findField(anim, "mContainer")
            blurVisibleField = runCatching { findField(anim, "mBlurVisible") }.getOrNull()

            val update = findMethod(anim, "updateBlurAppearance", java.lang.Boolean.TYPE)
            updateMethod = update
            XposedBridge.hookMethod(update, before { p ->
                val animator = p.thisObject
                if (animator != null) {
                    animatorRef = java.lang.ref.WeakReference(animator)
                    (runCatching { containerField?.get(animator) }.getOrNull() as? View)
                        ?.let { containerRef = java.lang.ref.WeakReference(it) }
                }
                val z = p.args[0] as? Boolean ?: return@before
                if (!enabled || !islandEnabled) {
                    blurActive = false
                    return@before
                }
                val effective = z || systemBlurAvailable()
                blurActive = effective
                if (effective != z) p.args[0] = effective
            })
            XposedBridge.hookMethod(findMethod(anim, "getPaintColor"), after { p ->
                if (enabled && islandEnabled && blurActive) p.result = 0
            })
            log("island capsule glass installed")
        }

        /** Replays the island appearance so radius / opacity / toggle changes apply instantly. */
        fun onConfigChanged(key: String?) {
            if (key == null) return
            val relevant = key == KEY_ENABLE || key == KEY_ISLAND || key == KEY_LIQUID_GLASS ||
                key == KEY_GLASS_OPACITY || key == radiusKey(Scene.ISLAND) || key == alphaKey(Scene.ISLAND)
            if (!relevant) return
            val container = containerRef.get() ?: return
            val animator = animatorRef.get() ?: return
            val update = updateMethod ?: return
            container.post {
                runCatching {
                    blurVisibleField?.setBoolean(animator, false)
                    update.invoke(animator, false)
                }
            }
        }

        private fun systemBlurAvailable(): Boolean = runCatching {
            val inst = configSInstance?.get(null) ?: configGetInstance?.invoke(null, appContext())
            inst != null && configBlurSwitch?.invoke(inst) == true && configLowPower?.invoke(inst) == false
        }.getOrDefault(false)

        private fun appContext(): android.content.Context? = runCatching {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? android.content.Context
        }.getOrNull()

        private fun findField(cls: Class<*>, name: String): Field {
            var c: Class<*>? = cls
            while (c != null && c != Any::class.java) {
                runCatching { return c.getDeclaredField(name).apply { isAccessible = true } }
                c = c.superclass
            }
            throw NoSuchFieldException(name)
        }

        private fun findMethod(cls: Class<*>, name: String, vararg params: Class<*>): Method {
            var c: Class<*>? = cls
            while (c != null && c != Any::class.java) {
                runCatching { return c.getDeclaredMethod(name, *params).apply { isAccessible = true } }
                c = c.superclass
            }
            throw NoSuchMethodException(name)
        }
    }
}
