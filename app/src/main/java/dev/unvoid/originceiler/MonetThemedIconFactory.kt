package dev.unvoid.originceiler

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.provider.Settings
import kotlin.math.roundToInt

object MonetThemedIconFactory {
    data class MoodCubeIconStyle(val themed: Boolean, val tint: Int)

    fun moodCubeIconStyle(context: Context): MoodCubeIconStyle {
        return runCatching {
            val managerClass = Class.forName("com.vivo.framework.themeicon.ThemeIconManager")
            val manager = managerClass.getMethod("getInstance").invoke(null)
            val mode = managerClass.getMethod("getIconColorMode").invoke(manager) as Int
            if (mode == 0) return MoodCubeIconStyle(false, Color.TRANSPARENT)
            val method = if (mode == 2) "getIconMainColor" else "getSystemPrimaryColor"
            val tint = managerClass.getMethod(method).invoke(manager) as Int
            MoodCubeIconStyle(true, opaqueColor(tint))
        }.getOrElse {
            val resolver = context.contentResolver
            val mode = Settings.System.getInt(resolver, "theme_custom_icon_color_mode", 0)
            if (mode == 0) {
                MoodCubeIconStyle(false, Color.TRANSPARENT)
            } else {
                val key = if (mode == 2) "theme_custom_main_color" else "theme_custom_primary_color"
                MoodCubeIconStyle(true, opaqueColor(Settings.System.getInt(resolver, key, Color.GRAY)))
            }
        }
    }

    fun renderAospThemedLive(
        context: Context,
        drawable: Drawable,
        packageName: String?,
        targetSize: Int,
        forceMonet: Boolean,
        scale: Float
    ): Bitmap? = runCatching {
        val size = if (targetSize > 0) targetSize else 192
        val googleIcon = googleSystemIcon(context, packageName)
        val coreGlyph = if (googleIcon == null) coreSystemGlyph(context, packageName) else null
        val source = googleIcon ?: drawable
        val adaptive = if (source is AdaptiveIconDrawable) {
            source
        } else {
            if (!forceMonet && coreGlyph == null) return null
            // Transparent backdrop: AospMonochromeDrawable reads the icon's own shape from alpha.
            AdaptiveIconDrawable(ColorDrawable(Color.TRANSPARENT), InsetDrawable(source, 0.18f))
        }
        adaptive.setBounds(0, 0, size, size)
        val monochrome: Drawable = coreGlyph
            ?: (if (Build.VERSION.SDK_INT >= 33) adaptive.monochrome else null)
            ?: if (forceMonet) AospMonochromeDrawable(adaptive, size) else return null
        val foreground = getMonetForegroundColor(context)
        monochrome.colorFilter = if (Build.VERSION.SDK_INT >= 29) {
            BlendModeColorFilter(foreground, BlendMode.SRC_IN)
        } else {
            PorterDuffColorFilter(foreground, PorterDuff.Mode.SRC_IN)
        }
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val checkpoint = canvas.save()
        adaptive.iconMask?.let(canvas::clipPath)
        canvas.drawColor(getMonetBackgroundColor(context))
        val inset = size * AdaptiveIconDrawable.getExtraInsetFraction()
        val layerSize = (size + inset * 2f) * scale.coerceIn(0.65f, 1.18f)
        val left = ((size - layerSize) / 2f).roundToInt()
        val right = (left + layerSize).roundToInt()
        monochrome.setBounds(left, left, right, right)
        monochrome.draw(canvas)
        canvas.restoreToCount(checkpoint)
        result
    }.getOrNull()

