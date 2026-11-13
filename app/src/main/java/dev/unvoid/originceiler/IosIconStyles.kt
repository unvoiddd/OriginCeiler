package dev.unvoid.originceiler

import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.LruCache
import java.util.concurrent.atomic.AtomicBoolean

/**
 * iOS 26 home screen icon appearance: Default, Dark, Clear and Tinted.
 *
 * Dark and Clear art comes from two vivo themes bundled as module assets (ios_icons/dark,
 * ios_icons/clear: 192 px WebP, named like the theme files, i.e. package or activity class).
 * Tinted is the Clear art recoloured at draw time with a colour matrix, so the hue and
 * saturation sliders repaint every icon without re-rendering anything. Apps missing from a set
 * get a generated icon in the same geometry.
 */
object IosIconStyles {
    const val DEFAULT = 0
    const val DARK = 1
    const val CLEAR = 2
    const val TINTED = 3

    const val APPEARANCE_LIGHT = 0
    const val APPEARANCE_DARK = 1
    const val APPEARANCE_AUTO = 2

    const val KEY_STYLE = "originceiler_ios_icon_style"
    const val KEY_APPEARANCE = "originceiler_ios_icon_appearance"
    const val KEY_HUE = "originceiler_ios_icon_hue"
    const val KEY_SAT = "originceiler_ios_icon_sat"
    /** Clear has its own colour (saturation 0 = plain white glass). */
    const val KEY_CLEAR_HUE = "originceiler_ios_icon_clear_hue"
    const val KEY_CLEAR_SAT = "originceiler_ios_icon_clear_sat"
    const val KEY_ICON_SIZE = "originroottoolbox_launcher_icon_size"
    /** iOS "Large": bigger icons without labels. */
    const val KEY_LARGE = "originceiler_ios_icon_large"
    /** The whole feature: the "Icons" menu in the launcher and every look it draws. */
    const val KEY_ENABLED = "originceiler_ios_icon_enabled"
    const val LARGE_SIZE = 120

    /** Visible plate of the theme art inside its 192 px canvas. */
    const val CLEAR_INSET = 19f / 192f
    const val DARK_INSET = 7f / 192f
    /** Corner radius of the plate relative to its side (theme art is an iOS squircle). */
    const val PLATE_RADIUS = 0.245f
    /**
     * The Clear art's plate is an iOS continuous-corner squircle: a plain rounded rect matching
     * it along the diagonal needs this larger radius (measured on the theme art).
     */
    const val CLEAR_RADIUS = 0.272f

    private const val ASSET_ROOT = "ios_icons"

    @Volatile var style = DEFAULT
        private set
    @Volatile var appearance = APPEARANCE_LIGHT
        private set
    @Volatile var tintHue = 210
        private set
    @Volatile var tintSat = 55
        private set
    @Volatile var clearHue = 210
        private set
    @Volatile var clearSat = 0
        private set
    /** The colour the sliders edit: Clear's own, otherwise Tinted's. */
    val hue: Int get() = if (style == CLEAR) clearHue else tintHue
    val saturation: Int get() = if (style == CLEAR) clearSat else tintSat
    @Volatile var large = false
        private set
    @Volatile var enabled = true
        private set

    private val listeners = mutableListOf<() -> Unit>()
    private val observing = AtomicBoolean(false)
    private val handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    @Volatile private var appContext: Context? = null
    @Volatile private var moduleContext: Context? = null
    @Volatile private var names: Map<String, Set<String>> = emptyMap()

