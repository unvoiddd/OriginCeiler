package dev.unvoid.originceiler

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.provider.Settings
import androidx.core.graphics.PathParser
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.FileOutputStream
import java.util.Collections
import java.util.WeakHashMap

class MaterialOriginOsUdfpsHook {
    fun install(classLoader: ClassLoader) {
        val renderer = Class.forName(RENDERER_CLASS, false, classLoader)
        synchronized(installedClasses) {
            if (installedClasses.put(renderer, true) != null) return
        }
        val transaction = Class.forName(TRANSACTION_CLASS, false, classLoader)
        listOf("d", "e").forEach { methodName ->
            XposedBridge.hookAllMethods(transaction, methodName, object : XC_MethodHook(Int.MAX_VALUE) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val path = synchronized(customImages) { customImages[param.args.firstOrNull()] } ?: return
                    param.args.indices.forEach { index ->
                        val images = param.args[index] as? Array<*> ?: return@forEach
                        param.args[index] = Array(images.size) { path }
                    }
                }
            })
        }
        XposedBridge.hookAllMethods(transaction, "i", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val path = synchronized(customImages) { customImages[param.args.firstOrNull()] } ?: return
                param.args[1] = path
            }
        })
        XposedBridge.hookAllMethods(transaction, "h", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val path = synchronized(customImages) { customImages[param.args.firstOrNull()] } ?: return
                BitmapFactory.decodeFile(path)?.let { param.args[1] = it }
            }
        })
        XposedBridge.hookAllMethods(renderer, "z", object : XC_MethodHook(Int.MIN_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject ?: return
                if (!enabled(context(owner))) return
                registerImages(owner)
            }
        })
        XposedBridge.hookAllMethods(renderer, "K", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject ?: return
                if (!enabled(context(owner))) return
                registerImages(owner)
            }
        })
    }

    private fun registerImages(owner: Any) {
        val context = context(owner) ?: return
        val bounds = field(owner, "t") as? Rect ?: return
        field(owner, "o")?.let { view ->
            val path = saveBitmap(context, "material_originos_udfps_normal.png", normalBitmap(context, bounds.width(), bounds.height()))
            synchronized(customImages) { customImages[view] = path }
        }
        field(owner, "p")?.let { view ->
            val path = saveBitmap(context, "material_originos_udfps_pressed.png", pressedBitmap(context, bounds.width(), bounds.height()))
            synchronized(customImages) { customImages[view] = path }
        }
    }

    private fun normalBitmap(context: Context, requestedWidth: Int, requestedHeight: Int): Bitmap {
        val width = requestedWidth.coerceAtLeast(1)
        val height = requestedHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val density = context.resources.displayMetrics.density
        val square = minOf(width, height).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val colors = MaterialOriginOsLockscreenPalette.resolve(context)
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = colors.background
        }
        canvas.drawCircle(centerX, centerY, minOf(square, 64f * density) / 2f, backgroundPaint)
        val scale = square / 72f * 0.5f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(centerX - 36f * scale, centerY - 36f * scale)
        }
        val path = PathParser.createPathFromPathData(FINGERPRINT_PATH)
        val transformed = android.graphics.Path()
        path?.transform(matrix, transformed)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 3f * scale
            color = colors.foreground
        }
        canvas.drawPath(transformed, paint)
        return bitmap
    }

    private fun pressedBitmap(context: Context, requestedWidth: Int, requestedHeight: Int): Bitmap {
        val width = requestedWidth.coerceAtLeast(1)
        val height = requestedHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val accent = systemColor(context, "system_accent1_200", 0xffd0bcff.toInt())
        val radius = minOf(width, height) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width / 2f,
                height / 2f,
                radius,
                intArrayOf(Color.argb(180, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        return bitmap
    }

    private fun saveBitmap(context: Context, name: String, bitmap: Bitmap): String {
        val file = context.cacheDir.resolve(name)
        FileOutputStream(file, false).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
        return file.absolutePath
    }

    private fun context(owner: Any): Context? = field(owner, "e") as? Context

    private fun field(owner: Any, name: String): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            val declared = runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            if (declared != null) return runCatching { declared.get(owner) }.getOrNull()
            type = type.superclass
        }
        return null
    }

    private fun enabled(context: Context?): Boolean {
        context ?: return false
        return Settings.Global.getInt(context.contentResolver, MaterialOriginOsLockscreenHook.SETTING_ENABLED, 0) == 1
    }

    private fun systemColor(context: Context, name: String, fallback: Int): Int {
        val id = context.resources.getIdentifier(name, "color", "android")
        return if (id != 0) runCatching { context.getColor(id) }.getOrDefault(fallback) else fallback
    }

    companion object {
        private const val RENDERER_CLASS = "U0.b0"
        private const val TRANSACTION_CLASS = "com.vivo.fingerprint.vkrenderer.a"
        private const val FINGERPRINT_PATH = "M25.5,16.3283C28.47,14.8433 31.9167,14 35.5834,14C39.2501,14 42.6968,14.8433 45.6668,16.3283 M20,28.6669C22.7683,24.3402 28.7084,21.3335 35.5834,21.3335C42.4585,21.3335 48.3985,24.3402 51.1669,28.6669 M22.8607,47.0002C21.834,44.3235 21.834,41.5002 21.834,41.5002C21.834,34.4051 27.7374,28.6667 35.5841,28.6667C43.4308,28.6667 49.3341,34.4051 49.3341,41.5002 M49.3344,41.5003V42.0319C49.3344,44.7636 47.1161,47.0003 44.3661,47.0003C41.9461,47.0003 39.8744,45.2403 39.471,42.857L38.9577,39.7769C38.591,37.5953 36.7027,36.0002 34.5027,36.0002C26.5826,36.0002 29.846,49.1087 35.291,50.6487 M44.9713,54.6267C42.5513,56.7167 39.2879,58.0001 35.5846,58.0001C32.2296,58.0001 29.2229,56.9551 26.8945,55.195"
        private val installedClasses = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
        private val customImages = Collections.synchronizedMap(WeakHashMap<Any, String>())
    }
}
