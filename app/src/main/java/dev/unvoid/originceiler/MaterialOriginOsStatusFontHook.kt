package dev.unvoid.originceiler

import android.content.Context
import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MaterialOriginOsStatusFontHook {
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        XposedBridge.hookAllMethods(TextView::class.java, "onAttachedToWindow", object : XC_MethodHook(Int.MIN_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                apply(param.thisObject as? TextView ?: return)
            }
        })
        XposedBridge.hookAllMethods(TextView::class.java, "setTypeface", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (applying.get() == true) return
                val view = param.thisObject as? TextView ?: return
                val kind = targetKind(view) ?: return
                if (!enabled(view.context)) return
                param.args[0] = typeface(view.context, kind) ?: return
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (applying.get() == true) return
                apply(param.thisObject as? TextView ?: return)
            }
        })
        XposedBridge.hookAllMethods(TextView::class.java, "setText", object : XC_MethodHook(Int.MIN_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                apply(param.thisObject as? TextView ?: return)
            }
        })
    }

    private fun apply(view: TextView) {
        if (!enabled(view.context)) return
        val kind = targetKind(view) ?: return
        val typeface = typeface(view.context, kind) ?: return
        if (appliedKinds[view] == kind && view.typeface === typeface) return
        applying.set(true)
        try {
            if (view.typeface !== typeface) view.typeface = typeface
            view.fontVariationSettings = variation(kind)
            appliedKinds[view] = kind
            if (kind == Kind.Clock) {
                view.includeFontPadding = false
                view.gravity = (view.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) or Gravity.CENTER_VERTICAL
                view.translationY = 0f
            }
        } finally {
            applying.remove()
        }
    }

    private fun targetKind(view: TextView): Kind? {
        targetKinds[view]?.let { return it }
        if (nonTargetViews[view] == true) return null
        val className = view.javaClass.name.lowercase()
        val resourceName = if (view.id != View.NO_ID && view.id != 0) {
            runCatching { view.resources.getResourceEntryName(view.id).lowercase() }.getOrNull().orEmpty()
        } else {
            ""
        }
        val ancestry = generateSequence(view.parent) { (it as? View)?.parent }
            .take(6)
            .map { it.javaClass.name.lowercase() }
            .toList()
        if (
            className.contains("statusbar.policy.clock") ||
            className.contains("statusbar.widget.statclock") ||
            className.contains("qsclock") ||
            resourceName == "fake_bar_clock" ||
            resourceName == "vivo_hood_clock"
        ) return Kind.Clock.also { targetKinds[view] = it }
        if (
            className.contains("qsdate") ||
            (resourceName.contains("date") && ancestry.any { it.contains("qsheader") || it.contains("shade") })
        ) return Kind.Date.also { targetKinds[view] = it }
        if (
            (className.contains("battery") && className.contains("text")) ||
            (resourceName.contains("battery") && resourceName.contains("percent")) ||
            (resourceName.contains("percent") && ancestry.any { it.contains("battery") })
        ) return Kind.Battery.also { targetKinds[view] = it }
        if (view.isAttachedToWindow) nonTargetViews[view] = true
        return null
    }

    private fun typeface(context: Context, kind: Kind): Typeface? {
        val cached = fonts[kind]
        if (cached != null) return cached
        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        val built = runCatching {
            Typeface.Builder(moduleContext.assets, FONT_ASSET)
                .setFontVariationSettings(variation(kind))
                .build()
        }.getOrNull() ?: return null
        fonts[kind] = built
        return built
    }

    private fun variation(kind: Kind): String = when (kind) {
        Kind.Clock -> "'wght' 600, 'wdth' 100, 'ROND' 100"
        Kind.Date -> "'wght' 500, 'wdth' 100, 'ROND' 100"
        Kind.Battery -> "'wght' 700, 'wdth' 100, 'ROND' 100"
    }

    private fun enabled(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, SETTING_ENABLED, 0) == 1
    }

    private enum class Kind { Clock, Date, Battery }

    companion object {
        const val SETTING_ENABLED = "originroottoolbox_material_originos_google_sans_flex"
        private const val MODULE_PACKAGE = "dev.unvoid.originceiler"
        private const val FONT_ASSET = "fonts/GoogleSansFlex-VariableFont_GRAD,ROND,opsz,slnt,wdth,wght.ttf"
        private val installed = AtomicBoolean(false)
        private val applying = ThreadLocal<Boolean>()
        private val fonts = mutableMapOf<Kind, Typeface>()
        private val appliedKinds = Collections.synchronizedMap(WeakHashMap<TextView, Kind>())
        private val targetKinds = Collections.synchronizedMap(WeakHashMap<TextView, Kind>())
        private val nonTargetViews = Collections.synchronizedMap(WeakHashMap<TextView, Boolean>())
    }
}