    fun recolorVivoThemeIcon(context: Context, original: Bitmap): Bitmap? = runCatching {
        if (original.isRecycled || original.width <= 0 || original.height <= 0) return null
        val width = original.width
        val height = original.height
        val pixels = IntArray(width * height)
        original.getPixels(pixels, 0, width, 0, 0, width, height)
        val histogram = IntArray(4096)
        var opaquePixels = 0
        pixels.forEach { pixel ->
            if (Color.alpha(pixel) >= 224) {
                opaquePixels++
                histogram[colorBucket(pixel)]++
            }
        }
        if (opaquePixels == 0) return null
        var backgroundBucket = 0
        for (index in 1 until histogram.size) {
            if (histogram[index] > histogram[backgroundBucket]) backgroundBucket = index
        }
        if (histogram[backgroundBucket].toDouble() / opaquePixels < 0.35) return null
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L
        var samples = 0
        pixels.forEach { pixel ->
            if (Color.alpha(pixel) >= 224 && colorBucket(pixel) == backgroundBucket) {
                redSum += Color.red(pixel)
                greenSum += Color.green(pixel)
                blueSum += Color.blue(pixel)
                samples++
            }
        }
        if (samples == 0) return null
        val sourceRed = (redSum / samples).toInt()
        val sourceGreen = (greenSum / samples).toInt()
        val sourceBlue = (blueSum / samples).toInt()
        val background = getMonetBackgroundColor(context)
        val foreground = getMonetForegroundColor(context)
        pixels.indices.forEach { index ->
            val source = pixels[index]
            val alpha = Color.alpha(source)
            if (alpha == 0) return@forEach
            val redDelta = Color.red(source) - sourceRed
            val greenDelta = Color.green(source) - sourceGreen
            val blueDelta = Color.blue(source) - sourceBlue
            val distance = kotlin.math.sqrt((redDelta * redDelta + greenDelta * greenDelta + blueDelta * blueDelta).toDouble())
            val amount = ((distance - 10.0) / 42.0).coerceIn(0.0, 1.0)
            pixels[index] = Color.argb(
                alpha,
                mixChannel(Color.red(background), Color.red(foreground), amount),
                mixChannel(Color.green(background), Color.green(foreground), amount),
                mixChannel(Color.blue(background), Color.blue(foreground), amount)
            )
        }
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).also {
            it.density = original.density
        }
    }.getOrNull()

    fun applyMoodTint(original: Bitmap, tint: Int, amount: Float = 0.18f): Bitmap? = runCatching {
        if (original.isRecycled || original.width <= 0 || original.height <= 0) return null
        val result = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        result.density = original.density
        val canvas = Canvas(result)
        canvas.drawBitmap(original, 0f, 0f, null)
        val overlay = Color.argb(
            (255f * amount.coerceIn(0f, 1f)).roundToInt(),
            Color.red(tint),
            Color.green(tint),
            Color.blue(tint)
        )
        if (Build.VERSION.SDK_INT >= 29) {
            canvas.drawColor(overlay, BlendMode.SRC_ATOP)
        } else {
            canvas.drawColor(overlay, PorterDuff.Mode.SRC_ATOP)
        }
        result
    }.getOrNull()

    /** vivo's own icon color mode only tints our themed icons when no custom color was picked. */
    fun applyMoodTintIfNeeded(context: Context, icon: Bitmap, style: MoodCubeIconStyle): Bitmap {
        if (!style.themed || customThemedColor(context) != null) return icon
        return applyMoodTint(icon, style.tint) ?: icon
    }

    /** Background of live AOSP themed icons: Material You or the custom color, swapped when inverted. */
    fun getMonetBackgroundColor(context: Context): Int =
        if (isThemedInverted(context)) baseForegroundColor(context) else baseBackgroundColor(context)

    fun getMonetForegroundColor(context: Context): Int =
        if (isThemedInverted(context)) baseBackgroundColor(context) else baseForegroundColor(context)

    private fun baseBackgroundColor(context: Context): Int {
        val dark = isDarkMode(context)
        customThemedColor(context)?.let { return if (dark) blend(Color.parseColor("#1C1B1F"), it, 0.30f) else blend(Color.WHITE, it, 0.24f) }
        return runCatching {
            context.getColor(if (dark) 0x1060028 else 0x106003a)
        }.getOrElse {
            Color.parseColor(if (dark) "#1C1B1F" else "#E1E2EC")
        }
    }

    private fun baseForegroundColor(context: Context): Int {
        val dark = isDarkMode(context)
        customThemedColor(context)?.let { return if (dark) blend(Color.WHITE, it, 0.38f) else blend(it, Color.BLACK, 0.50f) }
        return runCatching {
            context.getColor(if (dark) 0x106003a else 0x1060034)
        }.getOrElse {
            Color.parseColor(if (dark) "#E1E2EC" else "#1C1B1F")
        }
    }

    private fun customThemedColor(context: Context): Int? =
        Settings.Global.getInt(context.contentResolver, SETTING_THEMED_COLOR, 0).takeIf { it != 0 }?.let(::opaqueColor)

    private fun isThemedInverted(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, SETTING_THEMED_INVERT, 0) == 1

    /** vivo's dialer lives in com.android.contacts, so glyph lookups use its activity class as the key. */
    const val VIVO_DIALER_CLASS = "com.android.dialer.TwelveKeyDialer"
    const val SETTING_THEMED_COLOR = "originicons_themed_icon_color"
    const val SETTING_THEMED_INVERT = "originicons_themed_icon_invert"

    fun renderAospThemed(
        context: Context,
        sourceDrawable: Drawable,
        size: Int,
        tint: Int?,
        inverted: Boolean,
        scale: Float,
        sourceLayer: Drawable? = null
    ): Bitmap {
        val adaptive = if (sourceDrawable is AdaptiveIconDrawable) {
            sourceDrawable
        } else {
            AdaptiveIconDrawable(ColorDrawable(Color.BLACK), InsetDrawable(sourceDrawable, 0.18f))
        }
        adaptive.setBounds(0, 0, size, size)
        val systemBackground = context.resources.getColor(0x106003a, context.theme)
        val systemForeground = context.resources.getColor(0x1060033, context.theme)
        val baseBackground = if (inverted) systemForeground else systemBackground
        val baseForeground = if (inverted) systemBackground else systemForeground
        val backgroundColor = tint?.let { blend(baseBackground, it, if (inverted) 0.22f else 0.16f) } ?: baseBackground
        val foregroundColor = tint?.let { blend(baseForeground, it, if (inverted) 0.34f else 0.26f) } ?: baseForeground
        val monochrome = getAospGlyphDrawable(context, sourceDrawable, null, size, sourceLayer)
        monochrome.colorFilter = BlendModeColorFilter(foregroundColor, BlendMode.SRC_IN)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val checkpoint = canvas.save()
        canvas.clipPath(adaptive.iconMask)
        canvas.drawColor(backgroundColor)
        val inset = size * AdaptiveIconDrawable.getExtraInsetFraction()
        val layerSize = (size + inset * 2f) * scale.coerceIn(0.65f, 1.18f)
        val left = ((size - layerSize) / 2f).roundToInt()
        val right = (left + layerSize).roundToInt()
        monochrome.setBounds(left, left, right, right)
        monochrome.draw(canvas)
        canvas.restoreToCount(checkpoint)
        return result
    }

    fun getAospGlyphDrawable(
        context: Context,
        sourceDrawable: Drawable,
        packageName: String?,
        size: Int,
        sourceLayer: Drawable? = null
    ): Drawable {
        val googleIcon = googleSystemIcon(context, packageName)
        val source = googleIcon ?: removeEmbeddedBackground(context, sourceDrawable)
        val coreGlyph = sourceLayer ?: (if (googleIcon == null) coreSystemGlyph(context, packageName) else null)
        val adaptive = if (source is AdaptiveIconDrawable) {
            source
        } else {
            AdaptiveIconDrawable(ColorDrawable(Color.BLACK), InsetDrawable(source, 0.18f))
        }
        adaptive.setBounds(0, 0, size, size)
        val layer = coreGlyph ?: (if (Build.VERSION.SDK_INT >= 33) adaptive.monochrome else null)
        return layer ?: AospMonochromeDrawable(adaptive, size)
    }

    internal fun removeEmbeddedBackground(context: Context, drawable: Drawable): Drawable {
        if (drawable is AdaptiveIconDrawable) return drawable
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        val bounds = opaqueBounds(pixels, size) ?: return BitmapDrawable(context.resources, bitmap)
        val samples = edgeSamples(pixels, size, bounds)
        if (samples.size < 80) return BitmapDrawable(context.resources, bitmap)
        val grouped = linkedMapOf<Int, Int>()
        samples.forEach { pixel ->
            val key = (Color.red(pixel) / 16 shl 8) or (Color.green(pixel) / 16 shl 4) or (Color.blue(pixel) / 16)
            grouped[key] = (grouped[key] ?: 0) + 1
        }
        val dominant = grouped.maxByOrNull { it.value } ?: return BitmapDrawable(context.resources, bitmap)
        val matchingSamples = samples.filter { pixel ->
            val key = (Color.red(pixel) / 16 shl 8) or (Color.green(pixel) / 16 shl 4) or (Color.blue(pixel) / 16)
            key == dominant.key
        }
        val background = Color.rgb(
            matchingSamples.sumOf { Color.red(it) } / matchingSamples.size,
            matchingSamples.sumOf { Color.green(it) } / matchingSamples.size,
            matchingSamples.sumOf { Color.blue(it) } / matchingSamples.size
        )
        val lightBackground = Color.red(background) * 0.2126f + Color.green(background) * 0.7152f + Color.blue(background) * 0.0722f > 185f
        if (dominant.value.toFloat() / samples.size < if (lightBackground) 0.28f else 0.46f) return BitmapDrawable(context.resources, bitmap)
        val tolerance = if (lightBackground) 104 else 58
        val original = pixels.copyOf()
        val visited = BooleanArray(pixels.size)
        val queue = ArrayDeque<Int>()
        edgeIndices(size, bounds).forEach { index ->
            if (!visited[index] && resemblesBackground(pixels[index], background, tolerance)) {
                visited[index] = true
                queue += index
            }
        }
        var removed = 0
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            removed++
            pixels[index] = Color.argb(0, Color.red(pixels[index]), Color.green(pixels[index]), Color.blue(pixels[index]))
            val x = index % size
            val y = index / size
            if (x > bounds[0]) queueBackground(index - 1, pixels, visited, background, tolerance, queue)
            if (x < bounds[2]) queueBackground(index + 1, pixels, visited, background, tolerance, queue)
            if (y > bounds[1]) queueBackground(index - size, pixels, visited, background, tolerance, queue)
            if (y < bounds[3]) queueBackground(index + size, pixels, visited, background, tolerance, queue)
        }
        val opaque = original.count { Color.alpha(it) > 48 }
        if (removed < opaque * 0.28f || removed > opaque * 0.94f) {
            return BitmapDrawable(context.resources, Bitmap.createBitmap(original, size, size, Bitmap.Config.ARGB_8888))
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun opaqueBounds(pixels: IntArray, size: Int): IntArray? {
        var left = size
        var top = size
        var right = -1
        var bottom = -1
        pixels.forEachIndexed { index, pixel ->
            if (Color.alpha(pixel) <= 48) return@forEachIndexed
            val x = index % size
            val y = index / size
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
        return if (right < left || bottom < top) null else intArrayOf(left, top, right, bottom)
    }

    private fun edgeSamples(pixels: IntArray, size: Int, bounds: IntArray): List<Int> {
        val result = arrayListOf<Int>()
        for (x in bounds[0]..bounds[2]) {
            listOf(bounds[1] * size + x, bounds[3] * size + x).forEach { index ->
                if (Color.alpha(pixels[index]) > 48) result += pixels[index]
            }
        }
        for (y in bounds[1]..bounds[3]) {
            listOf(y * size + bounds[0], y * size + bounds[2]).forEach { index ->
                if (Color.alpha(pixels[index]) > 48) result += pixels[index]
            }
        }
        return result
    }

    private fun edgeIndices(size: Int, bounds: IntArray): Set<Int> {
        val result = linkedSetOf<Int>()
        for (x in bounds[0]..bounds[2]) {
            result += bounds[1] * size + x
            result += bounds[3] * size + x
        }
        for (y in bounds[1]..bounds[3]) {
            result += y * size + bounds[0]
            result += y * size + bounds[2]
        }
        return result
    }

    private fun queueBackground(
        index: Int,
        pixels: IntArray,
        visited: BooleanArray,
        background: Int,
        tolerance: Int,
        queue: ArrayDeque<Int>
    ) {
        if (!visited[index] && resemblesBackground(pixels[index], background, tolerance)) {
            visited[index] = true
            queue += index
        }
    }

    private fun resemblesBackground(pixel: Int, background: Int, tolerance: Int): Boolean {
        if (Color.alpha(pixel) <= 48) return false
        val red = Color.red(pixel) - Color.red(background)
        val green = Color.green(pixel) - Color.green(background)
        val blue = Color.blue(pixel) - Color.blue(background)
        return red * red + green * green + blue * blue <= tolerance * tolerance
    }

    private fun opaqueColor(color: Int): Int {
        return if (Color.alpha(color) == 0) color or -0x1000000 else color
    }

    private fun colorBucket(color: Int): Int {
        return (Color.red(color) / 16 shl 8) or (Color.green(color) / 16 shl 4) or (Color.blue(color) / 16)
    }

    private fun mixChannel(background: Int, foreground: Int, amount: Double): Int {
        return (background + (foreground - background) * amount).roundToInt().coerceIn(0, 255)
    }

    internal fun googleSystemIcon(context: Context, packageName: String?): Drawable? {
        val googlePackage = when (packageName) {
            "com.android.camera" -> "com.google.android.GoogleCamera"
            "com.vivo.gallery" -> "com.google.android.apps.photos"
            "com.android.contacts" -> "com.google.android.contacts"
            VIVO_DIALER_CLASS -> "com.google.android.dialer"
            "com.android.mms" -> "com.google.android.apps.messaging"
            "com.android.BBKClock" -> "com.google.android.deskclock"
            "com.bbk.calendar" -> "com.google.android.calendar"
            "com.android.bbkcalculator" -> "com.google.android.calculator"
            "com.android.bbksoundrecorder" -> "com.google.android.apps.recorder"
            "com.vivo.email" -> "com.google.android.gm"
            "com.vivo.health" -> "com.fitbit.FitbitMobile"
            "com.bbk.appstore" -> "com.android.vending"
            else -> return null
        }
        return runCatching { context.packageManager.getApplicationIcon(googlePackage) }.getOrNull()
    }

    internal fun coreSystemGlyph(context: Context, packageName: String?): Drawable? {
        val resource = when (packageName) {
            "com.android.camera" -> R.drawable.ic_pixel_camera
            "com.vivo.gallery" -> R.drawable.ic_pixel_gallery
            "com.android.contacts" -> R.drawable.ic_pixel_contacts
            VIVO_DIALER_CLASS -> R.drawable.ic_pixel_phone
            "com.android.mms" -> R.drawable.ic_pixel_messages
            "com.android.BBKClock" -> R.drawable.ic_pixel_clock
            "com.bbk.calendar" -> R.drawable.ic_pixel_calendar
            "com.android.bbkcalculator" -> R.drawable.ic_pixel_calculator
            "com.android.bbksoundrecorder" -> R.drawable.ic_pixel_recorder
            "com.android.settings" -> R.drawable.ic_pixel_settings
            "com.vivo.weather" -> R.drawable.ic_pixel_weather
            "com.vivo.compass" -> R.drawable.ic_pixel_compass
            "com.vivo.email" -> R.drawable.ic_pixel_mail
            "com.vivo.safecenter" -> R.drawable.ic_pixel_shield
            "com.vivo.health" -> R.drawable.ic_pixel_heart
            "com.vivo.Tips" -> R.drawable.ic_pixel_lightbulb
            else -> return null
        }
        return moduleContext(context).getDrawable(resource)?.let { InsetDrawable(it, 0.29f) }
    }

    /** Inside hooked apps R.drawable ids point into the module APK, not the host's resources. */
    private fun moduleContext(context: Context): Context {
        if (context.packageName == BuildConfig.APPLICATION_ID) return context
        moduleContext?.let { return it }
        return context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
            .also { moduleContext = it }
    }

    @Volatile
    private var moduleContext: Context? = null

    private fun isDarkMode(context: Context): Boolean {
        return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun blend(first: Int, second: Int, amount: Float): Int {
        val ratio = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(first) * (1f - ratio) + Color.red(second) * ratio).roundToInt(),
            (Color.green(first) * (1f - ratio) + Color.green(second) * ratio).roundToInt(),
            (Color.blue(first) * (1f - ratio) + Color.blue(second) * ratio).roundToInt()
        )
    }
}
