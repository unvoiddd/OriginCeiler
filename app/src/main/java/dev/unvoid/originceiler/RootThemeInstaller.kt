package dev.unvoid.originceiler

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class RootResult(val success: Boolean, val message: String)

class RootThemeInstaller(private val context: Context) {
    fun check(): RootResult = execute("id")

    fun apply(result: ConversionResult, pixelSearchTint: Int?): RootResult = runCatching {
        val script = scriptFile("apply.sh", applyScript())
        val template = templateFile()
        val tint = pixelSearchTint?.toString() ?: "none"
        execute("sh '${script.absolutePath}' '${result.stage.absolutePath}' '${template.absolutePath}' '$tint'")
    }.getOrElse { RootResult(false, it.message ?: "Unable to prepare theme template") }

    fun applyIconPack(packageName: String, autoRecolor: Boolean, replaceBackground: Boolean): RootResult {
        if (!packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) {
            return RootResult(false, "Invalid icon pack package")
        }
        if (runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isFailure) {
            return RootResult(false, "Icon pack is not installed")
        }
        return execute("settings put global ${LauncherIconPack.SETTING_PACK} '$packageName'; " +
            "settings put global ${LauncherIconPack.SETTING_AUTO_RECOLOR} ${if (autoRecolor) 1 else 0}; " +
            "settings put global ${LauncherIconPack.SETTING_REPLACE_BACKGROUND} ${if (replaceBackground) 1 else 0}; " +
            "settings put global originicons_themed_launcher_icons 0; " +
            "settings put system themed_icon_enabled 0; settings put secure themed_icon_enabled 0; " +
            "rm -rf /data/bbkcore/theme/icons; " +
            "settings put system key_system_color_change \$(date +%s); " +
            "am force-stop com.bbk.launcher2; $MARK_LAUNCHER_ICONS_DIRTY am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null")
    }

    fun setLiveIconPackOptions(autoRecolor: Boolean, replaceBackground: Boolean): RootResult = execute(
        "settings put global ${LauncherIconPack.SETTING_AUTO_RECOLOR} ${if (autoRecolor) 1 else 0}; " +
            "settings put global ${LauncherIconPack.SETTING_REPLACE_BACKGROUND} ${if (replaceBackground) 1 else 0}; " +
            "am force-stop com.bbk.launcher2; $MARK_LAUNCHER_ICONS_DIRTY am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null"
    )

    fun restartLauncher(): RootResult {
        return execute("am force-stop com.bbk.launcher2; am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null")
    }

    fun restartScope(packageName: String): RootResult {
        val command = when (packageName) {
            "android" -> "setprop ctl.restart zygote"
            "com.android.systemui" -> "killall com.android.systemui 2>/dev/null || true"
            "com.vivo.systemuiplugin" -> "am force-stop com.vivo.systemuiplugin; killall com.android.systemui 2>/dev/null || true"
            "com.bbk.launcher2" -> "am force-stop com.bbk.launcher2; am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null"
            "com.vivo.ai.copilot" -> blueLmRestartCommand()
            else -> "am force-stop '$packageName'"
        }
        return execute(command)
    }

    fun resetIcons(): RootResult {
        return execute("rm -rf /data/bbkcore/theme/icons; settings delete global ${LauncherIconPack.SETTING_PACK}; settings delete global ${LauncherIconPack.SETTING_AUTO_RECOLOR}; settings delete global ${LauncherIconPack.SETTING_REPLACE_BACKGROUND}; settings delete global originicons_pixel_search_aosp_tint; restorecon -RF /data/bbkcore/theme 2>/dev/null || true; am force-stop com.bbk.launcher2; $MARK_LAUNCHER_ICONS_DIRTY am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null")
    }

    fun setCircleToSearch(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_circle_to_search $value; am force-stop com.bbk.launcher2; am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null")
    }

    fun setPixelHomeLayout(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_pixel_home_layout $value; am force-stop com.bbk.launcher2; am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null")
    }

    fun setLauncherIconSize(value: Int): RootResult {
        return execute("settings put global originroottoolbox_launcher_icon_size ${value.coerceIn(70, 150)}")
    }

