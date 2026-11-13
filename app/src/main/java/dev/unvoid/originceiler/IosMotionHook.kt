package dev.unvoid.originceiler

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.database.ContentObserver
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.SurfaceControl
import android.view.View
import android.view.animation.PathInterpolator
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * iOS-style motion for OriginOS.
 *
 * SystemUI: heads-up banners drop in from above the screen on a soft spring and leave by
 * accelerating back up. vivo's own appear animator keeps running (so every callback, removal
 * and state change stays native), but its visual fraction is pinned and our transform is drawn
 * through the row's animation matrix.
 */
object IosMotionHook {

    const val KEY_BANNER = "originceiler_ios_banner"
    private const val TAG = "OriginIosMotion"

    private const val ROW_CLASS = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    private const val ACTIVATABLE_CLASS = "com.android.systemui.statusbar.notification.row.ActivatableNotificationView"

    private const val APPEAR_MS = 640L
    private const val DISAPPEAR_MS = 360L

    private val systemUiInstalled = AtomicBoolean(false)

    // ------------------------------------------------------------------ SystemUI: banners

    fun installSystemUi(classLoader: ClassLoader) {
        if (!systemUiInstalled.compareAndSet(false, true)) return
        val activatable = classLoader.loadClass(ACTIVATABLE_CLASS)
        val rowClass = classLoader.loadClass(ROW_CLASS)
        val hooks = XposedBridge.hookAllMethods(activatable, "startAppearAnimation", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!rowClass.isInstance(view)) return
                val appearing = param.args.getOrNull(0) as? Boolean ?: return
                runCatching { takeOverBanner(view, appearing) }
                    .onFailure { Log.w(TAG, "banner animation skipped", it) }
            }
        })
        Log.i(TAG, "SystemUI banner hooks installed: ${hooks.size}")
    }

    private fun bannerEnabled(context: Context) =
        Settings.Global.getInt(context.contentResolver, KEY_BANNER, 0) == 1

    private fun takeOverBanner(row: View, appearing: Boolean) {
        if (!bannerEnabled(row.context)) return
        if (!boolField(row, "mIsHeadsUpAnimation") || boolField(row, "mIsHeadsUpCycling")) return
        // Capsule / special-scene heads-ups belong to the island, keep vivo's motion there.
        if (boolField(row, "mIsPressCapsule") || boolField(row, "mIsSpecialScene")) return
        val animator = runCatching { readField(row, "mAppearAnimator") as? ValueAnimator }
            .getOrNull() ?: return
        // A swipe-up dismiss already moved the row out; vivo finishes that one itself.
        if (!appearing && row.translationY < -row.height * 0.25f) return

        val height = runCatching { invoke(row, "getActualHeight") as Int }
            .getOrDefault(row.height).coerceAtLeast(row.height / 2)
        val location = IntArray(2).also(row::getLocationOnScreen)
        val margin = 28f * row.resources.displayMetrics.density
        val distance = (location[1] - row.translationY).coerceAtLeast(0f) + height + margin

        val duration = if (appearing) APPEAR_MS else DISAPPEAR_MS
        animator.duration = duration
        // Pin vivo's own reveal: fully shown while appearing, untouched while leaving.
        animator.interpolator = if (appearing) TimeInterpolator { 1f } else TimeInterpolator { 0f }

        val matrix = Matrix()
        val apply = { t: Float ->
            val offset: Float
            val scale: Float
            val alpha: Float
            if (appearing) {
                val x = spring(t * duration / 1000f)
                offset = -distance * x
                scale = 1f - 0.10f * x.coerceIn(-0.2f, 1f)
                alpha = (t / 0.16f).coerceIn(0f, 1f)
            } else {
                val p = LEAVE.getInterpolation(t)
                offset = -distance * p
                scale = 1f - 0.06f * p
                alpha = 1f - ((t - 0.55f) / 0.45f).coerceIn(0f, 1f)
            }
            matrix.reset()
            matrix.setScale(scale, scale, row.width / 2f, 0f)
            matrix.postTranslate(0f, offset)
            row.setAnimationMatrix(matrix)
            row.transitionAlpha = alpha
        }
        apply(0f)
        animator.addUpdateListener { a ->
            val t = if (a.duration > 0) (a.currentPlayTime.toFloat() / a.duration).coerceIn(0f, 1f) else 1f
            apply(t)
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var done = false
            private fun reset() {
                if (done) return
                done = true
                row.setAnimationMatrix(null)
                row.transitionAlpha = 1f
            }
            override fun onAnimationEnd(animation: Animator) = reset()
            override fun onAnimationCancel(animation: Animator) = reset()
        })
    }

    /** Remaining offset (1 → 0) of an underdamped spring, iOS-like response 0.5 s, damping 0.74. */
    private fun spring(seconds: Float): Float {
        val omega = 2.0 * PI / 0.5
        val zeta = 0.74
        val omegaD = omega * sqrt(1 - zeta * zeta)
        val t = seconds.toDouble()
        return (exp(-zeta * omega * t) * (cos(omegaD * t) + zeta * omega / omegaD * sin(omegaD * t))).toFloat()
    }

    private val LEAVE = PathInterpolator(0.4f, 0f, 0.8f, 0.4f)

    private fun boolField(target: Any, name: String) =
        runCatching { readField(target, name) as Boolean }.getOrDefault(false)

    private fun readField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            runCatching { return c.getDeclaredField(name).apply { isAccessible = true }.get(target) }
            c = c.superclass
        }
        throw NoSuchFieldException(name)
    }

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val m = c.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
            if (m != null) return m.apply { isAccessible = true }.invoke(target, *args)
            c = c.superclass
        }
        throw NoSuchMethodException(name)
    }
}
