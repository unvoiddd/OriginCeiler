package dev.unvoid.originceiler

import android.app.Application
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class MaterialOriginOsVolumeHook {
    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        val dialogClass = runCatching {
            Class.forName("com.android.systemui.volume.VolumeDialogImpl", false, classLoader)
        }.getOrNull() ?: return
        XposedBridge.hookAllMethods(ProgressBar::class.java, "setProgressDrawable", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!enabled() || param.args.firstOrNull() is ExpressiveVolumeDrawable) return
                val slider = param.thisObject as? SeekBar ?: return
                val name = runCatching { slider.resources.getResourceEntryName(slider.id) }.getOrNull() ?: return
                if (name != "volume_row_slider") return
                styleSlider(slider, null)
            }
        })
        listOf("initDialog", "initSettingsH", "updateRowsH", "recheckH").forEach { methodName ->
            XposedBridge.hookAllMethods(dialogClass, methodName, object : XC_MethodHook(Int.MAX_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    styleDialog(param.thisObject)
                }
            })
        }
        listOf("updateVolumeRowTintH", "updateVolumeRowH").forEach { methodName ->
            XposedBridge.hookAllMethods(dialogClass, methodName, object : XC_MethodHook(Int.MAX_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    val row = param.args.firstOrNull { it?.javaClass?.name?.endsWith("VolumeDialogImpl\$VolumeRow") == true } ?: return
                    styleRow(row)
                }
            })
        }
    }

    private fun styleDialog(dialog: Any) {
        val application = currentApplication() ?: return
        val accent = activeColor(application)
        val surface = surfaceColor(application)
        listOf("mDialogView", "mDialogRowsViewContainer", "mTopContainer").forEach { name ->
            (readField(dialog, name) as? View)?.clipToOutline = false
        }
        (readField(dialog, "mDialogRowsView") as? ViewGroup)?.let { rows ->
            for (index in 0 until rows.childCount) {
                rows.getChildAt(index).background?.mutate()?.setTint(surface)
            }
        }
        (readField(dialog, "mRingerAndDrawerContainer") as? View)?.background?.mutate()?.setTint(surface)
        (readField(dialog, "mSettingsView") as? View)?.background?.mutate()?.setTint(surface)
        (readField(dialog, "mSettingsIcon") as? ImageButton)?.imageTintList = android.content.res.ColorStateList.valueOf(accent)
        listOf("mODICaptionsView", "mODICaptionsTooltipView").forEach { name ->
            (readField(dialog, name) as? View)?.visibility = View.GONE
        }
    }

    private fun styleRow(row: Any) {
        val application = currentApplication() ?: return
        val slider = readField(row, "slider") as? SeekBar ?: return
        val icon = readField(row, "sliderProgressIcon") as? Drawable
        styleSlider(slider, icon)
        (readField(row, "view") as? View)?.background?.mutate()?.setTint(surfaceColor(application))
        slider.invalidate()
    }

    private fun styleSlider(slider: SeekBar, icon: Drawable?) {
        val application = currentApplication() ?: return
        val active = activeColor(application)
        val inactive = inactiveColor(application)
        val glyph = if (isNight(application)) 0xff111217.toInt() else 0xffffffff.toInt()
        icon?.setTint(glyph)
        val current = styled[slider]
        if (current == null || current.icon !== icon) {
            val drawable = ExpressiveVolumeDrawable(
                application.resources.displayMetrics.density,
                active,
                inactive,
                icon
            )
            styled[slider] = drawable
            slider.progressDrawable = drawable
        } else {
            current.setColors(active, inactive)
            if (slider.progressDrawable !== current) slider.progressDrawable = current
        }
    }

    private fun activeColor(application: Application): Int {
        return androidColor(application, if (isNight(application)) "system_accent1_200" else "system_accent1_600", if (isNight(application)) 0xffcbc9ff.toInt() else 0xff5c6197.toInt())
    }

    private fun inactiveColor(application: Application): Int {
        return if (isNight(application)) 0xff2b2c36.toInt() else 0xffe8e6ee.toInt()
    }

    private fun surfaceColor(application: Application): Int {
        return if (isNight(application)) 0xff111217.toInt() else 0xfffcf9ff.toInt()
    }

    private fun isNight(application: Application): Boolean {
        return application.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun androidColor(application: Application, name: String, fallback: Int): Int {
        val id = application.resources.getIdentifier(name, "color", "android")
        return if (id == 0) fallback else runCatching { application.resources.getColor(id, null) }.getOrDefault(fallback)
    }

    private fun readField(target: Any, name: String): Any? {
        return generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()
            ?.let { field -> runCatching { field.isAccessible = true; field.get(target) }.getOrNull() }
    }

    private fun enabled(): Boolean {
        val application = currentApplication() ?: return false
        return Settings.Global.getInt(application.contentResolver, MaterialOriginOsHook.SETTING_VOLUME, 0) == 1
    }

    private fun currentApplication(): Application? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .invoke(null) as? Application
    }.getOrNull()

    private class ExpressiveVolumeDrawable(
        private val density: Float,
        active: Int,
        inactive: Int,
        val icon: Drawable?
    ) : Drawable() {
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = active }
        private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inactive }
        private val rect = RectF()

        fun setColors(active: Int, inactive: Int) {
            activePaint.color = active
            inactivePaint.color = inactive
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            val inset = 5f * density
            val trackHeight = min(38f * density, height)
            val top = (height - trackHeight) / 2f
            val bottom = top + trackHeight
            val start = inset
            val end = max(start, width - inset)
            val fraction = (level / 10000f).coerceIn(0f, 1f)
            val split = start + (end - start) * fraction
            val gap = 5f * density
            val outerRadius = 10f * density
            val innerRadius = 4f * density
            val activeEnd = min(end, max(start, split - gap / 2f))
            val inactiveStart = max(start, min(end, split + gap / 2f))
            if (activeEnd > start) {
                rect.set(start, top, activeEnd, bottom)
                canvas.drawRoundRect(rect, min(outerRadius, (activeEnd - start) / 2f), outerRadius, activePaint)
                if (activeEnd - start > innerRadius) {
                    canvas.drawRect(activeEnd - innerRadius, top, activeEnd, bottom, activePaint)
                }
            }
            if (end > inactiveStart) {
                rect.set(inactiveStart, top, end, bottom)
                canvas.drawRoundRect(rect, min(outerRadius, (end - inactiveStart) / 2f), outerRadius, inactivePaint)
                if (end - inactiveStart > innerRadius) {
                    canvas.drawRect(inactiveStart, top, inactiveStart + innerRadius, bottom, inactivePaint)
                }
            }
            icon?.let { drawable ->
                val size = min(22f * density, trackHeight)
                val center = (activeEnd - 11f * density).coerceIn(start + size / 2f, end - size / 2f)
                drawable.setBounds(
                    (center - size / 2f).toInt(),
                    ((height - size) / 2f).toInt(),
                    (center + size / 2f).toInt(),
                    ((height + size) / 2f).toInt()
                )
                canvas.save()
                canvas.rotate(90f, center, height / 2f)
                drawable.draw(canvas)
                canvas.restore()
            }
        }

        override fun onLevelChange(level: Int): Boolean {
            invalidateSelf()
            return true
        }

        override fun setAlpha(alpha: Int) {
            activePaint.alpha = alpha
            inactivePaint.alpha = alpha
            icon?.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            activePaint.colorFilter = colorFilter
            inactivePaint.colorFilter = colorFilter
            icon?.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        private val installed = AtomicBoolean(false)
        private val styled = WeakHashMap<SeekBar, ExpressiveVolumeDrawable>()
    }
}
