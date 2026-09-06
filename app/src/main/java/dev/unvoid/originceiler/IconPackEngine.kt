package dev.unvoid.originceiler

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.pow

data class IconPack(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

data class Coverage(
    val mapped: Int,
    val fallback: Int,
    val preview: List<Drawable>
) {
    val total: Int get() = mapped + fallback
}

data class ConversionResult(
    val converted: Int,
    val total: Int,
    val skipped: Int,
    val packages: List<String>,
    val stage: File
)

enum class IconSource {
    SYSTEM,
    PALETTE,
    AOSP_THEMED,
    PACK
}

class IconPackEngine(private val context: Context) {
    private val pm = context.packageManager

    fun findPacks(): List<IconPack> {
        val packages = linkedSetOf<String>()
        listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME").forEach { action ->
            queryActivities(Intent(action)).forEach { packages += it.activityInfo.packageName }
        }
        val discovered = packages.mapNotNull { packageName ->
            runCatching {
                val info = getApplicationInfo(packageName)
                IconPack(packageName, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
            }.getOrNull()
        }.sortedBy { it.label.lowercase(Locale.getDefault()) }
        return discovered
    }

    fun coverage(pack: IconPack, fullConversion: Boolean): Coverage {
        val packContext = context.createPackageContext(pack.packageName, Context.CONTEXT_IGNORE_SECURITY)
        val mapped = readAppFilter(pack.packageName)
        val fallback = readFallback(pack.packageName, mapped.keys)
        val installed = installedLauncherPackages()
        val visibleMapped = if (fullConversion) mapped else mapped.filterKeys { it in installed }
        val visibleFallback = if (fullConversion) fallback else fallback.filterKeys { it in installed }
        val preview = (visibleMapped.values + visibleFallback.values).distinct().take(8).mapNotNull {
            loadDrawable(pack.packageName, packContext.resources, packContext.theme, it)
        }
        return Coverage(visibleMapped.size, visibleFallback.size, preview)
    }

    fun paletteCoverage(tint: Int, tone: Float): Coverage {
        val packages = installedLauncherPackages().toList()
        val preview = packages.take(8).mapNotNull { packageName ->
            runCatching { tintedDrawable(pm.getApplicationIcon(packageName), tint, tone) }.getOrNull()
        }
        return Coverage(0, packages.size, preview)
    }

    fun convertPalette(tint: Int, tone: Float, progress: (Int, Int) -> Unit): ConversionResult {
        val packages = installedLauncherPackages().toList()
        val stage = File(context.getExternalFilesDir(null), "staging")
        stage.deleteRecursively()
        stage.mkdirs()
        val written = arrayListOf<String>()
        var skipped = 0
        packages.forEachIndexed { index, packageName ->
            val icon = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
            val output = File(stage, "$packageName.png")
            if (icon != null && validOutputName(packageName) && renderPalette(icon, output, tint, tone)) {
                written += packageName
            } else {
                skipped++
            }
            progress(index + 1, packages.size)
        }
        val dialerTarget = File(stage, "com.android.dialer.TwelveKeyDialer.png")
        val contactsFile = File(stage, "com.android.contacts.png")
        if (!dialerTarget.exists() && contactsFile.exists()) {
            contactsFile.copyTo(dialerTarget, overwrite = true)
            written += "com.android.dialer.TwelveKeyDialer"
        }
        return ConversionResult(written.size, packages.size, skipped, written, stage)
    }

    fun aospThemedCoverage(tint: Int?, inverted: Boolean, scale: Float): Coverage {
        val packages = installedLauncherPackages().toList()
        val preview = packages.take(8).mapNotNull { packageName ->
            runCatching {
                BitmapDrawable(context.resources, aospThemedBitmap(pm.getApplicationIcon(packageName), 192, packageName, tint, inverted, scale))
            }.getOrNull()
        }
        return Coverage(packages.size, 0, preview)
    }

    fun convertAospThemed(tint: Int?, inverted: Boolean, scale: Float, progress: (Int, Int) -> Unit): ConversionResult {
        val packages = installedLauncherPackages().toList()
        val stage = File(context.getExternalFilesDir(null), "staging")
        stage.deleteRecursively()
        stage.mkdirs()
        val written = arrayListOf<String>()
        var skipped = 0
        packages.forEachIndexed { index, packageName ->
            val icon = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
            val output = File(stage, "$packageName.png")
            if (icon != null && validOutputName(packageName) && renderAospThemed(icon, output, packageName, tint, inverted, scale)) {
                written += packageName
            } else {
                skipped++
            }
            progress(index + 1, packages.size)
        }
        val dialerTarget = File(stage, "com.android.dialer.TwelveKeyDialer.png")
        val contactsIcon = runCatching { pm.getApplicationIcon("com.android.contacts") }.getOrNull()
        if (!dialerTarget.exists() && contactsIcon != null && renderAospThemed(contactsIcon, dialerTarget, "com.android.dialer.TwelveKeyDialer", tint, inverted, scale)) {
            written += "com.android.dialer.TwelveKeyDialer"
        }
        return ConversionResult(written.size, packages.size, skipped, written, stage)
    }

    fun convert(pack: IconPack, fullConversion: Boolean, autoRecolor: Boolean, progress: (Int, Int) -> Unit): ConversionResult {
        val packContext = context.createPackageContext(pack.packageName, Context.CONTEXT_IGNORE_SECURITY)
        val mapped = readAppFilter(pack.packageName)
        val fallback = readFallback(pack.packageName, mapped.keys)
        val all = linkedMapOf<String, String>().apply {
            putAll(mapped)
            fallback.forEach { (name, drawable) -> putIfAbsent(name, drawable) }
        }
        val installed = installedLauncherPackages()
        val complete = if (fullConversion) all else LinkedHashMap(all.filterKeys { it in installed })
        if (all.isEmpty()) throw IllegalStateException("No icons found in this icon pack")
        val stage = File(context.getExternalFilesDir(null), "staging")
        stage.deleteRecursively()
        stage.mkdirs()
        val written = arrayListOf<String>()
        var skipped = 0
        complete.entries.forEachIndexed { index, entry ->
            val drawable = loadDrawable(pack.packageName, packContext.resources, packContext.theme, entry.value)
            if (drawable != null && validOutputName(entry.key)) {
                val output = File(stage, "${entry.key}.png")
                if (render(drawable, output)) written += entry.key else skipped++
            } else {
                skipped++
            }
            progress(index + 1, complete.size)
        }
        if (autoRecolor) {
            val sources = all.values.distinct().take(12).mapNotNull {
                loadDrawable(pack.packageName, packContext.resources, packContext.theme, it)
            }.ifEmpty { listOf(pack.icon) }
            val tint = representativeColor(sources)
            installed.forEach { packageName ->
                val output = File(stage, "$packageName.png")
                if (!output.exists() && validOutputName(packageName)) {
                    val systemIcon = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
                    if (systemIcon != null && renderTinted(systemIcon, output, tint)) written += packageName
                }
            }
        }
        val dialerTarget = File(stage, "com.android.dialer.TwelveKeyDialer.png")
        if (!dialerTarget.exists()) {
            val contactsFile = File(stage, "com.android.contacts.png")
            if (contactsFile.exists()) {
                contactsFile.copyTo(dialerTarget, overwrite = true)
                written += "com.android.dialer.TwelveKeyDialer"
            } else {
                val contactsDrawable = all["com.android.contacts"]?.let {
                    loadDrawable(pack.packageName, packContext.resources, packContext.theme, it)
                }
                if (contactsDrawable != null && render(contactsDrawable, dialerTarget)) {
                    written += "com.android.dialer.TwelveKeyDialer"
                }
            }
        }
        return ConversionResult(written.size, complete.size, skipped, written, stage)
    }

    private fun readAppFilter(packageName: String): Map<String, String> {
        val packContext = context.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
        val resources = packContext.resources
        val stream = runCatching { packContext.assets.open("appfilter.xml") }.getOrElse {
            val id = resources.getIdentifier("appfilter", "raw", packageName)
            if (id == 0) return emptyMap()
            resources.openRawResource(id)
        }
        val result = linkedMapOf<String, String>()
        runCatching {
            stream.use { input ->
                val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name.equals("item", true)) {
                        val component = attribute(parser, "component")
                        val drawable = attribute(parser, "drawable")
                        if (!component.isNullOrBlank() && !drawable.isNullOrBlank()) {
                            val normalized = normalizeComponent(component)
                            if (normalized != null && validOutputName(normalized.packageName)) {
                                result[normalized.packageName] = drawable
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        }
        return result
    }

    private fun readFallback(packageName: String, mappedPackages: Set<String>): Map<String, String> {
        val info = getApplicationInfo(packageName)
        val names = linkedSetOf<String>()
        ZipFile(info.sourceDir).use { apk ->
            val entries = apk.entries()
            while (entries.hasMoreElements()) {
                val path = entries.nextElement().name
                if (!path.startsWith("res/drawable") && !path.startsWith("res/mipmap")) continue
                val file = path.substringAfterLast('/')
                val lower = file.lowercase(Locale.US)
                if (!lower.endsWith(".png") && !lower.endsWith(".webp") && !lower.endsWith(".xml")) continue
                if (lower.endsWith(".9.png")) continue
                names += file.substringBeforeLast('.')
            }
        }
        val result = linkedMapOf<String, String>()
        names.forEach { drawable ->
            if (drawable.startsWith("abc_") || drawable.startsWith("ic_menu_")) return@forEach
            val outputName = drawable.replace('_', '.')
            if (outputName.contains('.') && validOutputName(outputName) && outputName !in mappedPackages) {
                result.putIfAbsent(outputName, drawable)
            }
        }
        return result
    }

    private fun attribute(parser: XmlPullParser, name: String): String? {
        for (index in 0 until parser.attributeCount) {
            if (parser.getAttributeName(index).equals(name, true)) return parser.getAttributeValue(index)
        }
        return null
    }

    private fun normalizeComponent(value: String): ComponentName? {
        val raw = value.removePrefix("ComponentInfo{").removeSuffix("}")
        val separator = raw.indexOf('/')
        if (separator <= 0 || separator == raw.lastIndex) return null
        val packageName = raw.substring(0, separator)
        val classValue = raw.substring(separator + 1)
        val className = if (classValue.startsWith('.')) packageName + classValue else classValue
        return ComponentName(packageName, className)
    }

    private fun loadDrawable(packageName: String, resources: Resources, theme: Resources.Theme, name: String): Drawable? {
        return runCatching {
            val id = resources.getIdentifier(name, "drawable", packageName).takeIf { it != 0 }
                ?: resources.getIdentifier(name, "mipmap", packageName).takeIf { it != 0 }
                ?: return null
            resources.getDrawable(id, theme)
        }.getOrNull()
    }

    private fun queryActivities(intent: Intent) = if (Build.VERSION.SDK_INT >= 33) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
    }

    private fun installedLauncherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return queryActivities(intent).mapTo(linkedSetOf()) { it.activityInfo.packageName }
    }

    private fun getApplicationInfo(packageName: String): ApplicationInfo = if (Build.VERSION.SDK_INT >= 33) {
        pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(packageName, 0)
    }

    private fun render(drawable: Drawable, output: File): Boolean = runCatching {
        val bitmap = Bitmap.createBitmap(620, 620, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, 620, 620)
        drawable.draw(canvas)
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        true
    }.getOrDefault(false)

    private fun renderTinted(drawable: Drawable, output: File, tint: Int): Boolean = runCatching {
        val result = autoRecolorBitmap(drawable, tint, 620)
        FileOutputStream(output).use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
        result.recycle()
        true
    }.getOrDefault(false)

    private fun autoRecolorBitmap(drawable: Drawable, tint: Int, size: Int): Bitmap {
        val source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(source))
        val monochrome = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val monochromeMatrix = ColorMatrix().apply { setSaturation(0f) }
        Canvas(monochrome).drawBitmap(
            source,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(monochromeMatrix) }
        )
        val strongest = maxOf(Color.red(tint), Color.green(tint), Color.blue(tint), 1).toFloat()
        val red = Color.red(tint) / strongest
        val green = Color.green(tint) / strongest
        val blue = Color.blue(tint) / strongest
        val tintMatrix = ColorMatrixColorFilter(
            floatArrayOf(
                red, 0f, 0f, 0f, 0f,
                0f, green, 0f, 0f, 0f,
                0f, 0f, blue, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(
            monochrome,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = tintMatrix }
        )
        source.recycle()
        monochrome.recycle()
        return result
    }

    private fun renderPalette(drawable: Drawable, output: File, tint: Int, tone: Float): Boolean = runCatching {
        val result = paletteBitmap(drawable, tint, tone, 620)
        FileOutputStream(output).use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
        result.recycle()
        true
    }.getOrDefault(false)

    private fun renderAospThemed(drawable: Drawable, output: File, packageName: String, tint: Int?, inverted: Boolean, scale: Float): Boolean = runCatching {
        val result = aospThemedBitmap(drawable, 620, packageName, tint, inverted, scale)
        FileOutputStream(output).use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
        result.recycle()
        true
    }.getOrDefault(false)

    private fun aospThemedBitmap(drawable: Drawable, size: Int, packageName: String, tint: Int?, inverted: Boolean, scale: Float): Bitmap {
        val googleIcon = googleSystemIcon(packageName)
        val sourceDrawable = googleIcon ?: removeEmbeddedBackground(drawable)
        val layer = (if (googleIcon == null) coreSystemGlyph(packageName) else null)
        return MonetThemedIconFactory.renderAospThemed(context, sourceDrawable, size, tint, inverted, scale, layer)
    }

    private fun googleSystemIcon(packageName: String): Drawable? {
        val googlePackage = when (packageName) {
            "com.android.camera" -> "com.google.android.GoogleCamera"
            "com.vivo.gallery" -> "com.google.android.apps.photos"
            "com.android.contacts" -> "com.google.android.contacts"
            "com.android.dialer.TwelveKeyDialer" -> "com.google.android.dialer"
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
        return runCatching { pm.getApplicationIcon(googlePackage) }.getOrNull()
    }

    private fun removeEmbeddedBackground(drawable: Drawable): Drawable {
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
        val lightBackground = (Color.red(background) * 0.2126f + Color.green(background) * 0.7152f + Color.blue(background) * 0.0722f) > 185f
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
        if (removed < opaque * 0.28f || removed > opaque * 0.94f) return BitmapDrawable(context.resources, Bitmap.createBitmap(original, size, size, Bitmap.Config.ARGB_8888))
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
            listOf(bounds[1] * size + x, bounds[3] * size + x).forEach { index -> if (Color.alpha(pixels[index]) > 48) result += pixels[index] }
        }
        for (y in bounds[1]..bounds[3]) {
            listOf(y * size + bounds[0], y * size + bounds[2]).forEach { index -> if (Color.alpha(pixels[index]) > 48) result += pixels[index] }
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

    private fun queueBackground(index: Int, pixels: IntArray, visited: BooleanArray, background: Int, tolerance: Int, queue: ArrayDeque<Int>) {
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

    private fun coreSystemGlyph(packageName: String): Drawable? {
        val resource = when (packageName) {
            "com.android.camera" -> R.drawable.ic_pixel_camera
            "com.vivo.gallery" -> R.drawable.ic_pixel_gallery
            "com.android.contacts" -> R.drawable.ic_pixel_contacts
            "com.android.dialer.TwelveKeyDialer" -> R.drawable.ic_pixel_phone
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
        return InsetDrawable(context.getDrawable(resource), 0.29f)
    }

    private fun tintedDrawable(drawable: Drawable, tint: Int, tone: Float): Drawable {
        return android.graphics.drawable.BitmapDrawable(context.resources, paletteBitmap(drawable, tint, tone, 192))
    }

    private fun paletteBitmap(drawable: Drawable, tint: Int, tone: Float, size: Int): Bitmap {
        val source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(source))
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        source.getPixels(pixels, 0, size, 0, 0, size, size)
        val vividTint = boostSaturation(tint)
        val strength = 0.55f + tone.coerceIn(0f, 1f) * 0.4f
        val brightness = 0.78f + tone.coerceIn(0f, 1f) * 0.38f
        val tr = Color.red(vividTint) / 255f
        val tg = Color.green(vividTint) / 255f
        val tb = Color.blue(vividTint) / 255f
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val alpha = Color.alpha(pixel)
            if (alpha == 0) continue
            val luminance = (
                Color.red(pixel) * 0.2126f +
                    Color.green(pixel) * 0.7152f +
                    Color.blue(pixel) * 0.0722f
                ) / 255f
            val contrasted = ((luminance - 0.5f) * 1.42f + 0.5f).coerceIn(0f, 1f)
            val shaded = contrasted.toDouble().pow(1.08).toFloat()
            val red = (shaded * (1f - strength) + shaded * tr * strength) * brightness
            val green = (shaded * (1f - strength) + shaded * tg * strength) * brightness
            val blue = (shaded * (1f - strength) + shaded * tb * strength) * brightness
            pixels[index] = Color.argb(
                alpha,
                (red * 255f).toInt().coerceIn(0, 255),
                (green * 255f).toInt().coerceIn(0, 255),
                (blue * 255f).toInt().coerceIn(0, 255)
            )
        }
        result.setPixels(pixels, 0, size, 0, 0, size, size)
        source.recycle()
        return result
    }

    private fun boostSaturation(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * 1.35f).coerceAtLeast(0.88f).coerceAtMost(1f)
        hsv[2] = (hsv[2] * 1.08f).coerceAtMost(1f)
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    private fun representativeColor(drawables: List<Drawable>): Int {
        val colors = drawables.map(::drawableColor)
        return Color.rgb(
            colors.sumOf { Color.red(it) } / colors.size,
            colors.sumOf { Color.green(it) } / colors.size,
            colors.sumOf { Color.blue(it) } / colors.size
        )
    }

    private fun drawableColor(drawable: Drawable): Int {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 96, 96)
        drawable.draw(Canvas(bitmap))
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 80) {
                    red += Color.red(pixel)
                    green += Color.green(pixel)
                    blue += Color.blue(pixel)
                    count++
                }
            }
        }
        bitmap.recycle()
        if (count == 0L) return Color.GRAY
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun validOutputName(name: String): Boolean {
        return name.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
    }
}