    fun applyLauncherGrid(cols: Int, rows: Int): RootResult {
        val safeCols = cols.coerceAtLeast(1)
        val safeRows = rows.coerceAtLeast(1)

        val cr = context.contentResolver
        val isCustomEnabled = Settings.Global.getInt(cr, LauncherGridHook.SETTING_CUSTOM_GRID_ENABLED, 0) == 1
        val origCols = Settings.Global.getInt(cr, LauncherGridHook.SETTING_ORIGINAL_COLUMNS, -1)
        val origRows = Settings.Global.getInt(cr, LauncherGridHook.SETTING_ORIGINAL_ROWS, -1)

        val saveOriginalCmd = if (!isCustomEnabled || origCols <= 0 || origRows <= 0) {
            val curCols = Settings.Global.getInt(cr, LauncherGridHook.SETTING_GRID_COLUMNS, 4).coerceAtLeast(1)
            val curRows = Settings.Global.getInt(cr, LauncherGridHook.SETTING_GRID_ROWS, 7).coerceAtLeast(1)
            "settings put global ${LauncherGridHook.SETTING_ORIGINAL_COLUMNS} $curCols; " +
            "settings put global ${LauncherGridHook.SETTING_ORIGINAL_ROWS} $curRows; "
        } else ""

        val layoutXml = """
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="oldCountY" value="$safeRows" />
    <int name="newCountY" value="$safeRows" />
    <int name="launcher_newCountX_2" value="$safeCols" />
    <int name="oldCountX" value="$safeCols" />
    <int name="newCountX" value="$safeCols" />
    <int name="launcher_newCountY_2" value="$safeRows" />
    <int name="launcher_newCountY_drawer_2" value="$safeRows" />
    <int name="launcher_newCountX_drawer_2" value="$safeCols" />
</map>
""".trimIndent()

        val launcher3Xml = """
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="idp_grid_name">${safeCols}_by_${safeRows}</string>
</map>
""".trimIndent()

        val script = """
            am force-stop com.bbk.launcher2;
            $saveOriginalCmd
            settings put global ${LauncherGridHook.SETTING_CUSTOM_GRID_ENABLED} 1;
            settings put global ${LauncherGridHook.SETTING_GRID_COLUMNS} $safeCols;
            settings put global ${LauncherGridHook.SETTING_GRID_ROWS} $safeRows;
            am force-stop com.bbk.launcher2;
            mkdir -p /data/user_de/0/com.bbk.launcher2/shared_prefs;
            rm -f /data/user_de/0/com.bbk.launcher2/shared_prefs/com.bbk.launcher2.layout.xml.bak /data/user_de/0/com.bbk.launcher2/shared_prefs/com.android.launcher3.prefs.xml.bak;
            LAUNCHER_OWNER=${'$'}(stat -c '%u:%g' /data/user_de/0/com.bbk.launcher2 2>/dev/null || echo "10093:10093");
            cat << 'EOF' > /data/user_de/0/com.bbk.launcher2/shared_prefs/com.bbk.launcher2.layout.xml
$layoutXml
EOF
            cat << 'EOF' > /data/user_de/0/com.bbk.launcher2/shared_prefs/com.android.launcher3.prefs.xml
$launcher3Xml
EOF
            chown ${'$'}LAUNCHER_OWNER /data/user_de/0/com.bbk.launcher2/shared_prefs/com.bbk.launcher2.layout.xml /data/user_de/0/com.bbk.launcher2/shared_prefs/com.android.launcher3.prefs.xml 2>/dev/null || true;
            chmod 660 /data/user_de/0/com.bbk.launcher2/shared_prefs/com.bbk.launcher2.layout.xml /data/user_de/0/com.bbk.launcher2/shared_prefs/com.android.launcher3.prefs.xml 2>/dev/null || true;
            restorecon -RF /data/user_de/0/com.bbk.launcher2/shared_prefs 2>/dev/null || true;
            am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
        """.trimIndent()

        return execute(script)
    }

    fun resetLauncherGrid(): RootResult {
        val cr = context.contentResolver
        val origCols = Settings.Global.getInt(cr, LauncherGridHook.SETTING_ORIGINAL_COLUMNS, 4).coerceAtLeast(1)
        val origRows = Settings.Global.getInt(cr, LauncherGridHook.SETTING_ORIGINAL_ROWS, 7).coerceAtLeast(1)

        val layoutXml = """
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="oldCountY" value="$origRows" />
    <int name="newCountY" value="$origRows" />
    <int name="launcher_newCountX_2" value="$origCols" />
    <int name="oldCountX" value="$origCols" />
    <int name="newCountX" value="$origCols" />
    <int name="launcher_newCountY_2" value="$origRows" />
    <int name="launcher_newCountY_drawer_2" value="$origRows" />
    <int name="launcher_newCountX_drawer_2" value="$origCols" />
</map>
""".trimIndent()

        val launcher3Xml = """
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="idp_grid_name">${origCols}_by_${origRows}</string>
</map>
""".trimIndent()

        val script = """
            am force-stop com.bbk.launcher2;
            settings put global ${LauncherGridHook.SETTING_CUSTOM_GRID_ENABLED} 0;
            settings put global ${LauncherGridHook.SETTING_GRID_COLUMNS} $origCols;
            settings put global ${LauncherGridHook.SETTING_GRID_ROWS} $origRows;
            am force-stop com.bbk.launcher2;
            mkdir -p /data/user_de/0/com.bbk.launcher2/shared_prefs;
            rm -f /data/user_de/0/com.bbk.launcher2/shared_prefs/com.bbk.launcher2.layout.xml.bak /data/user_de/0/com.bbk.launcher2/shared_prefs/com.android.launcher3.prefs.xml.bak;
            LAUNCHER_OWNER=${'$'}(stat -c '%u:%g' /data/user_de/0/com.bbk.launcher2 2>/dev/null || echo "10093:10093");
            cat << 'EOF' > /data/user_de/0/com.bbk.launcher2/shared_prefs/com.bbk.launcher2.layout.xml
$layoutXml
EOF
            cat << 'EOF' > /data/user_de/0/com.bbk.launcher2/shared_prefs/com.android.launcher3.prefs.xml
$launcher3Xml
EOF
            chown ${'$'}LAUNCHER_OWNER /data/user_de/0/com.bbk.launcher2/shared_prefs/com.bbk.launcher2.layout.xml /data/user_de/0/com.bbk.launcher2/shared_prefs/com.android.launcher3.prefs.xml 2>/dev/null || true;
            chmod 660 /data/user_de/0/com.bbk.launcher2/shared_prefs/com.bbk.launcher2.layout.xml /data/user_de/0/com.bbk.launcher2/shared_prefs/com.android.launcher3.prefs.xml 2>/dev/null || true;
            restorecon -RF /data/user_de/0/com.bbk.launcher2/shared_prefs 2>/dev/null || true;
            am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
        """.trimIndent()

        return execute(script)
    }

    fun setStatusBarBatteryScale(value: Int): RootResult {
        return execute("settings put global originroottoolbox_statusbar_battery_scale ${value.coerceIn(50, 150)}")
    }

