package dev.unvoid.originceiler

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.exp

/**
 * Light direction of the glass rim highlight (and the soft glass bevel), driven by the phone's
 * tilt like iOS 26: rolling the phone sweeps the light along the rim, tipping it forward or back
 * shifts it around. Tipping is measured against a slowly following rest pose, so however the phone
 * is held the light settles near the configured angle; rolling acts absolutely.
 *
 * The gravity sensor is only on while glass is being drawn (stops ~2 s after the last frame).
 */
object KyantGlassLight {
    private const val IDLE_STOP_MS = 2000L
    private const val ROLL_DEG = 110f
    private const val PITCH_DEG = 70f
    /** Rest pose time constant and output smoothing (s). */
    private const val REST_TAU = 3f
    private const val SMOOTH_TAU = 0.08f
    /** Redraw when the light moved by at least this much (deg). */
    private const val REDRAW_STEP = 0.8f

    private val main = Handler(Looper.getMainLooper())
    /** Called when the light moved enough to redraw (surfaces that bake it into an effect). */
    private val listeners = ArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        synchronized(listeners) { listeners += listener }
    }
    private var manager: SensorManager? = null
    @Volatile private var listening = false
    @Volatile private var lastDrawAt = 0L
    private var lastEventAt = 0L
    private var restY = Float.NaN
    @Volatile private var offsetDeg = 0f
    private var drawnOffset = 0f

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastEventAt == 0L) 0.016f else ((now - lastEventAt) / 1000f).coerceIn(0.001f, 0.25f)
            lastEventAt = now
            val gx = event.values[0] / SensorManager.GRAVITY_EARTH
            val gy = event.values[1] / SensorManager.GRAVITY_EARTH
            restY = if (restY.isNaN()) gy else restY + (gy - restY) * (1f - exp(-dt / REST_TAU))
            val strength = KyantGlass.config[KyantGlass.KEY_GYRO_STRENGTH].coerceIn(0, 300) / 100f
            val target = (gx.coerceIn(-1f, 1f) * ROLL_DEG + (gy - restY).coerceIn(-1f, 1f) * PITCH_DEG) * strength
            offsetDeg += (target - offsetDeg) * (1f - exp(-dt / SMOOTH_TAU))
            if (abs(offsetDeg - drawnOffset) >= REDRAW_STEP) {
                drawnOffset = offsetDeg
                KyantGlass.invalidateTracked()
                synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val idleCheck = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() - lastDrawAt >= IDLE_STOP_MS) stop() else main.postDelayed(this, IDLE_STOP_MS)
        }
    }

    /** Light angle (radians) for the configured [baseDeg]; keeps the sensor running while used. */
    fun angle(context: Context, baseDeg: Int): Float {
        if (!KyantGlass.config.on(KyantGlass.KEY_GYRO)) {
            if (listening) main.post { stop() }
            return Math.toRadians(baseDeg.toDouble()).toFloat()
        }
        lastDrawAt = SystemClock.uptimeMillis()
        if (!listening) main.post { start(context) }
        return Math.toRadians((baseDeg + offsetDeg).toDouble()).toFloat()
    }

    private fun start(context: Context) {
        if (listening) return
        val sm = manager ?: (context.applicationContext ?: context).getSystemService(SensorManager::class.java)?.also { manager = it } ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        listening = runCatching { sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, main) }.getOrDefault(false)
        if (listening) {
            lastEventAt = 0L
            main.postDelayed(idleCheck, IDLE_STOP_MS)
        }
    }

    private fun stop() {
        if (!listening) return
        listening = false
        main.removeCallbacks(idleCheck)
        runCatching { manager?.unregisterListener(listener) }
    }
}
