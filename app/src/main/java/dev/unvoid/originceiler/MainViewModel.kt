package dev.unvoid.originceiler

import android.app.Application
import android.graphics.Color
import android.provider.Settings
import android.widget.Toast
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
    val replaceIconBackground: Boolean = false,
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
    val launcherCustomGridEnabled: Boolean = false,
    val launcherGridColumns: Int = 4,
    val launcherGridRows: Int = 7,
    val launcherOriginalColumns: Int = 4,
    val launcherOriginalRows: Int = 7,
    val restoreGoogleAssistant: Boolean = false,
    val bypassGeminiRegionalRestrictions: Boolean = false,
    val removePackageInstallerAfterReboot: Boolean = false,
    val powerButtonGemini: Boolean = false,
    val blueLmRussian: Boolean = false,
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
    val customLockscreenClock: Boolean = false,
    val customLockscreenClockStyle: String = "s1-4x2",
    val customLockscreenClockColor: Int = 0,
    val customLockscreenClockGlass: Boolean = false,
    val clockGlassBlur: Float = 4f,
    val clockGlassRefract: Float = 6f,
    val clockGlassDispersion: Float = 35f,
    val clockGlassHighlight: Float = 75f,
    val clockGlassFalloff: Float = 2f,
    val clockGlassLightAngle: Float = 45f,
    val clockGlassTintStrength: Float = 25f,
    val lockscreenQuickToolGlass: Boolean = false,
    val popOutButtons: Boolean = false,
    val shakePhysics: Boolean = false,
    /** LiquidVivo glass settings, keyed by their Settings.Global name. */
    val liquidVivo: Map<String, Int> = emptyMap(),
    /** Kyant iOS-26 glass settings, keyed by their Settings.Global name. */
    val kyantGlass: Map<String, Int> = emptyMap(),
    val popOutDepth: Float = PopOutButtonsHook.DEFAULT_DEPTH,
    val popOutVolumeUpY: Float = PopOutButtonsHook.DEFAULT_VOLUME_UP_Y,
    val popOutVolumeDownY: Float = PopOutButtonsHook.DEFAULT_VOLUME_DOWN_Y,
    val popOutPowerY: Float = PopOutButtonsHook.DEFAULT_POWER_Y,
    val popOutLeftSide: Boolean = false,
    val aospStatusbar: Boolean = false,
    val statusbarWifiPack: Int = 0,
    val statusbarBatteryPack: Int = 0,
    val statusbarMobilePack: Int = 0,
    val statusBarBatteryScale: Float = 100f,
    val statusBarBatteryX: Float = 0f,
    val statusBarBatteryTextScale: Float = 100f,
    val statusBarBatteryTextWeight: Float = 700f,
    val statusBarWifiScale: Float = 100f,
    val statusBarWifiX: Float = 0f,
    val statusBarClockScale: Float = 100f,
    val statusBarClockX: Float = 0f,
    val statusBarSystemIconsScale: Float = 100f,
    val controlCenterIconPack: Int = 0,
    val nativeBlurControlCenter: Float = 100f,
    val nativeBlurNotifications: Float = 100f,
    val nativeBlurPanel: Float = 100f,
    val nativeBlurDisablePanelScale: Boolean = false,
    val nativeBlurTuner: Boolean = false,
    val nativeBlurTiles: Float = 100f,
    val nativeBlurTilesSat: Float = 100f,
    val nativeBlurSliders: Float = 100f,
    val nativeBlurSlidersSat: Float = 100f,
    val nativeBlurButtons: Float = 100f,
    val nativeBlurButtonsSat: Float = 100f,
    val nativeBlurPlayer: Float = 100f,
    val nativeBlurPlayerSat: Float = 100f,
    val nativeBlurOther: Float = 100f,
    val nativeBlurOtherSat: Float = 100f,
    val nativeBlurPanelDarken: Float = 100f,
    val allowAllMediaPlayers: Boolean = false,
    val liveUpdatesInOriginIsland: Boolean = false,
    val allProgressInOriginIsland: Boolean = false,
    val flashlightInOriginIsland: Boolean = false,
    val gboardBlurBackground: Boolean = false,
    val appThemeMode: Int = 1,
    val monetColors: Boolean = false,
    val uiScale: Float = 100f,
    val liquidGlassBar: Boolean = true,
    val autoHideBar: Boolean = true,
    val showIntro: Boolean = true
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = IconPackEngine(application)
    private val installer = RootThemeInstaller(application)
    private val preferences = application.getSharedPreferences("settings", 0)
    private var launcherIconSizeJob: Job? = null
    private var statusBarBatteryScaleJob: Job? = null
    private var statusBarBatteryXJob: Job? = null
    private var statusBarBatteryTextScaleJob: Job? = null
    private var statusBarBatteryTextWeightJob: Job? = null
    private var statusBarWifiScaleJob: Job? = null
    private var statusBarWifiXJob: Job? = null
    private var statusBarClockScaleJob: Job? = null
    private var statusBarClockXJob: Job? = null
    private var statusBarSystemIconsScaleJob: Job? = null
    private var iconPackOptionsJob: Job? = null
    private var notificationClockSizeJob: Job? = null
    private val liquidGlassSettingJobs = mutableMapOf<String, Job>()
    private val mutableState = MutableStateFlow(
        UiState(
            fullConversion = preferences.getBoolean("full_conversion", false),
            autoRecolor = preferences.getBoolean("auto_recolor", true),
            replaceIconBackground = preferences.getBoolean("replace_icon_background", false),
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
            launcherCustomGridEnabled = Settings.Global.getInt(application.contentResolver, LauncherGridHook.SETTING_CUSTOM_GRID_ENABLED, 0) == 1,
            launcherGridColumns = Settings.Global.getInt(application.contentResolver, LauncherGridHook.SETTING_GRID_COLUMNS, 4).coerceAtLeast(1),
            launcherGridRows = Settings.Global.getInt(application.contentResolver, LauncherGridHook.SETTING_GRID_ROWS, 7).coerceAtLeast(1),
            launcherOriginalColumns = Settings.Global.getInt(application.contentResolver, LauncherGridHook.SETTING_ORIGINAL_COLUMNS, 4).coerceAtLeast(1),
            launcherOriginalRows = Settings.Global.getInt(application.contentResolver, LauncherGridHook.SETTING_ORIGINAL_ROWS, 7).coerceAtLeast(1),
            restoreGoogleAssistant = Settings.Global.getInt(application.contentResolver, "originroottoolbox_restore_google_assistant", 0) == 1,
            bypassGeminiRegionalRestrictions = Settings.Global.getInt(application.contentResolver, "originroottoolbox_bypass_gemini_region", 0) == 1,
            removePackageInstallerAfterReboot = Settings.Global.getInt(application.contentResolver, "originroottoolbox_remove_package_installer_boot", 0) == 1,
            powerButtonGemini = Settings.Global.getInt(application.contentResolver, "originicons_power_button_gemini", 0) == 1,
            blueLmRussian = Settings.Global.getInt(application.contentResolver, BlueLmRussianHook.SETTING_ENABLED, 0) == 1,
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
            customLockscreenClock = Settings.Global.getInt(application.contentResolver, MaterialOriginOsLockscreenHook.SETTING_CUSTOM_CLOCK, 0) == 1 || Settings.Global.getInt(application.contentResolver, MaterialOriginOsLockscreenHook.SETTING_ENABLED, 0) == 1,
            customLockscreenClockStyle = Settings.Global.getString(application.contentResolver, CustomLockscreenClockHostView.SETTING_CLOCK_STYLE) ?: LockscreenClockCatalog.DEFAULT_STYLE_ID,
            customLockscreenClockColor = Settings.Global.getString(application.contentResolver, CustomLockscreenClockHostView.SETTING_CLOCK_COLOR)?.toLongOrNull()?.toInt() ?: 0,
            customLockscreenClockGlass = Settings.Global.getInt(application.contentResolver, CustomLockscreenClockHostView.SETTING_CLOCK_GLASS, 0) == 1,
            clockGlassBlur = Settings.Global.getString(application.contentResolver, CustomLockscreenClockHostView.SETTING_CLOCK_GLASS_BLUR)?.toFloatOrNull() ?: 4f,
            clockGlassRefract = Settings.Global.getString(application.contentResolver, CustomLockscreenClockHostView.SETTING_CLOCK_GLASS_REFRACT)?.toFloatOrNull() ?: 6f,
            clockGlassDispersion = Settings.Global.getString(application.contentResolver, CustomLockscreenClockHostView.SETTING_CLOCK_GLASS_DISPERSION)?.toFloatOrNull() ?: 35f,
            clockGlassHighlight = Settings.Global.getString(application.contentResolver, CustomLockscreenClockHostView.SETTING_CLOCK_GLASS_HIGHLIGHT)?.toFloatOrNull() ?: 75f,
            clockGlassFalloff = Settings.Global.getString(application.contentResolver, CustomLockscreenClockHostView.SETTING_CLOCK_GLASS_FALLOFF)?.toFloatOrNull() ?: 2f,
            clockGlassLightAngle = Settings.Global.getString(application.contentResolver, CustomLockscreenClockHostView.SETTING_CLOCK_GLASS_LIGHT_ANGLE)?.toFloatOrNull() ?: 45f,
            clockGlassTintStrength = Settings.Global.getString(application.contentResolver, CustomLockscreenClockHostView.SETTING_CLOCK_GLASS_TINT_STRENGTH)?.toFloatOrNull() ?: 25f,
            lockscreenQuickToolGlass = Settings.Global.getInt(application.contentResolver, LockscreenQuickToolGlassHook.SETTING_ENABLED, 0) == 1,
            popOutButtons = Settings.Global.getInt(application.contentResolver, PopOutButtonsHook.SETTING_ENABLED, 0) == 1,
            shakePhysics = Settings.Global.getInt(application.contentResolver, ShakePhysicsHook.SETTING_ENABLED, 0) == 1,
            liquidVivo = readLiquidVivo(application),
            kyantGlass = KyantGlass.DEFAULTS.keys.mapNotNull { key ->
                runCatching { Settings.Global.getInt(application.contentResolver, key) }.getOrNull()?.let { key to it }
            }.toMap(),
            popOutDepth = Settings.Global.getString(application.contentResolver, PopOutButtonsHook.SETTING_DEPTH)?.toFloatOrNull() ?: PopOutButtonsHook.DEFAULT_DEPTH,
            popOutVolumeUpY = Settings.Global.getString(application.contentResolver, PopOutButtonsHook.SETTING_VOLUME_UP_Y)?.toFloatOrNull() ?: PopOutButtonsHook.DEFAULT_VOLUME_UP_Y,
            popOutVolumeDownY = Settings.Global.getString(application.contentResolver, PopOutButtonsHook.SETTING_VOLUME_DOWN_Y)?.toFloatOrNull() ?: PopOutButtonsHook.DEFAULT_VOLUME_DOWN_Y,
            popOutPowerY = Settings.Global.getString(application.contentResolver, PopOutButtonsHook.SETTING_POWER_Y)?.toFloatOrNull() ?: PopOutButtonsHook.DEFAULT_POWER_Y,
            popOutLeftSide = Settings.Global.getInt(application.contentResolver, PopOutButtonsHook.SETTING_LEFT_SIDE, 0) == 1,
            aospStatusbar = Settings.Global.getInt(application.contentResolver, "originicons_aosp_statusbar", 0) == 1,
            statusbarWifiPack = Settings.Global.getInt(application.contentResolver, "originicons_statusbar_wifi_pack", 0),
            statusbarBatteryPack = Settings.Global.getInt(application.contentResolver, "originicons_statusbar_battery_pack", 0),
            statusbarMobilePack = Settings.Global.getInt(application.contentResolver, "originicons_statusbar_mobile_pack", 0),
            statusBarBatteryScale = Settings.Global.getInt(application.contentResolver, "originroottoolbox_statusbar_battery_scale", 100).toFloat(),
            statusBarBatteryX = Settings.Global.getInt(application.contentResolver, "originroottoolbox_statusbar_battery_x", 0).toFloat(),
            statusBarBatteryTextScale = Settings.Global.getInt(application.contentResolver, "originroottoolbox_statusbar_battery_text_scale", 100).toFloat(),
            statusBarBatteryTextWeight = Settings.Global.getInt(application.contentResolver, "originroottoolbox_statusbar_battery_text_weight", 700).toFloat(),
            statusBarWifiScale = Settings.Global.getInt(application.contentResolver, "originroottoolbox_statusbar_wifi_scale", 100).toFloat(),
            statusBarWifiX = Settings.Global.getInt(application.contentResolver, "originroottoolbox_statusbar_wifi_x", 0).toFloat(),
            statusBarClockScale = Settings.Global.getInt(application.contentResolver, "originroottoolbox_statusbar_clock_scale", 100).toFloat(),
            statusBarClockX = Settings.Global.getInt(application.contentResolver, "originroottoolbox_statusbar_clock_x", 0).toFloat(),
            statusBarSystemIconsScale = Settings.Global.getInt(application.contentResolver, "originroottoolbox_statusbar_system_icons_scale", 100).toFloat(),
            controlCenterIconPack = Settings.Global.getInt(application.contentResolver, "originicons_control_center_icon_pack", 0),
            nativeBlurControlCenter = Settings.Global.getInt(application.contentResolver, NativeBlurHook.SETTING_CONTROL_CENTER_BLUR, 100).toFloat(),
            nativeBlurNotifications = Settings.Global.getInt(application.contentResolver, NativeBlurHook.SETTING_NOTIFICATIONS_BLUR, 100).toFloat(),
            nativeBlurPanel = Settings.Global.getInt(application.contentResolver, NativeBlurHook.SETTING_PANEL_BLUR, 100).toFloat(),
            nativeBlurDisablePanelScale = Settings.Global.getInt(application.contentResolver, NativeBlurHook.SETTING_DISABLE_PANEL_SCALE, 0) == 1,
            allowAllMediaPlayers = Settings.Global.getInt(application.contentResolver, "originicons_allow_all_media_players", 0) == 1,
            liveUpdatesInOriginIsland = Settings.Global.getInt(application.contentResolver, OriginIslandLiveUpdatesHook.SETTING_ENABLED, 0) == 1,
            allProgressInOriginIsland = Settings.Global.getInt(application.contentResolver, OriginIslandLiveUpdatesHook.SETTING_ALL_PROGRESS, 0) == 1,
            flashlightInOriginIsland = Settings.Global.getInt(application.contentResolver, OriginIslandLiveUpdatesHook.SETTING_FLASHLIGHT, 0) == 1,
            gboardBlurBackground = Settings.Global.getInt(application.contentResolver, GboardBlurHook.SETTING_ENABLED, 0) == 1,
            appThemeMode = preferences.getInt("app_theme_mode", 1),
            monetColors = preferences.getBoolean("monet_colors", false),
            uiScale = preferences.getFloat("ui_scale", 100f),
            liquidGlassBar = preferences.getBoolean("liquid_glass_bar", true),
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
            val activePack = packs.firstOrNull {
                it.packageName == Settings.Global.getString(getApplication<Application>().contentResolver, LauncherIconPack.SETTING_PACK)
            }
            mutableState.value = mutableState.value.copy(
                packs = packs,
                source = if (activePack != null) IconSource.PACK else IconSource.SYSTEM,
                selected = activePack,
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

    fun setNativeBlurTuner(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying native blur tuner…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setNativeBlurTuner(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(nativeBlurTuner = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Native blur tuner applied" else "Unable to apply native blur tuner",
                success = result.success
            )
        }
    }

    fun setNativeBlurControlCenter(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(nativeBlurControlCenter = adjusted)
        persistNativeBlurSetting(NativeBlurHook.SETTING_CONTROL_CENTER_BLUR, adjusted.toInt())
    }

    fun setNativeBlurNotifications(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(nativeBlurNotifications = adjusted)
        persistNativeBlurSetting(NativeBlurHook.SETTING_NOTIFICATIONS_BLUR, adjusted.toInt())
    }

    fun setNativeBlurDisablePanelScale(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying background motion setting…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setNativeBlurPanelScaleDisabled(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(nativeBlurDisablePanelScale = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Background motion setting applied" else "Unable to apply background motion setting",
                success = result.success
            )
        }
    }

    fun setNativeBlurTiles(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(nativeBlurTiles = adjusted)
        persistNativeBlurSetting(NativeBlurHook.SETTING_TILES_BLUR, adjusted.toInt())
    }

    fun setNativeBlurTilesSat(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(nativeBlurTilesSat = adjusted)
        persistNativeBlurSetting(NativeBlurHook.SETTING_TILES_SAT, adjusted.toInt())
    }

    fun setNativeBlurSliders(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(nativeBlurSliders = adjusted)
        persistNativeBlurSetting(NativeBlurHook.SETTING_SLIDERS_BLUR, adjusted.toInt())
    }

    fun setNativeBlurSlidersSat(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(nativeBlurSlidersSat = adjusted)
        persistNativeBlurSetting(NativeBlurHook.SETTING_SLIDERS_SAT, adjusted.toInt())
    }

    fun setNativeBlurButtons(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(nativeBlurButtons = adjusted)
        persistNativeBlurSetting(NativeBlurHook.SETTING_BUTTONS_BLUR, adjusted.toInt())
    }

    fun setNativeBlurButtonsSat(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(nativeBlurButtonsSat = adjusted)
        persistNativeBlurSetting(NativeBlurHook.SETTING_BUTTONS_SAT, adjusted.toInt())
    }

    fun setNativeBlurPlayer(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(nativeBlurPlayer = adjusted)
        persistNativeBlurSetting(NativeBlurHook.SETTING_PLAYER_BLUR, adjusted.toInt())
    }

    fun setNativeBlurPlayerSat(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(nativeBlurPlayerSat = adjusted)
        persistNativeBlurSetting(NativeBlurHook.SETTING_PLAYER_SAT, adjusted.toInt())
    }

    fun setNativeBlurOther(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(nativeBlurOther = adjusted)
        persistNativeBlurSetting(NativeBlurHook.SETTING_OTHER_BLUR, adjusted.toInt())
    }

    fun setNativeBlurOtherSat(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(nativeBlurOtherSat = adjusted)
        persistNativeBlurSetting(NativeBlurHook.SETTING_OTHER_SAT, adjusted.toInt())
    }

    fun setNativeBlurPanel(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(nativeBlurPanel = adjusted)
        persistNativeBlurSetting(NativeBlurHook.SETTING_PANEL_BLUR, adjusted.toInt())
    }

    fun setNativeBlurPanelDarken(value: Float) {
        val adjusted = value.coerceIn(0f, 200f)
        mutableState.value = mutableState.value.copy(nativeBlurPanelDarken = adjusted)
        persistNativeBlurSetting(NativeBlurHook.SETTING_PANEL_DARKEN, adjusted.toInt())
    }

    private fun persistNativeBlurSetting(key: String, value: Int) {
        liquidGlassSettingJobs.remove(key)?.cancel()
        liquidGlassSettingJobs[key] = viewModelScope.launch {
            delay(90)
            withContext(Dispatchers.IO) { installer.setNativeBlurParameter(key, value) }
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
        updateActiveIconPackOptions()
    }

    fun setReplaceIconBackground(enabled: Boolean) {
        preferences.edit().putBoolean("replace_icon_background", enabled).apply()
        mutableState.value = mutableState.value.copy(replaceIconBackground = enabled)
        updateActiveIconPackOptions()
    }

    private fun updateActiveIconPackOptions() {
        val pack = mutableState.value.selected ?: return
        if (mutableState.value.source != IconSource.PACK ||
            Settings.Global.getString(getApplication<Application>().contentResolver, LauncherIconPack.SETTING_PACK) != pack.packageName
        ) return
        iconPackOptionsJob?.cancel()
        iconPackOptionsJob = viewModelScope.launch {
            delay(150)
            val options = mutableState.value
            val result = withContext(Dispatchers.IO) {
                installer.setLiveIconPackOptions(options.autoRecolor, options.replaceIconBackground)
            }
            if (!result.success) mutableState.value = mutableState.value.copy(status = result.message, success = false)
        }
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

    fun setLauncherGridColumns(cols: Int) {
        mutableState.value = mutableState.value.copy(launcherGridColumns = cols.coerceAtLeast(1))
    }

    fun setLauncherGridRows(rows: Int) {
        mutableState.value = mutableState.value.copy(launcherGridRows = rows.coerceAtLeast(1))
    }

    fun setLauncherGridPreset(cols: Int, rows: Int) {
        mutableState.value = mutableState.value.copy(
            launcherGridColumns = cols.coerceAtLeast(1),
            launcherGridRows = rows.coerceAtLeast(1)
        )
    }

    fun applyLauncherGrid() {
        viewModelScope.launch {
            val cols = mutableState.value.launcherGridColumns
            val rows = mutableState.value.launcherGridRows
            val result = withContext(Dispatchers.IO) {
                installer.applyLauncherGrid(cols, rows)
            }
            if (result.success) {
                val origCols = Settings.Global.getInt(getApplication<Application>().contentResolver, LauncherGridHook.SETTING_ORIGINAL_COLUMNS, 4)
                val origRows = Settings.Global.getInt(getApplication<Application>().contentResolver, LauncherGridHook.SETTING_ORIGINAL_ROWS, 7)
                mutableState.value = mutableState.value.copy(
                    launcherCustomGridEnabled = true,
                    launcherOriginalColumns = origCols,
                    launcherOriginalRows = origRows
                )
            }
            if (result.message.isNotBlank()) {
                Toast.makeText(getApplication(), result.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun resetLauncherGrid() {
        viewModelScope.launch {
            val origCols = mutableState.value.launcherOriginalColumns
            val origRows = mutableState.value.launcherOriginalRows
            val result = withContext(Dispatchers.IO) {
                installer.resetLauncherGrid()
            }
            if (result.success) {
                mutableState.value = mutableState.value.copy(
                    launcherCustomGridEnabled = false,
                    launcherGridColumns = origCols,
                    launcherGridRows = origRows
                )
            }
            if (result.message.isNotBlank()) {
                Toast.makeText(getApplication(), result.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun setStatusBarBatteryScale(value: Float) {
        val size = value.toInt().coerceIn(50, 150)
        mutableState.value = mutableState.value.copy(statusBarBatteryScale = size.toFloat())
        statusBarBatteryScaleJob?.cancel()
        statusBarBatteryScaleJob = viewModelScope.launch {
            delay(70)
            withContext(Dispatchers.IO) { installer.setStatusBarBatteryScale(size) }
        }
    }

    fun setStatusBarBatteryX(value: Float) {
        val offset = value.toInt().coerceIn(-150, 150)
        mutableState.value = mutableState.value.copy(statusBarBatteryX = offset.toFloat())
        statusBarBatteryXJob?.cancel()
        statusBarBatteryXJob = viewModelScope.launch {
            delay(70)
            withContext(Dispatchers.IO) { installer.setStatusBarBatteryX(offset) }
        }
    }

    fun setStatusBarBatteryTextScale(value: Float) {
        val size = value.toInt().coerceIn(50, 150)
        mutableState.value = mutableState.value.copy(statusBarBatteryTextScale = size.toFloat())
        statusBarBatteryTextScaleJob?.cancel()
        statusBarBatteryTextScaleJob = viewModelScope.launch {
            delay(70)
            withContext(Dispatchers.IO) { installer.setStatusBarBatteryTextScale(size) }
        }
    }

    fun setStatusBarBatteryTextWeight(value: Float) {
        val weight = ((value.toInt() + 50) / 100 * 100).coerceIn(100, 900)
        mutableState.value = mutableState.value.copy(statusBarBatteryTextWeight = weight.toFloat())
        statusBarBatteryTextWeightJob?.cancel()
        statusBarBatteryTextWeightJob = viewModelScope.launch {
            delay(70)
            withContext(Dispatchers.IO) { installer.setStatusBarBatteryTextWeight(weight) }
        }
    }

    fun setStatusBarWifiScale(value: Float) {
        val size = value.toInt().coerceIn(50, 150)
        mutableState.value = mutableState.value.copy(statusBarWifiScale = size.toFloat())
        statusBarWifiScaleJob?.cancel()
        statusBarWifiScaleJob = viewModelScope.launch {
            delay(70)
            withContext(Dispatchers.IO) { installer.setStatusBarWifiScale(size) }
        }
    }

    fun setStatusBarWifiX(value: Float) {
        val offset = value.toInt().coerceIn(-150, 150)
        mutableState.value = mutableState.value.copy(statusBarWifiX = offset.toFloat())
        statusBarWifiXJob?.cancel()
        statusBarWifiXJob = viewModelScope.launch {
            delay(70)
            withContext(Dispatchers.IO) { installer.setStatusBarWifiX(offset) }
        }
    }

    fun setStatusBarClockScale(value: Float) {
        val size = value.toInt().coerceIn(50, 150)
        mutableState.value = mutableState.value.copy(statusBarClockScale = size.toFloat())
        statusBarClockScaleJob?.cancel()
        statusBarClockScaleJob = viewModelScope.launch {
            delay(70)
            withContext(Dispatchers.IO) { installer.setStatusBarClockScale(size) }
        }
    }

    fun setStatusBarClockX(value: Float) {
        val offset = value.toInt().coerceIn(-150, 150)
        mutableState.value = mutableState.value.copy(statusBarClockX = offset.toFloat())
        statusBarClockXJob?.cancel()
        statusBarClockXJob = viewModelScope.launch {
            delay(70)
            withContext(Dispatchers.IO) { installer.setStatusBarClockX(offset) }
        }
    }

    fun setStatusBarSystemIconsScale(value: Float) {
        val size = value.toInt().coerceIn(50, 150)
        mutableState.value = mutableState.value.copy(statusBarSystemIconsScale = size.toFloat())
        statusBarSystemIconsScaleJob?.cancel()
        statusBarSystemIconsScaleJob = viewModelScope.launch {
            delay(70)
            withContext(Dispatchers.IO) { installer.setStatusBarSystemIconsScale(size) }
        }
    }

    fun resetStatusBarTweakDefaults() {
        mutableState.value = mutableState.value.copy(
            statusBarBatteryScale = 100f,
            statusBarBatteryX = 0f,
            statusBarBatteryTextScale = 100f,
            statusBarBatteryTextWeight = 700f,
            statusBarWifiScale = 100f,
            statusBarWifiX = 0f,
            statusBarClockScale = 100f,
            statusBarClockX = 0f,
            statusBarSystemIconsScale = 100f
        )
        statusBarBatteryScaleJob?.cancel()
        statusBarBatteryXJob?.cancel()
        statusBarBatteryTextScaleJob?.cancel()
        statusBarBatteryTextWeightJob?.cancel()
        statusBarWifiScaleJob?.cancel()
        statusBarWifiXJob?.cancel()
        statusBarClockScaleJob?.cancel()
        statusBarClockXJob?.cancel()
        statusBarSystemIconsScaleJob?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                installer.setStatusBarBatteryScale(100)
                installer.setStatusBarBatteryX(0)
                installer.setStatusBarBatteryTextScale(100)
                installer.setStatusBarBatteryTextWeight(700)
                installer.setStatusBarWifiScale(100)
                installer.setStatusBarWifiX(0)
                installer.setStatusBarClockScale(100)
                installer.setStatusBarClockX(0)
                installer.setStatusBarSystemIconsScale(100)
            }
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

    fun setBlueLmRussian(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying BlueLM Russian translation…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setBlueLmRussian(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(blueLmRussian = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "BlueLM translation applied" else "Unable to apply BlueLM translation",
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

    fun setCustomLockscreenClock(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying Custom lockscreen clock…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setCustomLockscreenClock(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(customLockscreenClock = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Custom lockscreen clock applied" else "Unable to apply Custom lockscreen clock",
                success = result.success
            )
        }
    }

    fun setCustomLockscreenClockStyle(styleId: String) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(customLockscreenClockStyle = styleId)
            withContext(Dispatchers.IO) { installer.setCustomLockscreenClockStyle(styleId) }
        }
    }

    fun setCustomLockscreenClockColor(color: Int) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(customLockscreenClockColor = color)
            withContext(Dispatchers.IO) { installer.setCustomLockscreenClockColor(color) }
        }
    }

    fun setCustomLockscreenClockGlass(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(customLockscreenClockGlass = enabled)
            withContext(Dispatchers.IO) { installer.setCustomLockscreenClockGlass(enabled) }
        }
    }

    fun setClockGlassBlur(value: Float) {
        mutableState.value = mutableState.value.copy(clockGlassBlur = value)
        viewModelScope.launch(Dispatchers.IO) {
            installer.setCustomLockscreenClockParam(CustomLockscreenClockHostView.SETTING_CLOCK_GLASS_BLUR, value)
        }
    }

    fun setClockGlassRefract(value: Float) {
        mutableState.value = mutableState.value.copy(clockGlassRefract = value)
        viewModelScope.launch(Dispatchers.IO) {
            installer.setCustomLockscreenClockParam(CustomLockscreenClockHostView.SETTING_CLOCK_GLASS_REFRACT, value)
        }
    }

    fun setClockGlassDispersion(value: Float) {
        mutableState.value = mutableState.value.copy(clockGlassDispersion = value)
        viewModelScope.launch(Dispatchers.IO) {
            installer.setCustomLockscreenClockParam(CustomLockscreenClockHostView.SETTING_CLOCK_GLASS_DISPERSION, value)
        }
    }

    fun setClockGlassHighlight(value: Float) {
        mutableState.value = mutableState.value.copy(clockGlassHighlight = value)
        viewModelScope.launch(Dispatchers.IO) {
            installer.setCustomLockscreenClockParam(CustomLockscreenClockHostView.SETTING_CLOCK_GLASS_HIGHLIGHT, value)
        }
    }

    fun setClockGlassFalloff(value: Float) {
        mutableState.value = mutableState.value.copy(clockGlassFalloff = value)
        viewModelScope.launch(Dispatchers.IO) {
            installer.setCustomLockscreenClockParam(CustomLockscreenClockHostView.SETTING_CLOCK_GLASS_FALLOFF, value)
        }
    }

    fun setClockGlassLightAngle(value: Float) {
        mutableState.value = mutableState.value.copy(clockGlassLightAngle = value)
        viewModelScope.launch(Dispatchers.IO) {
            installer.setCustomLockscreenClockParam(CustomLockscreenClockHostView.SETTING_CLOCK_GLASS_LIGHT_ANGLE, value)
        }
    }

    fun setClockGlassTintStrength(value: Float) {
        mutableState.value = mutableState.value.copy(clockGlassTintStrength = value)
        viewModelScope.launch(Dispatchers.IO) {
            installer.setCustomLockscreenClockParam(CustomLockscreenClockHostView.SETTING_CLOCK_GLASS_TINT_STRENGTH, value)
        }
    }

    fun liquidVivo(key: String, default: Int): Int = mutableState.value.liquidVivo[key] ?: default

    fun setLiquidVivo(key: String, value: Int) {
        mutableState.value = mutableState.value.copy(liquidVivo = mutableState.value.liquidVivo + (key to value))
        liquidGlassSettingJobs.remove(key)?.cancel()
        liquidGlassSettingJobs[key] = viewModelScope.launch {
            delay(60)
            withContext(Dispatchers.IO) { installer.setLiquidVivoParameter(key, value) }
            liquidGlassSettingJobs.remove(key)
        }
    }

    fun kyantGlass(key: String): Int = mutableState.value.kyantGlass[key] ?: KyantGlass.DEFAULTS[key] ?: 0

    fun setKyantGlass(key: String, value: Int) {
        mutableState.value = mutableState.value.copy(kyantGlass = mutableState.value.kyantGlass + (key to value))
        liquidGlassSettingJobs.remove(key)?.cancel()
        liquidGlassSettingJobs[key] = viewModelScope.launch {
            delay(60)
            withContext(Dispatchers.IO) { installer.setLiquidVivoParameter(key, value) }
            liquidGlassSettingJobs.remove(key)
        }
    }

    /** Applies Kyant's "Clear" button look; switches and the shade blur stay as they are. */
    fun resetKyantGlassLook() {
        KyantGlass.CLEAR_PRESET.forEach { (k, v) -> setKyantGlass(k, v) }
    }

    fun applyKyantGlassPreset(preset: Map<String, Int>) {
        preset.forEach { (k, v) -> setKyantGlass(k, v) }
    }

    private val glassExportTag = "originceiler-glass"

    /** Copies every look setting (not the surface switches) to the clipboard as JSON. */
    fun exportKyantGlass() {
        val json = org.json.JSONObject()
        json.put("format", glassExportTag)
        json.put("version", 1)
        val values = org.json.JSONObject()
        KyantGlass.LOOK_KEYS.sorted().forEach { key -> values.put(key.removePrefix(KyantGlass.PREFIX), kyantGlass(key)) }
        json.put("values", values)
        val clipboard = getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Liquid Glass", json.toString()))
        android.widget.Toast.makeText(getApplication<Application>(), "Glass settings copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Applies glass settings from the clipboard; only known look keys, values clamped by the hooks. */
    fun importKyantGlass() {
        val clipboard = getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(getApplication<Application>())?.toString()
        val count = runCatching {
            val json = org.json.JSONObject(text ?: "")
            require(json.optString("format") == glassExportTag)
            val values = json.getJSONObject("values")
            var n = 0
            KyantGlass.LOOK_KEYS.forEach { key ->
                val short = key.removePrefix(KyantGlass.PREFIX)
                if (values.has(short)) {
                    setKyantGlass(key, values.getInt(short))
                    n++
                }
            }
            n
        }.getOrNull()
        val message = if (count != null && count > 0) "Imported $count glass settings" else "No glass settings in the clipboard"
        android.widget.Toast.makeText(getApplication<Application>(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun resetLiquidVivoTuning() {
        val defaults = buildMap {
            put(LiquidVivoGlassHook.KEY_GLASS_OPACITY, LiquidVivoGlassHook.DEFAULT_GLASS_OPACITY)
            put(LiquidVivoGlassHook.KEY_GLASS_RADIUS, LiquidVivoGlassHook.DEFAULT_GLASS_RADIUS)
            LiquidVivoGlassHook.Scene.entries.forEach {
                put(LiquidVivoGlassHook.radiusKey(it), LiquidVivoGlassHook.DEFAULT_SCALE)
                put(LiquidVivoGlassHook.alphaKey(it), LiquidVivoGlassHook.DEFAULT_SCALE)
            }
        }
        defaults.forEach { (k, v) -> setLiquidVivo(k, v) }
    }

    fun setShakePhysics(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setShakePhysics(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(shakePhysics = enabled)
        }
    }

    fun setPopOutButtons(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(popOutButtons = enabled)
        persistPopOutSetting(PopOutButtonsHook.SETTING_ENABLED, if (enabled) "1" else "0")
    }

    init {
        // The position editor may have been killed mid-edit; never leave SystemUI holding the bulges.
        runCatching {
            val r = getApplication<Application>().contentResolver
            if (Settings.Global.getInt(r, PopOutButtonsHook.SETTING_EDIT, 0) == 1) {
                Settings.Global.putString(r, PopOutButtonsHook.SETTING_EDIT, "0")
            }
        }
    }

    fun reloadPopOutPositions() {
        val r = getApplication<Application>().contentResolver
        fun f(key: String, def: Float) = Settings.Global.getString(r, key)?.toFloatOrNull() ?: def
        mutableState.value = mutableState.value.copy(
            popOutVolumeUpY = f(PopOutButtonsHook.SETTING_VOLUME_UP_Y, PopOutButtonsHook.DEFAULT_VOLUME_UP_Y),
            popOutVolumeDownY = f(PopOutButtonsHook.SETTING_VOLUME_DOWN_Y, PopOutButtonsHook.DEFAULT_VOLUME_DOWN_Y),
            popOutPowerY = f(PopOutButtonsHook.SETTING_POWER_Y, PopOutButtonsHook.DEFAULT_POWER_Y)
        )
    }

    fun setPopOutLeftSide(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(popOutLeftSide = enabled)
        persistPopOutSetting(PopOutButtonsHook.SETTING_LEFT_SIDE, if (enabled) "1" else "0")
    }

    fun setPopOutDepth(value: Float) {
        val adjusted = value.coerceIn(2f, 30f)
        mutableState.value = mutableState.value.copy(popOutDepth = adjusted)
        persistPopOutSetting(PopOutButtonsHook.SETTING_DEPTH, "%.1f".format(java.util.Locale.US, adjusted))
    }

    fun setPopOutVolumeUpY(value: Float) {
        val adjusted = value.coerceIn(0f, 100f)
        mutableState.value = mutableState.value.copy(popOutVolumeUpY = adjusted)
        persistPopOutSetting(PopOutButtonsHook.SETTING_VOLUME_UP_Y, "%.1f".format(java.util.Locale.US, adjusted))
    }

    fun setPopOutVolumeDownY(value: Float) {
        val adjusted = value.coerceIn(0f, 100f)
        mutableState.value = mutableState.value.copy(popOutVolumeDownY = adjusted)
        persistPopOutSetting(PopOutButtonsHook.SETTING_VOLUME_DOWN_Y, "%.1f".format(java.util.Locale.US, adjusted))
    }

    fun setPopOutPowerY(value: Float) {
        val adjusted = value.coerceIn(0f, 100f)
        mutableState.value = mutableState.value.copy(popOutPowerY = adjusted)
        persistPopOutSetting(PopOutButtonsHook.SETTING_POWER_Y, "%.1f".format(java.util.Locale.US, adjusted))
    }

    private fun persistPopOutSetting(key: String, value: String) {
        liquidGlassSettingJobs.remove(key)?.cancel()
        liquidGlassSettingJobs[key] = viewModelScope.launch {
            delay(120)
            withContext(Dispatchers.IO) { installer.setPopOutParameter(key, value) }
            liquidGlassSettingJobs.remove(key)
        }
    }

    fun setLockscreenQuickToolGlass(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying lockscreen glass shortcuts…", success = null)
            val result = withContext(Dispatchers.IO) { installer.setLockscreenQuickToolGlass(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(lockscreenQuickToolGlass = enabled)
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Lockscreen glass shortcuts applied" else "Unable to apply lockscreen glass shortcuts",
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
            if (snapshot.source == IconSource.PACK) {
                val pack = snapshot.selected
                if (pack == null) {
                    mutableState.value = mutableState.value.copy(applying = false, status = "No icon pack selected", success = false)
                    return@launch
                }
                val root = withContext(Dispatchers.IO) {
                    installer.applyIconPack(pack.packageName, snapshot.autoRecolor, snapshot.replaceIconBackground)
                }
                mutableState.value = mutableState.value.copy(
                    applying = false,
                    progress = if (root.success) 1f else 0f,
                    status = if (root.success) "Icon pack applied" else root.message,
                    success = root.success,
                    nativeAospThemedIcons = if (root.success) false else mutableState.value.nativeAospThemedIcons
                )
                return@launch
            }
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
                        IconSource.PACK -> error("Icon packs are applied by the launcher hook")
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

private fun readLiquidVivo(application: Application): Map<String, Int> {
    val cr = application.contentResolver
    val keys = buildList {
        add(LiquidVivoGlassHook.KEY_ENABLE)
        add(LiquidVivoGlassHook.KEY_LIQUID_GLASS)
        add(LiquidVivoGlassHook.KEY_GLASS_OPACITY)
        add(LiquidVivoGlassHook.KEY_GLASS_RADIUS)
        add(LiquidVivoGlassHook.KEY_ISLAND)
        LiquidVivoGlassHook.Scene.entries.forEach {
            add(LiquidVivoGlassHook.radiusKey(it))
            add(LiquidVivoGlassHook.alphaKey(it))
        }
    }
    return buildMap {
        keys.forEach { key ->
            runCatching { Settings.Global.getInt(cr, key) }.getOrNull()?.let { put(key, it) }
        }
    }
}