    fun setStatusBarBatteryX(value: Int): RootResult {
        return execute("settings put global originroottoolbox_statusbar_battery_x ${value.coerceIn(-150, 150)}")
    }

    fun setStatusBarBatteryTextScale(value: Int): RootResult {
        return execute("settings put global originroottoolbox_statusbar_battery_text_scale ${value.coerceIn(50, 150)}")
    }

    fun setStatusBarBatteryTextWeight(value: Int): RootResult {
        return execute("settings put global originroottoolbox_statusbar_battery_text_weight ${value.coerceIn(100, 900)}")
    }

    fun setStatusBarWifiScale(value: Int): RootResult {
        return execute("settings put global originroottoolbox_statusbar_wifi_scale ${value.coerceIn(50, 150)}")
    }

    fun setStatusBarWifiX(value: Int): RootResult {
        return execute("settings put global originroottoolbox_statusbar_wifi_x ${value.coerceIn(-150, 150)}")
    }

    fun setStatusBarClockScale(value: Int): RootResult {
        return execute("settings put global originroottoolbox_statusbar_clock_scale ${value.coerceIn(50, 150)}")
    }

    fun setStatusBarClockX(value: Int): RootResult {
        return execute("settings put global originroottoolbox_statusbar_clock_x ${value.coerceIn(-150, 150)}")
    }

    fun setStatusBarSystemIconsScale(value: Int): RootResult {
        return execute("settings put global originroottoolbox_statusbar_system_icons_scale ${value.coerceIn(50, 150)}")
    }

    fun setRestoreGoogleAssistant(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        val restore = if (enabled) googleAssistantRestoreCommand() else ""
        return execute("settings put global originroottoolbox_restore_google_assistant $value; $restore")
    }

    fun restoreGoogleAssistantIfEnabled(): RootResult {
        return execute("if [ \"\$(settings get global originroottoolbox_restore_google_assistant)\" = \"1\" ]; then ${googleAssistantRestoreCommand()}; fi")
    }

    fun setBypassGeminiRegionalRestrictions(enabled: Boolean): RootResult {
        return if (enabled) {
            execute("if [ \"\$(settings get global originroottoolbox_dns_backup_active)\" = \"1\" ]; then DNS_MODE=\$(settings get global originroottoolbox_dns_mode_backup); DNS_SPECIFIER=\$(settings get global originroottoolbox_dns_specifier_backup); if [ \"\$DNS_MODE\" = \"__null__\" ] || [ \"\$DNS_MODE\" = \"null\" ]; then settings delete global private_dns_mode; else settings put global private_dns_mode \"\$DNS_MODE\"; fi; if [ \"\$DNS_SPECIFIER\" = \"__null__\" ] || [ \"\$DNS_SPECIFIER\" = \"null\" ]; then settings delete global private_dns_specifier; else settings put global private_dns_specifier \"\$DNS_SPECIFIER\"; fi; fi; settings delete global originroottoolbox_dns_mode_backup; settings delete global originroottoolbox_dns_specifier_backup; settings delete global originroottoolbox_dns_backup_active; if [ \"\$(settings get global originroottoolbox_gemini_dns_backup_active)\" != \"1\" ]; then DNS_MODE=\$(settings get global private_dns_mode); DNS_SPECIFIER=\$(settings get global private_dns_specifier); [ \"\$DNS_MODE\" = \"null\" ] && DNS_MODE=__null__; [ \"\$DNS_SPECIFIER\" = \"null\" ] && DNS_SPECIFIER=__null__; settings put global originroottoolbox_gemini_dns_mode_backup \"\$DNS_MODE\"; settings put global originroottoolbox_gemini_dns_specifier_backup \"\$DNS_SPECIFIER\"; settings put global originroottoolbox_gemini_dns_backup_active 1; fi; settings put global originicons_disable_private_dns_vpn 0; settings put global originroottoolbox_bypass_gemini_region 1; settings put global private_dns_mode hostname; settings put global private_dns_specifier xbox-dns.ru; cmd netd resolver flushdefaultif 2>/dev/null || true; am force-stop com.google.android.apps.bard; am force-stop com.google.android.googlequicksearchbox")
        } else {
            execute("settings put global originroottoolbox_bypass_gemini_region 0; if [ \"\$(settings get global originroottoolbox_gemini_dns_backup_active)\" = \"1\" ]; then DNS_MODE=\$(settings get global originroottoolbox_gemini_dns_mode_backup); DNS_SPECIFIER=\$(settings get global originroottoolbox_gemini_dns_specifier_backup); if [ \"\$DNS_MODE\" = \"__null__\" ] || [ \"\$DNS_MODE\" = \"null\" ]; then settings delete global private_dns_mode; else settings put global private_dns_mode \"\$DNS_MODE\"; fi; if [ \"\$DNS_SPECIFIER\" = \"__null__\" ] || [ \"\$DNS_SPECIFIER\" = \"null\" ]; then settings delete global private_dns_specifier; else settings put global private_dns_specifier \"\$DNS_SPECIFIER\"; fi; fi; settings delete global originroottoolbox_gemini_dns_mode_backup; settings delete global originroottoolbox_gemini_dns_specifier_backup; settings delete global originroottoolbox_gemini_dns_backup_active; cmd netd resolver flushdefaultif 2>/dev/null || true; am force-stop com.google.android.apps.bard; am force-stop com.google.android.googlequicksearchbox")
        }
    }

