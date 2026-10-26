package dev.unvoid.originceiler

import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build

data class LensParams(
    val blurRadius: Float,
    val shapeX: Float,
    val shapeY: Float,
    val shapeW: Float,
    val shapeH: Float,
    val radiusTL: Float,
    val radiusTR: Float,
    val radiusBR: Float,
    val radiusBL: Float,
    val shapeLX: Float,
    val shapeLY: Float,
    val shapeLW: Float,
    val shapeLH: Float,
    val bevel: Float,
    val refractPx: Float,
    val falloff: Float,
    val refractDirOutward: Boolean,
    val dispersion: Float,
    val rimBandMax: Float,
    val tintColor: Int,
    val adaptiveTint: Boolean,
    val materialStrength: Float,
    val highlightProtection: Float,
    val transmissionAttenuation: Float,
    val satFactor: Float,
    val rimSoft: Float = 0.5f,
    val bottomEdgeSoftening: Float = 0f,
    val press: Float = 0f,
    val touchX: Float = 0f,
    val touchY: Float = 0f,
    val touchAmp: Float = 0f
)

class LiquidGlassRenderer {
    private var shader: RuntimeShader? = null
    private var blurEffect: RenderEffect? = null
    private var cachedBlurRadius: Float = 0f

    val contentRenderNode: RenderNode by lazy { RenderNode("LiquidGlassContent") }
    val lensRenderNode: RenderNode by lazy { RenderNode("LiquidGlassLens") }

    fun buildEffect(params: LensParams, margin: Float, viewWidth: Float, viewHeight: Float): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null

        val currentShader = shader ?: runCatching {
            RuntimeShader(LiquidGlassShader.AGSL_SOURCE).also { shader = it }
        }.getOrNull() ?: return null

        currentShader.setFloatUniform("margin", margin)
        currentShader.setFloatUniform("viewSize", viewWidth, viewHeight)
        currentShader.setFloatUniform("shape1", params.shapeX, params.shapeY, params.shapeW, params.shapeH)
        currentShader.setFloatUniform("radii1", params.radiusTL, params.radiusTR, params.radiusBR, params.radiusBL)
        currentShader.setFloatUniform("shape1L", params.shapeLX, params.shapeLY, params.shapeLW, params.shapeLH)
        currentShader.setFloatUniform("rimSoft", params.rimSoft)
        currentShader.setFloatUniform("shape2", 0.0f, 0.0f, 0.0f, 0.0f)
        currentShader.setFloatUniform("radius2", 0.0f)
        currentShader.setFloatUniform("blendK", 0.0f)
        currentShader.setFloatUniform("bevel", params.bevel)
        currentShader.setFloatUniform("refractPx", params.refractPx)
        currentShader.setFloatUniform("falloff", params.falloff)
        currentShader.setFloatUniform("refractDir", if (params.refractDirOutward) 1.0f else -1.0f)
        currentShader.setFloatUniform("sampleLo", 0.0f, 0.0f)
        currentShader.setFloatUniform("sampleHi", viewWidth + 2f * margin, viewHeight + 2f * margin)
        currentShader.setFloatUniform("dispersion", params.dispersion)
        currentShader.setFloatUniform("lightDir", 0.866f, 0.5f)
        currentShader.setFloatUniform("specStrength", 1.0f)
        currentShader.setFloatUniform("rimBandMax", params.rimBandMax)
        currentShader.setFloatUniform("bottomEdgeSoftening", params.bottomEdgeSoftening)

        val tint = params.tintColor
        currentShader.setFloatUniform(
            "tintColor",
            Color.red(tint) / 255.0f,
            Color.green(tint) / 255.0f,
            Color.blue(tint) / 255.0f,
            Color.alpha(tint) / 255.0f
        )
        currentShader.setFloatUniform("adaptiveTint", if (params.adaptiveTint) 1.0f else 0.0f)
        currentShader.setFloatUniform("materialStrength", params.materialStrength)
        currentShader.setFloatUniform("glassTint", 0.0f, 0.0f, 0.0f, 0.0f)
        currentShader.setFloatUniform("dimAmount", 0.0f)
        currentShader.setFloatUniform("highlightProtection", params.highlightProtection)
        currentShader.setFloatUniform("transmissionAttenuation", params.transmissionAttenuation)
        currentShader.setFloatUniform("satFactor", params.satFactor / 100.0f)
        currentShader.setFloatUniform("press", params.press)
        currentShader.setFloatUniform("touchPos", params.touchX, params.touchY)
        currentShader.setFloatUniform("touchAmp", params.touchAmp)

        val shaderEffect = RenderEffect.createRuntimeShaderEffect(currentShader, "content")
        val blur = params.blurRadius
        if (blur <= 0.01f) return shaderEffect

        if (cachedBlurRadius != blur || blurEffect == null) {
            blurEffect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
            cachedBlurRadius = blur
        }
        val be = blurEffect ?: return shaderEffect
        return RenderEffect.createChainEffect(shaderEffect, be)
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentRenderNode.discardDisplayList()
            lensRenderNode.discardDisplayList()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lensRenderNode.setRenderEffect(null)
            }
        }
        shader = null
        blurEffect = null
    }

    companion object {
        data class PresetTuning(
            val bevel: Float,
            val refractPx: Float,
            val falloff: Float,
            val dispersion: Float,
            val satFactor: Float
        )

        fun getPresetTuning(preset: Int): PresetTuning {
            return when (preset) {
                0 -> PresetTuning(24.0f, 80.0f, 1.5f, 0.04f, 120.0f) // Light
                2 -> PresetTuning(72.0f, 220.0f, 2.5f, 0.18f, 150.0f) // Strong
                else -> PresetTuning(48.0f, 160.0f, 2.0f, 0.10f, 140.0f) // Standard
            }
        }
    }
}
