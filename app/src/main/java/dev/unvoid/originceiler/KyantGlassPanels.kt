package dev.unvoid.originceiler

import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [KyantGlass] on vivo's floating panels:
 *  - launcher: the long-press app menu (CustomDeepShortcutView cards) and, separately, the
 *    full-screen blur behind it (PopupBgBlurView, a vivo material on a screen-sized view);
 *  - com.vivo.upslide: the smart sidebar's app strip and its window manager (the small-window
 *    app panel), both drawn by com.vivo.card's side dock plugin with vivo materials.
 *
 * vivo paints all of these through the hidden View.setMaterial* facades: those calls are dropped
 * on the surfaces that wear glass, and their background becomes a [KyantGlass.GlassDrawable].
 */
object KyantGlassPanels {
    private const val MENU_CARD = "com.bbk.launcher2.launcherBasic.shortcut.CustomDeepShortcutView"
    private const val MENU_BG_BLUR = "com.bbk.launcher2.launcherBasic.popup.PopupBgBlurView"

    /** Side dock plugin ids: the sidebar strip and the window manager panel. */
    private const val SIDEBAR_PLATE = "hotseat_app_area"
    private val SIDEBAR_ROOTS = setOf("hotseat_container", "hotseat_app_area_container", SIDEBAR_PLATE)
    private const val WINDOWS_PLATE = "sidedock_allbox_bg"
    private val WINDOWS_ROOTS = setOf("allbox_container", "sidedock_allbox", WINDOWS_PLATE)

    private val launcherInstalled = AtomicBoolean(false)
    private val sidebarInstalled = AtomicBoolean(false)
    private val listenerAdded = AtomicBoolean(false)

    /** Glass surface → its drawable and the setting that switches it. */
    private val glass = Collections.synchronizedMap(WeakHashMap<View, android.graphics.drawable.Drawable>())
    private val glassKey = Collections.synchronizedMap(WeakHashMap<View, String>())
    private val thinnedDividers = Collections.synchronizedMap(WeakHashMap<View, Float>())
    /** What a glass surface wore before (restored when its switch goes off). */
    private val originals = Collections.synchronizedMap(WeakHashMap<View, android.graphics.drawable.Drawable?>())

    // ---------------------------------------------------------------- launcher app menu

