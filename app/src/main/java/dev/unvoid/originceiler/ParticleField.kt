package dev.unvoid.originceiler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Firefly particles that gather into a shape: the OriginCeiler logo ([LOGO]) or any Material
 * Symbols ligature ("routine", "favorite", …). Changing [shape] makes the swarm burst and re-form.
 * Touch pushes particles away; springs bring them back. Colors come from the Material You palette.
 */
@Composable
internal fun ParticleField(
    shape: String,
    modifier: Modifier = Modifier,
    count: Int = 700,
    shapeCenterY: Float = 0.4f,
    shapeSize: Float = 0.62f,
    particleScale: Float = 1f
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val palette = remember(dark) { fireflyPalette(context, dark) }
    val swarm = remember(count) { Swarm(count) }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(shape) {
        swarm.retarget(ParticleShapes.points(context, shape, count))
    }
    LaunchedEffect(swarm) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 1f / 30f)
                last = now
                swarm.step(dt, now / 1e9f)
                frame = now
            }
        }
    }

    Canvas(
        modifier.pointerInput(swarm) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val pointer = event.changes.firstOrNull { it.pressed }
                    if (pointer == null) {
                        swarm.touching = false
                    } else {
                        if (!swarm.touching) swarm.burst(pointer.position.x, pointer.position.y)
                        swarm.touching = true
                        swarm.touchX = pointer.position.x
                        swarm.touchY = pointer.position.y
                    }
                }
            }
        }
    ) {
        frame // redraw every frame
        val side = min(size.width, size.height * 0.9f) * shapeSize
        swarm.layout(size.width, size.height, size.width / 2f - side / 2f, size.height * shapeCenterY - side / 2f, side)
        val time = swarm.time
        val radius = 1.55f * density * particleScale
        val blend = if (dark) BlendMode.Plus else BlendMode.SrcOver
        for (i in 0 until swarm.count) {
            val x = swarm.x[i]
            val y = swarm.y[i]
            // One wave sweeps the whole swarm, so the fireflies pulse in sync.
            val wave = 0.5f + 0.5f * sin(time * 2.1f - (x * 0.8f + y) * 0.0105f + swarm.seed[i] * 0.5f)
            val brightness = 0.35f + 0.65f * wave * wave
            val hue = 0.5f + 0.5f * sin(time * 0.45f + (x - y) * 0.0032f + swarm.seed[i])
            val color = if (swarm.seed[i] > 5.4f) palette[2] else lerp(palette[0], palette[1], hue)
            val r = radius * swarm.scale[i]
            val center = Offset(x, y)
            if (dark) {
                drawCircle(color, r * 3.6f, center, alpha = 0.07f * brightness, blendMode = blend)
                drawCircle(color, r * 1.8f, center, alpha = 0.16f * brightness, blendMode = blend)
                drawCircle(lerp(color, Color.White, 0.22f * wave * wave), r, center, alpha = 0.92f * brightness, blendMode = blend)
            } else {
                // On a light backdrop glow reads as smudge: soft pastel dots with a faint halo.
                drawCircle(color, r * 2.2f, center, alpha = 0.06f * brightness)
                drawCircle(color, r, center, alpha = 0.3f + 0.4f * brightness)
            }
        }
    }
}

private fun fireflyPalette(context: Context, dark: Boolean): List<Color> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        fun c(id: Int) = Color(context.getColor(id))
        return if (dark) {
            listOf(c(android.R.color.system_accent1_300), c(android.R.color.system_accent3_300), c(android.R.color.system_accent2_200))
        } else {
            listOf(c(android.R.color.system_accent1_400), c(android.R.color.system_accent3_300), c(android.R.color.system_accent2_300))
        }
    }
    return if (dark) listOf(Color(0xFF9CC2FF), Color(0xFFE3B5FF), Color(0xFFFFE0A3))
    else listOf(Color(0xFF6E95E0), Color(0xFFB98AD6), Color(0xFFA89C78))
}

private class Swarm(val count: Int) {
    val x = FloatArray(count)
    val y = FloatArray(count)
    val vx = FloatArray(count)
    val vy = FloatArray(count)
    private val targetX = FloatArray(count)
    private val targetY = FloatArray(count)
    private val delay = FloatArray(count)
    val seed = FloatArray(count) { Random.nextFloat() * 6f }
    val scale = FloatArray(count) { 0.7f + Random.nextFloat() * 0.75f }
    private var normalized: FloatArray? = null
    private var width = 0f
    private var height = 0f
    private var originX = 0f
    private var originY = 0f
    private var side = 0f
    private var placed = false
    private var retargetAt = 0f
    var time = 0f
    var touching = false
    var touchX = 0f
    var touchY = 0f

