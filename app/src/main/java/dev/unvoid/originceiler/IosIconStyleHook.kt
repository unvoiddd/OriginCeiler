package dev.unvoid.originceiler

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * BBK Launcher side of the iOS icon looks ([IosIconStyles]).
 *
 * Icons are swapped where the launcher paints them (its bitmap drawable o7.i0, drawn from
 * ItemIcon.onDraw), not in the icon cache: a change repaints the home screen at once, without
 * the restart and database rebuild a cache swap needs. Clear and Tinted icons sit on Kyant
 * liquid glass refracting the wallpaper. The edit-mode "Interaction Cube" button becomes
 * "Icons" and opens [IosIconPanel].
 */
object IosIconStyleHook {
    private const val LAUNCHER = "com.bbk.launcher2"
    private const val FUNCTION_UNIT = "com.bbk.launcher2.ui.menu.FunctionUnitView"
    private const val MENU_ROOT = "com.bbk.launcher2.ui.menu.MenuRootContainer"

    private val installed = AtomicBoolean(false)
    private val owner = ThreadLocal<View>()
    private val icons = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val renderers = Collections.synchronizedMap(WeakHashMap<View, KyantGlass.Renderer>())
    /** Launcher icon bitmap → its app, for drawables painted outside ItemIcon (folder previews). */
    private val bitmapApps = Collections.synchronizedMap(WeakHashMap<Bitmap, ComponentName>())
    /**
     * Composed icon-cache bitmap → the launcher's own one. The cache keeps our composed bitmaps
     * after a switch back to Default (or turning the feature off), so those paint the original.
     */
    private val originals = Collections.synchronizedMap(WeakHashMap<Bitmap, Bitmap>())
    private val handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val plate = RectF()
    private val glassRect = RectF()
    private val radii = FloatArray(4)
    private val inverse = Matrix()
    private var interactionId = 0
    @Volatile private var itemIconClass: Class<*>? = null

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        runCatching { hookIconDrawing(classLoader) }.onFailure { XposedBridge.log("OriginIosIcons: drawing: $it") }
        runCatching { hookMenu(classLoader) }.onFailure { XposedBridge.log("OriginIosIcons: menu: $it") }
        // Any icon look, stock icons included: needed whenever the icon size is above 100.
        runCatching { hookSurfaceIcon(classLoader) }.onFailure { XposedBridge.log("OriginIosIcons: surface icon: $it") }
        XposedBridge.log("OriginIosIcons: launcher hooks installed")
    }

    // ---------------------------------------------------------------- icons

    private fun hookIconDrawing(classLoader: ClassLoader) {
        val names = LauncherCompat.names(classLoader)
        val itemIcon = Class.forName(names.itemIcon, false, classLoader)
        itemIconClass = itemIcon
        val drawable = Class.forName(names.iconDrawable, false, classLoader)
        val scope = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                owner.set(view)
                if (icons.put(view, true) == null) prepare(view.context)
            }

            override fun afterHookedMethod(param: MethodHookParam) = owner.remove()
        }
        // ItemIcon's own icon painting: O4 / F4 on OriginOS 6, R2 / X2 / O2 on OriginOS 7 - all
        // (Canvas, Drawable, ColorFilter).
        val paintSignature = listOf(Canvas::class.java, android.graphics.drawable.Drawable::class.java, android.graphics.ColorFilter::class.java)
        itemIcon.declaredMethods.filter {
            (it.name in setOf("O4", "F4") && it.parameterTypes.size == 3 && it.parameterTypes[0] == Canvas::class.java) ||
                it.parameterTypes.toList() == paintSignature
        }.forEach { XposedBridge.hookMethod(it, scope) }

        // draw(): the icon as the TextView's top compound drawable (the usual path);
        // m(): ItemIcon's own O4 / F4 painting during its animations.
        val swap = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val icon = param.thisObject as? BitmapDrawable ?: return
                if (!prepared.get()) (icon.callback as? View)?.let { prepare(it.context) }
                val canvas = param.args.firstOrNull() as? Canvas ?: return
                if (drawOriginal(canvas, icon)) { param.result = null; return }
                if (!IosIconStyles.active) return
                runCatching { if (draw(canvas, icon, drawable.isInstance(icon))) param.result = null }
                    .onFailure { XposedBridge.log("OriginIosIcons: draw: $it") }
            }
        }
        drawable.declaredMethods.filter {
            (it.name == "m" || it.name == "draw") && it.parameterTypes.size == 1 && it.parameterTypes[0] == Canvas::class.java
        }.forEach { XposedBridge.hookMethod(it, swap) }

        runCatching { hookFloatingIcons(classLoader) }.onFailure { XposedBridge.log("OriginIosIcons: floating: $it") }

        // Large icons go without labels: ItemIcon's text layout is skipped while it draws.
        itemIcon.declaredMethods.filter { it.name == "onDraw" && it.parameterTypes.size == 1 }.forEach {
            XposedBridge.hookMethod(it, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (IosIconStyles.enabled && IosIconStyles.large) hideLabel.set(true)
                }

                override fun afterHookedMethod(param: MethodHookParam) = hideLabel.remove()
            })
        }
        val skipText = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (hideLabel.get() == true) param.result = null
            }
        }
        listOf(android.text.Layout::class.java, android.text.BoringLayout::class.java).forEach { type ->
            type.declaredMethods.filter { it.name == "draw" && it.parameterTypes.firstOrNull() == Canvas::class.java }
                .forEach { XposedBridge.hookMethod(it, skipText) }
        }
    }

    private val hideLabel = ThreadLocal<Boolean>()

    private fun stock(context: Context?): Boolean {
        val c = context ?: appContext ?: return !IosIconStyles.active
        return IosIconStyles.effectiveStyle(c) == IosIconStyles.DEFAULT
    }

    /** A composed cache bitmap while the look is stock: paint the launcher's own icon instead. */
    private fun drawOriginal(canvas: Canvas, icon: BitmapDrawable): Boolean {
        val bitmap = icon.bitmap ?: return false
        val original = originals[bitmap] ?: return false
        if (original.isRecycled || !stock((icon.callback as? View)?.context)) return false
        val bounds = icon.bounds
        if (bounds.isEmpty) return false
        canvas.drawBitmap(original, null, bounds, icon.paint)
        return true
    }

    /** Floating (launch / close) icons hold the cache bitmap in a field: put the original back. */
    private fun restoreOriginal(view: View, field: java.lang.reflect.Field): Boolean {
        val bitmap = field.get(view) as? Bitmap ?: return false
        val original = originals[bitmap] ?: return false
        if (original.isRecycled || !stock(view.context)) return false
        field.set(view, original)
        return true
    }

    /**
     * App launch / close animations fly a copy of the icon (vivo's floating icon views) and
     * stretch the cached bitmap into the morphing window rect: fine for vivo's full-bleed icons,
     * but our plate then showed as a warped second shape. The flying icon is drawn here as the
     * same square icon (live glass included), centred in the rect, so it simply lands in place.
     */
    private fun hookFloatingIcons(classLoader: ClassLoader) {
        fun field(type: Class<*>, name: String) = runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
        fun onDrawOf(type: Class<*>) = type.declaredMethods.firstOrNull { it.name == "onDraw" && it.parameterTypes.size == 1 }

        // Launch / close icon of the home screen. Its geometry lives in the transition model
        // (i.a, field k): a = where the whole icon bitmap goes, b = insets of the morphing clip,
        // c = the bitmap's source crop. vivo's own field e only gets filled inside the stock
        // onDraw (which is skipped here), so reading it froze our icon on the first frame. The
        // source crop zooms the art from 60 % to full while the window lands ("falls in and
        // folds"): ignored, our icon keeps the size of its home plate the whole way.
        runCatching {
            val type = Class.forName("apptransition.floatingicon.VivoFloatingIconView", false, classLoader)
            val bmpF = field(type, "a")!!
            val modelF = field(type, "k")!!
            val badgeBmpF = field(type, "b")
            val badgeSrcF = field(type, "c")
            val badgeF = field(type, "l")
            val badgePaintF = field(type, "h")
            val model = modelF.type
            val dstF = field(model, "a")!!
            val insetF = field(model, "b")!!
            val badgeModel = badgeF?.type
            val badgeDstF = badgeModel?.let { field(it, "a") }
            val badgeAlphaF = badgeModel?.let { field(it, "b") }
            XposedBridge.hookMethod(onDrawOf(type)!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (restoreOriginal(view, bmpF)) return
                    if (!IosIconStyles.active) return
                    val canvas = param.args[0] as? Canvas ?: return
                    val bitmap = bmpF.get(view) as? Bitmap ?: return
                    val m = modelF.get(view) ?: return
                    val dst = dstF.get(m) as? RectF ?: return
                    val inset = insetF.get(m) as? RectF ?: return
                    if (dst.isEmpty) return
                    floatClip.set(dst.left + inset.left, dst.top + inset.top, dst.right - inset.right, dst.bottom - inset.bottom)
                    if (floatClip.isEmpty) return
                    val component = bitmapApps[bitmap] ?: return
                    runCatching {
                        if (!drawFloating(canvas, view, component, bitmap, dst, floatClip)) return
                        param.result = null
                        // The stock badge (clone / work mark) rides on top as before.
                        val badge = badgeBmpF?.get(view) as? Bitmap ?: return
                        val b = badgeF?.get(view) ?: return
                        val bDst = badgeDstF?.get(b) as? RectF ?: return
                        val bPaint = badgePaintF?.get(view) as? Paint ?: return
                        bPaint.alpha = (badgeAlphaF?.get(b) as? Int) ?: 255
                        canvas.drawBitmap(badge, badgeSrcF?.get(view) as? Rect, bDst, bPaint)
                    }.onFailure { XposedBridge.log("OriginIosIcons: floating draw: $it") }
                }
            })
        }.onFailure { XposedBridge.log("OriginIosIcons: floating icon view: $it") }

        // Recents swipe-home icon: mCurrentRect is where the (cropped) bitmap goes, updated live.
        runCatching {
            val type = Class.forName("com.android.quickstep.VivoLauncherFloatingIconView", false, classLoader)
            val bmpF = field(type, "mIconBmp")!!
            val rectF = field(type, "mCurrentRect")!!
            val pkgF = field(type, "mPackageName")
            val actF = field(type, "mActivityName")
            XposedBridge.hookMethod(onDrawOf(type)!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (restoreOriginal(view, bmpF)) return
                    if (!IosIconStyles.active) return
                    val canvas = param.args[0] as? Canvas ?: return
                    val bitmap = bmpF.get(view) as? Bitmap ?: return
                    val rect = rectF.get(view) as? RectF ?: return
                    if (rect.isEmpty) return
                    val component = bitmapApps[bitmap] ?: (pkgF?.get(view) as? String)?.let {
                        ComponentName(it, (actF?.get(view) as? String) ?: "")
                    } ?: return
                    runCatching { if (drawFloating(canvas, view, component, bitmap, rect, rect)) param.result = null }
                        .onFailure { XposedBridge.log("OriginIosIcons: floating draw: $it") }
                }
            })
        }.onFailure { XposedBridge.log("OriginIosIcons: recents floating icon: $it") }
    }

    /**
     * vivo's SurfaceFlinger launch / close animation flies the icon from / into IconInfo (h.k)
     * bounds that the y0.c helpers (z0.b, z0.g) work out from the icon's layout. Icon sizes above 100
     * (stock or iOS icons) are made by scaling MorphItemIcon's RenderNode around its centre while the view reports
     * scale 1, so those bounds missed the scale: the icon landed ~17 % smaller and off to the
     * side, then snapped when the real icon came back. The bounds are put through the icon's real
     * render transform here, so the flying icon lands exactly on the one on the home screen.
     */
    private fun hookSurfaceIcon(classLoader: ClassLoader) {
        val names = LauncherCompat.names(classLoader)
        val info = Class.forName(names.iconInfo, false, classLoader)
        fun field(name: String) = runCatching { info.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
        val boundsF = field("a") ?: return
        val originF = field("c")
        val scaleF = field("f")
        val padLeftF = field("i") ?: return
        val padTopF = field("j") ?: return
        val nodeF = runCatching { View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true } }.getOrNull() ?: return
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val k = param.result ?: return
                runCatching {
                    // vivo compensates a drawable scale itself; only an unaccounted node scale is fixed.
                    val drawableScale = scaleF?.get(k) as? Float ?: 1f
                    if (abs(drawableScale - 1f) > 1e-3f) return
                    val item = param.args[0]?.let { it.javaClass.getMethod("getItemIcon").invoke(it) } as? View ?: return
                    val node = nodeF.get(item) as? android.graphics.RenderNode ?: return
                    node.getMatrix(nodeMatrix)
                    if (nodeMatrix.isIdentity) return
                    val bounds = boundsF.get(k) as? RectF ?: return
                    if (bounds.isEmpty) return
                    // The bounds sit at the icon's (scaled) screen origin plus its unscaled padding.
                    val ox = bounds.left - (padLeftF.get(k) as? Float ?: return)
                    val oy = bounds.top - (padTopF.get(k) as? Float ?: return)
                    origin[0] = 0f; origin[1] = 0f
                    nodeMatrix.mapPoints(origin)
                    fun fix(r: RectF) {
                        r.offset(-ox, -oy)
                        nodeMatrix.mapRect(r)
                        r.offset(ox - origin[0], oy - origin[1])
                    }
                    fix(bounds)
                    (originF?.get(k) as? RectF)?.let(::fix)
                }.onFailure { XposedBridge.log("OriginIosIcons: surface icon bounds: $it") }
            }
        }
        names.iconInfoHelpers.forEach { name ->
            val type = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: return@forEach
            type.declaredMethods.filter { it.name == "a" && it.returnType == info && it.parameterTypes.size == 2 }
                .forEach { XposedBridge.hookMethod(it, hook) }
        }
    }

    private val nodeMatrix = Matrix()
    private val origin = FloatArray(2)

    private val floatClip = RectF()
    private val floatPlate = RectF()
    private val floatArt = RectF()

    /**
     * The flying icon as the same square iOS icon the home screen shows: [dst] is where vivo puts
     * the whole icon bitmap, so its plate inside the bitmap gives the plate's true size; it sits
     * centred in the morphing [clip] and never grows past it.
     */
    private fun drawFloating(canvas: Canvas, view: View, component: ComponentName, bitmap: Bitmap, dst: RectF, clip: RectF): Boolean {
        val context = view.context
        val style = IosIconStyles.effectiveStyle(context)
        if (style == IosIconStyles.DEFAULT) return false
        val p = plateOf(bitmap)
        val sx = dst.width() / bitmap.width
        val sy = dst.height() / bitmap.height
        val side = minOf(p.width() * sx, p.height() * sy, clip.width(), clip.height())
        if (side < 1f) return true
        // Where the plate sits in vivo's bitmap, kept inside the clip.
        val cx = (dst.left + p.exactCenterX() * sx).coerceIn(clip.left + side / 2f, clip.right - side / 2f)
        val cy = (dst.top + p.exactCenterY() * sy).coerceIn(clip.top + side / 2f, clip.bottom - side / 2f)
        floatPlate.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        artRect(floatPlate, IosIconStyles.usesDarkArt(style), floatArt)
        val art = IosIconStyles.bitmap(context, component, component.packageName, floatArt.width().toInt().coerceIn(16, 1024)) ?: return false
        val dark = IosIconStyles.isDark(context)
        if (style == IosIconStyles.CLEAR) {
            drawGlass(canvas, view, floatPlate)
            if (dark) drawClearScrim(canvas, floatPlate, 255)
        }
        paint.alpha = 255
        paint.colorFilter = IosIconStyles.filterFor(style, dark)
        canvas.drawBitmap(art, null, floatArt, paint)
        return true
    }

    /**
     * The launcher keeps finished icon bitmaps in its database and memory: after a change they
     * are rebuilt (one quick launcher restart, as the stock icon settings do), so animations and
     * drag show the new look as well.
     */
    fun rebuildIconCache(context: Context) {
        val app = context.applicationContext
        runCatching {
            app.getSharedPreferences("com.bbk.launcher2.prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("icon_in_db_dirty", true)
                .commit()
        }
        handler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 120)
    }

    private val prepared = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null

    private fun prepare(context: Context) {
        if (!prepared.compareAndSet(false, true)) return
        appContext = context.applicationContext
        IosIconStyles.ensure(context)
        KyantGlass.ensureConfig(context)
        var wasEnabled = IosIconStyles.enabled
        IosIconStyles.onChanged {
            handler.post {
                repaint()
                // Turning the feature off / on restores / re-applies the Tinted home glass.
                if (wasEnabled != IosIconStyles.enabled) {
                    wasEnabled = IosIconStyles.enabled
                    appContext?.let { syncGlassTint(it, immediate = true) }
                }
            }
        }
    }

    /** Paints the styled icon instead of the launcher's; false leaves the launcher's drawing. */
    private fun draw(canvas: Canvas, icon: BitmapDrawable, launcherIcon: Boolean): Boolean {
        // Only the launcher's own icon drawable may take its app from the view (badges are BitmapDrawables too).
        val view = if (!launcherIcon) null else owner.get() ?: (icon.callback as? View)?.takeIf { itemIconClass?.isInstance(it) == true }
        if (view != null) icons[view] = true
        val bitmap = icon.bitmap
        val component = view?.let(::componentOf)?.also { if (bitmap != null) bitmapApps[bitmap] = it }
            ?: bitmap?.let { bitmapApps[it] }
            ?: return false
        val host = view ?: (icon.callback as? View)
        val context = host?.context ?: appContext ?: return false
        val style = IosIconStyles.effectiveStyle(context)
        if (style == IosIconStyles.DEFAULT) return false
        val full = icon.bounds
        if (full.isEmpty || bitmap == null) return false
        // vivo's plate inside its (taller than wide) icon bitmap, mapped onto the drawable bounds.
        val plateInBitmap = plateOf(bitmap)
        val sx = full.width() / bitmap.width.toFloat()
        val sy = full.height() / bitmap.height.toFloat()
        plate.set(full.left + plateInBitmap.left * sx, full.top + plateInBitmap.top * sy,
            full.left + plateInBitmap.right * sx, full.top + plateInBitmap.bottom * sy)
        val darkArt = IosIconStyles.usesDarkArt(style)
        artRect(plate, darkArt, dst)
        val art = IosIconStyles.bitmap(context, component, component.packageName, dst.width().toInt().coerceAtLeast(16), source = icon)
            ?: return false
        val dark = IosIconStyles.isDark(context)
        // vivo hides the icon under its launch / close / drag animations through the drawable's
        // alpha (i0.getAlpha is per host view): honour it, or a second icon shows under the copy.
        val alpha = runCatching { icon.alpha }.getOrDefault(255).coerceIn(0, 255)
        if (alpha == 0) return true
        val layer = if (alpha < 255) canvas.saveLayerAlpha(null, alpha) else -1
        // The flying copy of launch / close animations wears the same live glass, so it lands as
        // the very icon it turns into. Folder previews sit on the folder's glass already.
        val glassHost = host?.takeIf { view != null || !it.javaClass.name.contains("Folder", ignoreCase = true) }
        if (glassHost != null && style == IosIconStyles.CLEAR) drawGlass(canvas, glassHost, plate)
        if (style == IosIconStyles.CLEAR && dark) drawClearScrim(canvas, plate, 255)
        paint.alpha = 255
        paint.colorFilter = IosIconStyles.filterFor(style, dark)
        canvas.drawBitmap(art, null, dst, paint)
        if (layer >= 0) canvas.restoreToCount(layer)
        return true
    }

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    @Volatile private var frost: Pair<Long, Int>? = null

    /** An opaque stand-in for the glass: the wallpaper's main colour, muted like frosted glass. */
    private fun frostColor(context: Context, dark: Boolean): Int {
        val now = android.os.SystemClock.uptimeMillis()
        val base = frost?.takeIf { now - it.first < 5000 }?.second ?: run {
            val c = runCatching {
                android.app.WallpaperManager.getInstance(context)
                    .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
            }.getOrNull() ?: android.graphics.Color.GRAY
            frost = now to c
            c
        }
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(base, hsv)
        if (IosIconStyles.clearSat > 0) {
            hsv[0] = IosIconStyles.clearHue.toFloat()
            hsv[1] = maxOf(hsv[1], IosIconStyles.clearSat / 100f * 0.5f)
        }
        hsv[1] = hsv[1].coerceAtMost(if (dark) 0.35f else 0.22f)
        hsv[2] = if (dark) 0.22f else 0.68f
        return android.graphics.Color.HSVToColor(hsv)
    }

    /** Clear in dark appearance: the glass is smoked (iOS "Clear Dark"), glyphs stay white. */
    private fun drawClearScrim(canvas: Canvas, plate: RectF, alpha: Int) {
        val (h, s) = IosIconStyles.clearHue to IosIconStyles.clearSat
        val base = android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), s / 100f * 0.7f, 0.10f))
        scrimPaint.color = base
        scrimPaint.alpha = 150 * alpha / 255
        val r = plate.width() * IosIconStyles.CLEAR_RADIUS
        canvas.drawRoundRect(plate, r, r, scrimPaint)
    }

    /** The art's canvas for a visible plate: the theme art carries a margin around its plate. */
    private fun artRect(plate: RectF, darkArt: Boolean, out: RectF) {
        val inset = if (darkArt) IosIconStyles.DARK_INSET else IosIconStyles.CLEAR_INSET
        val side = plate.width() / (1f - 2f * inset)
        out.set(plate.centerX() - side / 2f, plate.centerY() - side / 2f, plate.centerX() + side / 2f, plate.centerY() + side / 2f)
    }

    // ---------------------------------------------------------------- vivo icon geometry

    private val plates = Collections.synchronizedMap(WeakHashMap<Bitmap, Rect>())
    /** Most common plate per bitmap size: one icon's odd silhouette must not move it. */
    private val plateVotes = HashMap<String, HashMap<Rect, Int>>()

    /** Where vivo draws the icon plate inside its icon bitmap (square, same for all icons). */
    fun plateOf(bitmap: Bitmap): Rect {
        plates[bitmap]?.let { return it }
        val key = "${bitmap.width}x${bitmap.height}"
        val own = runCatching { opaqueBounds(bitmap) }.getOrNull()
        val result = synchronized(plateVotes) {
            val votes = plateVotes.getOrPut(key) { HashMap() }
            if (own != null && abs(own.width() - own.height()) <= 3 && own.width() > bitmap.width / 2) {
                votes[own] = (votes[own] ?: 0) + 1
            }
            votes.maxByOrNull { it.value }?.key
        } ?: own?.let { squareAround(it) } ?: Rect(0, 0, minOf(bitmap.width, bitmap.height), minOf(bitmap.width, bitmap.height))
        plates[bitmap] = result
        return result
    }

    private fun squareAround(r: Rect): Rect {
        val side = maxOf(r.width(), r.height())
        return Rect(r.centerX() - side / 2, r.top, r.centerX() - side / 2 + side, r.top + side)
    }

    private fun opaqueBounds(bitmap: Bitmap): Rect? {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        var top = -1; var bottom = -1; var left = w; var right = -1
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            var any = false
            for (x in 0 until w) {
                // Low enough to catch the Clear art's frosted plate (alpha ~54) in re-stored icons.
                if ((row[x] ushr 24) > 48) {
                    any = true
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
            if (any) { if (top < 0) top = y; bottom = y }
        }
        return if (top < 0) null else Rect(left, top, right + 1, bottom + 1)
    }

    /**
     * Icon cache side: the launcher's icon bitmap re-drawn with the look, same size and plate
     * position, so every path that paints the raw bitmap (launch / close and edit-mode
     * animations, drag, previews) shows the iOS icon too. Glass is added live when drawn.
     */
    fun compose(context: Context, component: ComponentName?, packageName: String, original: Bitmap, source: android.graphics.drawable.Drawable?): Bitmap? {
        if (!prepared.get()) prepare(context)
        if (!IosIconStyles.active) return null
        // Nested rasterizers (one calling another, both hooked) must not style an icon twice.
        if (originals.containsKey(original)) return null
        val style = IosIconStyles.effectiveStyle(context)
        if (style == IosIconStyles.DEFAULT) return null
        if (original.width !in 16..1024 || original.height !in 16..1024) return null
        val plateRect = plateOf(original)
        val plateF = RectF(plateRect)
        val out = RectF()
        artRect(plateF, IosIconStyles.usesDarkArt(style), out)
        val art = IosIconStyles.bitmap(context, component, packageName, out.width().toInt().coerceAtLeast(16), source = source) ?: return null
        val result = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        result.density = original.density
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dark = IosIconStyles.isDark(context)
        p.colorFilter = IosIconStyles.filterFor(style, dark)
        val canvas = Canvas(result)
        if (style == IosIconStyles.CLEAR) {
            // Animations morph the window into this bitmap inside vivo's own plate shape: a see-through
            // plate showed the window and a second outline through it. Give it a solid frosted fill.
            val r = plateF.width() * IosIconStyles.CLEAR_RADIUS
            canvas.drawRoundRect(plateF, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = frostColor(context, dark) })
            if (dark) drawClearScrim(canvas, plateF, 255)
        }
        canvas.drawBitmap(art, null, out, p)
        plates[result] = plateRect
        originals[result] = original
        (component ?: ComponentName(packageName, "")).let { bitmapApps[result] = it }
        return result
    }

    @Suppress("DEPRECATION")
    private fun drawGlass(canvas: Canvas, view: View, plate: RectF) {
        val matrix = canvas.matrix
        if (!matrix.invert(inverse)) return
        // A hair inside the art's plate: the rim highlight must not show past the icon.
        val inset = plate.width() * 0.022f
        glassRect.set(plate.left + inset, plate.top + inset, plate.right - inset, plate.bottom - inset)
        // The renderer maps its bounds through the view's transform: hand them over in view space.
        matrix.mapRect(glassRect)
        radii.fill(glassRect.width() * IosIconStyles.CLEAR_RADIUS)
        val renderer = renderers.getOrPut(view) { KyantGlass.Renderer(KyantGlass.Zone.HOME) }
        val backdrop = runCatching { ScreenBackdropSource.getBackdrop(view.context) }.getOrNull()
            ?: KyantGlassHook.ShadeBackdrop.get(view, requireFocus = true)
        val save = canvas.save()
        canvas.concat(inverse)
        renderer.draw(canvas, view, glassRect, radii, backdrop, withTouchGlow = false, lensScale = 1.2f)
        canvas.restoreToCount(save)
        KyantGlass.retryBackdrop(view, backdrop, 160L)
    }

    /**
     * The app behind an icon view. Shortcuts, clones and some system entries have no L4()
     * component: their intent names it. Without this those icons fell back to the cached bitmap
     * (no live glass, the solid stand-in plate), which looked like dark glass.
     */
    private fun componentOf(view: View): ComponentName? = runCatching {
        val info = view.javaClass.getMethod("getInfo").invoke(view) ?: return null
        fun call(name: String) = runCatching { info.javaClass.getMethod(name).invoke(info) }.getOrNull()
        LauncherCompat.componentGetters.firstNotNullOfOrNull { call(it) as? ComponentName }
            ?: (call("getIntent") as? android.content.Intent)?.let { intent ->
                intent.component ?: intent.`package`?.let { ComponentName(it, "") }
            }
    }.getOrNull()

    /** Every icon (and the folder plates holding their previews) draws again with the new look. */
    fun repaint() {
        synchronized(icons) { icons.keys.toList() }.forEach { icon ->
            icon.invalidate()
            (icon.parent as? View)?.invalidate()
        }
        KyantGlass.invalidateTracked()
        IosIconPanel.current?.let { root -> invalidateTree(root.rootView) }
    }

    private fun invalidateTree(view: View) {
        view.invalidate()
        if (view is ViewGroup) for (i in 0 until view.childCount) invalidateTree(view.getChildAt(i))
    }

    // ---------------------------------------------------------------- edit-mode menu

    private fun hookMenu(classLoader: ClassLoader) {
        val unit = Class.forName(FUNCTION_UNIT, false, classLoader)
        // d() applies the title to the label (inflation, locale and theme changes).
        XposedBridge.hookAllMethods(unit, "d", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!isInteractionUnit(view) || !menuEnabled(view.context)) return
                relabel(view)
            }
        })
        XposedBridge.hookAllMethods(unit, "onFinishInflate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (isInteractionUnit(view) && menuEnabled(view.context)) view.post { relabel(view) }
            }
        })
        val menu = Class.forName(MENU_ROOT, false, classLoader)
        XposedBridge.hookAllMethods(menu, "onClick", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val clicked = param.args.firstOrNull() as? View ?: return
                if (!isInteractionUnit(clicked) || !menuEnabled(clicked.context)) return
                param.result = null
                runCatching { openPanel(clicked) }.onFailure { XposedBridge.log("OriginIosIcons: panel: $it") }
            }
        })
    }

    private fun menuEnabled(context: Context): Boolean {
        if (!prepared.get()) prepare(context)
        return IosIconStyles.enabled
    }

    private fun isInteractionUnit(view: View): Boolean {
        if (interactionId == 0) {
            interactionId = view.resources.getIdentifier("unit_upslide_interaction", "id", LAUNCHER)
        }
        return interactionId != 0 && view.id == interactionId
    }

    private fun relabel(unit: View) {
        val label = if (unit.resources.configuration.locales[0].language == "ru") "Иконки" else "Icons"
        runCatching { unit.javaClass.getMethod("c", CharSequence::class.java).invoke(unit, label) }
            .recoverCatching { findText(unit)?.text = label }
        unit.contentDescription = label
    }

    private fun findText(view: View): TextView? {
        if (view is TextView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun openPanel(anchor: View) {
        val activity = anchor.context.activity() ?: return
        prepare(activity)
        // Leave edit mode first (as iOS does): the icons return to full size under the panel.
        runCatching { activity.onBackPressed() }
        // Built on the next frame: the sheet slides in together with the edit-mode exit.
        handler.post { IosIconPanel.show(activity) }
    }

    private fun Context.activity(): Activity? {
        var c: Context? = this
        while (c != null) {
            if (c is Activity) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }

    // ---------------------------------------------------------------- glass tint

    private const val BACKUP = "originceiler_ios_icon_bak_"
    private val TINT_KEYS by lazy {
        listOf(
            KyantGlass.zoneCustomKey(KyantGlass.Zone.HOME),
            KyantGlass.zoneKey(KyantGlass.Zone.HOME, KyantGlass.KEY_TINT_HUE),
            KyantGlass.zoneKey(KyantGlass.Zone.HOME, KyantGlass.KEY_TINT_CHROMA),
            KyantGlass.zoneKey(KyantGlass.Zone.HOME, KyantGlass.KEY_TINT),
            KyantGlass.zoneKey(KyantGlass.Zone.HOME, KyantGlass.KEY_TINT_DARK),
        )
    }
    private var pendingTint: Runnable? = null

    /**
     * Tinted spreads its colour over the home screen glass (widgets, folders, dock, icon plates)
     * through the home zone's own tint; leaving Tinted restores what the glass had before.
     */
    fun syncGlassTint(context: Context, immediate: Boolean = false) {
        pendingTint?.let(handler::removeCallbacks)
        val app = context.applicationContext
        val task = Runnable {
            pendingTint = null
            runCatching { writeGlassTint(app) }.onFailure { XposedBridge.log("OriginIosIcons: tint: $it") }
        }
        pendingTint = task
        if (immediate) task.run() else handler.postDelayed(task, 70)
    }

    private fun writeGlassTint(context: Context) {
        val cr = context.contentResolver
        val backedUp = Settings.Global.getInt(cr, BACKUP + "saved", 0) == 1
        val tint = IosIconStyles.glassTint()
        if (tint != null) {
            if (!backedUp) {
                TINT_KEYS.forEachIndexed { i, key ->
                    Settings.Global.putString(cr, BACKUP + i, Settings.Global.getString(cr, key) ?: "")
                }
                Settings.Global.putInt(cr, BACKUP + "saved", 1)
            }
            // The shared look is never touched here: its tint strength is the floor.
            val shared = KyantGlass.config
            Settings.Global.putInt(cr, TINT_KEYS[1], tint.first)
            Settings.Global.putInt(cr, TINT_KEYS[2], tint.second)
            Settings.Global.putInt(cr, TINT_KEYS[3], maxOf(shared[KyantGlass.KEY_TINT], 30))
            Settings.Global.putInt(cr, TINT_KEYS[4], maxOf(shared[KyantGlass.KEY_TINT_DARK], 38))
            Settings.Global.putInt(cr, TINT_KEYS[0], 1)
        } else if (backedUp) {
            TINT_KEYS.forEachIndexed { i, key ->
                val value = Settings.Global.getString(cr, BACKUP + i).orEmpty()
                if (value.isEmpty()) Settings.Global.putString(cr, key, null) else Settings.Global.putString(cr, key, value)
            }
            Settings.Global.putInt(cr, BACKUP + "saved", 0)
        }
    }
}