    fun setRemovePackageInstallerAfterReboot(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        val action = if (enabled) {
            "pm uninstall --user 0 com.android.packageinstaller >/dev/null 2>&1 || true"
        } else {
            "cmd package install-existing --user 0 com.android.packageinstaller >/dev/null 2>&1 || true"
        }
        return execute("settings put global originroottoolbox_remove_package_installer_boot $value; $action")
    }

    fun removePackageInstallerIfEnabled(): RootResult {
        return execute("if [ \"\$(settings get global originroottoolbox_remove_package_installer_boot)\" = \"1\" ]; then pm uninstall --user 0 com.android.packageinstaller >/dev/null 2>&1 || true; fi")
    }

    fun setPowerButtonGemini(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_power_button_gemini $value; am force-stop com.vivo.ai.copilot; killall com.vivo.ai.copilot 2>/dev/null || true; am force-stop com.vivo.launchercopilot; killall com.vivo.launchercopilot 2>/dev/null || true")
    }

    fun setBlueLmRussian(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        val recognition = if (enabled) enableWhisperRecognitionCommand() else disableWhisperRecognitionCommand()
        return execute("settings put global ${BlueLmRussianHook.SETTING_ENABLED} $value; $recognition; ${blueLmRestartCommand()}")
    }

    fun restoreWhisperRecognitionIfEnabled(): RootResult = execute(
        "if [ \"\$(settings get global ${BlueLmRussianHook.SETTING_ENABLED})\" = \"1\" ]; then ${enableWhisperRecognitionCommand()}; fi"
    )

    private fun blueLmRestartCommand(): String = BlueLmRussianHook.PACKAGES.joinToString("; ") {
        "am force-stop $it; killall $it 2>/dev/null || true"
    }

    private fun enableWhisperRecognitionCommand(): String =
        "CURRENT=\$(settings get secure voice_recognition_service); " +
            "if [ \"\$(settings get global originroottoolbox_voice_recognition_backup_active)\" != \"1\" ] && [ \"\$CURRENT\" != \"$WHISPER_RECOGNITION_COMPONENT\" ]; then " +
            "[ \"\$CURRENT\" = \"null\" ] && CURRENT=__null__; " +
            "settings put global originroottoolbox_voice_recognition_backup \"\$CURRENT\"; " +
            "settings put global originroottoolbox_voice_recognition_backup_active 1; fi; " +
            "pm grant $MODULE_PACKAGE android.permission.RECORD_AUDIO 2>/dev/null || true; " +
            "settings put secure voice_recognition_service $WHISPER_RECOGNITION_COMPONENT"

    private fun disableWhisperRecognitionCommand(): String =
        "if [ \"\$(settings get global originroottoolbox_voice_recognition_backup_active)\" = \"1\" ]; then " +
            "PREVIOUS=\$(settings get global originroottoolbox_voice_recognition_backup); " +
            "if [ \"\$PREVIOUS\" = \"__null__\" ] || [ \"\$PREVIOUS\" = \"null\" ]; then settings delete secure voice_recognition_service; " +
            "else settings put secure voice_recognition_service \"\$PREVIOUS\"; fi; fi; " +
            "settings delete global originroottoolbox_voice_recognition_backup; " +
            "settings delete global originroottoolbox_voice_recognition_backup_active"

    fun setHideVivoServiceError(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_hide_vivo_service_error $value; SYSTEMUI_PIDS=\$(pidof com.android.systemui); [ -z \"\$SYSTEMUI_PIDS\" ] || kill -9 \$SYSTEMUI_PIDS")
    }

    fun setAospVolumeBar(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_aosp_volume_bar $value; killall com.android.systemui 2>/dev/null || true")
    }

    fun setCenteredNotificationClock(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originroottoolbox_centered_notification_clock $value; am force-stop com.vivo.systemuiplugin; killall com.android.systemui 2>/dev/null || true")
    }

    fun setCenteredClockGlass(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originroottoolbox_centered_clock_glass $value; am force-stop com.vivo.systemuiplugin; killall com.android.systemui 2>/dev/null || true")
    }

    fun setNotificationClockSize(value: Int): RootResult {
        return execute("settings put global originroottoolbox_notification_clock_size ${value.coerceIn(80, 200)}")
    }

    fun setSettingsAdaptiveTheme(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originroottoolbox_settings_adaptive_theme $value; am force-stop com.android.settings; am force-stop com.android.phone; am force-stop com.vivo.systemuiplugin; am force-stop com.iqoo.powersaving; am force-stop com.vivo.gamecube; am force-stop com.bbk.theme; am force-stop com.vivo.pay")
    }

    fun setMaterialOriginOsNotifications(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global ${MaterialOriginOsHook.SETTING_ENABLED} $value; killall com.android.systemui 2>/dev/null || true")
    }

    fun setMaterialOriginOsStatusBar(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global ${MaterialOriginOsHook.SETTING_STATUS_BAR} $value; killall com.android.systemui 2>/dev/null || true")
    }

    fun setMaterialOriginOsGoogleSansFlex(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global ${MaterialOriginOsStatusFontHook.SETTING_ENABLED} $value; am force-stop com.vivo.systemuiplugin; killall com.android.systemui 2>/dev/null || true")
    }

    fun setMaterialOriginOsVolume(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global ${MaterialOriginOsHook.SETTING_VOLUME} $value; settings put global originicons_aosp_volume_bar $value; killall com.android.systemui 2>/dev/null || true")
    }

    fun setMaterialOriginOsLockscreen(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global ${MaterialOriginOsLockscreenHook.SETTING_ENABLED} $value; am force-stop com.vivo.systemuiplugin; am force-stop com.vivo.fingerprintui; am force-stop com.vivo.faceui; killall com.android.systemui 2>/dev/null || true")
    }

