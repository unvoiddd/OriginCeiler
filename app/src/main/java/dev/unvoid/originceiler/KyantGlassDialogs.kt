package dev.unvoid.originceiler

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OriginUI dialogs (VDialog: the alerts of vivo's system apps) as iOS 26 alerts:
 *  - the plate keeps vivo's real-time blur (BackgroundBlurDrawable) with a translucent tint for
 *    the theme and a glass rim;
 *  - the buttons are always stacked: full width, 52 dp, 10 dp apart, on rounded capsules, white
 *    text in the dark theme and black in the light one; a red (destructive) button gets a red
 *    capsule.
 * The library is bundled into each app, so the hooks go on the app's own class loader.
 */
object KyantGlassDialogs {
    private const val PANEL = "com.originui.widget.dialog.VCustomRoundRectLayout"
    private const val BUTTON_BAR = "com.originui.widget.dialog.VDialogButtonPanel"
    private const val BUTTON = "com.originui.widget.button.VButton"

    private val hookedLoaders = Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    private val styledButtons = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val listenerAdded = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        val panel = runCatching { Class.forName(PANEL, false, classLoader) }.getOrNull() ?: return
        synchronized(hookedLoaders) { if (!hookedLoaders.add(classLoader)) return }
        val after = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                ensureConfig(view)
                if (on()) stylePanel(view)
            }
        }
        XposedBridge.hookAllMethods(panel, "onAttachedToWindow", after)
        // vivo re-sets the plate's outline and clipping on layout: keep the rounded clip.
        XposedBridge.hookAllMethods(View::class.java, "setOutlineProvider", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!plates.containsKey(view) || !on()) return
                if (param.args[0] !is PlateOutline) param.args[0] = PlateOutline()
            }
        })
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!plates.containsKey(view) || !on()) return
                param.args[0] = wrap(view, param.args[0] as? Drawable)
            }
        })
        // Without its blur drawable vivo paints the plate with a native material over the glass.
        val material = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!plates.containsKey(view) || !on()) return
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
        XposedBridge.hookAllMethods(View::class.java, "setClipToOutline", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (plates.containsKey(view) && on()) param.args[0] = true
            }
        })
        XposedBridge.hookAllMethods(panel, "updateBlur", after)
        runCatching { Class.forName(BUTTON_BAR, false, classLoader) }.getOrNull()?.let(::hookButtonBar)
        runCatching { Class.forName(BUTTON, false, classLoader) }.getOrNull()?.let(::hookButton)
    }

    private fun on() = KyantGlass.config.on(KyantGlass.KEY_DIALOGS)

    private fun ensureConfig(view: View) {
        KyantGlass.ensureConfig(view.context)
        if (listenerAdded.compareAndSet(false, true)) KyantGlass.onConfigChanged {
            synchronized(plates) { plates.keys.toList() }.forEach {
                if (on()) {
                    // vivo's blur plate takes the corner as a value: hand it over again.
                    val bg = it.background
                    if (bg != null && bg !is PlateLayers) it.background = bg
                }
                it.invalidateOutline()
                it.invalidate()
            }
        }
    }

    private fun dark(view: View) =
        (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun dp(view: View, v: Float) = v * view.resources.displayMetrics.density

    // ---------------------------------------------------------------- plate

    private val plates = Collections.synchronizedMap(WeakHashMap<View, KyantGlass.GlassDrawable>())

    /**
     * The plate: vivo's real-time blur (tinted for the theme) underneath, the liquid glass over
     * it. Where the app may capture the screen the glass refracts what is behind the dialog and
     * covers the blur; elsewhere it adds tint, edge glow and rim over the blur.
     */
    private fun stylePanel(panel: View) {
        val dark = dark(panel)
        panelRadii.getOrPut(panel) { originalRadius(panel) }
        val glass = plates.getOrPut(panel) {
            KyantGlass.GlassDrawable(
                panel, KyantGlass.KEY_DIALOGS,
                backdrop = { v -> KyantGlassHook.ShadeBackdrop.get(v) },
                dim = true,
                // The alert's tint over the glass: keeps the text readable over a busy backdrop.
                surface = { v -> if (dark(v)) Color.argb(120, 28, 28, 30) else Color.argb(135, 250, 250, 252) },
                radius = { v, w, h -> panelRadius(v).coerceAtMost(minOf(w, h) / 2f) }
            )
        }
        KyantGlass.track(panel)
        // The plate's shape no longer comes from vivo's blur drawable: clip to the same corners.
        if (panel.outlineProvider !is PlateOutline) panel.outlineProvider = PlateOutline()
        panel.clipToOutline = true
        val current = panel.background
        if (!(current is PlateLayers && current.getDrawable(1) === glass)) panel.background = current
        clearMaterial(panel)
        panel.post { lightenDim(panel) }
        styleTexts(panel, dark)
    }

    /**
     * Every background vivo gives a styled plate (it re-creates its blur drawable on layout and
     * theme changes) goes under the glass, with the theme tint and the plate's corners.
     */
    private fun wrap(panel: View, incoming: Drawable?): Drawable? {
        if (incoming is PlateLayers) return incoming
        val glass = plates[panel] ?: return incoming
        val dark = dark(panel)
        val radius = panelRadius(panel)
        val isBlur = incoming != null && incoming.javaClass.simpleName == "BackgroundBlurDrawable"
        if (isBlur && !canCapture(panel)) {
            // No screen capture here: vivo's real-time blur stays the plate (it only blurs as
            // the view's own background), tinted and rounded; the glass rim goes on top.
            val tint = if (dark) Color.argb(110, 38, 38, 40) else Color.argb(130, 246, 246, 248)
            val cls = incoming!!.javaClass
            runCatching { cls.getMethod("setColor", Integer.TYPE).invoke(incoming, tint) }
            runCatching { cls.getMethod("setBlurRadius", Integer.TYPE).invoke(incoming, dp(panel, 30f).toInt()) }
            runCatching { cls.getMethod("setCornerRadius", java.lang.Float.TYPE).invoke(incoming, radius) }
            rimFor(panel)
            blurOwners[incoming] = panel
            hookBlurCorners(cls)
            return incoming
        }
        // Liquid glass over what is behind the dialog; a faint tint underneath until the first
        // capture arrives.
        val under = GradientDrawable().apply {
            setColor(if (dark) Color.argb(150, 30, 30, 32) else Color.argb(170, 244, 244, 246))
            cornerRadius = radius
        }
        if (panel.foreground is RimOnly) panel.foreground = null
        return PlateLayers(panel, under, glass)
    }

    /**
     * vivo darkens the screen heavily behind a dialog, and that darkening is part of what the
     * glass refracts: keep it light, as on iOS, so the glass shows what is behind.
     */
    private fun lightenDim(panel: View) {
        val root = panel.rootView ?: return
        val lp = root.layoutParams as? android.view.WindowManager.LayoutParams ?: return
        val target = if (dark(panel)) 0.28f else 0.18f
        if (lp.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND == 0 || lp.dimAmount <= target) return
        lp.dimAmount = target
        runCatching {
            val wm = panel.context.getSystemService(android.view.WindowManager::class.java)
            wm.updateViewLayout(root, lp)
        }
    }

    private fun clearMaterial(view: View) {
        runCatching { View::class.java.getDeclaredMethod("clearMaterial").apply { isAccessible = true }.invoke(view) }
    }

    /** Only apps holding READ_FRAME_BUFFER (launcher, Settings, SystemUI …) can capture the screen. */
    private fun canCapture(view: View): Boolean =
        KyantGlassHook.ShadeBackdrop.available &&
            view.context.checkSelfPermission("android.permission.READ_FRAME_BUFFER") == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** vivo's blur plates of styled dialogs → their dialog. */
    private val blurOwners = Collections.synchronizedMap(WeakHashMap<Drawable, View>())
    private val blurCornersHooked = AtomicBoolean(false)

    /**
     * vivo sets its blur plate's corner itself after handing it over (from its fillet setting):
     * on a styled dialog the corner is always the dialog corner setting.
     */
    private fun hookBlurCorners(cls: Class<*>) {
        if (!blurCornersHooked.compareAndSet(false, true)) return
        XposedBridge.hookAllMethods(cls, "setCornerRadius", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val panel = blurOwners[param.thisObject as? Drawable ?: return] ?: return
                if (!on() || KyantGlass.config[KyantGlass.KEY_DIALOG_RADIUS] <= 0) return
                val r = panelRadius(panel)
                for (i in param.args.indices) if (param.args[i] is Float) param.args[i] = r
            }
        })
    }

    private fun rimFor(panel: View) {
        if (panel.foreground !is RimOnly) panel.foreground = RimOnly(panel)
    }

    /** Edge glow and rim highlight over vivo's own blur plate. */
    private class RimOnly(view: View) : Drawable() {
        private val ref = java.lang.ref.WeakReference(view)
        private val renderer = KyantGlass.Renderer()
        private val rect = RectF()
        private val radii = FloatArray(4)

        override fun draw(canvas: Canvas) {
            val view = ref.get() ?: return
            if (!on()) return
            rect.set(bounds)
            radii.fill(panelRadius(view).coerceAtMost(minOf(rect.width(), rect.height()) / 2f))
            renderer.drawRimOnly(canvas, view, rect, radii)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Standard title / message: the alert's text colours (custom layouts keep theirs). */
    private fun styleTexts(root: View, dark: Boolean) {
        val title = if (dark) Color.WHITE else Color.BLACK
        val message = if (dark) Color.argb(190, 255, 255, 255) else Color.argb(170, 0, 0, 0)
        fun walk(v: View, depth: Int) {
            if (depth > 12) return
            if (v is TextView && v.id != View.NO_ID) {
                when (idName(v)) {
                    "alertTitle", "title_template_title", "originui_dialog_title", "title" -> if (!insideButton(v)) v.setTextColor(title)
                    "message", "originui_dialog_message", "description" -> v.setTextColor(message)
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(root, 0)
    }

    private fun insideButton(view: View): Boolean {
        var v = view.parent as? View
        var depth = 0
        while (v != null && depth < 4) {
            if (v.javaClass.name == BUTTON) return true
            v = v.parent as? View
            depth++
        }
        return false
    }

    private val outline = Outline()
    private val panelRadii = Collections.synchronizedMap(WeakHashMap<View, Float>())

    /** The corner setting (dp, 0 = vivo's own fillet). */
    private fun panelRadius(panel: View): Float {
        val custom = KyantGlass.config[KyantGlass.KEY_DIALOG_RADIUS]
        if (custom > 0) return dp(panel, custom.toFloat())
        return panelRadii[panel] ?: originalRadius(panel)
    }

    /** vivo's corner (fillet setting): its outline, else its blur drawable's corner, else 32 dp. */
    private fun originalRadius(panel: View): Float {
        val fromOutline = runCatching {
            outline.setEmpty()
            panel.outlineProvider?.getOutline(panel, outline)
            outline.radius
        }.getOrDefault(0f)
        if (fromOutline > 0f) return fromOutline
        val bg = panel.background
        val fromBlur = runCatching {
            var c: Class<*>? = bg?.javaClass
            var f: java.lang.reflect.Field? = null
            while (c != null && f == null) {
                f = runCatching { c!!.getDeclaredField("mCornerRadiusTL") }.getOrNull()
                c = c.superclass
            }
            f?.apply { isAccessible = true }?.getFloat(bg) ?: 0f
        }.getOrDefault(0f)
        return if (fromBlur > 0f) fromBlur else dp(panel, 32f)
    }

    /** vivo's blur under the glass; its outline is the plate's rounded rect (for BACKGROUND outlines). */
    private class PlateLayers(private val panel: View, private val under: Drawable, glass: Drawable) :
        android.graphics.drawable.LayerDrawable(arrayOf(under, glass)) {
        override fun getOutline(outline: Outline) {
            outline.setRoundRect(bounds, panelRadius(panel))
        }

        override fun draw(canvas: Canvas) {
            (under as? GradientDrawable)?.cornerRadius = panelRadius(panel)
            super.draw(canvas)
        }
    }

    private class PlateOutline : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, panelRadius(view))
        }
    }

    // ---------------------------------------------------------------- buttons

    private const val BUTTON_HEIGHT_DP = 48f
    private const val BUTTON_GAP_DP = 10f
    private const val SIDE_DP = 16f

    private val setMeasured by lazy {
        View::class.java.getDeclaredMethod("setMeasuredDimension", Integer.TYPE, Integer.TYPE).apply { isAccessible = true }
    }

    /**
     * vivo picks a row or a column for the buttons in onMeasure: with the switch on the bar is
     * measured here instead, always a column of equal capsules, and LinearLayout lays it out.
     */
    private fun hookButtonBar(cls: Class<*>) {
        XposedBridge.hookAllMethods(cls, "onMeasure", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val bar = param.thisObject as? LinearLayout ?: return
                if (!KyantGlass.configLoaded) KyantGlass.ensureConfig(bar.context)
                if (!on()) return
                val buttons = (0 until bar.childCount).map { bar.getChildAt(it) }.filter { it.visibility != View.GONE }
                if (buttons.isEmpty() || buttons.any { it.javaClass.name != BUTTON }) return
                // Only real changes: a layout request from inside measure would loop.
                if (bar.orientation != LinearLayout.VERTICAL) bar.orientation = LinearLayout.VERTICAL
                // vivo's divider between the buttons would add to the column measured here.
                if (bar.showDividers != LinearLayout.SHOW_DIVIDER_NONE) bar.showDividers = LinearLayout.SHOW_DIVIDER_NONE
                val widthSpec = param.args[0] as Int
                val heightSpec = param.args[1] as Int
                val width = View.MeasureSpec.getSize(widthSpec)
                val side = dp(bar, SIDE_DP).toInt()
                val gap = dp(bar, BUTTON_GAP_DP).toInt()
                val height = dp(bar, BUTTON_HEIGHT_DP).toInt()
                val top = bar.paddingTop.coerceAtMost(gap)
                if (bar.paddingLeft != side || bar.paddingRight != side || bar.paddingBottom != side || bar.paddingTop != top) {
                    bar.setPadding(side, top, side, side)
                }
                var total = bar.paddingTop + bar.paddingBottom
                buttons.forEachIndexed { i, b ->
                    val current = b.layoutParams as? LinearLayout.LayoutParams
                    val topMargin = if (i == 0) 0 else gap
                    val lp = if (current != null && current.width == ViewGroup.LayoutParams.MATCH_PARENT && current.height == height &&
                        current.weight == 0f && current.leftMargin == 0 && current.rightMargin == 0 && current.topMargin == topMargin &&
                        current.bottomMargin == 0) current
                    else (current ?: LinearLayout.LayoutParams(0, 0)).also {
                        it.width = ViewGroup.LayoutParams.MATCH_PARENT
                        it.height = height
                        it.weight = 0f
                        it.setMargins(0, topMargin, 0, 0)
                        b.layoutParams = it
                    }
                    val w = (width - side * 2).coerceAtLeast(0)
                    b.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    total += height + lp.topMargin
                    styleButton(b)
                }
                setMeasured.invoke(bar, View.resolveSize(width, widthSpec), View.resolveSize(total, heightSpec))
                param.result = null
            }
        })
    }

    private fun hookButton(cls: Class<*>) {
        // vivo's own plate (stroke / fill) is painted in onDraw: styled buttons wear the capsule.
        XposedBridge.hookAllMethods(cls, "onDraw", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val button = param.thisObject as? View ?: return
                if (!styledButtons.containsKey(button) || !on()) return
                keepTextColor(button)
                param.result = null
            }
        })
    }

    private val destructive = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    private fun styleButton(button: View) {
        val dark = dark(button)
        val text = buttonText(button)
        if (!styledButtons.containsKey(button)) {
            val original = text?.currentTextColor ?: Color.WHITE
            destructive[button] = Color.red(original) > 170 && Color.green(original) < 120 && Color.blue(original) < 120
        }
        val red = destructive[button] == true
        val fill = when {
            red && dark -> Color.argb(150, 255, 59, 48)
            red -> Color.argb(46, 255, 59, 48)
            dark -> Color.argb(46, 255, 255, 255)
            else -> Color.argb(20, 0, 0, 0)
        }
        val bg = button.background as? GradientDrawable
        if (bg == null || bg.color?.defaultColor != fill || !styledButtons.containsKey(button)) {
            button.background = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(button, BUTTON_HEIGHT_DP) / 2f
            }
        }
        styledButtons[button] = true
        keepTextColor(button)
    }

    private fun keepTextColor(button: View) {
        val text = buttonText(button) ?: return
        val dark = dark(button)
        val red = destructive[button] == true
        val color = when {
            dark -> Color.WHITE
            red -> Color.rgb(255, 59, 48)
            else -> Color.BLACK
        }
        if (text.currentTextColor != color) text.setTextColor(color)
    }

    private fun buttonText(button: View): TextView? =
        runCatching { button.javaClass.getMethod("getButtonTextView").invoke(button) as? TextView }.getOrNull()

    private fun idName(view: View): String? =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
}
