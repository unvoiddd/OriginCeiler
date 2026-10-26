package dev.unvoid.originceiler

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipFile

/** Resolves an installed ADW/Nova icon pack inside the launcher process. */
internal class LauncherIconPack(private val context: Context) {
    private var activePackage = ""
    private var packContext: Context? = null
    private var components = emptyMap<String, String>()
    private var packages = emptyMap<String, String>()
    private var packColor: Int? = null
    private var iconMask: Drawable? = null
    private var loggedRecolor = false
    private var loggedBackground = false

    @Synchronized
    fun bitmap(component: ComponentName?, packageName: String?, original: Bitmap, source: Drawable?): Bitmap? {
        val result = packBitmap(component, packageName, original, source) ?: return null
        return applyEffects(result).also { if (it !== result) result.recycle() }
    }

    /** User adjustments for any icon coming from the pack: scale, monochrome, inversion, color filter. */
    private fun applyEffects(icon: Bitmap): Bitmap {
        val resolver = context.contentResolver
        val scale = Settings.Global.getInt(resolver, SETTING_SCALE, 100).coerceIn(SCALE_MIN, SCALE_MAX) / 100f
        val mono = Settings.Global.getInt(resolver, SETTING_MONOCHROME, 0) == 1
        val invert = Settings.Global.getInt(resolver, SETTING_INVERT, 0) == 1
        val tint = Settings.Global.getInt(resolver, SETTING_TINT, 0)
        if (scale == 1f && !mono && !invert && tint == 0) return icon
        val matrix = ColorMatrix()
        if (mono || tint != 0) matrix.postConcat(ColorMatrix().apply { setSaturation(0f) })
        if (invert) {
            matrix.postConcat(ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        if (tint != 0) {
            // Gray levels mapped onto the chosen color, white stays light so details survive.
            matrix.postConcat(ColorMatrix(floatArrayOf(
                Color.red(tint) / 255f, 0f, 0f, 0f, 0f,
                0f, Color.green(tint) / 255f, 0f, 0f, 0f,
                0f, 0f, Color.blue(tint) / 255f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        val size = icon.width
        return runCatching {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { result ->
                result.density = icon.density
                val inset = size * (1f - scale) / 2f
                Canvas(result).drawBitmap(
                    icon,
                    null,
                    android.graphics.RectF(inset, inset, size - inset, size - inset),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(matrix)
                    }
                )
            }
        }.getOrDefault(icon)
    }

    private fun packBitmap(component: ComponentName?, packageName: String?, original: Bitmap, source: Drawable?): Bitmap? {
        val size = original.width
        if (size !in 1..2048 || original.height != size) return null

        val selected = Settings.Global.getString(context.contentResolver, SETTING_PACK).orEmpty()
        if (selected.isBlank()) return null
        if (selected != activePackage) load(selected)
        val target = packContext ?: return null
        val names = listOfNotNull(
            component?.flattenToString()?.let(components::get),
            packageName?.let(packages::get),
            component?.packageName?.let(packages::get),
            packageName?.replace('.', '_'),
            component?.packageName?.replace('.', '_')
        ).distinct()
        names.forEach { name ->
            loadDrawable(target, selected, name)?.let { return render(it, size) }
        }

        val replaceBackground = Settings.Global.getInt(context.contentResolver, SETTING_REPLACE_BACKGROUND, 0) == 1
        val autoRecolor = Settings.Global.getInt(context.contentResolver, SETTING_AUTO_RECOLOR, 0) == 1
        if (!replaceBackground && !autoRecolor) return null
        val installedIcon = component?.let {
            runCatching { context.packageManager.getActivityIcon(it) }.getOrNull()
        } ?: packageName?.let {
            runCatching { context.packageManager.getApplicationIcon(it) }.getOrNull()
        }
        val appIcon = installedIcon ?: source
        if (replaceBackground) {
            val adaptive = (appIcon as? AdaptiveIconDrawable) ?: (source as? AdaptiveIconDrawable)
            val mask = iconMask
            if (adaptive != null && mask != null && adaptive.foreground != null && adaptive.background != null) {
                composeOnMask(adaptive.foreground, mask, size)?.let {
                    if (!loggedBackground) {
                        Log.i("OriginIconPack", "Background replaced for $packageName")
                        loggedBackground = true
                    }
                    return it
                }
            }
        }
        if (autoRecolor) {
            packColor?.let { color ->
                val rendered = appIcon?.let { render(it, size) }
                val result = recolor(rendered ?: original, color)
                rendered?.recycle()
                if (result != null && !loggedRecolor) {
                    Log.i("OriginIconPack", "Missing icon recolored for $packageName")
                    loggedRecolor = true
                }
                return result
            }
        }
        return null
    }

    private fun load(packageName: String) {
        activePackage = packageName
        packContext = null
        components = emptyMap()
        packages = emptyMap()
        packColor = null
        iconMask = null
        loggedRecolor = false
        loggedBackground = false
        val target = runCatching { context.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY) }.getOrNull() ?: return
        packContext = target
        val input = runCatching { target.assets.open("appfilter.xml") }.getOrElse {
            val id = target.resources.getIdentifier("appfilter", "raw", packageName)
            if (id == 0) return
            runCatching { target.resources.openRawResource(id) }.getOrNull() ?: return
        }
        val byComponent = linkedMapOf<String, String>()
        val byPackage = linkedMapOf<String, String>()
        var maskName: String? = null
        runCatching {
            input.use { stream ->
                val parser = Xml.newPullParser().apply { setInput(stream, "UTF-8") }
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name.equals("iconmask", true)) {
                        maskName = parser.getAttributeValue(null, "img")
                    }
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name.equals("item", true)) {
                        val raw = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        val component = raw?.removePrefix("ComponentInfo{")?.removeSuffix("}")
                            ?.let(ComponentName::unflattenFromString)
                        if (component != null && !drawable.isNullOrBlank()) {
                            byComponent[component.flattenToString()] = drawable
                            byPackage.putIfAbsent(component.packageName, drawable)
                        }
                    }
                    parser.next()
                }
            }
        }
        components = byComponent
        packages = byPackage
        val maskResource = maskName?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) } ?: "iconmask"
        if (hasIconMaskPng(packageName, maskResource)) {
            iconMask = loadDrawable(target, packageName, maskResource) ?: runCatching {
                target.assets.open("$maskResource.png").use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { BitmapDrawable(target.resources, it) }
                }
            }.getOrNull()
        }
        val colors = byPackage.values.distinct().asSequence().mapNotNull {
            loadDrawable(target, packageName, it)?.let(::drawableColor)
        }.take(12).toList()
        if (colors.isNotEmpty()) {
            packColor = Color.rgb(
                colors.sumOf(Color::red) / colors.size,
                colors.sumOf(Color::green) / colors.size,
                colors.sumOf(Color::blue) / colors.size
            )
        }
        Log.i("OriginIconPack", "Loaded $packageName: ${components.size} components, mask=${iconMask != null}, tint=${packColor != null}")
    }

    private fun loadDrawable(target: Context, packageName: String, name: String): Drawable? {
        val resources = target.resources
        val id = resources.getIdentifier(name, "drawable", packageName).takeIf { it != 0 }
            ?: resources.getIdentifier(name, "mipmap", packageName).takeIf { it != 0 }
            ?: return null
        return runCatching { resources.getDrawable(id, target.theme) }.getOrNull()
    }

    private fun hasIconMaskPng(packageName: String, name: String): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).any { apk ->
            ZipFile(apk).use { archive ->
                archive.entries().asSequence().any { entry ->
                    entry.name == "assets/$name.png" ||
                        (entry.name.startsWith("res/") && entry.name.substringAfterLast('/') == "$name.png")
                }
            }
        }
    }.getOrDefault(false)

    private fun render(drawable: Drawable, size: Int): Bitmap? = runCatching {
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
        }
    }.getOrNull()

    private fun drawableColor(drawable: Drawable): Int? {
        val bitmap = render(drawable, 96) ?: return null
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        for (y in 0 until 96 step 2) for (x in 0 until 96 step 2) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) > 80) {
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count++
            }
        }
        bitmap.recycle()
        return if (count == 0L) null else Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun recolor(source: Bitmap, color: Int): Bitmap? = runCatching {
        val size = source.width
        val grayscale = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(grayscale).drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        })
        val strongest = maxOf(Color.red(color), Color.green(color), Color.blue(color), 1).toFloat()
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(grayscale, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(floatArrayOf(
                Color.red(color) / strongest, 0f, 0f, 0f, 0f,
                0f, Color.green(color) / strongest, 0f, 0f, 0f,
                0f, 0f, Color.blue(color) / strongest, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
        })
        grayscale.recycle()
        result
    }.getOrNull()

    private fun composeOnMask(foreground: Drawable, mask: Drawable, size: Int): Bitmap? = runCatching {
        val background = render(mask, size) ?: return null
        val result = background.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        // Adaptive layers are drawn at 1.5x the visible viewport by Android.
        val inset = size / 4
        foreground.setBounds(-inset, -inset, size + inset, size + inset)
        foreground.draw(canvas)
        canvas.drawBitmap(background, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        background.recycle()
        result
    }.getOrNull()

    companion object {
        const val SETTING_PACK = "originicons_live_icon_pack"
        const val SETTING_AUTO_RECOLOR = "originicons_live_pack_recolor"
        const val SETTING_REPLACE_BACKGROUND = "originicons_live_pack_background"
        const val SETTING_SCALE = "originicons_live_pack_scale"
        const val SETTING_MONOCHROME = "originicons_live_pack_monochrome"
        const val SETTING_INVERT = "originicons_live_pack_invert"
        const val SETTING_TINT = "originicons_live_pack_tint"
        const val SCALE_MIN = 50
        const val SCALE_MAX = 150
    }
}