    fun setCustomLockscreenClock(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global ${MaterialOriginOsLockscreenHook.SETTING_CUSTOM_CLOCK} $value; am broadcast --user 0 -a ${CustomLockscreenClockHostView.ACTION_CLOCK_STYLE_CHANGED} -f 0x01000000 2>/dev/null || true")
    }

    fun setCustomLockscreenClockStyle(styleId: String): RootResult {
        return execute("settings put global ${CustomLockscreenClockHostView.SETTING_CLOCK_STYLE} $styleId; am broadcast --user 0 -a ${CustomLockscreenClockHostView.ACTION_CLOCK_STYLE_CHANGED} -f 0x01000000 2>/dev/null || true")
    }

    fun setCustomLockscreenClockColor(color: Int): RootResult {
        return execute("settings put global ${CustomLockscreenClockHostView.SETTING_CLOCK_COLOR} $color; am broadcast --user 0 -a ${CustomLockscreenClockHostView.ACTION_CLOCK_STYLE_CHANGED} -f 0x01000000 2>/dev/null || true")
    }

    fun setCustomLockscreenClockGlass(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global ${CustomLockscreenClockHostView.SETTING_CLOCK_GLASS} $value; am broadcast --user 0 -a ${CustomLockscreenClockHostView.ACTION_CLOCK_STYLE_CHANGED} -f 0x01000000 2>/dev/null || true")
    }

    fun setCustomLockscreenClockParam(key: String, value: Any): RootResult {
        return execute("settings put global $key '$value'; am broadcast --user 0 -a ${CustomLockscreenClockHostView.ACTION_CLOCK_STYLE_CHANGED} -f 0x01000000 2>/dev/null || true")
    }

    fun setLiquidVivoParameter(key: String, value: Int): RootResult {
        val known = key.startsWith(LiquidVivoGlassHook.PREFIX) || key.startsWith(KyantGlass.PREFIX)
        if (!known || key.any { !it.isLetterOrDigit() && it != '_' }) {
            return RootResult(false, "Invalid glass setting")
        }
        // WRITE_SECURE_SETTINGS is granted by the Pop Out editor; fall back to root otherwise.
        val direct = runCatching { Settings.Global.putInt(context.contentResolver, key, value) }.getOrDefault(false)
        return if (direct) RootResult(true, "Done") else execute("settings put global $key $value")
    }

    fun setShakePhysics(enabled: Boolean): RootResult =
        execute("settings put global ${ShakePhysicsHook.SETTING_ENABLED} ${if (enabled) 1 else 0}")

    fun setPopOutParameter(key: String, value: String): RootResult {
        val allowed = setOf(
            PopOutButtonsHook.SETTING_ENABLED,
            PopOutButtonsHook.SETTING_DEPTH,
            PopOutButtonsHook.SETTING_VOLUME_UP_Y,
            PopOutButtonsHook.SETTING_VOLUME_DOWN_Y,
            PopOutButtonsHook.SETTING_POWER_Y,
            PopOutButtonsHook.SETTING_LEFT_SIDE
        )
        if (key !in allowed || value.toFloatOrNull() == null) return RootResult(false, "Invalid pop out setting")
        return execute("settings put global $key $value")
    }

    fun setLockscreenQuickToolGlass(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global ${LockscreenQuickToolGlassHook.SETTING_ENABLED} $value; am force-stop com.vivo.systemuiplugin; killall com.android.systemui 2>/dev/null || true")
    }

    fun setSettingsContainerGap(value: Int): RootResult {
        return execute("settings put global originroottoolbox_settings_container_gap ${value.coerceIn(0, 24)}")
    }

    fun setDisablePrivateDnsWithVpn(enabled: Boolean): RootResult {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpnActive = connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        return if (enabled) {
            val applyNow = if (vpnActive) {
                "if [ \"\$(settings get global originroottoolbox_dns_backup_active)\" != \"1\" ]; then DNS_MODE=\$(settings get global private_dns_mode); DNS_SPECIFIER=\$(settings get global private_dns_specifier); [ \"\$DNS_MODE\" = \"null\" ] && DNS_MODE=__null__; [ \"\$DNS_SPECIFIER\" = \"null\" ] && DNS_SPECIFIER=__null__; settings put global originroottoolbox_dns_mode_backup \"\$DNS_MODE\"; settings put global originroottoolbox_dns_specifier_backup \"\$DNS_SPECIFIER\"; settings put global originroottoolbox_dns_backup_active 1; fi; settings put global private_dns_mode off"
            } else ""
            execute("settings put global originicons_disable_private_dns_vpn 1; $applyNow")
        } else {
            execute("settings put global originicons_disable_private_dns_vpn 0; if [ \"\$(settings get global originroottoolbox_dns_backup_active)\" = \"1\" ]; then DNS_MODE=\$(settings get global originroottoolbox_dns_mode_backup); DNS_SPECIFIER=\$(settings get global originroottoolbox_dns_specifier_backup); if [ \"\$DNS_MODE\" = \"__null__\" ] || [ \"\$DNS_MODE\" = \"null\" ]; then settings delete global private_dns_mode; else settings put global private_dns_mode \"\$DNS_MODE\"; fi; if [ \"\$DNS_SPECIFIER\" = \"__null__\" ] || [ \"\$DNS_SPECIFIER\" = \"null\" ]; then settings delete global private_dns_specifier; else settings put global private_dns_specifier \"\$DNS_SPECIFIER\"; fi; fi; settings delete global originroottoolbox_dns_mode_backup; settings delete global originroottoolbox_dns_specifier_backup; settings delete global originroottoolbox_dns_backup_active")
        }
    }

