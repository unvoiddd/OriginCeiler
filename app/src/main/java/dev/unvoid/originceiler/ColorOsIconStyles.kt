package dev.unvoid.originceiler

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ColorOS 16 icon styles (System Launcher 17.5.4, `UxIconLoaderUtil` / `UxAdaptiveIconDrawable`)
 * reproduced for the vivo launcher. Style ids are ColorOS `IconConfig.THEME_*` values.
 *
 * Geometry follows the original: masks live in a 150x150 viewport, sizes are relative to the
 * maximum icon size (60dp), the default icon/foreground size is 56dp and transparent legacy icons
 * get a 34dp glyph on a #FBFBFB plate. The vivo bitmap is treated as the 56dp icon area, so only the
 * ratios between styles and layers are carried over, not ColorOS' absolute icon size.
 */
internal object ColorOsIconStyles {
    const val SETTING_STYLE = "originicons_coloros_style"
    const val SETTING_SHAPE = "originicons_coloros_shape"
    const val SETTING_RADIUS = "originicons_coloros_radius"
    const val SETTING_DARK = "originicons_coloros_dark"

    const val OFF = 0
    const val MATERIAL = 1
    const val RECTANGLE = 2
    const val CUSTOM = 3
    const val PEBBLE = 4
    const val DAYLIGHT = 6
    const val NIGHTSHADOW = 7
    const val MONOTONE = 8
    const val MEOW = 9

    // IconConfig.ICON_SHAPE_*, plus the hyperelliptical radius flag (0x100) as its own entry.
    const val SHAPE_ROUNDED = 0
    const val SHAPE_OCTAGON = 1
    const val SHAPE_LEAF = 2
    const val SHAPE_STICKER = 3
    const val SHAPE_PECULIAR = 4
    const val SHAPE_HYPERELLIPTICAL = 5

    const val RADIUS_DEFAULT = 25 // UXIconConfigUtils.mDefaultCorner
    const val RADIUS_MIN = 25
    const val RADIUS_MAX = 75

    val STYLES = listOf(RECTANGLE, MATERIAL, PEBBLE, DAYLIGHT, NIGHTSHADOW, CUSTOM, MONOTONE, MEOW)

    /** Styles whose mask follows the shape setting (theme index 0 and 5 in `checkConfigAndParams`). */
    fun hasShape(style: Int) = style == RECTANGLE || style == CUSTOM || style == MONOTONE

    /** Styles whose mask is the radius squircle when no designed shape is picked. */
    fun hasRadius(style: Int, shape: Int) =
        style in setOf(RECTANGLE, CUSTOM, MONOTONE, DAYLIGHT, NIGHTSHADOW, MEOW) &&
            (!hasShape(style) || shape == SHAPE_ROUNDED)

    private const val VIEWPORT = 150f
    private const val MAX_ICON_DP = 60f
    private const val ICON_DP = 56f
    private const val FOREGROUND_DP = 56f
    private const val TRANSPARENT_FG_DP = 34f
    private const val TRANSPARENT_MIN_DP = 40f
    private const val MATERIAL_RADIUS = 8f
    private const val MATERIAL_FOREGROUND_SCALE = 1.25f
    private const val SPECIAL_FOREGROUND_SCALE = 0.75f
    private const val PLATE_COLOR = 0xFFFBFBFB.toInt()
    private const val DARK_DIM = 0xD6D6D6
    private val DARK_OVERLAY = intArrayOf(0x33262C40, 0x33181C29)

    fun style(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, SETTING_STYLE, OFF).takeIf { it in STYLES } ?: OFF

