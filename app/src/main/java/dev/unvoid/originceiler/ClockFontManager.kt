package dev.unvoid.originceiler

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Bulletproof font loader and cache for lockscreen clocks.
 * SystemUI runs with its own assets, which do not contain our module fonts.
 * This manager unpacks assets from the module APK to a cached readable directory
 * and constructs authentic variable Typeface instances with 'wght' and 'ytde' axes.
 */
object ClockFontManager {

    private const val MODULE_PACKAGE = "dev.unvoid.originceiler"
    private val fontFileCache = ConcurrentHashMap<String, File>()

    @JvmStatic
    fun getFontFile(context: Context, assetSubpath: String): File {
        val fileName = File(assetSubpath).name
        fontFileCache[fileName]?.let { if (it.exists() && it.length() > 0) return it }

        // Use cache directory accessible by the caller (SystemUI or app)
        val cacheDir = File(context.cacheDir, "clock_fonts").apply { mkdirs() }
        val target = File(cacheDir, fileName)
        if (target.exists() && target.length() > 0) {
            fontFileCache[fileName] = target
            return target
        }

        // Extract from module package assets using direct asset stream
        runCatching {
            LockscreenClockRuntime.openAsset(context, assetSubpath).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            target.setReadable(true, false)
            fontFileCache[fileName] = target
            return target
        }

        return target
    }

    @JvmStatic
    fun createTypeface(
        context: Context,
        assetSubpath: String,
        variationSettings: String? = null
    ): Typeface {
        val fileName = File(assetSubpath).name

        // 1. Try from cached extracted file (safest across processes and classloaders)
        val file = getFontFile(context, assetSubpath)
        if (file != null && file.exists() && file.length() > 0) {
            val tf = runCatching {
                val builder = Typeface.Builder(file)
                if (!variationSettings.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    builder.setFontVariationSettings(variationSettings)
                }
                builder.build()
            }.getOrNull()
            if (tf != null && tf != Typeface.DEFAULT) {
                return tf
            }
            val plainTf = runCatching { Typeface.Builder(file).build() }.getOrNull()
            if (plainTf != null && plainTf != Typeface.DEFAULT) {
                return plainTf
            }
        }

        // 2. Try directly from module assets if possible
        val moduleContext = LockscreenClockRuntime.getModuleContext(context)
        val assetTf = runCatching {
            val builder = Typeface.Builder(moduleContext.assets, assetSubpath)
            if (!variationSettings.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setFontVariationSettings(variationSettings)
            }
            builder.build()
        }.getOrNull()
        if (assetTf != null && assetTf != Typeface.DEFAULT) {
            return assetTf
        }

        // 3. Fallback to /system/fonts if the ROM includes it
        val sysFont = File("/system/fonts/$fileName")
        if (sysFont.exists() && sysFont.canRead()) {
            val sysTf = runCatching {
                val builder = Typeface.Builder(sysFont)
                if (!variationSettings.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    builder.setFontVariationSettings(variationSettings)
                }
                builder.build()
            }.getOrNull()
            if (sysTf != null && sysTf != Typeface.DEFAULT) {
                return sysTf
            }
        }

        return Typeface.create("sans-serif", Typeface.BOLD)
    }
}