    fun setAospStatusbar(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_aosp_statusbar $value; settings put global originicons_statusbar_wifi_pack 0; settings put global originicons_statusbar_battery_pack 0; settings put global originicons_statusbar_mobile_pack 0; rm -f /data/bbkcore/theme/com.android.systemui; restorecon -RF /data/bbkcore/theme 2>/dev/null || true; killall com.android.systemui 2>/dev/null || true")
    }

    fun setStatusbarWifiPack(pack: Int): RootResult {
        if (pack !in 0..19) return RootResult(false, "Unknown Wi-Fi pack")
        val batteryPack = Settings.Global.getInt(context.contentResolver, "originicons_statusbar_battery_pack", 0)
        val mobilePack = Settings.Global.getInt(context.contentResolver, "originicons_statusbar_mobile_pack", 0)
        return applyStatusbarTheme(pack, batteryPack, mobilePack)
    }

    fun setStatusbarBatteryPack(pack: Int): RootResult {
        if (pack !in 0..3) return RootResult(false, "Unknown battery pack")
        val wifiPack = Settings.Global.getInt(context.contentResolver, "originicons_statusbar_wifi_pack", 0)
        val mobilePack = Settings.Global.getInt(context.contentResolver, "originicons_statusbar_mobile_pack", 0)
        return applyStatusbarTheme(wifiPack, pack, mobilePack)
    }

    fun setStatusbarMobilePack(pack: Int): RootResult {
        if (pack !in 0..2) return RootResult(false, "Unknown mobile signal pack")
        val wifiPack = Settings.Global.getInt(context.contentResolver, "originicons_statusbar_wifi_pack", 0)
        val batteryPack = Settings.Global.getInt(context.contentResolver, "originicons_statusbar_battery_pack", 0)
        return applyStatusbarTheme(wifiPack, batteryPack, pack)
    }

    fun setControlCenterIconPack(pack: Int): RootResult {
        if (pack !in 0..1) return RootResult(false, "Unknown Control Center icon pack")
        if (pack == 0) {
            return execute("settings put global originicons_control_center_icon_pack 0; rm -f /data/bbkcore/theme/com.vivo.systemuiplugin; restorecon -RF /data/bbkcore/theme 2>/dev/null || true; am force-stop com.vivo.systemuiplugin; killall com.android.systemui 2>/dev/null || true")
        }
        return runCatching {
            val theme = controlCenterThemeFile()
            execute("settings put global originicons_control_center_icon_pack 1; cp '${theme.absolutePath}' /data/bbkcore/theme/.originicons_systemuiplugin_new; chmod 644 /data/bbkcore/theme/.originicons_systemuiplugin_new; chown root:root /data/bbkcore/theme/.originicons_systemuiplugin_new; mv -f /data/bbkcore/theme/.originicons_systemuiplugin_new /data/bbkcore/theme/com.vivo.systemuiplugin; restorecon -RF /data/bbkcore/theme 2>/dev/null || true; am force-stop com.vivo.systemuiplugin; killall com.android.systemui 2>/dev/null || true")
        }.getOrElse { RootResult(false, it.message ?: "Unable to prepare Control Center icons") }
    }

    fun setNativeBlurTuner(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global ${NativeBlurHook.SETTING_ENABLED} $value; am force-stop com.vivo.systemuiplugin; killall com.android.systemui 2>/dev/null || true")
    }

    fun setNativeBlurParameter(key: String, value: Int): RootResult {
        val allowed = setOf(
            NativeBlurHook.SETTING_CONTROL_CENTER_BLUR,
            NativeBlurHook.SETTING_NOTIFICATIONS_BLUR,
            NativeBlurHook.SETTING_TILES_BLUR,
            NativeBlurHook.SETTING_TILES_SAT,
            NativeBlurHook.SETTING_SLIDERS_BLUR,
            NativeBlurHook.SETTING_SLIDERS_SAT,
            NativeBlurHook.SETTING_BUTTONS_BLUR,
            NativeBlurHook.SETTING_BUTTONS_SAT,
            NativeBlurHook.SETTING_PLAYER_BLUR,
            NativeBlurHook.SETTING_PLAYER_SAT,
            NativeBlurHook.SETTING_OTHER_BLUR,
            NativeBlurHook.SETTING_OTHER_SAT,
            NativeBlurHook.SETTING_PANEL_BLUR,
            NativeBlurHook.SETTING_PANEL_DARKEN
        )
        if (key !in allowed) return RootResult(false, "Unsupported native blur setting")
        return execute("settings put global $key $value")
    }

    fun setNativeBlurPanelScaleDisabled(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global ${NativeBlurHook.SETTING_DISABLE_PANEL_SCALE} $value; am force-stop com.vivo.systemuiplugin; killall com.android.systemui 2>/dev/null || true")
    }

    fun migrateStatusbarPackIndexes(): RootResult {
        return execute("if [ \"\$(settings get global originroottoolbox_statusbar_pack_order_v2)\" != \"1\" ]; then WIFI=\$(settings get global originicons_statusbar_wifi_pack); BATTERY=\$(settings get global originicons_statusbar_battery_pack); [ \"\$WIFI\" -gt 0 ] 2>/dev/null && settings put global originicons_statusbar_wifi_pack \$((WIFI + 1)); [ \"\$BATTERY\" -gt 0 ] 2>/dev/null && settings put global originicons_statusbar_battery_pack \$((BATTERY + 1)); settings put global originroottoolbox_statusbar_pack_order_v2 1; fi")
    }