    fun installLauncher() {
        if (!launcherInstalled.compareAndSet(false, true)) return
        hookMaterials { view ->
            when (view.javaClass.name) {
                MENU_CARD -> KyantGlass.config.on(KyantGlass.KEY_APP_MENU)
                MENU_BG_BLUR -> KyantGlass.config.on(KyantGlass.KEY_APP_MENU_NO_BLUR)
                else -> glassKey[view] == KyantGlass.KEY_RECENTS_CLEAR && KyantGlass.config.on(KyantGlass.KEY_RECENTS_CLEAR)
            }
        }
        hookBackgrounds()
        XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                when (view.javaClass.name) {
                    MENU_CARD -> {
                        ensureConfig(view)
                        if (KyantGlass.config.on(KyantGlass.KEY_APP_MENU)) applyMenuCard(view)
                    }
                    MENU_BG_BLUR -> {
                        ensureConfig(view)
                        if (KyantGlass.config.on(KyantGlass.KEY_APP_MENU_NO_BLUR)) clearMaterial(view)
                    }
                    else -> if (isRecentsClear(view)) {
                        ensureConfig(view)
                        if (KyantGlass.config.on(KyantGlass.KEY_RECENTS_CLEAR)) applyRecentsClear(view)
                    }
                }
            }
        })
        XposedBridge.log("OriginKyantGlass: app menu hooks installed")
    }

    private fun applyMenuCard(card: View) {
        apply(card, KyantGlass.KEY_APP_MENU, backdrop = { v ->
            runCatching { ScreenBackdropSource.getBackdrop(v.context) }.getOrNull()
                ?: KyantGlassHook.ShadeBackdrop.get(v, requireFocus = true)
        })
        // The thick group separator is an opaque grey band: let the glass show through it.
        card.post { thinDividers(card, true) }
    }

    // ---------------------------------------------------------------- recents clear button

    private const val RECENTS_CLEAR_HOST = "com.vivo.recents.views.RecentsHoverFrameLayout"

    /** The round "clear all" button under the recent apps (its plate: #action_container). */
    private fun isRecentsClear(view: View): Boolean =
        view.id != View.NO_ID && (view.parent as? View)?.javaClass?.name == RECENTS_CLEAR_HOST && idName(view) == "action_container"

    private fun applyRecentsClear(button: View) {
        // The button takes its size from vivo's round background; the glass has none.
        val original = if (glass[button] == null) button.background else originals[button]
        original?.let {
            if (it.intrinsicWidth > 0) button.minimumWidth = it.intrinsicWidth
            if (it.intrinsicHeight > 0) button.minimumHeight = it.intrinsicHeight
        }
        // Recents draw their own blur behind the button, which no capture can see: the glass
        // stays clear over it (tint, rim, touch light) instead of refracting the raw wallpaper.
        val drawable = glass.getOrPut(button) { ClearGlass(button) }
        glassKey[button] = KyantGlass.KEY_RECENTS_CLEAR
        KyantGlass.track(button)
        clearMaterial(button)
        if (button.background !== drawable && !originals.containsKey(button)) originals[button] = button.background
        if (button.background !== drawable) button.background = drawable
        // vivo's own light-effect layer over the plate.
        button.foreground = null
    }

    @Volatile private var recentsFrame: android.graphics.Bitmap? = null
    @Volatile private var recentsCapturing = false
    private val shownWatchers = Collections.newSetFromMap(WeakHashMap<android.view.ViewTreeObserver, Boolean>())
    private var recentsShown = false

    /** Recents closed (the button no longer shown): the next opening captures again. */
    private fun watchShown(view: View) {
        val vto = view.viewTreeObserver
        if (!vto.isAlive || !shownWatchers.add(vto)) return
        vto.addOnPreDrawListener {
            val shown = view.isShown && view.isAttachedToWindow
            if (recentsShown && !shown) recentsFrame = null
            recentsShown = shown
            true
        }
    }
    private val captureWorker by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "OriginRecentsCapture").apply { isDaemon = true } }
    }

    /**
     * Recents draw a blur of the home screen behind the button, inside the launcher's own
     * window, so no capture that leaves the launcher out can see it. Each time recents open the
     * whole display is captured once, with the button itself hidden for that frame, and the
     * glass refracts that frame.
     */
    private fun recentsBackdrop(view: View): android.graphics.Bitmap? {
        watchShown(view)
        recentsFrame?.let { if (!it.isRecycled) return it }
        if (recentsCapturing) return null
        recentsCapturing = true
        view.transitionAlpha = 0f
        // The hidden frame has to reach the screen before the capture.
        view.postOnAnimation {
            view.postOnAnimation {
                captureWorker.execute {
                    val frame = runCatching { captureDisplay(0.25f) }.getOrNull()?.let { raw ->
                        runCatching {
                            val soft = raw.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            KyantGlass.blurSoftware(soft, 3f).also { soft.recycle(); KyantGlass.markPreBlurred(it) }
                        }.getOrDefault(raw)
                    }
                    view.post {
                        recentsFrame = frame
                        recentsCapturing = false
                        view.animate().cancel()
                        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 140L
                            addUpdateListener { view.transitionAlpha = it.animatedValue as Float }
                            start()
                        }
                        view.invalidate()
                    }
                }
            }
        }
        return null
    }

    /** The whole display (scaled), nothing left out. */
    private fun captureDisplay(scale: Float): android.graphics.Bitmap? {
        val builderCls = Class.forName("android.window.ScreenCapture\$CaptureArgs\$Builder")
        val builder = builderCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        builderCls.getMethod("setFrameScale", java.lang.Float.TYPE).invoke(builder, scale)
        val args = builderCls.getMethod("build").invoke(builder)
        val screenCapture = Class.forName("android.window.ScreenCapture")
        val listener = screenCapture.getMethod("createSyncCaptureListener").invoke(null)
        val wmService = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
        val argsCls = Class.forName("android.window.ScreenCapture\$CaptureArgs")
        val listenerCls = Class.forName("android.window.ScreenCapture\$ScreenCaptureListener")
        Class.forName("android.view.IWindowManager").getMethod("captureDisplay", Integer.TYPE, argsCls, listenerCls).invoke(wmService, 0, args, listener)
        val buffer = listener.javaClass.getMethod("getBuffer").invoke(listener) ?: return null
        return buffer.javaClass.getMethod("asBitmap").invoke(buffer) as? android.graphics.Bitmap
    }

    /** Glass over the recents' blur: lens, light tint, rim and the light under the finger (no stretch). */
    private class ClearGlass(view: View) : android.graphics.drawable.Drawable() {
        private val ref = java.lang.ref.WeakReference(view)
        private val renderer = KyantGlass.Renderer()
        private val rect = android.graphics.RectF()
        private val radii = FloatArray(4)

        override fun draw(canvas: android.graphics.Canvas) {
            val view = ref.get() ?: return
            if (!KyantGlass.config.on(KyantGlass.KEY_RECENTS_CLEAR)) return
            rect.set(bounds)
            val short = minOf(rect.width(), rect.height())
            rect.set(rect.centerX() - short / 2f, rect.centerY() - short / 2f, rect.centerX() + short / 2f, rect.centerY() + short / 2f)
            radii.fill(short / 2f)
            renderer.draw(canvas, view, rect, radii, recentsBackdrop(view), surfaceColor = android.graphics.Color.argb(30, 255, 255, 255))
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun thinDividers(card: View, on: Boolean) {
        val group = card as? ViewGroup ?: return
        val thick = 4f * card.resources.displayMetrics.density
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (!child.javaClass.simpleName.contains("Divider") || child.height < thick) continue
            if (on) {
                if (!thinnedDividers.containsKey(child)) thinnedDividers[child] = child.alpha
                child.alpha = 0.3f
            } else {
                thinnedDividers.remove(child)?.let { child.alpha = it }
            }
        }
    }

    // ---------------------------------------------------------------- sidebar / window manager

    fun installSidebar() {
        if (!sidebarInstalled.compareAndSet(false, true)) return
        runCatching { KyantGlassTouch.install() }.onFailure { XposedBridge.log("OriginKyantGlass: touch: $it") }
        hookMaterials { view -> sideDockKey(view)?.let { KyantGlass.config.on(it) } == true }
        hookBackgrounds()
        XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view.id == View.NO_ID || view !is ViewGroup) return
                val key = when (idName(view)) {
                    SIDEBAR_PLATE -> KyantGlass.KEY_SIDEBAR
                    WINDOWS_PLATE -> KyantGlass.KEY_WINDOW_MANAGER
                    else -> return
                }
                ensureConfig(view)
                if (KyantGlass.config.on(key)) applySideDock(view, key)
            }
        })
        XposedBridge.log("OriginKyantGlass: sidebar hooks installed")
    }

    private fun applySideDock(plate: View, key: String) {
        apply(plate, key, backdrop = { v -> KyantGlassHook.ShadeBackdrop.get(v) }, dim = true)
        // The plugin paints the material on the plate's inner views too (tip / copilot areas).
        plate.post { clearSubtree(plate, 0) }
    }

    private fun clearSubtree(view: View, depth: Int) {
        clearMaterial(view)
        if (depth >= 3 || view !is ViewGroup) return
        for (i in 0 until view.childCount) clearSubtree(view.getChildAt(i), depth + 1)
    }

    /** The switch for a view of the side dock plugin: itself or a parent is one of its panels. */
    private fun sideDockKey(view: View): String? {
        var v: View? = view
        var depth = 0
        while (v != null && depth < 6) {
            if (v.id != View.NO_ID) {
                val name = idName(v)
                if (name in SIDEBAR_ROOTS) return KyantGlass.KEY_SIDEBAR
                if (name in WINDOWS_ROOTS) return KyantGlass.KEY_WINDOW_MANAGER
            }
            v = v.parent as? View
            depth++
        }
        return null
    }

    // ---------------------------------------------------------------- shared

    private fun ensureConfig(view: View) {
        KyantGlass.ensureConfig(view.context)
        if (listenerAdded.compareAndSet(false, true)) KyantGlass.onConfigChanged { refresh() }
    }

    private fun apply(
        view: View,
        key: String,
        backdrop: (View) -> android.graphics.Bitmap?,
        dim: Boolean = false
    ) {
        val drawable = glass.getOrPut(view) {
            KyantGlass.GlassDrawable(
                view, key,
                backdrop = backdrop,
                dim = dim,
                radius = { v, w, h -> panelRadius(v, w, h) }
            )
        }
        glassKey[view] = key
        KyantGlass.track(view)
        clearMaterial(view)
        if (view.background !== drawable && !originals.containsKey(view)) originals[view] = view.background
        if (view.background !== drawable) view.background = drawable
        view.invalidate()
    }

    private fun refresh() {
        synchronized(glass) { glass.entries.toList() }.forEach { (view, drawable) ->
            val key = glassKey[view] ?: return@forEach
            val on = KyantGlass.config.on(key)
            if (on && view.background !== drawable) {
                clearMaterial(view)
                if (!originals.containsKey(view)) originals[view] = view.background
                view.background = drawable
            } else if (!on && view.background === drawable) {
                // vivo puts its material back the next time the panel opens.
                view.background = originals.remove(view)
            }
            if (view.javaClass.name == MENU_CARD) thinDividers(view, on)
            view.invalidate()
        }
    }

    /** vivo re-sets plain / blur backgrounds on its panels (night mode, state changes). */
    private fun hookBackgrounds() {
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                val drawable = glass[view] ?: return
                val key = glassKey[view] ?: return
                if (param.args[0] !== drawable && KyantGlass.config.on(key)) param.args[0] = drawable
            }
        })
    }

    /** Drops every View.setMaterial* call for which [block] is true. */
    private fun hookMaterials(block: (View) -> Boolean) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!KyantGlass.configLoaded) KyantGlass.ensureConfig(view.context)
                if (!runCatching { block(view) }.getOrDefault(false)) return
                param.result = defaultResult(param.method as? Method)
            }
        }
        View::class.java.declaredMethods
            .filter { it.name.startsWith("setMaterial") && !Modifier.isStatic(it.modifiers) }
            .forEach { runCatching { XposedBridge.hookMethod(it, hook) } }
    }

    private fun defaultResult(method: Method?): Any? = when (method?.returnType) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Float.TYPE -> 0f
        java.lang.Long.TYPE -> 0L
        else -> null
    }

    private val clearMaterialMethod by lazy {
        runCatching { View::class.java.getDeclaredMethod("clearMaterial").apply { isAccessible = true } }.getOrNull()
    }

    private fun clearMaterial(view: View) {
        runCatching { clearMaterialMethod?.invoke(view) }
    }

    private fun idName(view: View): String? =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()

    private val outline = Outline()

    /** The panel's own clip outline (its or a parent's covering it), else ~24 dp. */
    private fun panelRadius(view: View, w: Float, h: Float): Float {
        val half = minOf(w, h) / 2f
        var v: View? = view
        var depth = 0
        while (v != null && depth < 3) {
            if (v.width >= view.width - 2 && v.height >= view.height - 2) {
                outlineRadius(v)?.let { return it.coerceAtMost(half) }
            }
            v = v.parent as? View
            depth++
        }
        return (24f * view.resources.displayMetrics.density).coerceAtMost(half)
    }

    private fun outlineRadius(view: View): Float? = runCatching {
        val provider = view.outlineProvider ?: return null
        outline.setEmpty()
        provider.getOutline(view, outline)
        outline.radius.takeIf { it > 0f }
    }.getOrNull()
}
