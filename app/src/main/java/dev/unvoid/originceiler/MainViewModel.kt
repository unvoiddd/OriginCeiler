package dev.unvoid.originceiler

import android.app.Application
import android.graphics.Color
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val moduleActive: Boolean? = null,
    val packs: List<IconPack> = emptyList(),
    val source: IconSource = IconSource.SYSTEM,
    val selected: IconPack? = null,
    val coverage: Coverage? = null,
    val loading: Boolean = true,
    val applying: Boolean = false,
    val progress: Float = 0f,
    val status: String = "Finding icon packs…",
    val success: Boolean? = null,
    val fullConversion: Boolean = false,
    val autoRecolor: Boolean = true,
    val paletteHue: Float = 258f,
    val paletteTone: Float = 0.62f,
    val aospTint: Int? = 0xFFA5C8E8.toInt(),
    val aospFilter: Boolean = true,
    val aospInverted: Boolean = false,
    val aospScale: Float = 1f,
    val circleToSearch: Boolean = false,
    val pixelHomeLayout: Boolean = false,
    val nativeAospThemedIcons: Boolean = false,
    val monetIconScale: Float = 100f,
    val launcherIconSize: Float = 100f,
    val restoreGoogleAssistant: Boolean = false,
    val bypassGeminiRegionalRestrictions: Boolean = false,
    val removePackageInstallerAfterReboot: Boolean = false,
    val powerButtonGemini: Boolean = false,
    val hideVivoServiceError: Boolean = false,
    val aospVolumeBar: Boolean = false,
    val centeredNotificationClock: Boolean = false,
    val centeredClockGlass: Boolean = true,
    val notificationClockSize: Float = 128f,
    val disablePrivateDnsWithVpn: Boolean = false,
    val settingsAdaptiveTheme: Boolean = false,
    val settingsContainerGap: Float = 8f,
    val materialOriginOsNotifications: Boolean = false,
    val materialOriginOsStatusBar: Boolean = false,
    val materialOriginOsGoogleSansFlex: Boolean = false,
    val materialOriginOsVolume: Boolean = false,
    val materialOriginOsLockscreen: Boolean = false,
    val materialBackGesture: Boolean = false,
    val aospStatusbar: Boolean = false,
    val statusbarWifiPack: Int = 0,
    val statusbarBatteryPack: Int = 0,
    val statusbarMobilePack: Int = 0,
    val controlCenterIconPack: Int = 0,
    val liquidGlassControlCenter: Boolean = false,
    val liquidGlassBlur: Float = 100f,
    val liquidGlassRefraction: Float = 100f,
    val liquidGlassDispersion: Float = 100f,
    val liquidGlassSaturation: Float = 120f,
    val liquidGlassTint: Float = 100f,
    val liquidGlassEdge: Float = 100f,
    val liquidGlassQuality: Float = 35f,
    val liquidGlassCornerRadius: Float = 0f,
    val allowAllMediaPlayers: Boolean = false,
    val liveUpdatesInOriginIsland: Boolean = false,
    val allProgressInOriginIsland: Boolean = false,
    val flashlightInOriginIsland: Boolean = false,
    val gboardBlurBackground: Boolean = false,
    val appThemeMode: Int = 1,
    val monetColors: Boolean = false,
    val uiScale: Float = 100f,
    val liquidGlassBar: Boolean = false,
    val autoHideBar: Boolean = true,
    val showIntro: Boolean = true
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = IconPackEngine(application)
    private val installer = RootThemeInstaller(application)
    private val preferences = application.getSharedPreferences("settings", 0)
    private var launcherIconSizeJob: Job? = null
    private var notificationClockSizeJob: Job? = null
    private val liquidGlassSettingJobs = mutableMapOf<String, Job>()
    private val mutableState = MutableStateFlow(
        UiState(
            fullConversion = preferences.getBoolean("full_conversion", false),
            autoRecolor = preferences.getBoolean("auto_recolor", true),
            paletteHue = preferences.getFloat("palette_hue", 258f),
            paletteTone = preferences.getFloat("palette_tone", 0.62f),
            aospTint = if (preferences.getBoolean("aosp_filter", true)) preferences.getInt("aosp_tint", 0xFFA5C8E8.toInt()) else null,
            aospFilter = preferences.getBoolean("aosp_filter", true),
            aospInverted = preferences.getBoolean("aosp_inverted", false),
            aospScale = preferences.getFloat("aosp_scale", 1f),
            circleToSearch = Settings.Global.getInt(application.contentResolver, "originicons_circle_to_search", 0) == 1,
            pixelHomeLayout = Settings.Global.getInt(application.contentResolver, "originicons_pixel_home_layout", 0) == 1,
            nativeAospThemedIcons = Settings.Global.getInt(application.contentResolver, "originicons_themed_launcher_icons", 0) == 1,
            monetIconScale = Settings.Global.getInt(application.contentResolver, "originicons_monet_icon_scale", 100).toFloat(),
            launcherIconSize = Settings.Global.getInt(application.contentResolver, "originroottoolbox_launcher_icon_size", 100).toFloat(),
            restoreGoogleAssistant = Settings.Global.getInt(application.contentResolver, "originroottoolbox_restore_google_assistant", 0) == 1,
            bypassGeminiRegionalRestrictions = Settings.Global.getInt(application.contentResolver, "originroottoolbox_bypass_gemini_region", 0) == 1,
            removePackageInstallerAfterReboot = Settings.Global.getInt(application.contentResolver, "originroottoolbox_remove_package_installer_boot", 0) == 1,
            powerButtonGemini = Settings.Global.getInt(application.contentResolver, "originicons_power_button_gemini", 0) == 1,
            hideVivoServiceError = Settings.Global.getInt(application.contentResolver, "originicons_hide_vivo_service_error", 0) == 1,
            aospVolumeBar = Settings.Global.getInt(application.contentResolver, "originicons_aosp_volume_bar", 0) == 1,
            centeredNotificationClock = Settings.Global.getInt(
                application.contentResolver,
                "originroottoolbox_centered_notification_clock",
                Settings.Global.getInt(application.contentResolver, "originroottoolbox_ios_notification_center", 0)
            ) == 1,
            centeredClockGlass = Settings.Global.getInt(application.contentResolver, "originroottoolbox_centered_clock_glass", 1) == 1,
            notificationClockSize = Settings.Global.getInt(application.contentResolver, "originroottoolbox_notification_clock_size", 128).toFloat(),
            disablePrivateDnsWithVpn = Settings.Global.getInt(application.contentResolver, "originicons_disable_private_dns_vpn", 0) == 1,
            settingsAdaptiveTheme = Settings.Global.getInt(application.contentResolver, "originroottoolbox_settings_adaptive_theme", 0) == 1,
            settingsContainerGap = Settings.Global.getInt(application.contentResolver, "originroottoolbox_settings_container_gap", 8).toFloat(),
            materialOriginOsNotifications = Settings.Global.getInt(application.contentResolver, MaterialOriginOsHook.SETTING_ENABLED, 0) == 1,
            materialOriginOsStatusBar = Settings.Global.getInt(application.contentResolver, MaterialOriginOsHook.SETTING_STATUS_BAR, 0) == 1,
            materialOriginOsGoogleSansFlex = Settings.Global.getInt(application.contentResolver, MaterialOriginOsStatusFontHook.SETTING_ENABLED, 0) == 1,
            materialOriginOsVolume = Settings.Global.getInt(application.contentResolver, MaterialOriginOsHook.SETTING_VOLUME, 0) == 1,
            materialOriginOsLockscreen = Settings.Global.getInt(application.contentResolver, MaterialOriginOsLockscreenHook.SETTING_ENABLED, 0) == 1,
            materialBackGesture = Settings.Global.getInt(application.contentResolver, MaterialBackGestureHook.SETTING_ENABLED, 0) == 1,
            aospStatusbar = Settings.Global.getInt(application.contentResolver, "originicons_aosp_statusbar", 0) == 1,
            statusbarWifiPack = Settings.Global.getInt(application.contentResolver, "originicons_statusbar_wifi_pack", 0),
            statusbarBatteryPack = Settings.Global.getInt(application.contentResolver, "originicons_statusbar_battery_pack", 0),
            statusbarMobilePack = Settings.Global.getInt(application.contentResolver, "originicons_statusbar_mobile_pack", 0),
            controlCenterIconPack = Settings.Global.getInt(application.contentResolver, "originicons_control_center_icon_pack", 0),
            liquidGlassControlCenter = Settings.Global.getInt(application.contentResolver, ShadeLiquidGlassHook.SETTING_CONTROL_CENTER, 0) == 1,
            liquidGlassBlur = Settings.Global.getInt(application.contentResolver, ShadeLiquidGlassHook.SETTING_BLUR, 100).toFloat(),
            liquidGlassRefraction = Settings.Global.getInt(application.contentResolver, ShadeLiquidGlassHook.SETTING_REFRACTION, 100).toFloat(),
            liquidGlassDispersion = Settings.Global.getInt(application.contentResolver, ShadeLiquidGlassHook.SETTING_DISPERSION, 100).toFloat(),
            liquidGlassSaturation = Settings.Global.getInt(application.contentResolver, ShadeLiquidGlassHook.SETTING_SATURATION, 120).toFloat(),
            liquidGlassTint = Settings.Global.getInt(application.contentResolver, ShadeLiquidGlassHook.SETTING_TINT, 100).toFloat(),
            liquidGlassEdge = Settings.Global.getInt(application.contentResolver, ShadeLiquidGlassHook.SETTING_EDGE, 100).toFloat(),
            liquidGlassQuality = Settings.Global.getInt(application.contentResolver, ShadeLiquidGlassHook.SETTING_QUALITY, 35).toFloat(),
            liquidGlassCornerRadius = Settings.Global.getInt(application.contentResolver, ShadeLiquidGlassHook.SETTING_CC_RADIUS, 0).toFloat(),
            allowAllMediaPlayers = Settings.Global.getInt(application.contentResolver, "originicons_allow_all_media_players", 0) == 1,
            liveUpdatesInOriginIsland = Settings.Global.getInt(application.contentResolver, OriginIslandLiveUpdatesHook.SETTING_ENABLED, 0) == 1,
            allProgressInOriginIsland = Settings.Global.getInt(application.contentResolver, OriginIslandLiveUpdatesHook.SETTING_ALL_PROGRESS, 0) == 1,
            flashlightInOriginIsland = Settings.Global.getInt(application.contentResolver, OriginIslandLiveUpdatesHook.SETTING_FLASHLIGHT, 0) == 1,
            gboardBlurBackground = Settings.Global.getInt(application.contentResolver, GboardBlurHook.SETTING_ENABLED, 0) == 1,
            appThemeMode = preferences.getInt("app_theme_mode", 1),
            monetColors = preferences.getBoolean("monet_colors", false),
            uiScale = preferences.getFloat("ui_scale", 100f),
            liquidGlassBar = preferences.getBoolean("liquid_glass_bar", false),
            autoHideBar = preferences.getBoolean("auto_hide_bar", true),
            showIntro = !preferences.getBoolean("intro_seen", false)
        )
    )
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val moduleActive = withContext(Dispatchers.IO) { checkModuleActive() }
            mutableState.value = mutableState.value.copy(moduleActive = moduleActive)
            val result = withContext(Dispatchers.IO) { installer.migrateStatusbarPackIndexes() }
            if (result.success) {
                mutableState.value = mutableState.value.copy(
                    statusbarWifiPack = Settings.Global.getInt(getApplication<Application>().contentResolver, "originicons_statusbar_wifi_pack", 0),
                    statusbarBatteryPack = Settings.Global.getInt(getApplication<Application>().contentResolver, "originicons_statusbar_battery_pack", 0)
                )
            }
            reload()
        }
    }

    private fun checkModuleActive(): Boolean {
        val script = "LSPD_PID=\$(pidof lspd | awk '{print \$1}'); [ -n \"\$LSPD_PID\" ] || exit 1; DB=/proc/\$LSPD_PID/root/data/adb/lspd/config/modules_config.db; [ -f \"\$DB\" ] || exit 1; sqlite3 \"\$DB\" \"SELECT enabled FROM modules_state WHERE module_pkg_name='dev.unvoid.originceiler' LIMIT 1;\""
        return runCatching {
            val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor() == 0 && output.lineSequence().lastOrNull()?.trim() == "1"
        }.getOrDefault(false)
    }

    fun reload() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, status = "Finding icon packs…", success = null)
            val packs = withContext(Dispatchers.IO) { engine.findPacks() }
            mutableState.value = mutableState.value.copy(
                packs = packs,
                source = IconSource.SYSTEM,
                selected = null,
                coverage = null,
                loading = false,
                status = if (packs.isEmpty()) "System and custom palette are ready" else "Ready"
            )
        }
    }

    fun selectSystem() {
        mutableState.value = mutableState.value.copy(
            source = IconSource.SYSTEM,
            selected = null,
            coverage = null,
            loading = false,
            status = "Restore system icons",
            success = null
        )
    }

    fun setExtendedStatusbar(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying extended status bar…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setAospStatusbar(enabled) }
            mutableState.value = mutableState.value.copy(
                applying = false,
                aospStatusbar = if (result.success) enabled else mutableState.value.aospStatusbar,
                statusbarWifiPack = if (result.success) 0 else mutableState.value.statusbarWifiPack,
                statusbarBatteryPack = if (result.success) 0 else mutableState.value.statusbarBatteryPack,
                statusbarMobilePack = if (result.success) 0 else mutableState.value.statusbarMobilePack,
                status = if (result.success) if (enabled) "Extended status bar applied" else "Status bar restored" else "Root operation failed",
                success = result.success
            )
        }
    }

    fun setStatusbarWifiPack(pack: Int) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying Wi-Fi icon pack…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setStatusbarWifiPack(pack) }
            mutableState.value = mutableState.value.copy(applying = false, statusbarWifiPack = if (result.success) pack else mutableState.value.statusbarWifiPack, status = if (result.success) "Wi-Fi icon pack applied" else "Root operation failed", success = result.success)
        }
    }

    fun setStatusbarBatteryPack(pack: Int) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying battery icon pack…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setStatusbarBatteryPack(pack) }
            mutableState.value = mutableState.value.copy(applying = false, statusbarBatteryPack = if (result.success) pack else mutableState.value.statusbarBatteryPack, status = if (result.success) "Battery icon pack applied" else "Root operation failed", success = result.success)
        }
    }

    fun setStatusbarMobilePack(pack: Int) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying mobile signal icon pack…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setStatusbarMobilePack(pack) }
            mutableState.value = mutableState.value.copy(applying = false, statusbarMobilePack = if (result.success) pack else mutableState.value.statusbarMobilePack, status = if (result.success) "Mobile signal icon pack applied" else "Root operation failed", success = result.success)
        }
    }

    fun setControlCenterIconPack(pack: Int) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying Control Center icons…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setControlCenterIconPack(pack) }
            mutableState.value = mutableState.value.copy(
                applying = false,
                controlCenterIconPack = if (result.success) pack else mutableState.value.controlCenterIconPack,
                status = if (result.success) "Control Center icons applied" else "Root operation failed",
                success = result.success
            )
        }
    }

    fun setLiquidGlassControlCenter(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying Liquid Glass Control Center…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setLiquidGlassControlCenter(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(liquidGlassControlCenter = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Liquid Glass Control Center applied" else "Unable to apply Liquid Glass Control Center",
                success = result.success
            )
        }
    }

    fun setLiquidGlassBlur(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(liquidGlassBlur = adjusted)
        persistLiquidGlassSetting(ShadeLiquidGlassHook.SETTING_BLUR, adjusted.toInt())
    }

    fun setLiquidGlassRefraction(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(liquidGlassRefraction = adjusted)
        persistLiquidGlassSetting(ShadeLiquidGlassHook.SETTING_REFRACTION, adjusted.toInt())
    }

    fun setLiquidGlassDispersion(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(liquidGlassDispersion = adjusted)
        persistLiquidGlassSetting(ShadeLiquidGlassHook.SETTING_DISPERSION, adjusted.toInt())
    }

    fun setLiquidGlassSaturation(value: Float) {
        val adjusted = value.coerceIn(50f, 180f)
        mutableState.value = mutableState.value.copy(liquidGlassSaturation = adjusted)
        persistLiquidGlassSetting(ShadeLiquidGlassHook.SETTING_SATURATION, adjusted.toInt())
    }

    fun setLiquidGlassTint(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(liquidGlassTint = adjusted)
        persistLiquidGlassSetting(ShadeLiquidGlassHook.SETTING_TINT, adjusted.toInt())
    }

    fun setLiquidGlassEdge(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(liquidGlassEdge = adjusted)
        persistLiquidGlassSetting(ShadeLiquidGlassHook.SETTING_EDGE, adjusted.toInt())
    }

    fun setLiquidGlassCornerRadius(value: Float) {
        val adjusted = value.coerceIn(0f, 100f)
        mutableState.value = mutableState.value.copy(liquidGlassCornerRadius = adjusted)
        persistLiquidGlassSetting(ShadeLiquidGlassHook.SETTING_CC_RADIUS, adjusted.toInt())
    }

    fun setLiquidGlassQuality(value: Float) {
        val adjusted = value.coerceIn(20f, 50f)
        mutableState.value = mutableState.value.copy(liquidGlassQuality = adjusted)
        persistLiquidGlassSetting(ShadeLiquidGlassHook.SETTING_QUALITY, adjusted.toInt())
    }

    private fun persistLiquidGlassSetting(key: String, value: Int) {
        liquidGlassSettingJobs.remove(key)?.cancel()
        liquidGlassSettingJobs[key] = viewModelScope.launch {
            delay(90)
            withContext(Dispatchers.IO) { installer.setLiquidGlassParameter(key, value) }
            liquidGlassSettingJobs.remove(key)
        }
    }

    fun selectPalette() {
        mutableState.value = mutableState.value.copy(source = IconSource.PALETTE, selected = null, loading = true, success = null)
        refreshPalettePreview()
    }

    fun selectAospThemed() {
        mutableState.value = mutableState.value.copy(
            source = IconSource.AOSP_THEMED,
            selected = null,
            loading = true,
            success = null,
            status = "Generating AOSP themed previewвЂ¦"
        )
        viewModelScope.launch {
            val snapshot = mutableState.value
            val coverage = withContext(Dispatchers.IO) { engine.aospThemedCoverage(snapshot.aospTint, snapshot.aospInverted, snapshot.aospScale) }
            if (mutableState.value.source == IconSource.AOSP_THEMED) {
                mutableState.value = mutableState.value.copy(
                    coverage = coverage,
                    loading = false,
                    status = "AOSP themed icons ready"
                )
            }
        }
    }

    fun select(pack: IconPack) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                source = IconSource.PACK,
                selected = pack,
                coverage = null,
                loading = true,
                status = "Scanning ${pack.label}…",
                success = null
            )
            runCatching { withContext(Dispatchers.IO) { engine.coverage(pack, mutableState.value.fullConversion) } }
                .onSuccess { coverage ->
                    mutableState.value = mutableState.value.copy(coverage = coverage, loading = false, status = "Ready to apply")
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(loading = false, status = "Unable to scan icon pack", success = false)
                }
        }
    }

    fun setPaletteHue(hue: Float) {
        val value = hue.coerceIn(0f, 360f)
        preferences.edit().putFloat("palette_hue", value).apply()
        mutableState.value = mutableState.value.copy(paletteHue = value)
        refreshPalettePreview()
    }

    fun setPaletteTone(tone: Float) {
        val value = tone.coerceIn(0f, 1f)
        preferences.edit().putFloat("palette_tone", value).apply()
        mutableState.value = mutableState.value.copy(paletteTone = value)
        refreshPalettePreview()
    }

    fun setAospTint(tint: Int?) {
        preferences.edit().putBoolean("aosp_filter", tint != null).apply()
        if (tint != null) preferences.edit().putInt("aosp_tint", tint).apply()
        mutableState.value = mutableState.value.copy(aospTint = tint, aospFilter = tint != null)
        refreshAospPreview()
    }

    fun setAospInverted(enabled: Boolean) {
        preferences.edit().putBoolean("aosp_inverted", enabled).apply()
        mutableState.value = mutableState.value.copy(aospInverted = enabled)
        refreshAospPreview()
    }

    fun setAospScale(scale: Float) {
        val value = scale.coerceIn(0.65f, 1.18f)
        preferences.edit().putFloat("aosp_scale", value).apply()
        mutableState.value = mutableState.value.copy(aospScale = value)
        refreshAospPreview()
    }

    fun setFullConversion(enabled: Boolean) {
        preferences.edit().putBoolean("full_conversion", enabled).apply()
        mutableState.value = mutableState.value.copy(fullConversion = enabled)
        mutableState.value.selected?.let(::select)
    }

    fun setAutoRecolor(enabled: Boolean) {
        preferences.edit().putBoolean("auto_recolor", enabled).apply()
        mutableState.value = mutableState.value.copy(autoRecolor = enabled)
    }

    fun setCircleToSearch(enabled: Boolean) {
        val application = getApplication<Application>()
        if (enabled && !BundledAppInstaller.isInstalled(application, "com.parallelc.micts")) {
            runCatching { BundledAppInstaller.requestInstall(application, "micts.apk", "MiCTS.apk") }
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setCircleToSearch(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(circleToSearch = enabled)
        }
    }

    fun setPixelHomeLayout(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setPixelHomeLayout(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(pixelHomeLayout = enabled)
        }
    }

    fun setNativeAospThemedIcons(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setNativeAospThemedIcons(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(nativeAospThemedIcons = enabled)
        }
    }

    fun setMonetIconScale(value: Float) {
        val scale = value.toInt().coerceIn(65, 118)
        mutableState.value = mutableState.value.copy(monetIconScale = scale.toFloat())
        viewModelScope.launch {
            withContext(Dispatchers.IO) { installer.setMonetIconScale(scale) }
        }
    }

    fun setLauncherIconSize(value: Float) {
        val size = value.toInt().coerceIn(70, 150)
        mutableState.value = mutableState.value.copy(launcherIconSize = size.toFloat())
        launcherIconSizeJob?.cancel()
        launcherIconSizeJob = viewModelScope.launch {
            delay(70)
            withContext(Dispatchers.IO) { installer.setLauncherIconSize(size) }
        }
    }

    fun setRestoreGoogleAssistant(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setRestoreGoogleAssistant(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(restoreGoogleAssistant = enabled)
        }
    }

    fun setBypassGeminiRegionalRestrictions(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setBypassGeminiRegionalRestrictions(enabled) }
            if (result.success) {
                mutableState.value = mutableState.value.copy(
                    bypassGeminiRegionalRestrictions = enabled,
                    disablePrivateDnsWithVpn = if (enabled) false else mutableState.value.disablePrivateDnsWithVpn
                )
            }
        }
    }

    fun setRemovePackageInstallerAfterReboot(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setRemovePackageInstallerAfterReboot(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(removePackageInstallerAfterReboot = enabled)
        }
    }

    fun setPowerButtonGemini(enabled: Boolean) {
        val application = getApplication<Application>()
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying Power Button Gemini trigger…", success = null)
            val triggerReady = !enabled || BundledAppInstaller.isInstalled(application, "com.parallelc.vistrigger") ||
                withContext(Dispatchers.IO) { BundledAppInstaller.installWithRoot(application, "vis_trigger.apk", "VISTrigger-2.6.apk") }
            if (!triggerReady) {
                mutableState.value = mutableState.value.copy(applying = false, status = "Unable to install VISTrigger with root", success = false)
                return@launch
            }
            val result = withContext(Dispatchers.IO) { installer.setPowerButtonGemini(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(powerButtonGemini = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Power Button Gemini trigger applied" else "Unable to apply Power Button Gemini trigger",
                success = result.success
            )
        }
    }

    fun setHideVivoServiceError(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setHideVivoServiceError(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(hideVivoServiceError = enabled)
        }
    }

    fun setAospVolumeBar(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setAospVolumeBar(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(aospVolumeBar = enabled)
        }
    }

    fun setCenteredNotificationClock(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setCenteredNotificationClock(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(centeredNotificationClock = enabled)
        }
    }

    fun setCenteredClockGlass(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setCenteredClockGlass(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(centeredClockGlass = enabled)
        }
    }

    fun setNotificationClockSize(value: Float) {
        val size = value.toInt().coerceIn(80, 200)
        mutableState.value = mutableState.value.copy(notificationClockSize = size.toFloat())
        notificationClockSizeJob?.cancel()
        notificationClockSizeJob = viewModelScope.launch {
            delay(70)
            withContext(Dispatchers.IO) { installer.setNotificationClockSize(size) }
        }
    }

    fun setDisablePrivateDnsWithVpn(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setDisablePrivateDnsWithVpn(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(disablePrivateDnsWithVpn = enabled)
        }
    }

    fun setSettingsAdaptiveTheme(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setSettingsAdaptiveTheme(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(settingsAdaptiveTheme = enabled)
        }
    }

    fun setSettingsContainerGap(value: Float) {
        val gap = value.toInt().coerceIn(0, 24)
        mutableState.value = mutableState.value.copy(settingsContainerGap = gap.toFloat())
        viewModelScope.launch {
            withContext(Dispatchers.IO) { installer.setSettingsContainerGap(gap) }
        }
    }

    fun setMaterialOriginOsNotifications(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying Material OriginOS notifications…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setMaterialOriginOsNotifications(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(materialOriginOsNotifications = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Material OriginOS notifications applied" else "Unable to apply Material OriginOS notifications",
                success = result.success
            )
        }
    }

    fun setMaterialOriginOsStatusBar(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying Material OriginOS status bar…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setMaterialOriginOsStatusBar(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(materialOriginOsStatusBar = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Material OriginOS status bar applied" else "Unable to apply Material OriginOS status bar",
                success = result.success
            )
        }
    }

    fun setMaterialOriginOsGoogleSansFlex(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying Google Sans Flex Rounded…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setMaterialOriginOsGoogleSansFlex(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(materialOriginOsGoogleSansFlex = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Google Sans Flex Rounded applied" else "Unable to apply Google Sans Flex Rounded",
                success = result.success
            )
        }
    }

    fun setMaterialOriginOsVolume(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying Material OriginOS volume panel…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setMaterialOriginOsVolume(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(materialOriginOsVolume = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Material OriginOS volume panel applied" else "Unable to apply Material OriginOS volume panel",
                success = result.success
            )
        }
    }

    fun setMaterialOriginOsLockscreen(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying Material OriginOS lockscreen…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setMaterialOriginOsLockscreen(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(materialOriginOsLockscreen = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Material OriginOS lockscreen applied" else "Unable to apply Material OriginOS lockscreen",
                success = result.success
            )
        }
    }

    fun setMaterialBackGesture(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying Material back gesture…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setMaterialBackGesture(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(materialBackGesture = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Material back gesture applied" else "Unable to apply Material back gesture",
                success = result.success
            )
        }
    }

    fun setAllowAllMediaPlayers(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setAllowAllMediaPlayers(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(allowAllMediaPlayers = enabled)
        }
    }

    fun setLiveUpdatesInOriginIsland(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying Live Updates bridge…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setLiveUpdatesInOriginIsland(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(liveUpdatesInOriginIsland = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Live Updates bridge applied" else "Unable to apply Live Updates bridge",
                success = result.success
            )
        }
    }

    fun setAllProgressInOriginIsland(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying progress notifications bridge…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setAllProgressInOriginIsland(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(allProgressInOriginIsland = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Progress notifications bridge applied" else "Unable to apply progress notifications bridge",
                success = result.success
            )
        }
    }

    fun setFlashlightInOriginIsland(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying flashlight island control…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setFlashlightInOriginIsland(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(flashlightInOriginIsland = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Flashlight island control applied" else "Unable to apply flashlight island control",
                success = result.success
            )
        }
    }

    fun setGboardBlurBackground(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying Gboard blur…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setGboardBlurBackground(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(gboardBlurBackground = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Gboard blur applied" else "Unable to apply Gboard blur",
                success = result.success
            )
        }
    }

    fun acceptIntro() {
        preferences.edit().putBoolean("intro_seen", true).apply()
        mutableState.value = mutableState.value.copy(showIntro = false)
        requestRoot()
    }

    fun setAppThemeMode(value: Int) {
        val mode = value.coerceIn(0, 2)
        preferences.edit().putInt("app_theme_mode", mode).apply()
        mutableState.value = mutableState.value.copy(appThemeMode = mode)
    }

    fun setMonetColors(enabled: Boolean) {
        preferences.edit().putBoolean("monet_colors", enabled).apply()
        mutableState.value = mutableState.value.copy(monetColors = enabled)
    }

    fun setUiScale(value: Float) {
        val scale = value.coerceIn(80f, 125f)
        preferences.edit().putFloat("ui_scale", scale).apply()
        mutableState.value = mutableState.value.copy(uiScale = scale)
    }

    fun setLiquidGlassBar(enabled: Boolean) {
        preferences.edit().putBoolean("liquid_glass_bar", enabled).apply()
        mutableState.value = mutableState.value.copy(liquidGlassBar = enabled)
    }

    fun requestRoot() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(status = "Requesting root access…")
            val result = withContext(Dispatchers.IO) { installer.check() }
            val moduleActive = if (result.success) withContext(Dispatchers.IO) { checkModuleActive() } else false
            mutableState.value = mutableState.value.copy(
                status = if (result.success) "Root access granted" else "Root access denied",
                success = result.success,
                moduleActive = moduleActive
            )
        }
    }

    fun apply() {
        viewModelScope.launch {
            val snapshot = mutableState.value
            mutableState.value = snapshot.copy(applying = true, progress = 0f, status = "Preparing icons…", success = null)
            if (snapshot.source == IconSource.SYSTEM) {
                val root = withContext(Dispatchers.IO) { installer.resetIcons() }
                mutableState.value = mutableState.value.copy(
                    applying = false,
                    progress = 1f,
                    status = if (root.success) "System icons restored" else "Root operation failed",
                    success = root.success
                )
                return@launch
            }
            val conversion = runCatching {
                withContext(Dispatchers.IO) {
                    when (snapshot.source) {
                        IconSource.PALETTE -> engine.convertPalette(paletteColor(snapshot.paletteHue), snapshot.paletteTone, ::updateProgress)
                        IconSource.AOSP_THEMED -> engine.convertAospThemed(snapshot.aospTint, snapshot.aospInverted, snapshot.aospScale, ::updateProgress)
                        IconSource.PACK -> engine.convert(snapshot.selected ?: error("No icon pack selected"), snapshot.fullConversion, snapshot.autoRecolor, ::updateProgress)
                        IconSource.SYSTEM -> error("Invalid source")
                    }
                }
            }
            if (conversion.isFailure) {
                mutableState.value = mutableState.value.copy(applying = false, status = "Conversion failed", success = false)
                return@launch
            }
            mutableState.value = mutableState.value.copy(status = "Applying icons…", progress = 1f)
            val pixelSearchTint = if (snapshot.source == IconSource.AOSP_THEMED) snapshot.aospTint else null
            val root = withContext(Dispatchers.IO) { installer.apply(conversion.getOrThrow(), pixelSearchTint) }
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (root.success) "Icons applied" else "Root operation failed",
                success = root.success
            )
        }
    }

    fun restartLauncher() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Restarting Launcher…", success = null)
            val result = withContext(Dispatchers.IO) { installer.restartLauncher() }
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Launcher restarted" else "Restart failed",
                success = result.success
            )
        }
    }

    fun restartScope(packageName: String, label: String) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Restarting $label...", success = null)
            val result = withContext(Dispatchers.IO) { installer.restartScope(packageName) }
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "$label restarted" else "Restart failed",
                success = result.success
            )
        }
    }

    private fun refreshPalettePreview() {
        if (mutableState.value.source != IconSource.PALETTE) return
        val hue = mutableState.value.paletteHue
        val tone = mutableState.value.paletteTone
        viewModelScope.launch {
            val coverage = withContext(Dispatchers.IO) { engine.paletteCoverage(paletteColor(hue), tone) }
            if (mutableState.value.source == IconSource.PALETTE && mutableState.value.paletteHue == hue && mutableState.value.paletteTone == tone) {
                mutableState.value = mutableState.value.copy(coverage = coverage, loading = false, status = "Custom palette ready")
            }
        }
    }

    private fun refreshAospPreview() {
        if (mutableState.value.source != IconSource.AOSP_THEMED) return
        val tint = mutableState.value.aospTint
        val inverted = mutableState.value.aospInverted
        val scale = mutableState.value.aospScale
        mutableState.value = mutableState.value.copy(loading = true)
        viewModelScope.launch {
            val coverage = withContext(Dispatchers.IO) { engine.aospThemedCoverage(tint, inverted, scale) }
            if (mutableState.value.source == IconSource.AOSP_THEMED && mutableState.value.aospTint == tint && mutableState.value.aospInverted == inverted && mutableState.value.aospScale == scale) {
                mutableState.value = mutableState.value.copy(coverage = coverage, loading = false, status = "AOSP themed icons ready")
            }
        }
    }

    private fun updateProgress(current: Int, total: Int) {
        mutableState.value = mutableState.value.copy(progress = if (total == 0) 0f else current.toFloat() / total)
    }

    private fun paletteColor(hue: Float): Int = Color.HSVToColor(floatArrayOf(hue, 0.92f, 1f))

}