    fun setAllowAllMediaPlayers(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        val nativePlayer = if (enabled) "settings put secure vivo_keyguard_widget_music 1; settings put secure vivo_keyguard_widget_music_island 1; settings put secure vivo_widget_music_notification_center_switch 1; settings put system music_island_switch 1;" else ""
        return execute("settings put global originicons_allow_all_media_players $value; $nativePlayer am force-stop com.vivo.musicwidgetmix; am force-stop com.vivo.musicmixcard; killall com.android.systemui 2>/dev/null || true")
    }

    fun setNativeAospThemedIcons(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_themed_launcher_icons $value; settings delete global ${LauncherIconPack.SETTING_PACK}; settings delete global originicons_force_monet_icons; settings put system themed_icon_enabled $value; settings put secure themed_icon_enabled $value; settings put system key_icon_color_follow_system $value; settings put system key_system_color_change \$(date +%s); am force-stop com.bbk.launcher2; $MARK_LAUNCHER_ICONS_DIRTY am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null")
    }

    fun setMonetIconScale(value: Int): RootResult {
        return execute("settings put global originicons_monet_icon_scale ${value.coerceIn(65, 118)}; am force-stop com.bbk.launcher2; $MARK_LAUNCHER_ICONS_DIRTY am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null")
    }

    fun setLiveUpdatesInOriginIsland(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global ${OriginIslandLiveUpdatesHook.SETTING_ENABLED} $value; settings delete global originicons_island_enabled; settings delete global originicons_island_packages; settings delete global originicons_island_all_media; am force-stop com.vivo.systemuiplugin; killall com.android.systemui 2>/dev/null || true")
    }

    fun setAllProgressInOriginIsland(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global ${OriginIslandLiveUpdatesHook.SETTING_ALL_PROGRESS} $value; am force-stop com.vivo.systemuiplugin; killall com.android.systemui 2>/dev/null || true")
    }

    fun setFlashlightInOriginIsland(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global ${OriginIslandLiveUpdatesHook.SETTING_FLASHLIGHT} $value; am force-stop com.vivo.systemuiplugin; killall com.android.systemui 2>/dev/null || true")
    }

    fun setGboardBlurBackground(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global ${GboardBlurHook.SETTING_ENABLED} $value; am force-stop com.google.android.inputmethod.latin")
    }

    private fun scriptFile(name: String, content: String): File {
        val directory = File(context.getExternalFilesDir(null), "root")
        directory.mkdirs()
        return File(directory, name).apply {
            writeText(content)
            setReadable(true, false)
            setExecutable(true, false)
        }
    }

    private fun templateFile(): File {
        val directory = File(context.getExternalFilesDir(null), "root")
        directory.mkdirs()
        val file = File(directory, "origin_theme_template.tgz")
        context.assets.open("origin_theme_template.tgz").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file.setReadable(true, false)
        return file
    }

    private fun applyStatusbarTheme(wifiPack: Int, batteryPack: Int, mobilePack: Int): RootResult {
        if (wifiPack == 0 && batteryPack == 0 && mobilePack == 0) {
            return execute("settings put global originicons_aosp_statusbar 1; settings put global originicons_statusbar_wifi_pack 0; settings put global originicons_statusbar_battery_pack 0; settings put global originicons_statusbar_mobile_pack 0; rm -f /data/bbkcore/theme/com.android.systemui; restorecon -RF /data/bbkcore/theme 2>/dev/null || true; killall com.android.systemui 2>/dev/null || true")
        }
        return runCatching {
            val theme = statusbarThemeFile(wifiPack, batteryPack, mobilePack)
            execute("settings put global originicons_aosp_statusbar 1; settings put global originicons_statusbar_wifi_pack $wifiPack; settings put global originicons_statusbar_battery_pack $batteryPack; settings put global originicons_statusbar_mobile_pack $mobilePack; cp '${theme.absolutePath}' /data/bbkcore/theme/.originicons_systemui_new; chmod 644 /data/bbkcore/theme/.originicons_systemui_new; chown root:root /data/bbkcore/theme/.originicons_systemui_new; mv -f /data/bbkcore/theme/.originicons_systemui_new /data/bbkcore/theme/com.android.systemui; restorecon -RF /data/bbkcore/theme 2>/dev/null || true; killall com.android.systemui 2>/dev/null || true")
        }.getOrElse { RootResult(false, it.message ?: "Unable to prepare status bar icons") }
    }