    fun render(context: Context, icon: Drawable, size: Int): Bitmap? = runCatching {
        val style = style(context)
        if (style == OFF || size <= 0) return null
        val resolver = context.contentResolver
        val shape = Settings.Global.getInt(resolver, SETTING_SHAPE, SHAPE_ROUNDED)
        val radius = Settings.Global.getInt(resolver, SETTING_RADIUS, RADIUS_DEFAULT).coerceIn(RADIUS_MIN, RADIUS_MAX)
        // isDarkModeIcon() && night, exactly like `p0` / `isRecDarkMode`.
        val dark = Settings.Global.getInt(resolver, SETTING_DARK, 0) == 1 && isNight(context)
        val mask = mask(context, style, shape, radius)
        when (style) {
            CUSTOM, MONOTONE -> renderSingleColor(context, icon, size, mask, style == MONOTONE, dark)
            NIGHTSHADOW -> renderSpecial(icon, size, mask, ColorDrawable(Color.BLACK), circle = true, dark)
            MEOW -> renderSpecial(
                icon, size, mask,
                moduleContext(context).getDrawable(R.drawable.coloros_meow_bg) ?: return null,
                circle = false, dark, moduleContext(context).getDrawable(R.drawable.coloros_meow_mask)
            )
            MATERIAL -> renderMaterial(context, icon, size, mask, dark)
            else -> renderRegular(context, icon, size, mask, style, dark)
        }
    }.getOrNull()

    // region styles

    /** `generateIconWithConfig` for theme index 0/2/3 (Rectangle, Pebble, Daylight). */
    private fun renderRegular(context: Context, icon: Drawable, size: Int, mask: Path, style: Int, dark: Boolean): Bitmap {
        // Only the Rectangle style draws the dark gradient (`createDarkModeIcon`).
        val overlay = dark && style == RECTANGLE
        if (icon is AdaptiveIconDrawable && icon.foreground != null && icon.background != null) {
            // I(): Rectangle and Custom use the foreground size, the others the maximum size.
            val foregroundScale = if (style == RECTANGLE) FOREGROUND_DP / MAX_ICON_DP else 1f
            return compose(size, mask, 1f, dim(icon.background, dark), dim(icon.foreground, dark), foregroundScale, adaptive = true, overlay)
        }
        if (isTransparent(context, icon)) {
            val foregroundScale = if (style == DAYLIGHT) {
                TRANSPARENT_FG_DP / MAX_ICON_DP * (FOREGROUND_DP / MAX_ICON_DP)
            } else {
                TRANSPARENT_FG_DP / MAX_ICON_DP
            }
            return compose(size, mask, 1f, plate(dark), dim(icon, dark), foregroundScale, adaptive = false, overlay)
        }
        return compose(size, mask, 1f, dim(icon, dark), null, 1f, adaptive = false, overlay)
    }

    /**
     * Material keeps the whole app icon as one layer (`setBackground(drawable)`) inside a nearly square
     * mask, at foregroundSize * iconSize / max^2 of the bounds.
     */
    private fun renderMaterial(context: Context, icon: Drawable, size: Int, mask: Path, dark: Boolean): Bitmap {
        // ColorOS: e = fg/max * icon/max; relative to the regular icon (icon/max) that leaves fg/max.
        val scale = FOREGROUND_DP / MAX_ICON_DP
        if (isTransparent(context, icon)) {
            val foregroundScale = TRANSPARENT_FG_DP * MATERIAL_FOREGROUND_SCALE / MAX_ICON_DP
            return compose(size, mask, scale, plate(dark), dim(icon, dark), foregroundScale, adaptive = false, overlay = false)
        }
        return compose(size, mask, scale, dim(icon, dark), null, 1f, adaptive = false, overlay = false)
    }

