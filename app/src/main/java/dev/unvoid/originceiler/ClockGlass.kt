package dev.unvoid.originceiler

import android.content.res.Configuration
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * The shared glass (KyantGlass) on clock digits of any shape: [ClockLiquidGlassShader] builds the
 * digits' 3D profile from their alpha, everything else (blur, refraction, dispersion, rim light
 * following the tilt, saturation, brightness, dimming, tint) comes from the glass settings. The
 * lock wallpaper is what the digits refract.
 */
object ClockGlass {
    private val shaders = WeakHashMap<View, RuntimeShader>()
    private val applied = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<View, Boolean>()))
    private val retries = WeakHashMap<View, Int>()
    private var listening = false

    private val fallback by lazy { RuntimeShader("half4 main(float2 c) { return half4(0.92, 0.94, 0.98, 1.0); }") }

    /** Puts (or refreshes) the glass on [view]; [tint] is an optional clock colour dyeing it. */
    fun apply(view: View, tint: Int = 0) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val context = view.context
        KyantGlass.ensureConfig(context)
        listen()
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0f || h <= 0f) {
            view.post { if (view in applied || view.isAttachedToWindow) apply(view, tint) }
            return
        }
        applied += view
        tints[view] = tint
        val cfg = KyantGlass.config
        val dm = view.resources.displayMetrics
        val density = dm.density
        val hl = cfg[KyantGlass.KEY_CLOCK_HIGHLIGHT].coerceIn(0, 100) / 100f
        // The clock's own tint: strength, then a colour from hue / colourfulness / lightness.
        val tintAlpha = (cfg[KyantGlass.KEY_CLOCK_TINT].coerceIn(0, 100) * 2.55f).toInt()
        val surface = Color.HSVToColor(floatArrayOf(
            cfg[KyantGlass.KEY_CLOCK_TINT_HUE].mod(360).toFloat(),
            cfg[KyantGlass.KEY_CLOCK_TINT_CHROMA].coerceIn(0, 100) / 100f,
            cfg[KyantGlass.KEY_CLOCK_TINT_LIGHTNESS].coerceIn(0, 100) / 100f
        ))
        val lens = if (cfg[KyantGlass.KEY_MATERIAL] == 1) 0f else 1f

        val shader = shaders.getOrPut(view) { RuntimeShader(ClockLiquidGlassShader.AGSL_SOURCE) }
        shader.setFloatUniform("refractHeight", cfg[KyantGlass.KEY_CLOCK_EDGE].coerceAtLeast(0) * density * lens)
        shader.setFloatUniform("refractAmount", cfg[KyantGlass.KEY_CLOCK_REFRACTION].coerceAtLeast(0) * density * lens * 0.5f)
        shader.setFloatUniform("dispersion", if (cfg.on(KyantGlass.KEY_DISPERSION)) 1f else 0f)
        shader.setFloatUniform("lightAngle", KyantGlassLight.angle(context, cfg[KyantGlass.KEY_HIGHLIGHT_ANGLE]))
        shader.setFloatUniform("rimWidth", maxOf(0.75f * density, 1.5f))
        shader.setFloatUniform("rimAlpha", hl)
        shader.setFloatUniform("sheenWidth", 4f * density)
        shader.setFloatUniform("sheenAlpha", hl * 0.22f)
        shader.setFloatUniform("saturation", cfg[KyantGlass.KEY_CLOCK_SATURATION].coerceIn(0, 300) / 100f)
        shader.setFloatUniform("brightness", 1f + cfg[KyantGlass.KEY_CLOCK_BRIGHTNESS].coerceIn(-90, 200) / 100f)
        shader.setFloatUniform("dim", cfg[KyantGlass.KEY_CLOCK_DIM].coerceIn(0, 90) / 100f)
        shader.setFloatUniform("surfaceTint", Color.red(surface) / 255f, Color.green(surface) / 255f, Color.blue(surface) / 255f, tintAlpha / 255f)
        if (tint != 0) {
            shader.setFloatUniform("tintColor", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, 1f)
            shader.setFloatUniform("tintStrength", 25f)
        } else {
            shader.setFloatUniform("tintColor", 1f, 1f, 1f, 0f)
            shader.setFloatUniform("tintStrength", 0f)
        }

        val backdrop = runCatching { ScreenBackdropSource.getLockBackdrop(context) }.getOrNull()
            ?: runCatching { ScreenBackdropSource.getBackdrop(context) }.getOrNull()
        val blurred = backdrop?.takeIf { !it.isRecycled }?.let { blurred(it, cfg[KyantGlass.KEY_CLOCK_BLUR].coerceIn(0, 60) * density) }
        if (blurred != null) {
            retries.remove(view)
            val bitmapShader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            bitmapShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            val matrix = Matrix()
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            matrix.postScale(dm.widthPixels.coerceAtLeast(1) / blurred.width.toFloat(), dm.heightPixels.coerceAtLeast(1) / blurred.height.toFloat())
            matrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            bitmapShader.setLocalMatrix(matrix)
            shader.setInputShader("backdrop", bitmapShader)
            shader.setFloatUniform("hasBackdrop", 1f)
        } else {
            shader.setInputShader("backdrop", fallback)
            shader.setFloatUniform("hasBackdrop", 0f)
            // The wallpaper is read asynchronously: look again shortly.
            val n = retries[view] ?: 0
            if (n < 20) {
                retries[view] = n + 1
                view.postDelayed({ if (view in applied) apply(view, tints[view] ?: 0) }, 250L)
            }
        }
        view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
    }

    private val tints = WeakHashMap<View, Int>()

    /** The wallpaper blurred by the glass Blur setting (a quarter size is plenty under blur). */
    private var blurSource: java.lang.ref.WeakReference<android.graphics.Bitmap>? = null
    private var blurRadius = -1f
    private var blurResult: android.graphics.Bitmap? = null

    private fun blurred(source: android.graphics.Bitmap, radiusPx: Float): android.graphics.Bitmap? {
        blurResult?.let { if (blurSource?.get() === source && blurRadius == radiusPx && !it.isRecycled) return it }
        return runCatching {
            val soft = if (source.config == android.graphics.Bitmap.Config.HARDWARE) source.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else source
            val small = android.graphics.Bitmap.createScaledBitmap(soft, (soft.width / 4).coerceAtLeast(1), (soft.height / 4).coerceAtLeast(1), true)
            val out = if (radiusPx / 4f >= 0.5f) KyantGlass.blurSoftware(small, radiusPx / 4f) else small
            blurSource = java.lang.ref.WeakReference(source)
            blurRadius = radiusPx
            blurResult = out
            out
        }.getOrNull()
    }

    fun clear(view: View) {
        if (!applied.remove(view)) return
        view.setRenderEffect(null)
    }

    fun isApplied(view: View) = view in applied

    /** Settings and the light baked into the effect: re-apply on changes / tilt. */
    private fun listen() {
        if (listening) return
        listening = true
        val refresh = {
            synchronized(applied) { applied.toList() }.forEach { v ->
                if (v.isAttachedToWindow && v.isShown) v.post { if (v in applied) apply(v, tints[v] ?: 0) }
            }
        }
        KyantGlass.onConfigChanged(refresh)
        KyantGlassLight.addListener(refresh)
    }
}