    private val cache = object : LruCache<String, Bitmap>(28 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val tintFilterLock = Any()
    private var tintFilter: ColorMatrixColorFilter? = null
    private var tintKey = -1

    // ---------------------------------------------------------------- state

    fun ensure(context: Context) {
        if (!observing.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        appContext = app
        load(app)
        runCatching {
            app.contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    val key = uri?.lastPathSegment ?: return
                    if (!key.startsWith("originceiler_ios_icon_")) return
                    val before = signature() + large
                    load(app)
                    if (before != signature() + large) notifyChanged()
                }
            })
        }
    }

    private fun load(context: Context) {
        val cr = context.contentResolver
        style = runCatching { Settings.Global.getInt(cr, KEY_STYLE, DEFAULT) }.getOrDefault(DEFAULT).coerceIn(DEFAULT, TINTED)
        appearance = runCatching { Settings.Global.getInt(cr, KEY_APPEARANCE, APPEARANCE_LIGHT) }.getOrDefault(APPEARANCE_LIGHT)
            .coerceIn(APPEARANCE_LIGHT, APPEARANCE_AUTO)
        tintHue = runCatching { Settings.Global.getInt(cr, KEY_HUE, 210) }.getOrDefault(210).mod(360)
        tintSat = runCatching { Settings.Global.getInt(cr, KEY_SAT, 55) }.getOrDefault(55).coerceIn(0, 100)
        clearHue = runCatching { Settings.Global.getInt(cr, KEY_CLEAR_HUE, 210) }.getOrDefault(210).mod(360)
        clearSat = runCatching { Settings.Global.getInt(cr, KEY_CLEAR_SAT, 0) }.getOrDefault(0).coerceIn(0, 100)
        large = runCatching { Settings.Global.getInt(cr, KEY_LARGE, 0) == 1 }.getOrDefault(false)
        enabled = runCatching { Settings.Global.getInt(cr, KEY_ENABLED, 1) == 1 }.getOrDefault(true)
    }

    /** Small icons with labels, or large ones without (the launcher's icon size follows). */
    fun setLarge(context: Context, on: Boolean) {
        if (on == large) return
        large = on
        notifyChanged()
        val cr = context.applicationContext.contentResolver
        runCatching {
            Settings.Global.putInt(cr, KEY_LARGE, if (on) 1 else 0)
            Settings.Global.putInt(cr, KEY_ICON_SIZE, if (on) LARGE_SIZE else 100)
        }
    }

    /** Everything a cached icon bitmap depends on. */
    fun signature(): String = "$enabled|$style|$appearance|$tintHue|$tintSat|$clearHue|$clearSat"

    fun onChanged(listener: () -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    private fun notifyChanged() {
        synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }
    }

    /** Applies a change at once in this process and stores it (other processes follow the observer). */
    fun update(context: Context, newStyle: Int = style, newAppearance: Int = appearance, newHue: Int = hue, newSat: Int = saturation) {
        // Colour arguments belong to the look being edited (the current one).
        val colorChanged = newHue != hue || newSat != saturation
        val changed = newStyle != style || newAppearance != appearance || colorChanged
        if (!changed) return
        if (colorChanged) {
            if (style == CLEAR) { clearHue = newHue.mod(360); clearSat = newSat.coerceIn(0, 100) }
            else { tintHue = newHue.mod(360); tintSat = newSat.coerceIn(0, 100) }
        }
        style = newStyle
        appearance = newAppearance
        notifyChanged()
        val cr = context.applicationContext.contentResolver
        runCatching {
            Settings.Global.putInt(cr, KEY_STYLE, style)
            Settings.Global.putInt(cr, KEY_APPEARANCE, appearance)
            Settings.Global.putInt(cr, KEY_HUE, tintHue)
            Settings.Global.putInt(cr, KEY_SAT, tintSat)
            Settings.Global.putInt(cr, KEY_CLEAR_HUE, clearHue)
            Settings.Global.putInt(cr, KEY_CLEAR_SAT, clearSat)
        }
    }

    fun isDark(context: Context, forAppearance: Int = appearance): Boolean = when (forAppearance) {
        APPEARANCE_DARK -> true
        APPEARANCE_LIGHT -> false
        else -> nightMode(context)
    }

    private fun nightMode(context: Context) =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * What actually draws now, as on iOS 26: Default turns into the dark art in dark appearance;
     * Dark is "Always" (dark appearance) or "Auto" (dark art only at night).
     */
    fun effectiveStyle(context: Context, forStyle: Int = style): Int = if (!enabled) DEFAULT else when (forStyle) {
        DEFAULT -> if (isDark(context)) DARK else DEFAULT
        DARK -> if (appearance == APPEARANCE_AUTO && !nightMode(context)) DEFAULT else DARK
        else -> forStyle
    }

    /** False when nothing can differ from the launcher's own icons (cheap early exit). */
    val active: Boolean get() = enabled && (style != DEFAULT || appearance != APPEARANCE_LIGHT)

    // ---------------------------------------------------------------- tint

    /**
     * Tinted is the Dark art through a duotone: its near-black plate takes the deep tint, the
     * glyph the light one (shading in between is kept). Dark appearance: dark plates with
     * bright glyphs; light appearance: vivid plates with near-white glyphs.
     */
    fun tintColors(dark: Boolean, h: Int = tintHue, s: Int = tintSat): Pair<Int, Int> {
        val sat = s / 100f
        return if (dark) {
            Color.HSVToColor(floatArrayOf(h.toFloat(), (sat * 1.05f).coerceAtMost(1f), 0.14f)) to
                Color.HSVToColor(floatArrayOf(h.toFloat(), sat * 0.62f, 0.95f))
        } else {
            Color.HSVToColor(floatArrayOf(h.toFloat(), (sat * 1.05f).coerceAtMost(1f), 0.62f)) to
                Color.HSVToColor(floatArrayOf(h.toFloat(), sat * 0.16f, 1f))
        }
    }

    /** Grey level to colour between the deep and the light tint; alpha is kept. */
    fun tintFilter(dark: Boolean): ColorMatrixColorFilter = synchronized(tintFilterLock) {
        val key = (tintHue shl 9) or (tintSat shl 1) or (if (dark) 1 else 0)
        tintFilter?.takeIf { tintKey == key }?.let { return it }
        val (deep, light) = tintColors(dark)
        // The Dark art's plate sits around grey 40: stretch it so the plate lands on the deep tint.
        val black = 34f
        fun row(d: Int, l: Int): FloatArray {
            val span = (l - d) / (255f - black)
            return floatArrayOf(0.299f * span, 0.587f * span, 0.114f * span, 0f, d - black * span)
        }
        val r = row(Color.red(deep), Color.red(light))
        val g = row(Color.green(deep), Color.green(light))
        val b = row(Color.blue(deep), Color.blue(light))
        val matrix = ColorMatrix(r + g + b + floatArrayOf(0f, 0f, 0f, 1f, 0f))
        ColorMatrixColorFilter(matrix).also { tintFilter = it; tintKey = key }
    }

    private var clearFilter: ColorMatrixColorFilter? = null
    private var clearKey = -1

    /**
     * Clear's glyph colour: the white art multiplied by a pale tint. In dark appearance the
     * theme art's baked-in white frost (alpha ~54-110) is pulled out with an alpha curve, so the
     * smoked glass shows through as on the generated icons; glyphs (alpha 255) stay solid.
     * Null for plain white in light appearance.
     */
    fun clearFilter(dark: Boolean = false): ColorMatrixColorFilter? = synchronized(tintFilterLock) {
        if (clearSat == 0 && !dark) return null
        val key = (clearHue shl 9) or (clearSat shl 1) or (if (dark) 1 else 0)
        clearFilter?.takeIf { clearKey == key }?.let { return it }
        val c = if (clearSat == 0) Color.WHITE else Color.HSVToColor(floatArrayOf(clearHue.toFloat(), clearSat / 100f * 0.6f, 1f))
        val alphaScale = if (dark) 1.5f else 1f
        val alphaShift = if (dark) -95f else 0f
        val matrix = ColorMatrix(floatArrayOf(
            Color.red(c) / 255f, 0f, 0f, 0f, 0f,
            0f, Color.green(c) / 255f, 0f, 0f, 0f,
            0f, 0f, Color.blue(c) / 255f, 0f, 0f,
            0f, 0f, 0f, alphaScale, alphaShift,
        ))
        ColorMatrixColorFilter(matrix).also { clearFilter = it; clearKey = key }
    }

    /** The colour filter the art of [style] is drawn through. */
    fun filterFor(style: Int, dark: Boolean): ColorMatrixColorFilter? = when (style) {
        TINTED -> tintFilter(dark)
        CLEAR -> clearFilter(dark)
        else -> null
    }

    /** Dark and Tinted draw the Dark art, Clear the Clear art. */
    fun usesDarkArt(style: Int) = style == DARK || style == TINTED

    /** Hue / saturation the home screen glass takes, or null for its own look. */
    fun glassTint(): Pair<Int, Int>? = if (!enabled) null else when {
        style == TINTED -> tintHue to tintSat
        style == CLEAR && clearSat > 0 -> clearHue to clearSat
        else -> null
    }

    // ---------------------------------------------------------------- art

    /**
     * The icon for [component] in [forStyle] (resolved through [effectiveStyle]); null for the
     * default look. Draw it through [filterFor].
     */
    fun bitmap(context: Context, component: ComponentName?, packageName: String?, size: Int, forStyle: Int = style, source: Drawable? = null): Bitmap? {
        val effective = effectiveStyle(context, forStyle)
        if (effective == DEFAULT) return null
        return art(context, component, packageName, size, dark = usesDarkArt(effective), source = source)
    }

    /** The Dark ([dark]) or Clear art itself, whatever the current look. */
    fun art(context: Context, component: ComponentName?, packageName: String?, size: Int, dark: Boolean, source: Drawable? = null): Bitmap? {
        val pkg = packageName ?: component?.packageName ?: return null
        if (size !in 16..1024) return null
        val variant = if (dark) "dark" else "clear"
        val key = "$variant|${component?.className ?: pkg}|$size"
        cache.get(key)?.takeIf { !it.isRecycled }?.let { return it }
        val result = runCatching { loadArt(context, variant, component, pkg, size) }.getOrNull()
            ?: runCatching { generate(context, variant, component, pkg, size, source) }.getOrNull()
            ?: return null
        cache.put(key, result)
        return result
    }

    fun clearCache() = cache.evictAll()

    private fun module(context: Context): Context {
        if (context.packageName == BuildConfig.APPLICATION_ID) return context
        moduleContext?.let { return it }
        return context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
            .also { moduleContext = it }
    }

    private fun namesFor(context: Context, variant: String): Set<String> {
        names[variant]?.let { return it }
        synchronized(this) {
            names[variant]?.let { return it }
            val list = runCatching { module(context).assets.list("$ASSET_ROOT/$variant") }.getOrNull().orEmpty()
            val set = list.mapTo(HashSet(list.size * 2)) { it.removeSuffix(".webp") }
            names = names + (variant to set)
            return set
        }
    }

    private fun candidates(component: ComponentName?, pkg: String): List<String> {
        val out = ArrayList<String>(4)
        component?.className?.takeIf { it.isNotEmpty() }?.let { cls ->
            if (cls == MonetThemedIconFactory.VIVO_DIALER_CLASS || cls.contains("Dialer")) out += "_PHONE"
            out += cls
        }
        ALIASES[pkg]?.let { out += it }
        out += pkg
        return out
    }

    private fun loadArt(context: Context, variant: String, component: ComponentName?, pkg: String, size: Int): Bitmap? {
        val available = namesFor(context, variant)
        val name = candidates(component, pkg).firstOrNull { it in available } ?: return null
        val decoded = module(context).assets.open("$ASSET_ROOT/$variant/$name.webp").use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        } ?: return null
        if (decoded.width == size) return decoded
        return Bitmap.createScaledBitmap(decoded, size, size, true).also { if (it !== decoded) decoded.recycle() }
    }

    /** An icon in the set's geometry for apps the themes do not cover. */
    private fun generate(context: Context, variant: String, component: ComponentName?, pkg: String, size: Int, source: Drawable?): Bitmap? {
        val pm = context.packageManager
        val icon = component?.takeIf { it.className.isNotEmpty() }?.let { runCatching { pm.getActivityIcon(it) }.getOrNull() }
            ?: runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            ?: source
            ?: return null
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (variant == "dark") {
            val inset = size * DARK_INSET
            val plate = RectF(inset, inset, size - inset, size - inset)
            val radius = plate.width() * PLATE_RADIUS
            paint.shader = LinearGradient(0f, plate.top, 0f, plate.bottom, Color.rgb(50, 50, 52), Color.rgb(22, 22, 24), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(plate, radius, radius, paint)
            paint.shader = null
            val clip = android.graphics.Path().apply { addRoundRect(plate, radius, radius, android.graphics.Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)
            val stripped = MonetThemedIconFactory.removeEmbeddedBackground(context, icon)
            if (stripped is AdaptiveIconDrawable && stripped.foreground != null) {
                // Adaptive layers are 1.5x the visible viewport.
                val extra = plate.width() / 4f
                stripped.foreground.setBounds((plate.left - extra).toInt(), (plate.top - extra).toInt(), (plate.right + extra).toInt(), (plate.bottom + extra).toInt())
                stripped.foreground.draw(canvas)
            } else {
                val glyphInset = plate.width() * 0.14f
                stripped.setBounds((plate.left + glyphInset).toInt(), (plate.top + glyphInset).toInt(), (plate.right - glyphInset).toInt(), (plate.bottom - glyphInset).toInt())
                stripped.draw(canvas)
            }
            canvas.restore()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size / 96f
            paint.color = Color.argb(46, 255, 255, 255)
            canvas.drawRoundRect(plate, radius, radius, paint)
        } else {
            val inset = size * CLEAR_INSET
            val plate = RectF(inset, inset, size - inset, size - inset)
            val radius = plate.width() * CLEAR_RADIUS
            paint.shader = LinearGradient(0f, plate.top, 0f, plate.bottom, Color.argb(58, 255, 255, 255), Color.argb(24, 255, 255, 255), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(plate, radius, radius, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size / 80f
            paint.color = Color.argb(90, 255, 255, 255)
            canvas.drawRoundRect(plate, radius, radius, paint)
            val glyphSize = plate.width().toInt()
            val glyph = MonetThemedIconFactory.getAospGlyphDrawable(context, icon, pkg, glyphSize)
            glyph.mutate()
            glyph.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            glyph.alpha = 235
            canvas.save()
            canvas.translate(plate.left, plate.top)
            glyph.setBounds(0, 0, glyphSize, glyphSize)
            glyph.draw(canvas)
            canvas.restore()
        }
        return result
    }

    private val ALIASES = mapOf(
        "com.vivo.browser" to "_BROWSER",
        "com.android.bbkcalculator" to "_CALCULATOR",
        "com.vivo.calculator" to "_CALCULATOR",
        "com.bbk.calendar" to "_CALENDAR",
        "com.android.calendar" to "_CALENDAR",
        "com.android.camera" to "_CAMERA",
        "com.android.BBKClock" to "_CLOCK",
        "com.android.contacts" to "_CONTACTS",
        "com.vivo.email" to "_EMAIL",
        "com.android.email" to "_EMAIL",
        "com.vivo.gallery" to "_GALLERY",
        "com.android.mms" to "_SMS",
        "com.android.dialer" to "_PHONE",
    )
}
