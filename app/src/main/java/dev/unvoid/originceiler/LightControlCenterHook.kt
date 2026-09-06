package dev.unvoid.originceiler

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

class LightControlCenterHook {
    fun install() {
        if (!hooked.compareAndSet(false, true)) return
        val constructorHook = object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (rewriting.get() == true || param.args.isEmpty()) return
                val context = param.args[0] as? Context ?: return
                if (!enabled(context) || !isControlCenterCaller() || !isDark(context)) return
                param.args[0] = lightContext(context)
            }
        }
        XposedBridge::class.java.getDeclaredMethod(
            "hookAllConstructors",
            Class::class.java,
            XC_MethodHook::class.java
        ).invoke(null, View::class.java, constructorHook)
        XposedBridge.hookAllMethods(LayoutInflater::class.java, "inflate", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (rewriting.get() == true || param.args.firstOrNull() !is Int) return
                val inflater = param.thisObject as? LayoutInflater ?: return
                val context = inflater.context
                if (!enabled(context) || !isControlCenterCaller() || !isDark(context)) return
                rewriting.set(true)
                try {
                    val clone = inflater.cloneInContext(lightContext(context))
                    val method = param.javaClass.getField("method").get(param) as java.lang.reflect.Method
                    param.result = method.invoke(clone, *param.args)
                } finally {
                    rewriting.remove()
                }
            }
        })
    }

    private fun lightContext(context: Context): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or Configuration.UI_MODE_NIGHT_NO
        val configured = context.createConfigurationContext(configuration)
        return runCatching { ContextThemeWrapper(configured, context.theme) }.getOrDefault(configured)
    }

    private fun enabled(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, SETTING_ENABLED, 0) == 1
    }

    private fun isDark(context: Context): Boolean {
        return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun isControlCenterCaller(): Boolean {
        return Thread.currentThread().stackTrace.any {
            val name = it.className.lowercase()
            name.contains("controlcenter") || name.contains("control_center") ||
                name.contains("systemuiplugin.qs.") || name.contains("systemui.qs.") ||
                name.contains("superqs") || name.contains("super_qs")
        }
    }

    companion object {
        const val SETTING_ENABLED = "originroottoolbox_light_control_center_dark_mode"
        private val hooked = AtomicBoolean(false)
        private val rewriting = ThreadLocal<Boolean>()
    }
}