    fun retarget(points: FloatArray) {
        val first = normalized == null
        normalized = points
        retargetAt = time
        for (i in 0 until count) {
            delay[i] = if (first) Random.nextFloat() * 0.6f else Random.nextFloat() * 0.45f
            if (!first) {
                // A little outward burst before the swarm re-forms.
                val angle = Random.nextFloat() * 2f * PI.toFloat()
                val speed = 120f + Random.nextFloat() * 380f
                vx[i] += cos(angle) * speed
                vy[i] += sin(angle) * speed
            }
        }
        updateTargets()
    }

    fun layout(width: Float, height: Float, originX: Float, originY: Float, side: Float) {
        if (width == this.width && height == this.height && side == this.side && originX == this.originX && originY == this.originY) return
        this.width = width
        this.height = height
        this.originX = originX
        this.originY = originY
        this.side = side
        if (!placed && width > 0f) {
            for (i in 0 until count) {
                x[i] = Random.nextFloat() * width
                y[i] = Random.nextFloat() * height
            }
            placed = true
        }
        updateTargets()
    }

    private fun updateTargets() {
        val points = normalized ?: return
        for (i in 0 until count) {
            targetX[i] = originX + points[i * 2] * side
            targetY[i] = originY + points[i * 2 + 1] * side
        }
    }

    fun burst(px: Float, py: Float) {
        for (i in 0 until count) {
            val dx = x[i] - px
            val dy = y[i] - py
            val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val reach = side * 0.55f
            if (distance < reach) {
                val force = (1f - distance / reach) * 900f
                vx[i] += dx / distance * force
                vy[i] += dy / distance * force
            }
        }
    }

    fun step(dt: Float, now: Float) {
        time = now
        if (!placed || dt == 0f) return
        val reach = side * 0.38f
        for (i in 0 until count) {
            val active = time - retargetAt >= delay[i] && normalized != null
            var ax: Float
            var ay: Float
            if (active) {
                ax = (targetX[i] - x[i]) * 16f - vx[i] * 5.2f
                ay = (targetY[i] - y[i]) * 16f - vy[i] * 5.2f
            } else {
                ax = -vx[i] * 1.5f
                ay = -vy[i] * 1.5f
            }
            // Gentle firefly wander so the shape breathes.
            ax += sin(time * 1.3f + seed[i] * 7f) * 38f
            ay += cos(time * 1.1f + seed[i] * 5f) * 38f
            if (touching) {
                val dx = x[i] - touchX
                val dy = y[i] - touchY
                val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                if (distance < reach) {
                    val falloff = 1f - distance / reach
                    val force = falloff * falloff * 5200f
                    ax += dx / distance * force
                    ay += dy / distance * force
                }
            }
            vx[i] += ax * dt
            vy[i] += ay * dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
        }
    }
}

internal object ParticleShapes {
    const val LOGO = "origin_logo"
    private const val RESOLUTION = 220
    private val cache = HashMap<String, FloatArray>()
    private var symbols: Typeface? = null

    @Synchronized
    fun points(context: Context, shape: String, count: Int): FloatArray {
        cache["$shape:$count"]?.let { return it }
        val bitmap = Bitmap.createBitmap(RESOLUTION, RESOLUTION, Bitmap.Config.ALPHA_8)
        val canvas = AndroidCanvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val s = RESOLUTION.toFloat()
        if (shape == LOGO) {
            // Ring with a small satellite dot, as in the OriginCeiler logo.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = s * 0.15f
            canvas.drawCircle(s * 0.44f, s * 0.56f, s * 0.27f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(s * 0.82f, s * 0.18f, s * 0.085f, paint)
        } else {
            val typeface = symbols ?: runCatching { Typeface.createFromAsset(context.assets, "MaterialSymbolsRounded.ttf") }
                .getOrNull()?.also { symbols = it }
            paint.typeface = typeface
            paint.textSize = s * 0.98f
            paint.textAlign = Paint.Align.CENTER
            runCatching { paint.fontVariationSettings = "'FILL' 1, 'wght' 500" }
            val metrics = paint.fontMetrics
            canvas.drawText(shape, s / 2f, s / 2f - (metrics.ascent + metrics.descent) / 2f, paint)
        }
        val pixels = ByteArray(RESOLUTION * RESOLUTION)
        bitmap.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(pixels))
        bitmap.recycle()
        val inside = ArrayList<Int>()
        pixels.forEachIndexed { index, alpha -> if ((alpha.toInt() and 255) > 120) inside += index }
        val random = Random(shape.hashCode())
        val result = FloatArray(count * 2)
        for (i in 0 until count) {
            val index = if (inside.isEmpty()) random.nextInt(pixels.size) else inside[random.nextInt(inside.size)]
            result[i * 2] = (index % RESOLUTION + random.nextFloat()) / s
            result[i * 2 + 1] = (index / RESOLUTION + random.nextFloat()) / s
        }
        return result.also { cache["$shape:$count"] = it }
    }
}
