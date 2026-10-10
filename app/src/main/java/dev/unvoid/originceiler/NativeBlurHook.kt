package dev.unvoid.originceiler

import android.content.Context
import android.provider.Settings
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap

class NativeBlurHook {
    private val originalRadii = Collections.synchronizedMap(IdentityHashMap<Any, Float>())

    fun install(classLoader: ClassLoader) {
        HELPER_CLASSES.forEach { name ->
            findClass(name, classLoader)?.let(::hookEffectHelper)
        }
        DELEGATOR_CLASSES.forEach { name ->
            findClass(name, classLoader)?.let(::hookMaterialDelegator)
        }
        PANEL_CLASSES.forEach { name ->
            findClass(name, classLoader)?.let(::hookPanelBackground)
        }
        PANEL_BASE_CLASSES.forEach { name ->
            findClass(name, classLoader)?.let(::hookPanelBase)
        }
    }

    private fun hookEffectHelper(type: Class<*>) {
        listOf("setMaterialParam", "setLightAndDarkMaterialParam").forEach { name ->
            XposedBridge.hookAllMethods(type, name, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = helperView(param.thisObject)?.context ?: return
                    val scale = settingScale(context, SETTING_CONTROL_CENTER_BLUR)
                    param.args.forEach { value ->
                        if (isMaterialParams(value)) applyMaterialScale(value, scale)
                    }
                }
            })
        }
        XposedBridge.hookAllMethods(type, "refreshBlur", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = helperView(param.thisObject)?.context ?: return
                val scale = settingScale(context, SETTING_CONTROL_CENTER_BLUR)
                listOf("mMaterialParams", "mMaterialParamsLight", "mMaterialParamsDark").forEach { name ->
                    readField(param.thisObject, name)?.let { applyMaterialScale(it, scale) }
                }
            }
        })
    }

    private fun hookMaterialDelegator(type: Class<*>) {
        XposedBridge.hookAllMethods(type, "setMaterialCustomized", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.args.firstOrNull() as? View ?: return
                val key = blurSettingFor(view) ?: return
                val scale = settingScale(view.context, key)
                val material = param.args.getOrNull(1) ?: return
                if (isMaterialParams(material)) {
                    applyMaterialScale(material, scale)
                } else if (material is Number) {
                    param.args[1] = (material.toFloat() * scale).coerceAtLeast(0f)
                }
            }
        })
    }

    private fun hookPanelBackground(type: Class<*>) {
        XposedBridge.hookAllMethods(type, "setBlurRadius", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val value = param.args.firstOrNull() as? Number ?: return
                val context = ownerContext(param.thisObject) ?: return
                param.args[0] = (value.toFloat() * settingScale(context, SETTING_PANEL_BLUR)).coerceAtLeast(0f)
            }
        })
        XposedBridge.hookAllMethods(type, "updateBgViewAlpha", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = ownerContext(param.thisObject) ?: return
                if (panelScaleDisabled(context)) setBooleanField(param.thisObject, "mEnableBlurScale", false)
            }
        })
    }

    private fun hookPanelBase(type: Class<*>) {
        XposedBridge.hookAllMethods(type, "enableBlurScale", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = ownerContext(param.thisObject) ?: return
                if (panelScaleDisabled(context) && param.args.isNotEmpty()) param.args[0] = false
            }
        })
    }

    private fun helperView(owner: Any): View? {
        return runCatching {
            owner.javaClass.methods.firstOrNull { it.name == "getView" && it.parameterCount == 0 }?.invoke(owner) as? View
        }.getOrNull() ?: readField(owner, "mView") as? View
    }

    private fun applyMaterialScale(material: Any, scale: Float) {
        if (!isMaterialParams(material)) return
        val getter = findMethod(material.javaClass, "getBlurRadius") ?: return
        val setter = findMethod(material.javaClass, "setBlurRadius", Float::class.javaPrimitiveType ?: return) ?: return
        val current = runCatching { (getter.invoke(material) as Number).toFloat() }.getOrNull() ?: return
        val original = originalRadii.getOrPut(material) { current }
        runCatching { setter.invoke(material, (original * scale).coerceAtLeast(0f)) }
    }

    private fun blurSettingFor(view: View): String? {
        val identity = buildString {
            var current: View? = view
            repeat(14) {
                val item = current ?: return@repeat
                append(item.javaClass.name.lowercase())
                append(' ')
                if (item.id != View.NO_ID) {
                    append(runCatching { item.resources.getResourceEntryName(item.id).lowercase() }.getOrDefault(""))
                    append(' ')
                }
                current = item.parent as? View
            }
        }
        if (NOTIFICATION_MARKERS.any(identity::contains)) return SETTING_NOTIFICATIONS_BLUR
        if (CONTROL_CENTER_MARKERS.any(identity::contains)) return SETTING_CONTROL_CENTER_BLUR
        return null
    }

    private fun ownerContext(owner: Any): Context? {
        return readField(owner, "mContext") as? Context
            ?: readField(owner, "context") as? Context
            ?: (readField(owner, "mContainer") as? View)?.context
    }

    private fun settingScale(context: Context, key: String): Float {
        return Settings.Global.getInt(context.contentResolver, key, 100).coerceIn(0, 200) / 100f
    }

    private fun panelScaleDisabled(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, SETTING_DISABLE_PANEL_SCALE, 0) == 1
    }

    private fun isMaterialParams(value: Any?): Boolean {
        return value?.javaClass?.name?.endsWith("VivoThreeLayerMaterialParams") == true
    }

    private fun readField(owner: Any, name: String): Any? {
        return runCatching { findField(owner.javaClass, name)?.get(owner) }.getOrNull()
    }

    private fun setBooleanField(owner: Any, name: String, value: Boolean) {
        runCatching { findField(owner.javaClass, name)?.setBoolean(owner, value) }
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    private fun findMethod(type: Class<*>, name: String, vararg parameters: Class<*>): Method? {
        return runCatching { type.getMethod(name, *parameters) }.getOrNull()
            ?: runCatching { type.getDeclaredMethod(name, *parameters).apply { isAccessible = true } }.getOrNull()
    }

    private fun findClass(name: String, classLoader: ClassLoader): Class<*>? {
        return runCatching { Class.forName(name, false, classLoader) }.getOrNull()
    }

    companion object {
        const val SETTING_CONTROL_CENTER_BLUR = "originceiler_native_blur_control_center"
        const val SETTING_NOTIFICATIONS_BLUR = "originceiler_native_blur_notifications"
        const val SETTING_PANEL_BLUR = "originceiler_native_blur_panel"
        const val SETTING_DISABLE_PANEL_SCALE = "originceiler_native_blur_disable_panel_scale"
        const val SETTING_ENABLED = "originceiler_native_blur_enabled"
        const val SETTING_TILES_BLUR = "originceiler_native_blur_tiles"
        const val SETTING_TILES_SAT = "originceiler_native_blur_tiles_sat"
        const val SETTING_SLIDERS_BLUR = "originceiler_native_blur_sliders"
        const val SETTING_SLIDERS_SAT = "originceiler_native_blur_sliders_sat"
        const val SETTING_BUTTONS_BLUR = "originceiler_native_blur_buttons"
        const val SETTING_BUTTONS_SAT = "originceiler_native_blur_buttons_sat"
        const val SETTING_PLAYER_BLUR = "originceiler_native_blur_player"
        const val SETTING_PLAYER_SAT = "originceiler_native_blur_player_sat"
        const val SETTING_OTHER_BLUR = "originceiler_native_blur_other"
        const val SETTING_OTHER_SAT = "originceiler_native_blur_other_sat"
        const val SETTING_PANEL_DARKEN = "originceiler_native_blur_panel_darken"

        private val HELPER_CLASSES = listOf(
            "com.vivo.systemuiplugin.systemui.qs.utils.VivoEffectHelper"
        )
        private val DELEGATOR_CLASSES = listOf(
            "com.vivo.blur.VivoMaterialBlurDelegator",
            "com.vivo.systemuiplugin.keyguard.blur.VivoMaterialBlurDelegator"
        )
        private val PANEL_CLASSES = listOf(
            "com.vivo.systemuiplugin.systemui.statusbar.panel.background.VivoDynamicBlurBg",
            "com.vivo.systemui.statusbar.panel.background.VivoDynamicBlurBg"
        )
        private val PANEL_BASE_CLASSES = listOf(
            "com.vivo.systemuiplugin.systemui.statusbar.panel.background.VivoBackground",
            "com.vivo.systemui.statusbar.panel.background.VivoBackground"
        )
        private val NOTIFICATION_MARKERS = listOf(
            "notification", "notif", "expandable", "heads_up", "headsup", "stack_scroller", "row"
        )
        private val CONTROL_CENTER_MARKERS = listOf(
            "systemui.qs", "vivoqs", "qs_tile", "qstile", "paneltile", "control_center", "controlcenter",
            "brightness", "volume", "mixmusic", "media_qs", "quick_setting", "quicksetting"
        )
    }
}
