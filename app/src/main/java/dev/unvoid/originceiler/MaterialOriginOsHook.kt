package dev.unvoid.originceiler

import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class MaterialOriginOsHook {
    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        hookDimension("getDimension") { value -> value }
        hookDimension("getDimensionPixelSize") { value ->
            value.roundToInt().let { rounded ->
                when {
                    rounded != 0 -> rounded
                    value > 0f -> 1
                    value < 0f -> -1
                    else -> 0
                }
            }
        }
        hookDimension("getDimensionPixelOffset") { value -> value.toInt() }
        hookDrawable("getDrawable")
        hookDrawable("getDrawableForDensity")
        hookColor("getColor", false)
        hookColor("getColorStateList", true)
        hookMobileTypeLayout()
        hookDynamicBattery(classLoader)
        MaterialOriginOsVolumeHook().install(classLoader)
    }

    private fun hookDynamicBattery(classLoader: ClassLoader) {
        val batteryClass = runCatching {
            Class.forName("com.vivo.systemui.battery.VivoHorBatteryIconView", false, classLoader)
        }.getOrNull() ?: return
        val customField = generateSequence(batteryClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField("mHasCusBatteryIcon") }.getOrNull() }
            .firstOrNull()
            ?.apply { isAccessible = true }
            ?: return
        XposedBridge.hookAllMethods(batteryClass, "hasCustomizedBatteryIcon", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!statusBarEnabled()) return
                customField.setBoolean(param.thisObject, false)
            }
        })
    }

    private fun hookMobileTypeLayout() {
        XposedBridge.hookAllMethods(ImageView::class.java, "setImageDrawable", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!statusBarEnabled()) return
                val view = param.thisObject as? ImageView ?: return
                val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() ?: return
                if (name in hiddenStatusViews) view.visibility = View.GONE
            }
        })
        XposedBridge.hookAllMethods(View::class.java, "setVisibility", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!statusBarEnabled()) return
                val view = param.thisObject as? View ?: return
                val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() ?: return
                if (name in hiddenStatusViews) param.args[0] = View.GONE
            }
        })
    }

    private fun hookColor(methodName: String, stateList: Boolean) {
        XposedBridge.hookAllMethods(Resources::class.java, methodName, object : XC_MethodHook(Int.MAX_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!statusBarEnabled() && !volumeEnabled()) return
                val resources = param.thisObject as? Resources ?: return
                val id = param.args.firstOrNull() as? Int ?: return
                val color = materialColor(resources, id) ?: return
                param.result = if (stateList) ColorStateList.valueOf(color) else color
            }
        })
    }

    private fun materialColor(resources: Resources, id: Int): Int? {
        val packageName = runCatching { resources.getResourcePackageName(id) }.getOrNull() ?: return null
        if (packageName != "com.android.systemui") return null
        val name = runCatching { resources.getResourceEntryName(id) }.getOrNull() ?: return null
        if (volumeEnabled() && name in setOf("volume_dialog_background_color", "volume_dialog_background_color_above_blur")) {
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return if (night) 0xff111217.toInt() else 0xfffcf9ff.toInt()
        }
        if (!name.contains("battery") && !name.contains("batterymeter")) return null
        return when {
            name.contains("level_low") || name.contains("lev_color_low") || name.contains("low_battery") -> 0xffff0e01.toInt()
            name.contains("save_mode") || name.contains("saver") -> 0xffffc917.toInt()
            name.contains("engine_charge") || name.contains("flash") || name.contains("level_charge") -> 0xff18cc47.toInt()
            else -> null
        }
    }

    private fun hookDrawable(methodName: String) {
        XposedBridge.hookAllMethods(Resources::class.java, methodName, object : XC_MethodHook(Int.MAX_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!statusBarEnabled() && !volumeEnabled()) return
                val resources = param.thisObject as? Resources ?: return
                val id = param.args.firstOrNull() as? Int ?: return
                val packageName = runCatching { resources.getResourcePackageName(id) }.getOrNull() ?: return
                if (packageName != "com.android.systemui") return
                val entryName = runCatching { resources.getResourceEntryName(id) }.getOrNull() ?: return
                if (entryName.startsWith("vivo_wifi_activity_")) {
                    param.result = transparentDrawable(resources)
                    return
                }
                val battery = batteryDrawable(resources, entryName)
                if (battery != null) {
                    param.result = battery
                    return
                }
                val sourceName = statusBarDrawable(entryName) ?: return
                val replacement = moduleDrawable(sourceName) ?: return
                param.result = replacement
            }
        })
    }

    private fun transparentDrawable(resources: Resources): Drawable {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.density = resources.displayMetrics.densityDpi
        return BitmapDrawable(resources, bitmap)
    }

    private fun batteryDrawable(resources: Resources, entryName: String): Drawable? {
        val baseLevel = when (entryName) {
            "vivo_battery" -> 0f
            "vivo_battery_full_icon" -> 1f
            else -> null
        }
        if (baseLevel != null) {
            return renderBattery(resources, baseLevel, 0, false)
        }
        if (!batteryPrefixes.any(entryName::startsWith)) return null
        val indexedLevel = batteryLevel.find(entryName)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val staticLevel = batteryStatic.containsMatchIn(entryName)
        if (indexedLevel == null && !staticLevel) return null
        val index = indexedLevel ?: 19
        val chargeStyle = when {
            entryName.contains("percent_in") -> 0
            entryName.contains("flash") || entryName.contains("engine") || entryName.contains("direct") -> 2
            entryName.contains("charge") -> 1
            else -> 0
        }
        return renderBattery(
            resources,
            ((index + 1) / 20f).coerceIn(0f, 1f),
            chargeStyle,
            entryName.endsWith("_white")
        )
    }

    private fun renderBattery(resources: Resources, level: Float, chargeStyle: Int, white: Boolean): Drawable {
        val source = MaterialStatusBatteryDrawable(resources.displayMetrics.density, level, chargeStyle, white)
        val width = source.intrinsicWidth.coerceAtLeast(1)
        val height = source.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        source.setBounds(0, 0, width, height)
        source.draw(Canvas(bitmap))
        return BitmapDrawable(resources, bitmap)
    }

    private fun moduleDrawable(sourceName: String): Drawable? {
        val application = currentApplication() ?: return null
        val moduleContext = runCatching {
            application.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        val name = "material_originos_$sourceName"
        val id = moduleContext.resources.getIdentifier(name, "drawable", MODULE_PACKAGE)
        if (id == 0) return null
        val source = runCatching { moduleContext.resources.getDrawable(id, null).mutate() }.getOrNull() ?: return null
        if (source is BitmapDrawable) return source
        val width = source.intrinsicWidth.coerceAtLeast(1)
        val height = source.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        source.setBounds(0, 0, width, height)
        source.draw(Canvas(bitmap))
        val cropDp = mobileTypeCropDp[sourceName]
        val result = if (cropDp != null) {
            val cropWidth = (cropDp * moduleContext.resources.displayMetrics.density).roundToInt().coerceIn(1, width)
            Bitmap.createBitmap(bitmap, 0, 0, cropWidth, height)
        } else {
            bitmap
        }
        return BitmapDrawable(moduleContext.resources, result)
    }

    private fun statusBarDrawable(entryName: String): String? {
        if (entryName in originalDrawables) return entryName
        wifiLevel.matchEntire(entryName)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return "stat_signal_wifi_signal_${(it + 1).coerceIn(1, 4)}"
        }
        mobileLevel.matchEntire(entryName)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return "stat_signal_signal_lte_single_${it.coerceIn(0, 4)}"
        }
        unavailableWifiLevel.matchEntire(entryName)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return "stat_signal_wifi_signal_0"
        }
        bluetoothBattery.matchEntire(entryName)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { percent ->
            val level = ((percent + 9) / 10).coerceIn(1, 9)
            return when (level) {
                1 -> "stat_bt_battery_low_1"
                2 -> "stat_bt_battery_low_2"
                else -> "stat_bt_battery_black_$level"
            }
        }
        return when {
            entryName in setOf("vivo_stat_sys_airplane_mode", "vivo_airplane_mode_white", "vivo_airplane_mode_color") -> "stat_sys_airplane_mode"
            entryName.startsWith("vivo_signal_flightmode") -> "stat_sys_airplane_mode"
            entryName.startsWith("vivo_signal_no_sim") || entryName.startsWith("vivo_signal_sim") -> "stat_signal_signal_null_lte"
            entryName.startsWith("vivo_alarm_") -> "stat_sys_alarm"
            entryName.contains("bluetooth_connected") || entryName == "stat_sys_data_bluetooth_connected" -> "stat_sys_data_bluetooth_connected_stop"
            entryName.startsWith("vivo_bluetooth") -> "stat_sys_data_bluetooth"
            entryName.startsWith("vivo_headset_") || entryName == "stat_sys_headset_mic" -> "stat_sys_headset"
            entryName.contains("ringer_vibrate") -> "stat_sys_ringer_vibrate"
            entryName.contains("ringer_silent") -> "stat_sys_ringer_silent"
            entryName.contains("stat_sys_location") -> "stat_sys_location"
            entryName.contains("stat_sys_nfc") -> "stat_sys_nfc"
            entryName == "stat_sys_roaming" || entryName == "stat_sys_roaming_large" -> "stat_signal_roma_lte"
            entryName == "vivo_wifi_signal_empty" || entryName == "vivo_wifi_unavailable" -> "stat_signal_wifi_signal_0"
            entryName.startsWith("vivo_data_activity_in") -> "stat_signal_activity_in_public"
            entryName.startsWith("vivo_data_activity_out") -> "stat_signal_activity_out_public"
            entryName.startsWith("vivo_double_wifi_signal_") -> "stat_signal_wifi_double"
            entryName.startsWith("vivo_ic_3g_mobiledata") && !entryName.contains("_combine") -> "stat_signal_connected_3g_lte_big"
            entryName.startsWith("vivo_ic_4g_plus_mobiledata") && !entryName.contains("_combine") -> "stat_signal_connected_4gp_lte_big"
            entryName.startsWith("vivo_ic_4g_mobiledata") && !entryName.contains("_combine") -> "stat_signal_connected_4g_lte_big"
            entryName.startsWith("vivo_ic_5g_a_mobiledata") && !entryName.contains("_combine") -> "stat_sys_data_fully_connected_5g_a"
            entryName.startsWith("vivo_ic_5g_plus_mobiledata") && !entryName.contains("_combine") -> "stat_sys_data_fully_connected_5g_plus"
            entryName.startsWith("vivo_ic_5g_mobiledata") && !entryName.contains("_combine") -> "stat_signal_connected_5g"
            entryName == "vivo_volte_1_2" || entryName == "vivo_volte" -> "stat_signal_volte_sim_both"
            entryName == "vivo_volte1" -> "stat_signal_volte_sim1"
            entryName == "vivo_volte2" -> "stat_signal_volte_sim2"
            entryName.startsWith("vivo_sys_roaming") -> "stat_signal_roma_lte"
            entryName == "vivo_performance_mode_white" -> "stat_sys_high_performance"
            entryName in setOf("vivo_stat_sys_vpn_ic", "vivo_vpn_white", "vivo_vpn_color") -> "stat_sys_vpn_ic"
            else -> null
        }
    }

    private fun hookDimension(methodName: String, result: (Float) -> Any) {
        XposedBridge.hookAllMethods(Resources::class.java, methodName, object : XC_MethodHook(Int.MAX_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                val resources = param.thisObject as? Resources ?: return
                val id = param.args.firstOrNull() as? Int ?: return
                val targetDp = targetDp(resources, id) ?: return
                param.result = result(targetDp * resources.displayMetrics.density)
            }
        })
    }

    private fun targetDp(resources: Resources, id: Int): Float? {
        val packageName = runCatching { resources.getResourcePackageName(id) }.getOrNull() ?: return null
        if (packageName != "com.android.systemui") return null
        val entryName = runCatching { resources.getResourceEntryName(id) }.getOrNull() ?: return null
        if (enabled()) {
            when (entryName) {
                "notification_side_paddings" -> return 32f
                "notification_corner_radius" -> return 18f
            }
        }
        if (volumeEnabled()) {
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            when (entryName) {
                "volume_dialog_slider_height" -> return if (landscape) 240f else 260f
                "volume_row_slider_height" -> return if (landscape) 240f else 260f
                "volume_dialog_slider_width" -> return 48f
                "volume_dialog_slider_width_legacy" -> return 38f
                "volume_dialog_track_width" -> return 38f
                "volume_dialog_width" -> return 48f
                "volume_dialog_panel_width" -> return 48f
                "volume_dialog_panel_width_half" -> return 24f
                "volume_dialog_background_corner_radius" -> return 24f
                "volume_dialog_components_spacing" -> return 4f
                "volume_dialog_button_size" -> return 42f
                "volume_dialog_tap_target_size" -> return 42f
                "volume_dialog_ringer_size" -> return 42f
                "volume_dialog_ringer_rows_padding" -> return 0f
                "volume_dialog_row_margin_bottom" -> return 4f
                "volume_dialog_spacer" -> return 4f
                "volume_dialog_slider_corner_radius" -> return 10f
            }
        }
        if (!statusBarEnabled()) return null
        return when (entryName) {
            "status_bar_icon_drawing_size",
            "status_bar_icon_drawing_size_dark",
            "status_bar_wifi_signal_size",
            "status_bar_mobile_signal_size",
            "status_bar_mobile_signal_size_updated" -> 16f
            "status_bar_battery_unified_icon_height" -> 12f
            "status_bar_battery_unified_icon_width" -> 23f
            "vivo_statusbar_battery_percentage_in_text_size",
            "vivo_statusbar_battery_percentage_in_text_size_for_my_language" -> 10.199982f
            "vivo_status_bar_Percent_padding_top_land",
            "vivo_status_bar_Percent_padding_top_portrait" -> -0.27499998f
            else -> null
        }
    }

    private fun enabled(): Boolean {
        val application = currentApplication() ?: return false
        return Settings.Global.getInt(application.contentResolver, SETTING_ENABLED, 0) == 1
    }

    private fun statusBarEnabled(): Boolean {
        val application = currentApplication() ?: return false
        return Settings.Global.getInt(application.contentResolver, SETTING_STATUS_BAR, 0) == 1
    }

    private fun volumeEnabled(): Boolean {
        val application = currentApplication() ?: return false
        return Settings.Global.getInt(application.contentResolver, SETTING_VOLUME, 0) == 1
    }

    private fun currentApplication(): Application? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .invoke(null) as? Application
    }.getOrNull()

    companion object {
        const val SETTING_ENABLED = "originroottoolbox_material_originos_notifications"
        const val SETTING_STATUS_BAR = "originroottoolbox_material_originos_statusbar"
        const val SETTING_VOLUME = "originroottoolbox_material_originos_volume"
        private const val MODULE_PACKAGE = "dev.unvoid.originceiler"
        private val batteryLevel = Regex("_20_(\\d{2})(?:_|$)")
        private val batteryStatic = Regex("_20(?:_static)?_(?:color|white)$")
        private val batteryPrefixes = listOf(
            "vivo_battery_",
            "vivo_save_mode_battery_"
        )
        private val wifiLevel = Regex("vivo_wifi_signal_(\\d)")
        private val unavailableWifiLevel = Regex("vivo_unavailable_wifi_signal_(\\d)")
        private val mobileLevel = Regex("vivo_signal_strength_(\\d)(?:_combine)?")
        private val bluetoothBattery = Regex("vivo_bt_headset_pw_(?:dark_)?(\\d{2,3})")
        private val mobileTypeCropDp = mapOf(
            "stat_signal_connected_3g_lte_big" to 18f,
            "stat_signal_connected_4g_lte_big" to 18f,
            "stat_signal_connected_4gp_lte_big" to 23f,
            "stat_signal_connected_5g" to 18f,
            "stat_sys_data_fully_connected_5g_a" to 24f,
            "stat_sys_data_fully_connected_5g_plus" to 24f,
            "stat_sys_data_fully_connected_5g_plus_plus" to 25f
        )
        private val hiddenStatusViews = setOf("mobile_type", "mobile_type_container", "wifi_in", "wifi_out")
        private val originalDrawables = setOf(
            "stat_sys_airplane_mode",
            "stat_sys_alarm",
            "stat_sys_data_bluetooth",
            "stat_sys_data_bluetooth_connected_ing",
            "stat_sys_data_bluetooth_connected_stop",
            "stat_sys_data_fully_connected_5g_a",
            "stat_sys_data_fully_connected_5g_plus",
            "stat_sys_data_fully_connected_5g_plus_plus",
            "stat_sys_data_saver",
            "stat_sys_dnd",
            "stat_sys_headset",
            "stat_sys_high_performance",
            "stat_sys_location",
            "stat_sys_nfc",
            "stat_sys_ringer_silent",
            "stat_sys_ringer_vibrate",
            "stat_sys_sos",
            "stat_sys_tool_block_banner_on",
            "stat_sys_vpn_ic",
            "stat_signal_connected_3g_lte_big",
            "stat_signal_connected_4g_lte_big",
            "stat_signal_connected_4gp_lte_big",
            "stat_signal_connected_5g",
            "stat_signal_noservice_lte",
            "stat_signal_signal_null_lte",
            "stat_signal_activity_default_public",
            "stat_signal_activity_in_public",
            "stat_signal_activity_inout_public",
            "stat_signal_activity_out_public",
            "stat_signal_activity_wifi_in",
            "stat_signal_activity_wifi_inout",
            "stat_signal_activity_wifi_none",
            "stat_signal_activity_wifi_out",
            "stat_signal_roma_lte",
            "stat_signal_signal_novoice_1",
            "stat_signal_signal_novoice_2",
            "stat_signal_signal_novoice_3",
            "stat_signal_signal_novoice_4",
            "stat_signal_volte_sim_both",
            "stat_signal_volte_sim1",
            "stat_signal_volte_sim2",
            "stat_signal_wifi_double",
            "stat_bt_battery_black_0",
            "stat_bt_battery_black_3",
            "stat_bt_battery_black_4",
            "stat_bt_battery_black_5",
            "stat_bt_battery_black_6",
            "stat_bt_battery_black_7",
            "stat_bt_battery_black_8",
            "stat_bt_battery_black_9",
            "stat_bt_battery_low_1",
            "stat_bt_battery_low_2",
            "stat_charge_normal",
            "stat_charge_super_vooc"
        )
        private val installed = AtomicBoolean(false)
    }
}