    private fun statusbarThemeFile(wifiPack: Int, batteryPack: Int, mobilePack: Int): File {
        val directory = File(context.getExternalFilesDir(null), "root")
        directory.mkdirs()
        val themed = File(directory, "originicons_statusbar_${wifiPack}_${batteryPack}_$mobilePack.zip")
        ZipOutputStream(FileOutputStream(themed)).use { output ->
            if (wifiPack > 0) {
                ZipFile(wifiPngFile()).use { images ->
                    for (level in 0..5) {
                        val sourceLevel = minOf(level, 4)
                        val entry = images.getEntry("WIFI$wifiPack/$sourceLevel.png") ?: continue
                        output.putNextEntry(ZipEntry("vivo_wifi_signal_$level.png"))
                        images.getInputStream(entry).copyTo(output)
                        output.closeEntry()
                    }
                }
            }
            if (batteryPack > 0) {
                ZipFile(batteryPngFile()).use { images ->
                    images.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("PACK$batteryPack/") && it.name.endsWith(".png") }.forEach { entry ->
                        output.putNextEntry(ZipEntry(entry.name.removePrefix("PACK$batteryPack/")))
                        images.getInputStream(entry).copyTo(output)
                        output.closeEntry()
                    }
                }
            }
            if (mobilePack > 0) {
                ZipFile(mobilePngFile()).use { images ->
                    images.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("PACK$mobilePack/") && it.name.endsWith(".png") }.forEach { entry ->
                        output.putNextEntry(ZipEntry(entry.name.removePrefix("PACK$mobilePack/")))
                        images.getInputStream(entry).copyTo(output)
                        output.closeEntry()
                    }
                }
            }
        }
        return themed.apply { setReadable(true, false) }
    }

    private fun wifiPngFile(): File {
        val directory = File(context.getExternalFilesDir(null), "root")
        directory.mkdirs()
        val file = File(directory, "statusbar_wifi_packs.zip")
        context.assets.open("statusbar_wifi_packs.zip").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun batteryPngFile(): File {
        val directory = File(context.getExternalFilesDir(null), "root")
        directory.mkdirs()
        val file = File(directory, "statusbar_battery_packs.zip")
        context.assets.open("statusbar_battery_packs.zip").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun mobilePngFile(): File {
        val directory = File(context.getExternalFilesDir(null), "root")
        directory.mkdirs()
        val file = File(directory, "statusbar_mobile_packs.zip")
        context.assets.open("statusbar_mobile_packs.zip").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun controlCenterThemeFile(): File {
        val directory = File(context.getExternalFilesDir(null), "root")
        directory.mkdirs()
        val file = File(directory, "control_center_icons_ios.zip")
        context.assets.open("control_center_icons_ios.zip").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file.apply { setReadable(true, false) }
    }


    private fun execute(command: String): RootResult = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        RootResult(code == 0, output.ifBlank { if (code == 0) "Done" else "Error code $code" })
    }.getOrElse { RootResult(false, it.message ?: "Unable to start su") }

    private fun googleAssistantRestoreCommand(): String {
        val role = "android.app.role.ASSISTANT"
        val google = "com.google.android.googlequicksearchbox"
        val component = "$google/com.google.android.voiceinteraction.GsaVoiceInteractionService"
        return "cmd role clear-role-holders --user 0 $role; settings delete secure assistant; sleep 1; cmd role add-role-holder --user 0 $role $google; settings put secure assistant $component"
    }

    private fun applyScript() = """
set -eu
STAGE="${'$'}1"
TEMPLATE="${'$'}2"
TINT="${'$'}3"
THEME=/data/bbkcore/theme
ICONS=${'$'}THEME/icons
NEW=${'$'}THEME/.icons_originicons_new
[ -d "${'$'}THEME" ]
[ -d "${'$'}STAGE" ]
[ -f "${'$'}TEMPLATE" ]
if [ "${'$'}TINT" = "none" ]; then
  settings delete global originicons_pixel_search_aosp_tint
else
  settings put global originicons_pixel_search_aosp_tint "${'$'}TINT"
fi
rm -f "${'$'}THEME/com.bbk.launcher2"
if [ "${'$'}(settings get global originicons_aosp_statusbar 2>/dev/null || true)" != "1" ]; then
  rm -f "${'$'}THEME/com.android.systemui"
fi
tar -xzf "${'$'}TEMPLATE" -C "${'$'}THEME" --exclude='./com.bbk.launcher2' --exclude='./com.android.systemui'
rm -rf "${'$'}NEW"
mkdir -p "${'$'}NEW"
for SOURCE in "${'$'}STAGE"/*.png; do
  [ -f "${'$'}SOURCE" ] || continue
  NAME=${'$'}{SOURCE##*/}
  cp "${'$'}SOURCE" "${'$'}NEW/${'$'}NAME"
done
[ -n "${'$'}(ls -A "${'$'}NEW")" ]
rm -rf "${'$'}ICONS"
restorecon -RF "${'$'}THEME" 2>/dev/null || true
settings put global theme_icons_style_explore 1
settings put global originicons_themed_launcher_icons 0
settings delete global originicons_live_icon_pack
settings delete global originicons_live_pack_recolor
settings delete global originicons_live_pack_background
settings delete global originicons_force_monet_icons
am force-stop com.bbk.launcher2
am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
sleep 2
settings put global theme_icons_style_explore 2
am force-stop com.bbk.launcher2
am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
sleep 2
settings put global theme_icons_style_explore 1
am force-stop com.bbk.launcher2
am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
sleep 2
mkdir -p "${'$'}ICONS"
for SOURCE in "${'$'}NEW"/*.png; do
  [ -f "${'$'}SOURCE" ] || continue
  cp "${'$'}SOURCE" "${'$'}ICONS/"
done
rm -rf "${'$'}NEW"
chown -R root:root "${'$'}ICONS"
chmod 777 "${'$'}ICONS"
chmod 777 "${'$'}ICONS"/*.png
restorecon -RF "${'$'}ICONS" 2>/dev/null || true
am force-stop com.bbk.launcher2
am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
sleep 2
echo applied
""".trimIndent()

    private companion object {
        const val MODULE_PACKAGE = "dev.unvoid.originceiler"
        const val WHISPER_RECOGNITION_COMPONENT =
            "$MODULE_PACKAGE/dev.unvoid.originceiler.WhisperRecognitionService"

        // Workspace icons are stored as bitmaps in launcher.db; with this flag the launcher
        // re-renders them on its next start. Must run while the launcher is stopped.
        const val MARK_LAUNCHER_ICONS_DIRTY =
            "sed -i 's/name=\"icon_in_db_dirty\" value=\"false\"/name=\"icon_in_db_dirty\" value=\"true\"/' " +
                "/data/user_de/0/com.bbk.launcher2/shared_prefs/com.bbk.launcher2.prefs.xml 2>/dev/null;"
    }

}
