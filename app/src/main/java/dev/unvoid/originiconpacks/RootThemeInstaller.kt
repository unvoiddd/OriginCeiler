package com.autonavi.minimap

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

    fun restartLauncher(): RootResult {
        return execute("am force-stop com.bbk.launcher2; am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null")
    }

    fun resetIcons(): RootResult {
        return execute("rm -rf /data/bbkcore/theme/icons; settings delete global originicons_pixel_search_aosp_tint; restorecon -RF /data/bbkcore/theme 2>/dev/null || true; am force-stop com.bbk.launcher2; am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null")
    }

    fun setCircleToSearch(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_circle_to_search $value; am force-stop com.bbk.launcher2; am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null")
    }

    fun setPixelHomeLayout(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_pixel_home_layout $value; am force-stop com.bbk.launcher2; am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null")
    }

    fun setPixelGoogleIdentity(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_pixel_google_identity $value; am force-stop com.google.android.googlequicksearchbox; am force-stop com.bbk.launcher2; am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null")
    }

    fun setPowerButtonGemini(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_power_button_gemini $value; am force-stop com.vivo.ai.copilot; killall com.vivo.ai.copilot 2>/dev/null || true; am force-stop com.vivo.launchercopilot; killall com.vivo.launchercopilot 2>/dev/null || true")
    }

    fun setHideVivoServiceError(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_hide_vivo_service_error $value; SYSTEMUI_PIDS=\$(pidof com.android.systemui); [ -z \"\$SYSTEMUI_PIDS\" ] || kill -9 \$SYSTEMUI_PIDS")
    }

    fun setAospVolumeBar(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_aosp_volume_bar $value; killall com.android.systemui 2>/dev/null || true")
    }

    fun setAospControlCenter(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_aosp_control_center $value; killall com.android.systemui 2>/dev/null || true")
    }

    fun setSettingsAdaptiveTheme(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originroottoolbox_settings_adaptive_theme $value; am force-stop com.android.settings; am force-stop com.android.phone; am force-stop com.vivo.systemuiplugin; am force-stop com.iqoo.powersaving; am force-stop com.vivo.gamecube; am force-stop com.bbk.theme; am force-stop com.vivo.pay")
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
        if (pack !in 0..18) return RootResult(false, "Unknown Wi-Fi pack")
        val batteryPack = Settings.Global.getInt(context.contentResolver, "originicons_statusbar_battery_pack", 0)
        val mobilePack = Settings.Global.getInt(context.contentResolver, "originicons_statusbar_mobile_pack", 0)
        return applyStatusbarTheme(pack, batteryPack, mobilePack)
    }

    fun setStatusbarBatteryPack(pack: Int): RootResult {
        if (pack !in 0..2) return RootResult(false, "Unknown battery pack")
        val wifiPack = Settings.Global.getInt(context.contentResolver, "originicons_statusbar_wifi_pack", 0)
        val mobilePack = Settings.Global.getInt(context.contentResolver, "originicons_statusbar_mobile_pack", 0)
        return applyStatusbarTheme(wifiPack, pack, mobilePack)
    }

    fun setStatusbarMobilePack(pack: Int): RootResult {
        if (pack !in 0..1) return RootResult(false, "Unknown mobile signal pack")
        val wifiPack = Settings.Global.getInt(context.contentResolver, "originicons_statusbar_wifi_pack", 0)
        val batteryPack = Settings.Global.getInt(context.contentResolver, "originicons_statusbar_battery_pack", 0)
        return applyStatusbarTheme(wifiPack, batteryPack, pack)
    }

    fun migrateStatusbarPackIndexes(): RootResult {
        return execute("if [ \"\$(settings get global originroottoolbox_statusbar_pack_order_v2)\" != \"1\" ]; then WIFI=\$(settings get global originicons_statusbar_wifi_pack); BATTERY=\$(settings get global originicons_statusbar_battery_pack); [ \"\$WIFI\" -gt 0 ] 2>/dev/null && settings put global originicons_statusbar_wifi_pack \$((WIFI + 1)); [ \"\$BATTERY\" -gt 0 ] 2>/dev/null && settings put global originicons_statusbar_battery_pack \$((BATTERY + 1)); settings put global originroottoolbox_statusbar_pack_order_v2 1; fi")
    }

    fun setAllowAllMediaPlayers(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_allow_all_media_players $value; am force-stop com.vivo.musicwidgetmix")
    }

    fun setNativeAospThemedIcons(enabled: Boolean): RootResult {
        val value = if (enabled) 1 else 0
        return execute("settings put global originicons_native_aosp_themed_icons $value; settings put system themed_icon_enabled $value; settings put secure themed_icon_enabled $value; settings put system key_icon_color_follow_system $value; settings put system key_system_color_change $(date +%s); am force-stop com.bbk.launcher2; am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null")
    }

    fun setOriginIsland(
        enabled: Boolean,
        packages: Set<String>,
        keepAfterUnlock: Boolean,
        promotedRows: Boolean,
        captureAllMedia: Boolean,
        captureAllProgress: Boolean
    ): RootResult {
        val component = "com.autonavi.minimap/com.autonavi.minimap.OriginIslandNotificationListener"
        val packageList = packages.sorted().joinToString(",")
        val mediaValue = if (captureAllMedia) 1 else 0
        val progressValue = if (captureAllProgress) 1 else 0
        val command = if (enabled) {
            "settings put global originicons_island_enabled 1; settings put global originicons_island_packages '$packageList'; settings put global originicons_island_all_media $mediaValue; settings put global originicons_island_all_progress $progressValue; pm grant com.autonavi.minimap android.permission.POST_NOTIFICATIONS 2>/dev/null || true; cmd notification allow_listener $component"
        } else {
            "settings put global originicons_island_enabled 0; cmd notification disallow_listener $component 2>/dev/null || true"
        }
        return execute(command)
    }

    fun setOriginIslandOption(key: String, enabled: Boolean): RootResult {
        if (key !in setOf("keep_after_unlock", "promoted_rows")) return RootResult(false, "Unknown setting")
        return RootResult(true, "Done")
    }

    fun setOriginIslandPackages(packages: Set<String>): RootResult {
        val packageList = packages.sorted().joinToString(",")
        return execute("settings put global originicons_island_packages '$packageList'")
    }

    fun setOriginIslandCaptureOption(key: String, enabled: Boolean): RootResult {
        val setting = when (key) {
            "media" -> "originicons_island_all_media"
            "progress" -> "originicons_island_all_progress"
            else -> return RootResult(false, "Unknown capture option")
        }
        val value = if (enabled) 1 else 0
        val component = "com.autonavi.minimap/com.autonavi.minimap.OriginIslandNotificationListener"
        return execute("settings put global $setting $value; cmd notification disallow_listener $component 2>/dev/null || true; cmd notification allow_listener $component")
    }

    fun restartOriginIsland(): RootResult {
        val component = "com.autonavi.minimap/com.autonavi.minimap.OriginIslandNotificationListener"
        return execute("cmd notification disallow_listener $component 2>/dev/null || true; cmd notification allow_listener $component")
    }

    fun openOriginIslandSettings(): RootResult {
        return execute("am start -n com.vivo.systemuiplugin/com.vivo.systemui.statusbar.notification.settings.StatusbarSettingActivity --es ':settings:show_fragment' com.vivo.systemui.statusbar.notification.settings.AtomIslandSettings")
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


    private fun execute(command: String): RootResult = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        RootResult(code == 0, output.ifBlank { if (code == 0) "Done" else "Error code $code" })
    }.getOrElse { RootResult(false, it.message ?: "Unable to start su") }

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
settings put global originicons_native_aosp_themed_icons 0
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

}
