package dev.unvoid.originceiler

import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.RectF
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Replaces the backgrounds of launcher folders and the dock (full refractive glass over the
 * wallpaper) and of SystemUI notifications and control-center tiles (glass over the native
 * real-time blur) with [KyantGlass].
 */
object KyantGlassHook {
    private const val FOLDER_BG = "com.bbk.launcher2.ui.originfolder.OriginFolderIconBlurView"
    private const val DOCK_BG = "com.bbk.launcher2.ui.hotseat.NinePatchInsideBlurView"
    private const val NOTIFICATION_BG = "com.android.systemui.statusbar.notification.row.NotificationBackgroundView"
    private const val EFFECT_HELPER = "com.vivo.systemuiplugin.systemui.qs.utils.VivoEffectHelper"
    private const val VOLUME_BG = "com.vivo.systemuiplugin.systemui.volume.VivoVolumeSeekbarBackground"
    private const val VOLUME_SLIDER = "com.vivo.systemuiplugin.systemui.volume.VivoVolumeSeekbar"

    private val launcherInstalled = AtomicBoolean(false)
    private val systemUiInstalled = AtomicBoolean(false)
    private val tileLoaders = Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())

    // ---------------------------------------------------------------- launcher

    private val foregrounds = Collections.synchronizedMap(WeakHashMap<View, KyantGlass.GlassDrawable>())

    fun installLauncher() {
        if (!launcherInstalled.compareAndSet(false, true)) return
        runCatching { KyantGlassTouch.install() }.onFailure { XposedBridge.log("OriginKyantGlass: touch: $it") }
        XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                val key = when (view.javaClass.name) {
                    FOLDER_BG -> KyantGlass.KEY_FOLDERS
                    DOCK_BG -> KyantGlass.KEY_DOCK
                    else -> return
                }
                KyantGlass.ensureConfig(view.context)
                if (listenerAdded.compareAndSet(false, true)) KyantGlass.onConfigChanged { refreshLauncherForegrounds() }
                if (key == KyantGlass.KEY_DOCK) hookDockDrawing(view.javaClass)
                attachForeground(view, key)
            }
        })
        // The launcher re-sets the plate's foreground on some state changes (drag, folder open /
        // close, theme refresh), which dropped the glass until the folder re-attached.
        XposedBridge.hookAllMethods(View::class.java, "setForeground", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                val glass = foregrounds[view] ?: return
                val key = if (view.javaClass.name == DOCK_BG) KyantGlass.KEY_DOCK else KyantGlass.KEY_FOLDERS
                if (param.args[0] !== glass && KyantGlass.config.on(key)) param.args[0] = glass
            }
        })
        runCatching { hookWidgets() }.onFailure { XposedBridge.log("OriginKyantGlass: widgets: $it") }
        XposedBridge.log("OriginKyantGlass: launcher hooks installed")
    }

    // ---------------------------------------------------------------- home screen widgets

    /** Widget plate (or its full-size background image) → its glass and what it replaced. */
    private val widgetGlass = Collections.synchronizedMap(WeakHashMap<View, KyantGlass.GlassDrawable>())
    private val widgetOriginal = Collections.synchronizedMap(WeakHashMap<View, android.graphics.drawable.Drawable?>())
    private val widgetImages = Collections.synchronizedMap(WeakHashMap<View, android.graphics.drawable.Drawable?>())
    private val SYSTEM_WIDGET_PACKAGES = arrayOf("com.vivo.", "com.bbk.", "com.iqoo.", "com.android.", "android")

    /**
     * System widgets (vivo / bbk / Android packages) draw their card plate inside the widget's
     * own layout: a full-size view with a background, or a full-size "bg" image. That plate
     * becomes the same wallpaper glass as the folders, under the folders switch. Re-applied after
     * every RemoteViews update, which re-sets the plate.
     */
    private fun hookWidgets() {
        val apply = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val host = param.thisObject as? android.appwidget.AppWidgetHostView ?: return
                KyantGlass.ensureConfig(host.context)
                if (listenerAdded.compareAndSet(false, true)) KyantGlass.onConfigChanged { refreshLauncherForegrounds() }
                if (!isSystemWidget(host)) return
                host.post { styleWidget(host) }
            }
        }
        XposedBridge.hookAllMethods(android.appwidget.AppWidgetHostView::class.java, "updateAppWidget", apply)
        XposedBridge.hookAllMethods(android.appwidget.AppWidgetHostView::class.java, "onAttachedToWindow", apply)
        // vivo's own widgets (clock / weather) switch to a real-time blur material while the
        // home screen scrolls, drawn over the plate: drop it on widgets that wear glass.
        val material = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!KyantGlass.config.on(KyantGlass.KEY_FOLDERS) || !insideGlassWidget(view)) return
                param.result = when ((param.method as? java.lang.reflect.Method)?.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        }
        View::class.java.declaredMethods
            .filter { it.name.startsWith("setMaterial") && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .forEach { runCatching { XposedBridge.hookMethod(it, material) } }
        // RemoteViews re-set the plate's background later (theme / night mode): keep the glass.
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                val glass = widgetGlass[view] ?: return
                if (param.args[0] === glass || view in widgetImages || !KyantGlass.config.on(KyantGlass.KEY_FOLDERS)) return
                widgetOriginal[view] = param.args[0] as? android.graphics.drawable.Drawable
                param.args[0] = glass
            }
        })
    }

    /** Widget hosts whose plate wears glass. */
    private val glassWidgetHosts = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    private fun insideGlassWidget(view: View): Boolean {
        var v: View? = view
        var depth = 0
        while (v != null && depth < 8) {
            if (v is android.appwidget.AppWidgetHostView) return glassWidgetHosts.containsKey(v)
            v = v.parent as? View
            depth++
        }
        return false
    }

    /** The plate and the views between it and the host: none keeps a vivo material. */
    private fun clearWidgetMaterials(plate: View, host: View) {
        var v: View? = plate
        var depth = 0
        while (v != null && v !== host && depth < 8) {
            clearNativeMaterial(v)
            v = v.parent as? View
            depth++
        }
    }

    private fun isSystemWidget(host: android.appwidget.AppWidgetHostView): Boolean {
        val pkg = runCatching { host.appWidgetInfo?.provider?.packageName }.getOrNull() ?: return false
        return SYSTEM_WIDGET_PACKAGES.any { pkg == it || pkg.startsWith(it) }
    }

    private fun styleWidget(host: android.appwidget.AppWidgetHostView) {
        val root = host.getChildAt(0) ?: return
        if (root.width == 0 || root.height == 0) return
        val plate = findWidgetPlate(root, root.width * root.height, 0) ?: return
        val glass = widgetGlass.getOrPut(plate) {
            KyantGlass.GlassDrawable(
                plate, KyantGlass.KEY_FOLDERS,
                backdrop = { v -> runCatching { ScreenBackdropSource.getBackdrop(v.context) }.getOrNull() ?: ShadeBackdrop.get(v, requireFocus = true) },
                elastic = { host },
                radius = { v, w, h -> widgetRadius(v, host, w, h) }
            )
        }
        KyantGlass.track(plate)
        val on = KyantGlass.config.on(KyantGlass.KEY_FOLDERS)
        glassWidgetHosts[host] = true
        if (on) clearWidgetMaterials(plate, host)
        applyWidgetGlass(plate, glass, on)
    }

    /** The plate: the root or a view covering ~all of it (depth ≤ 3) that paints a background. */
    private fun findWidgetPlate(view: View, rootArea: Int, depth: Int): View? {
        if (view.visibility != View.VISIBLE || view.width * view.height < rootArea * 0.85f) return null
        if (view.background != null || view in widgetGlass) return view
        if (view is android.widget.ImageView && view.drawable != null && isBackgroundId(view)) return view
        if (depth >= 3 || view !is android.view.ViewGroup) return null
        for (i in 0 until view.childCount) findWidgetPlate(view.getChildAt(i), rootArea, depth + 1)?.let { return it }
        return null
    }

    private fun isBackgroundId(view: View): Boolean {
        val name = runCatching { if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id) }.getOrNull() ?: return false
        return name.contains("bg") || name.contains("background")
    }

    private fun applyWidgetGlass(plate: View, glass: KyantGlass.GlassDrawable, on: Boolean) {
        val image = plate as? android.widget.ImageView
        val isImage = image != null && (plate in widgetImages || (plate.background == null && image.drawable != null))
        if (on) {
            if (isImage) {
                // A background picture: hide the picture, glass goes behind it.
                if (plate !in widgetImages) widgetImages[plate] = image!!.drawable
                image!!.setImageDrawable(null)
            } else if (plate.background !== glass && !widgetOriginal.containsKey(plate)) {
                widgetOriginal[plate] = plate.background
            }
            if (plate.background !== glass) plate.background = glass
        } else {
            if (plate in widgetImages) {
                image?.setImageDrawable(widgetImages.remove(plate))
                if (plate.background === glass) plate.background = null
            } else if (plate.background === glass) {
                plate.background = widgetOriginal.remove(plate)
            }
        }
        plate.invalidate()
    }

    /** The widget's own clip outline, else Android's widget corner, else ~22 dp. */
    private fun widgetRadius(plate: View, host: View, w: Float, h: Float): Float {
        val half = minOf(w, h) / 2f
        outlineRadius(plate)?.let { return it.coerceAtMost(half) }
        (plate.parent as? View)?.let { outlineRadius(it) }?.let { return it.coerceAtMost(half) }
        outlineRadius(host)?.let { return it.coerceAtMost(half) }
        val res = plate.resources
        val id = res.getIdentifier("system_app_widget_background_radius", "dimen", "android")
        val system = if (id != 0) runCatching { res.getDimension(id) }.getOrNull() else null
        return (system?.takeIf { it > 0f } ?: 22f * res.displayMetrics.density).coerceAtMost(half)
    }

    private val listenerAdded = AtomicBoolean(false)
    private val dockDrawingHooked = AtomicBoolean(false)
    private val dockBackgrounds = Collections.synchronizedMap(WeakHashMap<View, android.graphics.drawable.Drawable?>())

    /**
     * With glass on, vivo's own dock plate is not drawn at all: the glass may be resized past it
     * (dock width / height settings) and must not show the native plate around it.
     */
    private fun hookDockDrawing(cls: Class<*>) {
        if (!dockDrawingHooked.compareAndSet(false, true)) return
        val skip = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // Framework methods are shared by every view: act only on the dock plate itself.
                val dock = param.thisObject as? View ?: return
                if (dock.javaClass.name == DOCK_BG && KyantGlass.config.on(KyantGlass.KEY_DOCK)) {
                    hideNativeDockBlur(dock)
                    param.result = null
                }
            }
        }
        // Only the launcher's own classes: hooking View / ViewGroup here would stop the whole
        // launcher from drawing.
        var c: Class<*>? = cls
        while (c != null && c.name.startsWith("com.bbk.")) {
            runCatching { c.getDeclaredMethod("onDraw", Canvas::class.java) }.getOrNull()?.let { XposedBridge.hookMethod(it, skip) }
            c = c.superclass
        }
    }

    private fun attachForeground(view: View, key: String) {
        val glass = foregrounds.getOrPut(view) {
            KyantGlass.GlassDrawable(
                view, key,
                // Static wallpaper: read it directly. Live wallpaper (nothing readable): capture the
                // display without the launcher window, which leaves the wallpaper's current frame.
                backdrop = { v ->
                    runCatching { ScreenBackdropSource.getBackdrop(v.context) }.getOrNull() ?: ShadeBackdrop.get(v, requireFocus = true)
                },
                shape = if (key == KyantGlass.KEY_DOCK) ::dockShape else ::folderShape,
                // The folder icon (plate + its app previews); the dock plate stretches on its own.
                elastic = if (key == KyantGlass.KEY_DOCK) ({ v -> v }) else KyantGlassTouch::clickableTarget,
                radius = { v, w, h -> launcherRadius(v, w, h) }
            )
        }
        KyantGlass.track(view)
        applyLauncherGlass(view, glass, KyantGlass.config.on(key), key == KyantGlass.KEY_DOCK)
    }

    private fun applyLauncherGlass(view: View, glass: KyantGlass.GlassDrawable, on: Boolean, dock: Boolean) {
        if (on) {
            if (view.foreground !== glass) view.foreground = glass
            if (dock) {
                if (!dockBackgrounds.containsKey(view)) dockBackgrounds[view] = view.background
                view.background = null
                clearNativeMaterial(view)
                hideNativeDockBlur(view)
                // A resized dock may reach past the plate view: let it draw there.
                view.clipToOutline = false
                view.clipBounds = null
                var parent = view.parent as? android.view.ViewGroup
                repeat(5) {
                    parent?.clipChildren = false
                    parent?.clipToPadding = false
                    parent = parent?.parent as? android.view.ViewGroup
                }
            }
        } else {
            if (view.foreground === glass) view.foreground = null
            if (dock && dockBackgrounds.containsKey(view)) view.background = dockBackgrounds.remove(view)
            if (dock) restoreNativeDockBlur(view)
        }
        view.invalidate()
    }

    private val hiddenDockChildren = Collections.synchronizedMap(WeakHashMap<View, Int>())

    /**
     * The dock plate is a NewBlurView (FrameLayout) whose native blur is a child
     * (VivoDynamicBlurView): hide the children, or its small square corners show around the glass.
     */
    private fun hideNativeDockBlur(dock: View) {
        val group = dock as? android.view.ViewGroup ?: return
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility == View.INVISIBLE && hiddenDockChildren.containsKey(child)) continue
            if (!hiddenDockChildren.containsKey(child)) hiddenDockChildren[child] = child.visibility
            child.visibility = View.INVISIBLE
        }
    }

    private fun restoreNativeDockBlur(dock: View) {
        val group = dock as? android.view.ViewGroup ?: return
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            hiddenDockChildren.remove(child)?.let { child.visibility = it }
        }
    }

    private fun refreshLauncherForegrounds() {
        val widgetsOn = KyantGlass.config.on(KyantGlass.KEY_FOLDERS)
        synchronized(widgetGlass) { widgetGlass.entries.toList() }.forEach { (plate, glass) -> applyWidgetGlass(plate, glass, widgetsOn) }
        synchronized(foregrounds) { foregrounds.entries.toList() }.forEach { (view, glass) ->
            val dock = view.javaClass.name == DOCK_BG
            applyLauncherGlass(view, glass, KyantGlass.config.on(if (dock) KyantGlass.KEY_DOCK else KyantGlass.KEY_FOLDERS), dock)
        }
    }

    private val shapeOutline = Outline()
    private val shapeRect = android.graphics.Rect()

    /**
     * Folder plates are a rounded rect inside the view (OriginFolderIconBlurView.setRoundRect),
     * described by its outline: use exactly that rect and radius, not the whole view.
     */
    private fun folderShape(view: View, rect: RectF): Float? {
        val provider = view.outlineProvider
        shapeOutline.setEmpty()
        if (provider != null) runCatching { provider.getOutline(view, shapeOutline) }
        if (shapeOutline.getRect(shapeRect) && !shapeRect.isEmpty) {
            rect.set(shapeRect)
        } else {
            // Smooth-corner (path) outline: the plate rect the folder keeps itself.
            val plate = runCatching { findField(view.javaClass, "d")?.get(view) as? android.graphics.Rect }.getOrNull()
            if (plate != null && !plate.isEmpty) rect.set(plate)
        }
        val short = minOf(rect.width(), rect.height())
        val radius = vivoFolderRadius(view) ?: shapeOutline.radius.takeIf { it > 0f } ?: short * FOLDER_RADIUS_FRACTION
        return radius.coerceAtMost(short / 2f)
    }

    private const val FOLDER_RADIUS_FRACTION = 0.22f
    private var folderRadiusCall: ((View) -> Float)? = null
    @Volatile private var folderRadiusBroken = false

    /**
     * The radius vivo itself gives the folder outline (OriginFolderIconBlurView$a.getOutline):
     * x8.r.P(g, x8.d.D().T(), t, u, v), resolved once by reflection. Null if this launcher build
     * is laid out differently.
     */
    private fun vivoFolderRadius(view: View): Float? {
        if (folderRadiusBroken) return null
        val call = folderRadiusCall ?: runCatching {
            val cls = view.javaClass
            val loader = cls.classLoader
            fun accessor(name: String) = cls.getDeclaredMethod(name, cls).apply { isAccessible = true }
            val g = accessor("r")
            val t = accessor("t")
            val u = accessor("u")
            val v = accessor("v")
            val params = Class.forName("x8.d", false, loader).getDeclaredMethod("D").apply { isAccessible = true }
            val switch = Class.forName("f7.b", false, loader).getMethod("T")
            val i = Integer.TYPE
            val p = Class.forName("x8.r", false, loader).getDeclaredMethod("P", i, i, i, i, i).apply { isAccessible = true }
            val fn: (View) -> Float = { target ->
                val gv = (g.invoke(null, target) as Int).coerceAtLeast(1)
                p.invoke(null, gv, switch.invoke(params.invoke(null)) as Int,
                    t.invoke(null, target) as Int, u.invoke(null, target) as Int, v.invoke(null, target) as Int) as Float
            }
            fn
        }.onFailure { folderRadiusBroken = true }.getOrNull()?.also { folderRadiusCall = it } ?: return null
        return runCatching { call(view).takeIf { it > 0f } }.onFailure { folderRadiusBroken = true }.getOrNull()
    }

    /** The dock plate, resized around its centre by the dock width / height settings. */
    private fun dockShape(view: View, rect: RectF): Float? {
        val cfg = KyantGlass.config
        val sx = cfg[KyantGlass.KEY_DOCK_WIDTH].coerceIn(50, 150) / 100f
        val sy = cfg[KyantGlass.KEY_DOCK_HEIGHT].coerceIn(50, 200) / 100f
        val cx = rect.centerX()
        val cy = rect.centerY()
        val hw = rect.width() * sx / 2f
        val hh = rect.height() * sy / 2f
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        val custom = cfg[KyantGlass.KEY_DOCK_RADIUS]
        if (custom > 0) return (custom * view.resources.displayMetrics.density).coerceAtMost(minOf(rect.width(), rect.height()) / 2f)
        return launcherRadius(view, rect.width(), rect.height())
    }

    /** Dock exposes getRadius(); folder plates use the outline or vivo's ~26% corner. */
    private fun launcherRadius(view: View, w: Float, h: Float): Float {
        if (view.javaClass.name == DOCK_BG) {
            runCatching { (view.javaClass.getMethod("getRadius").invoke(view) as Int).toFloat() }
                .getOrNull()?.takeIf { it > 0f }?.let { return it.coerceAtMost(minOf(w, h) / 2f) }
        }
        outlineRadius(view)?.let { return it }
        return minOf(w, h) * 0.26f
    }

    // ---------------------------------------------------------------- SystemUI

    private val notificationRenderers = Collections.synchronizedMap(WeakHashMap<View, KyantGlass.Renderer>())

    fun installSystemUi(classLoader: ClassLoader) {
        if (!systemUiInstalled.compareAndSet(false, true)) return
        runCatching { KyantGlassTouch.install() }.onFailure { XposedBridge.log("OriginKyantGlass: touch: $it") }
        runCatching { hookVivoEffectForeground() }.onFailure { XposedBridge.log("OriginKyantGlass: effect fg: $it") }
        // OriginPlayer / shade buttons can show up before the control center is ever opened.
        runCatching { hookDirectMaterials() }.onFailure { XposedBridge.log("OriginKyantGlass: materials: $it") }
        runCatching { hookPinKeys(classLoader) }.onFailure { XposedBridge.log("OriginKyantGlass: pin keys: $it") }
        runCatching { hookHeadsUpPop(classLoader) }.onFailure { XposedBridge.log("OriginKyantGlass: heads-up pop: $it") }
        runCatching { KyantGlassNotificationMenu.install(classLoader) }.onFailure { XposedBridge.log("OriginKyantGlass: notification menu: $it") }
        runCatching { hookNotifications(classLoader) }.onFailure { XposedBridge.log("OriginKyantGlass: notifications: $it") }
        runCatching { Class.forName(EFFECT_HELPER, false, classLoader) }.getOrNull()?.let { hookEffectHelper(it) }
        runCatching { hookWindowBlur() }.onFailure { XposedBridge.log("OriginKyantGlass: window blur: $it") }
        PANEL_BLUR_CLASSES.forEach { name -> runCatching { Class.forName(name, false, classLoader) }.getOrNull()?.let { hookPanelBlur(it) } }
        runCatching { Class.forName(VOLUME_BG, false, classLoader) }.getOrNull()?.let { hookVolume(it) }
        runCatching { Class.forName(RINGER, false, classLoader) }.getOrNull()?.let { hookRinger(it) }
        runCatching { Class.forName(CLOCK_TEXT, false, classLoader) }.getOrNull()?.let { hookClock(it) }
        CLOCK_APPLIERS.forEach { name -> runCatching { Class.forName(name, false, classLoader) }.getOrNull()?.let { hookClockApplier(it) } }
        // Control-center cards and tiles come from the SystemUI plugin's own class loader.
        XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val cls = param.result as? Class<*> ?: return
                if (cls.name == EFFECT_HELPER) hookEffectHelper(cls)
                if (cls.name in PANEL_BLUR_CLASSES) hookPanelBlur(cls)
                if (cls.name == VOLUME_BG) hookVolume(cls)
                if (cls.name == RINGER) hookRinger(cls)
                if (cls.name == CLOCK_TEXT) hookClock(cls)
                if (cls.name in CLOCK_APPLIERS) hookClockApplier(cls)
            }
        })
    }

    /** vivo re-sets its EffectDrawable on every tile refresh: glass surfaces never take it. */
    private fun hookVivoEffectForeground() {
        XposedBridge.hookAllMethods(View::class.java, "setForeground", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                val fg = param.args.firstOrNull() ?: return
                if ((view in playerPlates || view in playerForegrounds) && glassOnForPlayer()) {
                    param.args[0] = null
                    return
                }
                if (fg.javaClass.simpleName != "EffectDrawable") return
                if (view.background is KyantGlass.GlassDrawable || view in notificationRenderers) param.args[0] = null
            }
        })
    }

    // ---------------------------------------------------------------- elastic targets

    /**
     * Which view stretches when a glass host is pressed: the whole volume slider (track and
     * level together), nothing for control-center sliders (vivo's SpringBackLayout already
     * stretches them) and expanded-tile windows, else the host itself.
     */
    private fun elasticTarget(host: View): View? {
        if (detailWindowOf(host) != null || idName(host) == "qs_detail_view") return null
        var v: View? = host
        var depth = 0
        while (v != null && depth < 5) {
            val name = v.javaClass.simpleName
            if (name == "VivoVolumeSeekbar") return v
            if (name.startsWith("VivoQs") && (name.contains("SeekBar") || name.contains("Slider"))) return null
            if (depth > 0 && (name.contains("SeekBar") || name.contains("Seekbar") || name.contains("Slider"))) return v
            v = v.parent as? View
            depth++
        }
        val own = host.javaClass.simpleName
        return if (own.contains("SeekBar") || own.contains("Seekbar")) null else host
    }

    // ---------------------------------------------------------------- lockscreen PIN pad

    private const val PIN_KEY = "com.android.keyguard.vivo.bouncer.VivoNumPadKey"
    private const val BOUNCER_BLUR = "com.vivo.keyguard.bouncer.BouncerBlurView"
    private const val PIN_KEY_BG = "com.android.keyguard.vivo.bouncer.VivoNumKeyBg"
    private const val PIN_KEY_AMBIENT = "com.android.keyguard.vivo.bouncer.VivoNumKeyAmbientBg"

    private val pinGlass = Collections.synchronizedMap(WeakHashMap<View, KyantGlass.GlassDrawable>())
    private val pinBackgrounds = Collections.synchronizedMap(WeakHashMap<View, android.graphics.drawable.Drawable?>())
    private val pinImages = Collections.synchronizedMap(WeakHashMap<View, android.graphics.drawable.Drawable?>())
    private val pinAmbient = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val pinKeys = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<View, Boolean>()))

    private fun pinOn(view: View): Boolean {
        KyantGlass.ensureConfig(view.context)
        return KyantGlass.config.on(KyantGlass.KEY_PIN_KEYS)
    }

    /**
     * Bouncer number keys (VivoNumPadKey): the round plate (VivoNumKeyBg) becomes glass over the
     * lock wallpaper, blurred like the bouncer background; vivo's plate and its ambient glow
     * (VivoNumKeyAmbientBg) are not drawn. The key stretches and lights up under the finger.
     */
    private fun hookPinKeys(classLoader: ClassLoader) {
        val key = Class.forName(PIN_KEY, false, classLoader)
        val bgClass = Class.forName(PIN_KEY_BG, false, classLoader)
        val ambientClass = runCatching { Class.forName(PIN_KEY_AMBIENT, false, classLoader) }.getOrNull()
        val bgField = findField(key, "mBg")
        val ambientField = findField(key, "ambientView")
        val boardLightField = findField(key, "boardLightView")

        fun style(owner: View) {
            val bg = runCatching { bgField?.get(owner) as? View }.getOrNull() ?: return
            val ambient = runCatching { ambientField?.get(owner) as? View }.getOrNull()
            // vivo's board light: the lit rim / frame drawn over each key.
            val boardLight = runCatching { boardLightField?.get(owner) as? View }.getOrNull()
            pinKeys += owner
            if (pinOn(owner)) {
                applyPinGlass(bg)
                if (boardLight != null && boardLight.visibility != View.INVISIBLE) {
                    if (!pinAmbient.containsKey(boardLight)) pinAmbient[boardLight] = boardLight.visibility
                    boardLight.visibility = View.INVISIBLE
                }
                if (ambient != null && ambient.visibility != View.INVISIBLE) {
                    if (!pinAmbient.containsKey(ambient)) pinAmbient[ambient] = ambient.visibility
                    ambient.visibility = View.INVISIBLE
                }
            } else {
                removePinGlass(bg)
                ambient?.let { a -> pinAmbient.remove(a)?.let { a.visibility = it } }
                boardLight?.let { b -> pinAmbient.remove(b)?.let { b.visibility = it } }
            }
        }

        val restyle = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.thisObject as? View)?.let(::style)
            }
        }
        for (name in arrayOf("onAttachedToWindow", "onLayout", "updateMaterialBlurEffect", "updateBackground", "onConfigurationChanged", "reset")) {
            XposedBridge.hookAllMethods(key, name, restyle)
        }
        // vivo's own plate / ambient glow: not drawn while the keys are glass.
        val skip = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (pinOn(view)) param.result = null
            }
        }
        for (cls in listOfNotNull(bgClass, ambientClass)) {
            cls.declaredMethods.filter { it.name == "updateBackground" || it.name.startsWith("onMaterialModeChanged") }
                .forEach { XposedBridge.hookMethod(it, skip) }
        }
        // The bouncer's own real-time blur of the wallpaper: off with the glass keys, the
        // wallpaper stays sharp behind them.
        runCatching { Class.forName(BOUNCER_BLUR, false, classLoader) }.getOrNull()?.let { blur ->
            XposedBridge.hookAllMethods(blur, "setVisibility", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (pinOn(view)) param.args[0] = View.INVISIBLE
                }
            })
            XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (blur.isInstance(view) && pinOn(view)) view.visibility = View.INVISIBLE
                }
            })
        }
        KyantGlass.onConfigChanged {
            synchronized(pinKeys) { pinKeys.toList() }.forEach { owner ->
                style(owner)
                if (!pinOn(owner)) runCatching { key.getDeclaredMethod("updateMaterialBlurEffect").apply { isAccessible = true }.invoke(owner) }
            }
        }
        XposedBridge.log("OriginKyantGlass: PIN pad glass installed")
    }

    private fun applyPinGlass(bg: View) {
        val glass = pinGlass.getOrPut(bg) {
            KyantGlass.GlassDrawable(
                bg, KyantGlass.KEY_PIN_KEYS,
                backdrop = { v -> ScreenBackdropSource.getLockBackdrop(v.context) },
                dim = true,
                elastic = { v -> v },
                radius = { _, w, h -> minOf(w, h) / 2f }
            )
        }
        KyantGlass.track(bg)
        clearNativeMaterial(bg)
        (bg as? android.widget.ImageView)?.let { image ->
            if (image.drawable != null) {
                if (!pinImages.containsKey(bg)) pinImages[bg] = image.drawable
                image.setImageDrawable(null)
            }
        }
        if (bg.background !== glass) {
            if (!pinBackgrounds.containsKey(bg)) pinBackgrounds[bg] = bg.background
            bg.background = glass
        }
        bg.alpha = 1f
        glass.invalidateSelf()
    }

    private fun removePinGlass(bg: View) {
        val glass = pinGlass[bg] ?: return
        if (bg.background === glass) bg.background = pinBackgrounds.remove(bg)
        if (pinImages.containsKey(bg)) (bg as? android.widget.ImageView)?.setImageDrawable(pinImages.remove(bg))
    }

    // ---------------------------------------------------------------- heads-up pop

    private const val ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    private const val HEADS_UP_POP_MS = 460L
    private const val HEADS_UP_BLUR_DP = 22f
    private val wasHeadsUp = ThreadLocal<Boolean>()

    /** A notification turning into a heads-up comes in out of a blur with a little swell. */
    private fun hookHeadsUpPop(classLoader: ClassLoader) {
        val row = Class.forName(ROW, false, classLoader)
        val isHeadsUp = findField(row, "mIsHeadsUp") ?: return
        XposedBridge.hookAllMethods(row, "setHeadsUp", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                wasHeadsUp.set(runCatching { isHeadsUp.getBoolean(param.thisObject) }.getOrDefault(true))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                val was = wasHeadsUp.get() ?: true
                wasHeadsUp.remove()
                if (was || param.args.firstOrNull() != true) return
                KyantGlass.ensureConfig(view.context)
                if (!KyantGlass.config.on(KyantGlass.KEY_HEADSUP_POP) || !KyantGlass.supported) return
                popIn(view)
            }
        })
    }

    private fun popIn(view: View) {
        val maxBlur = HEADS_UP_BLUR_DP * view.resources.displayMetrics.density
        val matrix = android.graphics.Matrix()
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
        animator.duration = HEADS_UP_POP_MS
        animator.interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        animator.addUpdateListener { a ->
            val t = a.animatedValue as Float
            val r = maxBlur * (1f - t)
            view.setRenderEffect(if (r > 0.5f) android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.DECAL) else null)
            val s = 0.94f + 0.06f * t
            matrix.setScale(s, s, view.width / 2f, 0f)
            view.animationMatrix = matrix
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                view.setRenderEffect(null)
                view.animationMatrix = null
            }

            override fun onAnimationCancel(animation: android.animation.Animator) = onAnimationEnd(animation)
        })
        view.post { animator.start() }
    }

    // ---------------------------------------------------------------- shade background blur

    private val PANEL_BLUR_CLASSES = setOf(
        "com.vivo.systemuiplugin.systemui.statusbar.panel.background.VivoDynamicBlurBg",
        "com.vivo.systemui.statusbar.panel.background.VivoDynamicBlurBg"
    )
    private val panelBlurClasses = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>()))

    /** The shade background blur scale, independent of the glass (1 = ROM value). */
    private fun shadeBlurScale(): Float {
        if (!KyantGlass.configLoaded) appContext()?.let { KyantGlass.ensureConfig(it) }
        return KyantGlass.config[KyantGlass.KEY_SHADE_BLUR].coerceIn(0, 200) / 100f
    }

    /** OriginOS shade / control-center backdrop: a VivoDynamicBlurBg fed per expansion frame. */
    private fun hookPanelBlur(cls: Class<*>) {
        if (!panelBlurClasses.add(cls)) return
        XposedBridge.hookAllMethods(cls, "setBlurRadius", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val scale = shadeBlurScale()
                if (scale == 1f) return
                when (val v = param.args.firstOrNull()) {
                    is Float -> param.args[0] = (v * scale).coerceAtLeast(0f)
                    is Int -> param.args[0] = (v * scale).toInt().coerceAtLeast(0)
                }
            }
        })
    }

    /** AOSP path (NotificationShadeDepthController → BlurUtils): blur-behind on the shade window. */
    private fun hookWindowBlur() {
        XposedBridge.hookAllMethods(android.view.SurfaceControl.Transaction::class.java, "setBackgroundBlurRadius", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val radius = param.args.getOrNull(1) as? Int ?: return
                val scale = shadeBlurScale()
                if (scale != 1f) param.args[1] = (radius * scale).toInt().coerceAtLeast(0)
            }
        })
    }

    private fun appContext(): android.content.Context? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? android.content.Context
    }.getOrNull()

    /** Skips the notification background drawable and paints glass over the native blur. */
    private fun hookNotifications(classLoader: ClassLoader) {
        val cls = Class.forName(NOTIFICATION_BG, false, classLoader)
        val f = { name: String -> runCatching { cls.getDeclaredField(name).apply { isAccessible = true } }.getOrNull() }
        val actualHeight = f("mActualHeight")
        val actualWidth = f("mActualWidth")
        val clipTop = f("mClipTopAmount")
        val clipBottom = f("mClipBottomAmount")
        val cornerRadii = f("mCornerRadii")
        val colorized = f("mIsColorizedNoti")
        val rect = RectF()
        val radii = FloatArray(4)
        bgGeometry = { view, outRect, outRadii ->
            val width = (runCatching { actualWidth?.getInt(view) }.getOrNull() ?: 0).takeIf { it > 0 } ?: view.width
            val height = (runCatching { actualHeight?.getInt(view) }.getOrNull() ?: 0).takeIf { it > 0 } ?: view.height
            val top = runCatching { clipTop?.getInt(view) }.getOrNull() ?: 0
            val bottom = height - (runCatching { clipBottom?.getInt(view) }.getOrNull() ?: 0)
            if (bottom - top < 2 || width < 2) {
                false
            } else {
                outRect.set(0f, top.toFloat(), width.toFloat(), bottom.toFloat())
                val r = runCatching { cornerRadii?.get(view) as? FloatArray }.getOrNull()
                if (r != null && r.size >= 8) {
                    outRadii[0] = r[0]; outRadii[1] = r[2]; outRadii[2] = r[4]; outRadii[3] = r[6]
                } else {
                    outRadii.fill(outlineRadius(view) ?: 24f * view.resources.displayMetrics.density)
                }
                true
            }
        }
        // Every geometry change / redraw of the background redraws its row in the same frame:
        // the rim lives in the row and would otherwise lag a frame behind (flicker while
        // notifications expand / collapse).
        val follow = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                val row = notificationRowOf(view) ?: return
                if (row in notificationRims) row.invalidate()
            }
        }
        for (name in arrayOf("invalidate", "setActualHeight", "setActualWidth", "setClipTopAmount", "setClipBottomAmount",
            "setRadius", "updateBackgroundRadii", "setDrawInBounds", "setExpandAnimationRunning", "setParentTranslationX", "setTranslationX")) {
            runCatching { XposedBridge.hookAllMethods(cls, name, follow) }
        }
        XposedBridge.hookAllMethods(cls, "onDraw", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                val canvas = param.args.firstOrNull() as? Canvas ?: return
                KyantGlass.ensureConfig(view.context)
                KyantGlass.track(view)
                // Colourised notifications (calls) keep their colour; media players become glass.
                val keepColour = runCatching { colorized?.getBoolean(view) }.getOrNull() == true && !isMediaRow(view)
                if (!KyantGlass.config.on(KyantGlass.KEY_NOTIFICATIONS) || keepColour) {
                    restoreNotificationLayer(view)
                    return
                }
                if (bgGeometry?.invoke(view, rect, radii) != true) {
                    param.result = null
                    return
                }
                val backdrop = ShadeBackdrop.get(view)
                // Without any backdrop keep vivo's own background: it punches the hole in the
                // shade scrim that shows the blur, skipping it leaves a black card.
                if (backdrop == null && !ShadeBackdrop.available) {
                    restoreNotificationLayer(view)
                    return
                }
                // vivo renders the card into a hardware layer composited with a hole-punch
                // xfermode for its own blur: glass drawn into that layer comes out black.
                dropNotificationLayer(view)
                dropVivoEffect(view)
                val renderer = notificationRenderers.getOrPut(view) { KyantGlass.Renderer() }
                // The rim and the finger light go on top of the whole row (NotificationRim): drawn
                // here they sit under the notification's content, which hides them.
                renderer.draw(canvas, view, rect, radii, backdrop, dim = true, highlight = false, withTouchGlow = false,
                    // The row clips its outline a little inside the background: keep the rim visible.
                    rimInset = 1.5f * view.resources.displayMetrics.density)
                KyantGlassTouch.registerHost(view, ::notificationRowOf)
                notificationRowOf(view)?.let { row -> attachRim(row, view) }

                param.result = null
            }
        })
        XposedBridge.hookAllMethods(cls, "setLayerType", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view in layerlessNotifications && !restoringLayer.get()) {
                    notificationLayers[view] = (param.args[0] as Int) to (param.args.getOrNull(1) as? android.graphics.Paint)
                    param.result = null
                }
            }
        })
        XposedBridge.log("OriginKyantGlass: notification glass installed")
    }

    private val keyguardManager by lazy { appContext()?.getSystemService(android.app.KeyguardManager::class.java) }

    private fun onKeyguard(view: View): Boolean =
        runCatching { (keyguardManager ?: view.context.getSystemService(android.app.KeyguardManager::class.java))?.isKeyguardLocked }.getOrNull() == true

    /**
     * vivo's material / light-rim layer (EffectDrawable as the foreground) draws its own plate and
     * border over the glass: glass surfaces do without it.
     */
    fun dropVivoEffect(view: View) {
        val fg = view.foreground ?: return
        if (fg.javaClass.simpleName != "EffectDrawable") return
        // Not while drawing: changing the foreground invalidates the display list being recorded.
        view.post { if (view.foreground === fg) view.foreground = null }
    }

    /** Background → its visible card rect and corner radii (false: nothing visible). */
    private var bgGeometry: ((View, RectF, FloatArray) -> Boolean)? = null

    /**
     * Rim highlight + finger light of a notification, drawn after the row's children so they lie
     * over the content like on every other glass surface. Geometry is read from the background
     * at draw time, never from an earlier frame.
     */
    private class NotificationRim(row: View) {
        private val rowRef = java.lang.ref.WeakReference(row)
        private val renderer = KyantGlass.Renderer()
        private val rect = RectF()
        private val radii = FloatArray(4)
        private val drawRect = RectF()
        var background: java.lang.ref.WeakReference<View>? = null

        fun draw(canvas: Canvas) {
            val row = rowRef.get() ?: return
            val bg = background?.get() ?: return
            if (!KyantGlass.config.on(KyantGlass.KEY_NOTIFICATIONS) || bg.visibility != View.VISIBLE || bg.parent == null) return
            if (bgGeometry?.invoke(bg, rect, radii) != true || rect.isEmpty) return
            // Background coordinates → row coordinates.
            var dx = 0f
            var dy = 0f
            var v: View? = bg
            while (v != null && v !== row) {
                dx += v.left + v.translationX
                dy += v.top + v.translationY
                v = v.parent as? View
            }
            if (v == null) return
            drawRect.set(rect)
            drawRect.offset(dx, dy)
            renderer.drawRimOnly(canvas, row, drawRect, radii, edgeGlow = false)
        }
    }

    private val notificationRims = Collections.synchronizedMap(WeakHashMap<View, NotificationRim>())
    private val rowDrawHooked = AtomicBoolean(false)

    /**
     * Rows have no background, so Android records them through dispatchDraw directly (View.draw
     * and the overlay are skipped): the rim goes on after ViewGroup.dispatchDraw of the row.
     */
    private fun hookRowDispatchDraw() {
        if (!rowDrawHooked.compareAndSet(false, true)) return
        XposedBridge.hookAllMethods(android.view.ViewGroup::class.java, "dispatchDraw", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val rim = notificationRims[param.thisObject as? View ?: return] ?: return
                val canvas = param.args.firstOrNull() as? Canvas ?: return
                runCatching { rim.draw(canvas) }
            }
        })
    }

    private fun attachRim(row: View, background: View) {
        var created = false
        val rim = notificationRims.getOrPut(row) {
            hookRowDispatchDraw()
            KyantGlass.track(row)
            created = true
            NotificationRim(row)
        }
        if (rim.background?.get() !== background) {
            rim.background = java.lang.ref.WeakReference(background)
            created = true
        }
        // A new rim is not in the row's recorded display list yet.
        if (created) row.postInvalidateOnAnimation()
    }

    /** The whole notification (content and glass) stretches, not just its background. */
    private fun notificationRowOf(background: View): View? =
        generateSequence(background.parent as? View) { it.parent as? View }.take(3)
            .firstOrNull { it.javaClass.simpleName == "ExpandableNotificationRow" } ?: background.parent as? View

    private val mediaRows = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    /** True for the background of a media-style notification (OriginPlayer and other players). */
    private fun isMediaRow(background: View): Boolean {
        val row = generateSequence(background.parent as? View) { it.parent as? View }.take(3)
            .firstOrNull { it.javaClass.simpleName == "ExpandableNotificationRow" } ?: return false
        return mediaRows.getOrPut(row) {
            runCatching<Boolean> {
                val entry = row.javaClass.getMethod("getEntry").invoke(row)
                val sbn = entry.javaClass.getMethod("getSbn").invoke(entry) as android.service.notification.StatusBarNotification
                sbn.notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)
            }.getOrDefault(false)
        }
    }

    private val notificationLayers = Collections.synchronizedMap(WeakHashMap<View, Pair<Int, android.graphics.Paint?>>())
    private val layerlessNotifications = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<View, Boolean>()))
    private val restoringLayer = ThreadLocal.withInitial { false }

    private fun dropNotificationLayer(view: View) {
        if (view in layerlessNotifications) return
        layerlessNotifications += view
        if (view.layerType == View.LAYER_TYPE_NONE) return
        notificationLayers[view] = view.layerType to layerPaint(view)
        // Not while drawing: switching layers invalidates the display list being recorded.
        view.post { setLayer(view, View.LAYER_TYPE_NONE, null) }
    }

    private fun restoreNotificationLayer(view: View) {
        if (!layerlessNotifications.remove(view)) return
        val (type, paint) = notificationLayers.remove(view) ?: return
        view.post { setLayer(view, type, paint) }
    }

    private fun setLayer(view: View, type: Int, paint: android.graphics.Paint?) {
        restoringLayer.set(true)
        try {
            view.setLayerType(type, paint)
        } finally {
            restoringLayer.set(false)
        }
    }

    private fun layerPaint(view: View): android.graphics.Paint? = runCatching {
        findField(View::class.java, "mLayerPaint")?.get(view) as? android.graphics.Paint
    }.getOrNull()

    // ---------------------------------------------------------------- control center

    private val glassBackgrounds = Collections.synchronizedMap(WeakHashMap<View, KyantGlass.GlassDrawable>())
    private val originalBackgrounds = Collections.synchronizedMap(WeakHashMap<View, android.graphics.drawable.Drawable?>())
    private val glassViews = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<View, Boolean>()))

    /**
     * Every control-center card, group, tile, slider and header button is a VivoEffect* view
     * whose plate is applied by VivoEffectHelper.addBlurBg (native material) plus
     * addBorderEffect (vivo's light rim). Skip both, clear any applied material and make glass
     * the background, so it sits under the icons and text. Active plates (vivo's opaque white
     * or coloured mask) stay glass too, with the mask colour painted over it as the surface.
     */
    private fun hookEffectHelper(helperClass: Class<*>) {
        synchronized(tileLoaders) { if (!tileLoaders.add(helperClass.classLoader ?: return)) return }
        val viewField = runCatching { helperClass.getDeclaredField("mView").apply { isAccessible = true } }.getOrNull() ?: return
        fun viewOf(helper: Any?): View? = helper?.let { runCatching { viewField.get(it) as? View }.getOrNull() }

        XposedBridge.hookAllMethods(helperClass, "addBlurBg", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val helper = param.thisObject ?: return
                val view = viewOf(helper) ?: return
                KyantGlass.ensureConfig(view.context)
                if (!KyantGlass.config.on(KyantGlass.KEY_TILES)) {
                    removeGlass(view)
                    return
                }
                // The first call can come before the params exist (focus menu items, tiles):
                // glass right away, the surface fills in once vivo sets the params.
                param.result = null
                clearNativeMaterial(view)
                val detailWindow = detailWindowOf(view)
                if (detailWindow != null) {
                    applyDetailPart(view, detailWindow, helper)
                } else if (isSliderLevel(view, helper)) {
                    applySliderLevel(view, helper)
                } else {
                    applyGlass(view, surface = { helperSurface(helper) }, radius = { v, w, h -> helperRadius(helper, v, w, h) })
                }
                runCatching { helper.javaClass.getMethod("pauseBorderHLDraw").invoke(helper) }
            }
        })
        XposedBridge.hookAllMethods(helperClass, "addBorderEffect", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = viewOf(param.thisObject) ?: return
                if (view in glassViews) param.result = null
            }
        })
        hookDirectMaterials()
        KyantGlass.onConfigChanged {
            synchronized(glassViews) { glassViews.toList() }.forEach { view ->
                if (!KyantGlass.config.on(KyantGlass.KEY_TILES)) {
                    removeGlass(view)
                    // Let vivo re-apply its plate.
                    runCatching { view.javaClass.getMethod("refreshBlur", java.lang.Boolean.TYPE).invoke(view, true) }
                }
            }
        }
        XposedBridge.log("OriginKyantGlass: control center glass installed")
    }

    private val directMaterialsHooked = AtomicBoolean(false)

    /**
     * Cards that bypass VivoEffectHelper (the 2x2 music widget sets View.setMaterialCustomized
     * itself) get glass here; any material call on a view that already wears glass is dropped,
     * so a later refresh cannot paint the native plate back over it.
     */
    private fun hookDirectMaterials() {
        if (!directMaterialsHooked.compareAndSet(false, true)) return
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!KyantGlass.configLoaded) KyantGlass.ensureConfig(view.context)
                if (!KyantGlass.config.on(KyantGlass.KEY_TILES)) return
                if (view in glassViews) {
                    param.result = null
                    return
                }
                // Inside a glass player card only the card is glass: its buttons get no plate of
                // their own (neither vivo's material nor a glass one).
                if (insidePlayerCard(view)) {
                    param.result = null
                    return
                }
                if (isShadeButton(view, materialCall = true)) {
                    param.result = null
                    applyShadeButton(view)
                    return
                }
                if (!isStandaloneCard(view)) return
                param.result = null
                clearNativeMaterial(view)
                applyGlass(view, surface = { android.graphics.Color.TRANSPARENT }, radius = { v, w, h -> cardRadius(v, w, h) })
                view.post { clearPlayerPlate(view) }
            }
        }
        for (name in arrayOf("setMaterial", "setMaterialForce", "setMaterialCustomized", "setMaterialMaskFusion")) {
            runCatching { XposedBridge.hookAllMethods(View::class.java, name, hook) }
        }
        // vivo re-sets plain backgrounds (a ColorDrawable on the AI button) after the glass went
        // on: keep the glass on views that wear it.
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view in playerPlates && glassOnForPlayer()) {
                    param.args[0] = null
                    return
                }
                if (view !in glassViews) return
                val mine: android.graphics.drawable.Drawable = glassBackgrounds[view] ?: levelDrawables[view] ?: detailParts[view] ?: return
                if (param.args[0] !== mine && KyantGlass.config.on(KyantGlass.KEY_TILES)) param.args[0] = mine
            }
        })
        // OriginPlayer's card can get its native material before this hook sees it (the media
        // card above the notifications): wear glass whenever the card attaches to a window.
        XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (isShadeButton(view, materialCall = false)) {
                    KyantGlass.ensureConfig(view.context)
                    if (KyantGlass.config.on(KyantGlass.KEY_TILES) || KyantGlass.config.on(KyantGlass.KEY_NOTIFICATIONS)) applyShadeButton(view)
                    return
                }
                // OriginPlayer's outermost view, or the card a vivo music container holds (the media
                // card above notifications: no material call ever reaches it, its plate blurs itself).
                val playerCard = isOriginPlayerView(view) && (view.parent as? View)?.let(::isOriginPlayerView) != true
                val containerCard = (view.parent as? View)?.javaClass?.simpleName?.contains("MixMusicContainer") == true &&
                    view.javaClass.simpleName != "MenuLayout"
                if (!playerCard && !containerCard) return
                KyantGlass.ensureConfig(view.context)
                if (!KyantGlass.config.on(KyantGlass.KEY_TILES) && !KyantGlass.config.on(KyantGlass.KEY_NOTIFICATIONS)) return
                clearNativeMaterial(view)
                applyGlass(view, surface = { android.graphics.Color.TRANSPARENT }, radius = { v, w, h -> cardRadius(v, w, h) })
                // Its inner plate is laid out later than the card attaches.
                view.post { clearPlayerPlate(view) }
            }
        })
    }

    /** Only used until the card's own clipping outline is laid out. */
    private const val CARD_RADIUS_FRACTION = 0.23f

    /**
     * The card's real shape: OriginUI clips its content (the music widget's media_parent) with
     * a rounded outline whose radius follows the system corner setting. Read it on every draw,
     * from the card itself or the descendant that fills it, scaled to the glass size.
     */
    private fun cardRadius(view: View, w: Float, h: Float): Float {
        val half = minOf(w, h) / 2f
        val shape = clippingOutline(view, 0)
        if (shape != null) {
            val (radius, size) = shape
            val scale = if (size > 0f) minOf(w, h) / size else 1f
            return (radius * scale).coerceAtMost(half)
        }
        return outlineRadius(view)?.coerceAtMost(half) ?: minOf(w, h) * CARD_RADIUS_FRACTION
    }

    /** First clipping outline (radius, short side) of [view] or a descendant covering it. */
    private fun clippingOutline(view: View, depth: Int, rootShort: Float = minOf(view.width, view.height).toFloat()): Pair<Float, Float>? {
        val short = minOf(view.width, view.height).toFloat()
        if (view.clipToOutline && short >= rootShort * 0.9f) {
            outlineRadius(view)?.let { return it to short }
        }
        if (depth >= 4 || view !is android.view.ViewGroup) return null
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            clippingOutline(child, depth + 1, rootShort)?.let { return it }
        }
        return null
    }

    private val CARD_HOSTS = arrayOf("MixMusic", "MediaPlayer", "MusicWidget", "MusicContainer")

    private fun isStandaloneCard(view: View): Boolean {
        if (view.javaClass.simpleName.startsWith("VivoEffect")) return false
        // OriginPlayer (com.vivo.musicmixcard) everywhere it shows up: control center, its
        // expanded view, lockscreen and notification. Only its outermost plated view.
        if (isOriginPlayerView(view) && (view.parent as? View)?.let(::isOriginPlayerView) != true) return true
        var parent = view.parent as? View
        var depth = 0
        while (parent != null && depth < 3) {
            val name = parent.javaClass.simpleName
            if (CARD_HOSTS.any { name.contains(it) }) return true
            parent = parent.parent as? View
            depth++
        }
        return false
    }

    // ---------------------------------------------------------------- volume panel

    private val volumeHooked = AtomicBoolean(false)

    /**
     * The side volume slider (brief dialog) and the expanded dialog's rows draw their track in
     * VivoVolumeSeekbarBackground: a native material (blur) or painted rects. With control
     * center glass on, neither is applied and the track is glass; vivo's level is drawn by a
     * separate view on top and stays as it is.
     */
    private fun hookVolume(cls: Class<*>) {
        if (!volumeHooked.compareAndSet(false, true)) return
        val radius = runCatching { cls.getDeclaredMethod("getRadius").apply { isAccessible = true } }.getOrNull()
        fun volumeOn(view: View): Boolean {
            KyantGlass.ensureConfig(view.context)
            return KyantGlass.config.on(KyantGlass.KEY_TILES)
        }
        val apply = object : XC_MethodHook() {
            public override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!volumeOn(view)) {
                    removeGlass(view)
                    return
                }
                XposedBridge.log("OriginKyantGlass: DBGVOL ${param.method.name} ${view.width}x${view.height} brief=${runCatching { findField(cls, "mIsBriefSeekbar")?.getBoolean(view) }.getOrNull()} vis=${view.isShown} parents=${generateSequence(view.parent as? View) { it.parent as? View }.take(8).joinToString { it.javaClass.simpleName + "(" + it.width + "x" + it.height + ",bg=" + (it.background?.javaClass?.simpleName) + ")" }}")
                clearNativeMaterial(view)
                applyGlass(view, surface = { android.graphics.Color.TRANSPARENT }, radius = { v, w, h ->
                    val r =runCatching { (radius?.invoke(v) as? Int)?.toFloat() }.getOrNull()
                    (r?.takeIf { it > 0f } ?: outlineRadius(v) ?: minOf(w, h) / 2f).coerceAtMost(minOf(w, h) / 2f)
                })
            }
        }
        for (name in arrayOf("onAttachedToWindow", "resetView", "resize", "updateRadius", "onUiModeChanged")) {
            XposedBridge.hookAllMethods(cls, name, apply)
        }
        val skip = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!volumeOn(view)) return
                // The panel can be shown without any of the hooks above running (a reused
                // dialog): the first draw puts the glass on, instead of the first touch.
                if (view !in glassViews) apply.afterHookedMethod(param)
                param.result = null
            }
        }
        for (name in arrayOf("onDraw", "setMaterialCustomizedForBriefSeekbarBg", "setVibrancyEffectForExpandSeekbarBg")) {
            XposedBridge.hookAllMethods(cls, name, skip)
        }
        // The slider itself (track + level): vivo clears its vibrancy material while it is touched
        // and puts it back on release, over the whole slider, which hid the glass whenever the
        // finger was up. With glass on, the material never goes back on.
        runCatching { Class.forName(VOLUME_SLIDER, false, cls.classLoader) }.getOrNull()?.let { slider ->
            XposedBridge.hookAllMethods(slider, "setVibrancyEffect", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!volumeOn(view)) return
                    param.result = null
                    clearNativeMaterial(view)
                }
            })
            val clear = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (volumeOn(view)) clearNativeMaterial(view)
                }
            }
            for (name in arrayOf("onAttachedToWindow", "resetView", "resetState", "resize", "onUiModeChanged")) {
                XposedBridge.hookAllMethods(slider, name, clear)
            }
        }
        KyantGlass.onConfigChanged {
            if (KyantGlass.config.on(KyantGlass.KEY_TILES)) return@onConfigChanged
            synchronized(glassViews) { glassViews.filter { it.javaClass === cls } }.forEach { view ->
                removeGlass(view)
                // Let vivo put its own track back.
                runCatching { cls.getDeclaredMethod("resetView").apply { isAccessible = true }.invoke(view) }
                view.invalidate()
            }
        }
        XposedBridge.log("OriginKyantGlass: volume glass installed")
    }

    // ---------------------------------------------------------------- lockscreen clock

    private const val CLOCK_TEXT = "com.vivo.systemuiplugin.keyguard.time.VivoNotAdjustFontWeightTextView"
    private val CLOCK_APPLIERS = setOf(
        "com.vivo.systemuiplugin.keyguard.time.material.applier.GlassApplier",
        "com.vivo.systemuiplugin.keyguard.time.material.applier.BlurApplier",
        "com.vivo.systemuiplugin.keyguard.time.material.applier.ColorApplier"
    )
    private val clockHooked = AtomicBoolean(false)
    private val clockViews = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<View, Boolean>()))
    private val clockAppliersHooked = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>()))

    private fun clockOn(view: View): Boolean {
        KyantGlass.ensureConfig(view.context)
        return KyantGlass.config.on(KyantGlass.KEY_CLOCK)
    }

    /**
     * vivo's lockscreen clock text: with the clock glass on it is plain opaque white text (the
     * glass mask), vivo's material is off, and the shared glass shader goes on as its effect.
     */
    private fun hookClock(cls: Class<*>) {
        if (!clockHooked.compareAndSet(false, true)) return
        fun style(view: View) {
            clockViews += view
            if (!clockOn(view)) {
                if (ClockGlass.isApplied(view)) {
                    ClockGlass.clear(view)
                    // Let vivo style its clock again.
                    view.requestLayout()
                }
                return
            }
            clearNativeMaterial(view)
            (view as? android.widget.TextView)?.let { if (it.currentTextColor != android.graphics.Color.WHITE) it.setTextColor(android.graphics.Color.WHITE) }
            ClockGlass.apply(view)
        }
        val restyle = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.thisObject as? View)?.let(::style)
            }
        }
        XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view.javaClass === cls) style(view)
            }
        })
        XposedBridge.hookAllMethods(View::class.java, "onSizeChanged", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view.javaClass === cls && clockOn(view)) view.post { style(view) }
            }
        })
        // vivo re-tints the digits on every material update: they stay the white glass mask.
        XposedBridge.hookAllMethods(android.widget.TextView::class.java, "setTextColor", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view.javaClass !== cls || !clockOn(view)) return
                if (param.args.firstOrNull() is Int) param.args[0] = android.graphics.Color.WHITE
            }
        })
        KyantGlass.onConfigChanged {
            synchronized(clockViews) { clockViews.toList() }.forEach { v -> v.post { style(v) } }
        }
        XposedBridge.log("OriginKyantGlass: clock glass installed")
    }

    /** vivo's clock material appliers (glass / blur / colour) do nothing while our glass is on. */
    private fun hookClockApplier(cls: Class<*>) {
        if (!clockAppliersHooked.add(cls)) return
        XposedBridge.hookAllMethods(cls, "apply", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.args.firstOrNull() as? View ?: return
                if (view.javaClass.name != CLOCK_TEXT || !clockOn(view)) return
                param.result = null
                clearNativeMaterial(view)
                ClockGlass.apply(view)
            }
        })
    }

    // ---------------------------------------------------------------- ringer button

    private const val RINGER = "com.vivo.systemuiplugin.systemui.volume.VivoVolumeScaleFrameForNew"
    private val ringerHooked = AtomicBoolean(false)
    private val ringerColors = Collections.synchronizedMap(WeakHashMap<View, Int>())

    /**
     * The ringer / silent-mode button under the volume slider: glass like everything else, its
     * mode colour kept as the surface (vivo re-colours it through setBackgroundColor), and the
     * glass physics instead of vivo's own scale animation.
     */
    private fun hookRinger(cls: Class<*>) {
        if (!ringerHooked.compareAndSet(false, true)) return
        fun on(view: View): Boolean {
            KyantGlass.ensureConfig(view.context)
            return KyantGlass.config.on(KyantGlass.KEY_TILES)
        }
        fun style(view: View) {
            if (!on(view)) return
            val glass = glassBackgrounds[view]
            val bg = view.background
            if (bg !== glass && bg is android.graphics.drawable.ColorDrawable) ringerColors[view] = bg.color
            clearNativeMaterial(view)
            applyGlass(view, surface = { v -> ringerSurface(ringerColors[v] ?: android.graphics.Color.TRANSPARENT) }, radius = { v, w, h ->
                (outlineRadius(v) ?: minOf(w, h) / 2f).coerceAtMost(minOf(w, h) / 2f)
            })
        }
        val restyle = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.thisObject as? View)?.let(::style)
            }
        }
        for (name in arrayOf("onFinishInflate", "resetView", "setRingerIcon", "setRingerIconForSilentKey", "setRingerIconForNoSilentKey", "setDialogType")) {
            XposedBridge.hookAllMethods(cls, name, restyle)
        }
        XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view.javaClass === cls) style(view)
            }
        })
        // vivo re-colours the button by replacing its background: keep the colour, keep the glass.
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view.javaClass !== cls || !on(view)) return
                val glass = glassBackgrounds[view] ?: return
                val d = param.args[0]
                if (d === glass) return
                if (d is android.graphics.drawable.ColorDrawable) ringerColors[view] = d.color
                param.args[0] = glass
                glass.invalidateSelf()
            }
        })
        // The glass physics replaces vivo's press scale.
        XposedBridge.hookAllMethods(cls, "startScaleAnim", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (on(view)) param.result = null
            }
        })
        XposedBridge.log("OriginKyantGlass: ringer glass installed")
    }

    /** The mode colour over the glass, slightly thinner so the lens still reads. */
    private fun ringerSurface(color: Int): Int {
        val a = android.graphics.Color.alpha(color)
        if (a == 0) return android.graphics.Color.TRANSPARENT
        return (color and 0x00FFFFFF) or ((a * ACTIVE_SURFACE_OPACITY).toInt().coerceIn(0, 255) shl 24)
    }

    // ---------------------------------------------------------------- shade buttons

    private val shadeButtonClasses = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())

    /**
     * Round / pill buttons of the notification shade: clear all, the AI assistant entry and the
     * expand / collapse pill of notification stacks (a NotificationExpandButton that vivo plates
     * with a material; plain chevrons get no material and stay as they are).
     */
    private fun isShadeButton(view: View, materialCall: Boolean): Boolean {
        val simple = view.javaClass.simpleName
        if (simple == "NotificationExpandButton" && (materialCall || isGroupHeaderButton(view))) return true
        val byClass = shadeButtonClasses.getOrPut(view.javaClass) {
            simple.contains("ClearAll") || simple.contains("Copilot") || simple.contains("AiEntry") || simple.contains("AiButton")
        }
        if (byClass) return true
        val id = idName(view) ?: return false
        return id.contains("clear_all") || id.contains("clearall") || id.contains("notification_clear") ||
            id.contains("ai_launcher") || id.contains("copilot") || id.contains("ai_assistant") || id.contains("ai_consult") ||
            id.startsWith("ai_") || id.contains("_ai_")
    }

    /** The expand / collapse pill of a notification stack sits in the group's header. */
    private fun isGroupHeaderButton(view: View): Boolean {
        var v: View? = view.parent as? View
        var depth = 0
        while (v != null && depth < 4) {
            if (v.javaClass.simpleName == "NotificationChildrenContainer") return true
            v = v.parent as? View
            depth++
        }
        return false
    }

    private fun applyShadeButton(view: View) {
        clearNativeMaterial(view)
        applyGlass(view, surface = { android.graphics.Color.TRANSPARENT }, radius = { v, w, h ->
            outlineRadius(v)?.coerceAtMost(minOf(w, h) / 2f) ?: minOf(w, h) / 2f
        })
    }

    private val originPlayerLoaders = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())
    /** OriginPlayer's inner plate views (music_widget_background) whose fill / blur we took off. */
    private val playerPlates = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<View, Boolean>()))
    /** Player cards wearing glass whose own blur foreground was taken off. */
    private val playerForegrounds = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<View, Boolean>()))

    /**
     * OriginPlayer draws its own plate inside the card: music_widget_background, a colour fill
     * plus a blur foreground. Over the glass it hides it, so with glass on it carries neither.
     */
    /** Player cards wearing glass: everything inside them goes without a plate. */
    private val playerCards = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<View, Boolean>()))

    private fun insidePlayerCard(view: View): Boolean {
        if (playerCards.isEmpty()) return false
        var v = view.parent as? View
        var depth = 0
        while (v != null && depth < 10) {
            if (v in playerCards) return true
            v = v.parent as? View
            depth++
        }
        return false
    }

    /** Takes vivo's material and any glass off every view inside a player card. */
    private fun clearPlayerButtons(card: View) {
        fun walk(v: View, depth: Int) {
            if (v !== card) {
                clearNativeMaterial(v)
                if (v in glassViews) removeGlass(v)
            }
            if (depth >= 8 || v !is android.view.ViewGroup) return
            for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(card, 0)
    }

    private fun clearPlayerPlate(card: View) {
        playerCards += card
        clearPlayerButtons(card)
        // The card's own blur foreground (the lockscreen widget) and the colour fill of the
        // keyguard container around it.
        if (card.foreground != null && card.foreground !is KyantGlass.GlassDrawable) {
            playerForegrounds += card
            card.foreground = null
        }
        (card.parent as? View)?.takeIf { idName(it) == "keyguard_music_widget_container" }?.let { box ->
            playerPlates += box
            if (box.background != null) box.background = null
        }
        fun walk(v: View, depth: Int) {
            if (idName(v) == "music_widget_background") {
                playerPlates += v
                if (v.background != null) v.background = null
                if (v.foreground != null) v.foreground = null
                return
            }
            if (depth >= 5 || v !is android.view.ViewGroup) return
            for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(card, 0)
    }

    private fun glassOnForPlayer() = KyantGlass.config.on(KyantGlass.KEY_TILES) || KyantGlass.config.on(KyantGlass.KEY_NOTIFICATIONS)

    private fun isOriginPlayerView(view: View): Boolean {
        // System apps load from a path without the package name: the class name tells.
        val cls = view.javaClass.name
        if (ORIGIN_PLAYER_PACKAGES.any { cls.startsWith("$it.") }) return true
        val loader = view.javaClass.classLoader ?: return false
        return originPlayerLoaders.getOrPut(loader) {
            val name = loader.toString()
            ORIGIN_PLAYER_PACKAGES.any { name.contains(it) }
        }
    }

    /** OriginPlayer in the shade / control center, and its lockscreen widget. */
    private val ORIGIN_PLAYER_PACKAGES = arrayOf("com.vivo.musicmixcard", "com.vivo.musicwidgetmix")

    // ---------------------------------------------------------------- expanded tiles

    private val detailRadii = Collections.synchronizedMap(WeakHashMap<View, Float>())
    private val detailParts = Collections.synchronizedMap(WeakHashMap<View, KyantGlass.GlassDrawable>())

    /**
     * Expanded tiles (Wi-Fi, Bluetooth, ...) live in VivoQSDetail: a qs_detail_view whose title,
     * list and settings button each get their own plate. Returns that window when [view] is one
     * of its parts, so it can wear one glass pane instead of separate pieces. Full-screen detail
     * views (the flashlight slider) keep per-element glass.
     */
    private fun detailWindowOf(view: View): View? {
        var v: View? = view.parent as? View
        var depth = 0
        while (v != null && depth < 3) {
            if (idName(v) == "qs_detail_view") {
                if (!isInsideDetail(v)) return null
                val screenH = v.resources.displayMetrics.heightPixels
                return if (v.height == 0 || v.height < screenH * 0.8f) v else null
            }
            v = v.parent as? View
            depth++
        }
        return null
    }

    private fun isInsideDetail(view: View): Boolean {
        var v: View? = view.parent as? View
        var depth = 0
        while (v != null && depth < 6) {
            if (v.javaClass.simpleName == "VivoQSDetail") return true
            v = v.parent as? View
            depth++
        }
        return false
    }

    private fun idName(v: View): String? =
        if (v.id == View.NO_ID) null else runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()

    /** A part of an expanded tile: the window gets the glass, buttons a light glass button. */
    private fun applyDetailPart(part: View, window: View, helper: Any) {
        val partRadius = { w: Float, h: Float -> helperRadius(helper, part, w, h) }
        detailRadii[window] = maxOf(detailRadii[window] ?: 0f, partRadius(part.width.toFloat(), part.height.toFloat()))
        if (window !in glassViews) clearNativeMaterial(window)
        applyGlass(window, surface = { android.graphics.Color.TRANSPARENT }, radius = { v, w, h ->
            (outlineRadius(v) ?: detailRadii[v]?.takeIf { it > 0f } ?: 28f * v.resources.displayMetrics.density).coerceAtMost(minOf(w, h) / 2f)
        })
        val isButton = idName(part)?.let { it.contains("setting") || it.contains("button") } == true ||
            part is android.widget.TextView
        if (!isButton) {
            // Plain section: no plate of its own, the window glass shows through.
            if (part.background is KyantGlass.GlassDrawable) part.background = originalBackgrounds.remove(part)
            glassViews += part
            return
        }
        val button = detailParts.getOrPut(part) {
            KyantGlass.GlassDrawable(
                part, KyantGlass.KEY_TILES,
                backdrop = { v -> ShadeBackdrop.get(v) },
                dim = true,
                elastic = { v -> v },
                radius = { v, w, h -> helperRadius(helper, v, w, h) }
            )
        }
        glassViews += part
        if (part.background !== button) {
            if (!originalBackgrounds.containsKey(part)) originalBackgrounds[part] = part.background
            part.background = button
        }
    }


    private fun materialParams(helper: Any): Any? =
        runCatching { findField(helper.javaClass, "mMaterialParams")?.get(helper) }.getOrNull()

    /**
     * Inactive plates carry a translucent dark mask (pure glass). Active ones an opaque white or
     * coloured mask: paint it over the glass, slightly thinner so the lens still reads.
     */
    private fun helperSurface(helper: Any): Int {
        val params = materialParams(helper) ?: return android.graphics.Color.TRANSPARENT
        val color = runCatching { params.javaClass.getField("maskColor1").getInt(params) }.getOrNull() ?: return android.graphics.Color.TRANSPARENT
        val opacity = runCatching { params.javaClass.getField("maskOpacity1").getFloat(params) }.getOrNull() ?: return android.graphics.Color.TRANSPARENT
        if (opacity < 0.5f) return android.graphics.Color.TRANSPARENT
        val alpha = (opacity * ACTIVE_SURFACE_OPACITY * 255).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private const val ACTIVE_SURFACE_OPACITY = 0.82f

    private fun clearNativeMaterial(view: View) {
        runCatching { View::class.java.getMethod("clearMaterial").invoke(view) }
        runCatching { View::class.java.getDeclaredMethod("clearMaterial").apply { isAccessible = true }.invoke(view) }
    }

    // ---------------------------------------------------------------- slider level

    private val levelDrawables = Collections.synchronizedMap(WeakHashMap<View, SliderLevelDrawable>())

    /** The opaque (white) plate inside a brightness / volume seek bar: the level, not a surface. */
    private fun isSliderLevel(view: View, helper: Any): Boolean {
        val parent = view.parent as? View ?: return false
        val name = parent.javaClass.simpleName
        if (!name.contains("SeekBar") && !name.contains("Seekbar")) return false
        return android.graphics.Color.alpha(helperSurface(helper)) > 0
    }

    private fun applySliderLevel(view: View, helper: Any) {
        val level = levelDrawables.getOrPut(view) { SliderLevelDrawable(view, helper) }
        KyantGlass.track(view)
        glassViews += view
        if (view.background !== level) {
            if (!originalBackgrounds.containsKey(view)) originalBackgrounds[view] = view.background
            view.background = level
        }
        level.invalidateSelf()
    }

    /**
     * Level fill of a control-center slider. Flat (default): a straight top edge, clipped only by
     * the track's rounded shape, so the corners match the glass track. Rounded: vivo's pill-shaped
     * level as glass with the white surface, like any active plate.
     */
    private class SliderLevelDrawable(view: View, private val helper: Any) : android.graphics.drawable.Drawable() {
        private val viewRef = java.lang.ref.WeakReference(view)
        private val glass = KyantGlass.GlassDrawable(
            view, KyantGlass.KEY_TILES,
            backdrop = { v -> ShadeBackdrop.get(v) },
            dim = true,
            surface = { helperSurface(helper) },
            radius = { v, w, h -> helperRadius(helper, v, w, h) }
        )
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val track = RectF()
        private val path = android.graphics.Path()

        override fun draw(canvas: Canvas) {
            val view = viewRef.get() ?: return
            if (!KyantGlass.config.on(KyantGlass.KEY_SLIDER_FLAT)) {
                glass.bounds = bounds
                glass.draw(canvas)
                return
            }
            val parent = view.parent as? View ?: return
            // The track fills the seek bar: map its bounds into this view.
            val dx = view.left + view.translationX
            val dy = view.top + view.translationY
            track.set(-dx, -dy, parent.width - dx, parent.height - dy)
            val radius = trackRadius(view, parent) ?: (minOf(track.width(), track.height()) / 2f)
            path.rewind()
            path.addRoundRect(track, radius, radius, android.graphics.Path.Direction.CW)
            paint.color = levelColor()
            val save = canvas.save()
            canvas.clipPath(path)
            canvas.drawRect(bounds, paint)
            canvas.restoreToCount(save)
        }

        /** The glass track next to the level, if it already knows its corner radius. */
        private fun trackRadius(view: View, parent: View): Float? {
            val group = parent as? android.view.ViewGroup ?: return null
            for (i in 0 until group.childCount) {
                val sibling = group.getChildAt(i)
                if (sibling === view) continue
                val r = glassBackgrounds[sibling]?.lastRadius ?: continue
                if (r > 0f) return r
            }
            return null
        }

        /** vivo's level colour at its own opacity (the native fill is near-opaque white). */
        private fun levelColor(): Int {
            val params = materialParams(helper) ?: return android.graphics.Color.WHITE
            val color = runCatching { params.javaClass.getField("maskColor1").getInt(params) }.getOrDefault(android.graphics.Color.WHITE)
            val opacity = runCatching { params.javaClass.getField("maskOpacity1").getFloat(params) }.getOrDefault(0.9f)
            return (color and 0x00FFFFFF) or ((opacity.coerceIn(0f, 1f) * 255).toInt() shl 24)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun applyGlass(view: View, surface: (View) -> Int, radius: (View, Float, Float) -> Float) {
        val glass = glassBackgrounds.getOrPut(view) {
            KyantGlass.GlassDrawable(
                view, KyantGlass.KEY_TILES,
                backdrop = { v -> ShadeBackdrop.get(v) },
                dim = true,
                surface = surface,
                elastic = ::elasticTarget,
                radius = radius
            )
        }
        KyantGlass.track(view)
        glassViews += view
        if (view.background !== glass) {
            if (!originalBackgrounds.containsKey(view)) originalBackgrounds[view] = view.background
            view.background = glass
        }
        // State changes (active ↔ inactive) only change the surface colour.
        glass.invalidateSelf()
    }

    private fun removeGlass(view: View) {
        glassViews -= view
        val glass: android.graphics.drawable.Drawable = glassBackgrounds[view] ?: levelDrawables[view] ?: detailParts[view] ?: return
        if (view.background === glass) view.background = originalBackgrounds.remove(view)
    }

    private fun helperRadius(helper: Any, view: View, w: Float, h: Float): Float {
        val half = minOf(w, h) / 2f
        val circle = runCatching { findField(helper.javaClass, "mIsCircle")?.getBoolean(helper) }.getOrNull() ?: false
        if (circle) return half
        val pair = runCatching { helper.javaClass.getMethod("getRadius").invoke(helper) as? kotlin.Pair<*, *> }.getOrNull()
        (pair?.first as? Number)?.toFloat()?.takeIf { it > 0f }?.let { return it.coerceAtMost(half) }
        val tile = runCatching { findField(helper.javaClass, "mTileRadius")?.getInt(helper) }.getOrNull()
        return tile?.toFloat()?.coerceAtMost(half) ?: outlineRadius(view) ?: half * 0.6f
    }

    /**
     * What lies under the shade: SystemUI may capture the display, so grab it without the
     * calling window (quarter resolution is plenty under blur) and refresh while it is drawn.
     */
    internal object ShadeBackdrop {
        /** What is under the shade rarely changes: refresh slowly, and only once nothing moves. */
        /** Settings: performance → refresh interval under the shade. */
        private val MAX_AGE_MS: Long get() = KyantGlass.config[KyantGlass.KEY_REFRESH_MS].coerceIn(250, 5000).toLong()
        private const val STALE_MS = 4000L
        private const val STILL_MS = 250L
        /** A known-wrong frame is replaced at once, but never twice within this window. */
        private const val RETRY_MS = 250L
        /** No glass drawn for this long means the shade was closed: the next open recaptures. */
        private const val REOPEN_GAP_MS = 350L
        /** Launcher: an app's launch / close animation is still on top right after a focus change. */
        private const val FOCUS_SETTLE_MS = 600L
        private val LAUNCHER_MAX_AGE_MS: Long get() = KyantGlass.config[KyantGlass.KEY_HOME_REFRESH_MS].coerceIn(200, 5000).toLong()
        private const val MAX_FAILURES = 3
        /** Capture resolution: low is plenty under blur, higher keeps a thin lens sharp. */
        private val SCALE: Float get() = KyantGlass.config[KyantGlass.KEY_CAPTURE_SCALE].coerceIn(15, 100) / 100f
        @Volatile private var listening = false
        @Volatile private var bitmap: android.graphics.Bitmap? = null
        @Volatile private var capturedAt = 0L
        @Volatile private var capturedWindow: Any? = null
        @Volatile private var lastUseAt = 0L
        @Volatile private var inFlight = false
        @Volatile private var recheckPosted = false
        @Volatile private var retryPosted = false
        @Volatile private var failures = 0
        /** The frame no longer matches what is behind (shade reopened, other window, app was on top). */
        @Volatile private var stale = false
        @Volatile private var focusGainedAt = 0L
        @Volatile private var focusLostAt = 0L
        private val focusObservers = Collections.synchronizedMap(WeakHashMap<android.view.ViewTreeObserver, Boolean>())
        private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "OriginGlassCapture").apply { isDaemon = true }
        }

        /** False once capturing turned out impossible: callers fall back to the native look. */
        val available: Boolean get() = failures < MAX_FAILURES

        /**
         * The backdrop for [view], or null. SystemUI gets null while the only frame is known to be
         * wrong (a fresh one is on its way, usually a frame or two). [requireFocus] (launcher):
         * capture only while the launcher window has held focus for a moment, so an app's splash
         * screen or closing window never ends up in the glass.
         */
        fun get(view: View, requireFocus: Boolean = false): android.graphics.Bitmap? {
            // Lockscreen: the lock wallpaper lives inside the keyguard window, which the capture
            // leaves out (the glass turns black): every SystemUI surface refracts the wallpaper.
            if (!requireFocus && onKeyguard(view)) ScreenBackdropSource.getLockBackdrop(view.context)?.let { return it }
            if (failures >= MAX_FAILURES) return null
            val now = android.os.SystemClock.uptimeMillis()
            if (requireFocus) {
                watchFocus(view)
                if (!view.hasWindowFocus() || now - focusGainedAt < FOCUS_SETTLE_MS) return current()
            }
            val reopened = now - lastUseAt > REOPEN_GAP_MS
            lastUseAt = now
            val age = now - capturedAt
            val surface = windowSurface(view) ?: return current()
            if (bitmap == null || reopened || surface !== capturedWindow || (requireFocus && capturedAt < focusGainedAt)) stale = true
            if (!listening) {
                listening = true
                // Blur / glass settings are baked into the frame: take a new one when they change.
                KyantGlass.onConfigChanged { capturedAt = 0L; stale = true }
            }
            // Launcher (live wallpaper keeps moving): refresh more often than under the shade.
            val maxAge = if (requireFocus) LAUNCHER_MAX_AGE_MS else MAX_AGE_MS
            if (!inFlight) {
                val still = now - KyantGlass.lastMotionAt > STILL_MS
                when {
                    stale && age > RETRY_MS -> request(view, surface, requireFocus)
                    stale -> retryLater(view, requireFocus)
                    age > STALE_MS || (age > maxAge && still) -> request(view, surface, requireFocus)
                    age > maxAge && !recheckPosted -> {
                        // Moving now: capture once it settles. Never redraws anything by itself, so
                        // a surface that keeps moving cannot turn this into a redraw loop.
                        recheckPosted = true
                        view.postDelayed({
                            recheckPosted = false
                            val t = android.os.SystemClock.uptimeMillis()
                            if (view.isShown && !inFlight && t - KyantGlass.lastMotionAt > STILL_MS && t - capturedAt > maxAge) {
                                windowSurface(view)?.let { request(view, it, requireFocus) }
                            }
                        }, STILL_MS + 16L)
                    }
                }
            }
            // A stale frame is still the right blur and colours; a missing one flashes the glass
            // black. The fresh capture cross-fades in over it.
            return current()
        }

        private fun current(): android.graphics.Bitmap? = bitmap?.takeIf { !it.isRecycled }

        private fun retryLater(view: View, requireFocus: Boolean) {
            if (retryPosted) return
            retryPosted = true
            view.postDelayed({
                retryPosted = false
                if (stale && !inFlight && view.isShown) windowSurface(view)?.let { request(view, it, requireFocus) }
            }, RETRY_MS + 16L)
        }

        private fun watchFocus(view: View) {
            val observer = view.viewTreeObserver
            if (!observer.isAlive || focusObservers.put(observer, true) != null) return
            observer.addOnWindowFocusChangeListener { hasFocus ->
                val t = android.os.SystemClock.uptimeMillis()
                if (hasFocus) {
                    focusGainedAt = t
                    // Recapture (and redraw) once the app's closing animation is gone.
                    view.postDelayed({ KyantGlass.invalidateTracked() }, FOCUS_SETTLE_MS + 16L)
                } else {
                    focusLostAt = t
                }
            }
        }

        private val getViewRootImpl by lazy { runCatching { View::class.java.getMethod("getViewRootImpl") }.getOrNull() }
        private var getSurfaceControl: java.lang.reflect.Method? = null

        private fun windowSurface(view: View): Any? = runCatching {
            val root = getViewRootImpl?.invoke(view) ?: return null
            val method = getSurfaceControl ?: root.javaClass.getMethod("getSurfaceControl").also { getSurfaceControl = it }
            method.invoke(root)
        }.getOrNull()

        private fun request(view: View, surface: Any, requireFocus: Boolean) {
            inFlight = true
            val requestedAt = android.os.SystemClock.uptimeMillis()
            // The glass blur, in capture pixels, applied once here on the worker.
            val scale = SCALE
            val blurRadius = KyantGlass.blurPx(KyantGlass.config, view.resources.displayMetrics.density, matchShade = !requireFocus) * scale
            worker.execute {
                try {
                    val raw = capture(surface, scale)
                    val captured = if (raw != null && blurRadius >= 0.5f) {
                        runCatching {
                            val soft = raw.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            KyantGlass.blurSoftware(soft, blurRadius).also {
                                soft.recycle()
                                KyantGlass.markPreBlurred(it)
                            }
                        }.getOrDefault(raw)
                    } else raw
                    if (captured != null) {
                        failures = 0
                        view.post {
                            // Launcher lost focus meanwhile: an app window may be in that frame.
                            if (requireFocus && (!view.hasWindowFocus() || focusLostAt >= requestedAt)) return@post
                            bitmap = captured
                            capturedWindow = surface
                            capturedAt = android.os.SystemClock.uptimeMillis()
                            // The redraw below is the same opening, not a new one: a still surface
                            // (volume panel) drawn only now must not throw this frame away again.
                            lastUseAt = capturedAt
                            stale = false
                            KyantGlass.invalidateTracked()
                        }
                    }
                } catch (t: Throwable) {
                    if (++failures >= MAX_FAILURES) XposedBridge.log("OriginKyantGlass: backdrop capture unavailable: $t")
                } finally {
                    inFlight = false
                }
            }
        }

        private fun capture(exclude: Any, scale: Float): android.graphics.Bitmap? {
            val sc = Class.forName("android.view.SurfaceControl")
            val builderCls = Class.forName("android.window.ScreenCapture\$CaptureArgs\$Builder")
            val builder = builderCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            builderCls.getMethod("setFrameScale", java.lang.Float.TYPE).invoke(builder, scale)
            val layers = java.lang.reflect.Array.newInstance(sc, 1).also { java.lang.reflect.Array.set(it, 0, exclude) }
            builderCls.getMethod("setExcludeLayers", layers.javaClass).invoke(builder, layers)
            val args = builderCls.getMethod("build").invoke(builder)
            val screenCapture = Class.forName("android.window.ScreenCapture")
            val listener = screenCapture.getMethod("createSyncCaptureListener").invoke(null)
            val wmService = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
            val wmCls = Class.forName("android.view.IWindowManager")
            val argsCls = Class.forName("android.window.ScreenCapture\$CaptureArgs")
            val listenerCls = Class.forName("android.window.ScreenCapture\$ScreenCaptureListener")
            wmCls.getMethod("captureDisplay", Integer.TYPE, argsCls, listenerCls).invoke(wmService, 0, args, listener)
            val buffer = listener.javaClass.getMethod("getBuffer").invoke(listener) ?: return null
            return buffer.javaClass.getMethod("asBitmap").invoke(buffer) as? android.graphics.Bitmap
        }
    }

    private fun findField(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null) {
            runCatching { return c.getDeclaredField(name).apply { isAccessible = true } }
            c = c.superclass
        }
        return null
    }


    private val outline = Outline()

    private fun outlineRadius(view: View): Float? = runCatching {
        val provider = view.outlineProvider ?: return null
        outline.setEmpty()
        provider.getOutline(view, outline)
        outline.radius.takeIf { it > 0f }
    }.getOrNull()
}