    /**
     * Night shadow (`clipCircleDrawable` on black) and Meow (`clipMeowkukuDrawable` on fur): the icon
     * is cut to a circle or the soft Meow mask and drawn at 75% over the style's backplate.
     */
    private fun renderSpecial(
        icon: Drawable,
        size: Int,
        mask: Path,
        background: Drawable,
        circle: Boolean,
        dark: Boolean,
        softMask: Drawable? = null
    ): Bitmap {
        val cut = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cut)
        if (circle) {
            val source = rasterize(icon, size)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            source.recycle()
        } else {
            icon.setBounds(0, 0, size, size)
            icon.draw(canvas)
            softMask?.let {
                val maskBitmap = rasterize(it, size)
                canvas.drawBitmap(maskBitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                })
                maskBitmap.recycle()
            }
        }
        val foreground = BitmapDrawable(null, cut)
        return compose(size, mask, 1f, dim(background, dark), dim(foreground, dark), SPECIAL_FOREGROUND_SCALE, adaptive = false, overlay = false)
            .also { cut.recycle() }
    }

    /**
     * Single-color theme (`handleIconThemeFgDrawable`): adaptive layers become flat color
     * silhouettes (SRC_IN), legacy icons are grayscaled and their luminance is turned into the alpha
     * of the foreground color (`handleGameIconTheme`). Sits on a solid backplate.
     */
    private fun renderSingleColor(context: Context, icon: Drawable, size: Int, mask: Path, followWallpaper: Boolean, dark: Boolean): Bitmap {
        val (backgroundColor, foregroundColor) = singleColors(context, followWallpaper, dark)
        val content = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(content)
        canvas.drawColor(backgroundColor)
        if (icon is AdaptiveIconDrawable && (icon.foreground != null || icon.background != null)) {
            val extra = size / 4
            val scale = FOREGROUND_DP / MAX_ICON_DP
            icon.background?.mutate()?.let {
                it.colorFilter = srcIn(backgroundColor)
                it.setBounds(-extra, -extra, size + extra, size + extra)
                it.draw(canvas)
            }
            icon.foreground?.mutate()?.let {
                it.colorFilter = srcIn(foregroundColor)
                val checkpoint = canvas.save()
                canvas.scale(scale, scale, size / 2f, size / 2f)
                it.setBounds(-extra, -extra, size + extra, size + extra)
                it.draw(canvas)
                canvas.restoreToCount(checkpoint)
            }
        } else {
            val glyph = luminanceGlyph(icon, size, foregroundColor, dark)
            canvas.drawBitmap(glyph, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            glyph.recycle()
        }
        return clip(content, mask, size, 1f).also { content.recycle() }
    }

    /** `grayScaleFg` + `handleIconThemeFgDrawable(z = true)` + `handleGameIconTheme`. */
    private fun luminanceGlyph(icon: Drawable, size: Int, color: Int, dark: Boolean): Bitmap {
        val source = rasterize(icon, size)
        val layered = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(layered).apply {
            drawColor(if (dark) Integer.MAX_VALUE else 0x7F000000)
            drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            })
        }
        val r = Color.red(color).toFloat()
        val g = Color.green(color).toFloat()
        val b = Color.blue(color).toFloat()
        val matrix = if (dark) {
            floatArrayOf(0f, 0f, 0f, 0f, r, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, b, 0.299f, 0.587f, 0.114f, 0f, 0f)
        } else {
            floatArrayOf(0f, 0f, 0f, 0f, r, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, b, -0.299f, -0.587f, -0.114f, 0f, 255f)
        }
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(layered, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
        // `getMaskDrawable`: keep the recolored glyph only where the original icon had pixels.
        canvas.drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        layered.recycle()
        source.recycle()
        return result
    }

    /**
     * `UxIconUtils.getSingleColor`: wallpaper-following colors when asked, otherwise Material You
     * accent1_100 / neutral2_700 by day and neutral1_800 / accent1_100 for dark-mode icons.
     */
    @android.annotation.SuppressLint("InlinedApi")
    private fun singleColors(context: Context, followWallpaper: Boolean, dark: Boolean): Pair<Int, Int> {
        if (followWallpaper) wallpaperColors(context, dark)?.let { return it }
        return if (dark) {
            systemColor(context, android.R.color.system_neutral1_800, "#2F3033") to
                systemColor(context, android.R.color.system_accent1_100, "#D8E2FF")
        } else {
            systemColor(context, android.R.color.system_accent1_100, "#D8E2FF") to
                systemColor(context, android.R.color.system_neutral2_700, "#45464F")
        }
    }

    /** Seed tones in the same roles as ColorOS' `icon_material_style_and_seed_color` palette. */
    private fun wallpaperColors(context: Context, dark: Boolean): Pair<Int, Int>? {
        val seed = runCatching {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
        }.getOrNull() ?: return null
        fun tone(lightness: Double, chroma: Double): Int {
            val lab = DoubleArray(3)
            ColorUtils.colorToLAB(seed, lab)
            return ColorUtils.LABToColor(lightness, lab[1] * chroma, lab[2] * chroma)
        }
        return if (dark) tone(20.0, 0.35) to tone(90.0, 0.45) else tone(90.0, 0.45) to tone(30.0, 0.6)
    }

    // endregion

    // region drawing

    /**
     * `UxAdaptiveIconDrawable.draw`: background at full bounds, foreground scaled by its ratio,
     * adaptive layers at 1.5x (`f(rect)`), optional dark gradient, then the mask with antialiasing.
     */
    private fun compose(
        size: Int,
        mask: Path,
        iconScale: Float,
        background: Drawable?,
        foreground: Drawable?,
        foregroundScale: Float,
        adaptive: Boolean,
        overlay: Boolean
    ): Bitmap {
        val content = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(content)
        val extra = if (adaptive) size / 4 else 0
        val layer = Rect(-extra, -extra, size + extra, size + extra)
        val center = size / 2f
        canvas.save()
        canvas.scale(iconScale, iconScale, center, center)
        background?.let {
            it.bounds = layer
            it.draw(canvas)
        }
        canvas.scale(foregroundScale, foregroundScale, center, center)
        foreground?.let {
            it.bounds = layer
            it.draw(canvas)
        }
        canvas.restore()
        if (overlay) {
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, DARK_OVERLAY).apply {
                if (Build.VERSION.SDK_INT >= 29) colorFilter = BlendModeColorFilter(0, BlendMode.HARD_LIGHT)
                setBounds(0, 0, size, size)
                draw(canvas)
            }
        }
        return clip(content, mask, size, iconScale).also { content.recycle() }
    }

    private fun clip(content: Bitmap, mask: Path, size: Int, iconScale: Float): Bitmap {
        val path = Path(mask)
        val pathScale = size * iconScale / VIEWPORT
        path.transform(Matrix().apply { setScale(pathScale, pathScale) })
        val offset = (size - size * iconScale) / 2f
        path.offset(offset, offset)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(result).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        })
        return result
    }

    /** Default plate `this.b`, dimmed by the same lighting filter as the icon in dark mode. */
    private fun plate(dark: Boolean): Drawable = ColorDrawable(PLATE_COLOR).also {
        if (dark) it.colorFilter = LightingColorFilter(DARK_DIM, 0)
    }

    /** `p0`: dark-mode icons multiply every layer by #D6D6D6. */
    private fun dim(drawable: Drawable?, dark: Boolean): Drawable? {
        if (drawable == null || !dark) return drawable
        return drawable.mutate().also { it.colorFilter = LightingColorFilter(DARK_DIM, 0) }
    }

    private fun srcIn(color: Int) = if (Build.VERSION.SDK_INT >= 29) {
        BlendModeColorFilter(color, BlendMode.SRC_IN)
    } else {
        PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    private fun rasterize(drawable: Drawable, size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(it))
        }

    // endregion

    // region masks

    /** `checkConfigAndParams` path selection. */
    private fun mask(context: Context, style: Int, shape: Int, radius: Int): Path {
        if (style == MATERIAL) return smoothRoundRect(VIEWPORT, VIEWPORT, MATERIAL_RADIUS)
        if (style == PEBBLE) return smoothRoundRect(VIEWPORT, VIEWPORT, PEBBLE_RADIUS)
        if (hasShape(style)) {
            val name = when (shape) {
                SHAPE_OCTAGON -> R.string.coloros_icon_mask_octagon
                SHAPE_LEAF -> R.string.coloros_icon_mask_leaf
                SHAPE_STICKER -> R.string.coloros_icon_mask_sticker
                SHAPE_PECULIAR -> R.string.coloros_icon_mask_peculiar
                SHAPE_HYPERELLIPTICAL -> R.string.coloros_icon_mask_hyperelliptical
                else -> 0
            }
            if (name != 0) {
                runCatching { PathParser.createPathFromPathData(moduleContext(context).getString(name)) }
                    .getOrNull()?.let { return it }
            }
        }
        return smoothRoundRect(VIEWPORT, VIEWPORT, radius.toFloat())
    }

    /**
     * Pebble's outline is `oplus_uxicon_common_style_path[2]` in oplus framework-res, which is not part
     * of the launcher APK; a softer squircle stands in until that path is pulled from a ColorOS device.
     */
    private const val PEBBLE_RADIUS = 45f

    /** `UxRoundRectUtil` fallback (used when `OplusPath.addSmoothRoundRect` is unavailable). */
    internal fun smoothRoundRect(w: Float, h: Float, radius: Float): Path {
        val r = max(radius, 0f)
        val hw = w / 2f
        val hh = h / 2f
        val ratio = r / min(hw, hh)
        val k1 = if (ratio > 0.5f) 1f - min(1f, (ratio - 0.5f) / 0.4f) * 0.13877845f else 1f
        val k2 = if (ratio > 0.6f) 1f + min(1f, (ratio - 0.6f) / 0.3f) * 0.042454004f else 1f
        val u = r / 100f
        val a = u * 128.19f * k1
        val b = u * 83.62f * k2
        val c = u * 67.45f
        val d = u * 4.64f
        val e = u * 51.16f
        val f = u * 13.36f
        val g = u * 34.86f
        val q = u * 22.07f
        return Path().apply {
            moveTo(hw, 0f)
            lineTo(max(hw, w - a), 0f)
            cubicTo(w - b, 0f, w - c, d, w - e, f)
            cubicTo(w - g, q, w - q, g, w - f, e)
            cubicTo(w - d, c, w, b, w, min(hh, a))
            lineTo(w, max(hh, h - a))
            cubicTo(w, h - b, w - d, h - c, w - f, h - e)
            cubicTo(w - q, h - g, w - g, h - q, w - e, h - f)
            cubicTo(w - c, h - d, w - b, h, max(hw, w - a), h)
            lineTo(min(hw, a), h)
            cubicTo(b, h, c, h - d, e, h - f)
            cubicTo(g, h - q, q, h - g, f, h - e)
            cubicTo(d, h - c, 0f, h - b, 0f, max(hh, h - a))
            lineTo(0f, min(hh, a))
            cubicTo(0f, b, d, c, f, e)
            cubicTo(q, g, g, q, e, f)
            cubicTo(c, d, b, 0f, min(hw, a), 0f)
            close()
        }
    }

    // endregion

    // region classification

    /**
     * `isTransparentIcon`: icons narrower than 40dp, or with at least 6 transparent probes along the
     * 1..3px edge band (alpha < 220), get the plate instead of filling the mask.
     */
    private fun isTransparent(context: Context, icon: Drawable): Boolean {
        val density = context.resources.displayMetrics.density
        val width = icon.intrinsicWidth
        val height = icon.intrinsicHeight
        if (width > 0 && width < TRANSPARENT_MIN_DP * density) return true
        val w = if (width in 8..512) width else 192
        val h = if (height in 8..512) height else 192
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, w, h)
        icon.draw(Canvas(bitmap))
        val stepX = (w / 4f).let { kotlin.math.ceil(it).toInt() }.coerceAtLeast(1)
        val stepY = (h / 4f).let { kotlin.math.ceil(it).toInt() }.coerceAtLeast(1)
        fun clear(x: Int, y: Int) = Color.alpha(bitmap.getPixel(x, y)) < 220
        var hits = 0
        var x = stepX
        while (x < w) {
            if ((1..3).count { clear(x, it) } > 1) hits++
            if ((h - 4..h - 2).count { clear(x, it) } > 1) hits++
            x += stepX
        }
        var y = stepY
        while (y < h) {
            if ((1..3).count { clear(it, y) } > 1) hits++
            if ((w - 4..w - 2).count { clear(it, y) } > 1) hits++
            y += stepY
        }
        bitmap.recycle()
        return hits >= 6
    }

    // endregion

    @android.annotation.SuppressLint("InlinedApi")
    private fun systemColor(context: Context, id: Int, fallback: String): Int =
        if (Build.VERSION.SDK_INT >= 31) runCatching { context.getColor(id) }.getOrDefault(Color.parseColor(fallback))
        else Color.parseColor(fallback)

    private fun isNight(context: Context) =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Inside the launcher R ids point into the module APK. */
    private fun moduleContext(context: Context): Context {
        if (context.packageName == BuildConfig.APPLICATION_ID) return context
        cachedModuleContext?.let { return it }
        return context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
            .also { cachedModuleContext = it }
    }

    @Volatile
    private var cachedModuleContext: Context? = null
}
