package com.autonavi.minimap

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
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
    val pixelGoogleIdentity: Boolean = false,
    val powerButtonGemini: Boolean = false,
    val hideVivoServiceError: Boolean = false,
    val aospVolumeBar: Boolean = false,
    val aospControlCenter: Boolean = false,
    val disablePrivateDnsWithVpn: Boolean = false,
    val settingsAdaptiveTheme: Boolean = false,
    val settingsContainerGap: Float = 8f,
    val aospStatusbar: Boolean = false,
    val statusbarWifiPack: Int = 0,
    val statusbarBatteryPack: Int = 0,
    val statusbarMobilePack: Int = 0,
    val allowAllMediaPlayers: Boolean = false,
    val originIslandEnabled: Boolean = false,
    val islandKeepAfterUnlock: Boolean = true,
    val islandPromotedRows: Boolean = true,
    val captureAllMedia: Boolean = false,
    val captureAllProgress: Boolean = false,
    val islandApps: List<IslandApp> = emptyList(),
    val showIntro: Boolean = true
)

data class IslandApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val selected: Boolean
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = IconPackEngine(application)
    private val installer = RootThemeInstaller(application)
    private val preferences = application.getSharedPreferences("settings", 0)
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
            pixelGoogleIdentity = Settings.Global.getInt(application.contentResolver, "originicons_pixel_google_identity", 0) == 1,
            powerButtonGemini = Settings.Global.getInt(application.contentResolver, "originicons_power_button_gemini", 0) == 1,
            hideVivoServiceError = Settings.Global.getInt(application.contentResolver, "originicons_hide_vivo_service_error", 0) == 1,
            aospVolumeBar = Settings.Global.getInt(application.contentResolver, "originicons_aosp_volume_bar", 0) == 1,
            aospControlCenter = Settings.Global.getInt(application.contentResolver, "originicons_aosp_control_center", 0) == 1,
            disablePrivateDnsWithVpn = Settings.Global.getInt(application.contentResolver, "originicons_disable_private_dns_vpn", 0) == 1,
            settingsAdaptiveTheme = Settings.Global.getInt(application.contentResolver, "originroottoolbox_settings_adaptive_theme", 0) == 1,
            settingsContainerGap = Settings.Global.getInt(application.contentResolver, "originroottoolbox_settings_container_gap", 8).toFloat(),
            aospStatusbar = Settings.Global.getInt(application.contentResolver, "originicons_aosp_statusbar", 0) == 1,
            statusbarWifiPack = Settings.Global.getInt(application.contentResolver, "originicons_statusbar_wifi_pack", 0),
            statusbarBatteryPack = Settings.Global.getInt(application.contentResolver, "originicons_statusbar_battery_pack", 0),
            statusbarMobilePack = Settings.Global.getInt(application.contentResolver, "originicons_statusbar_mobile_pack", 0),
            allowAllMediaPlayers = Settings.Global.getInt(application.contentResolver, "originicons_allow_all_media_players", 0) == 1,
            originIslandEnabled = preferences.getBoolean("origin_island_enabled", false),
            islandKeepAfterUnlock = preferences.getBoolean("origin_island_keep_after_unlock", true),
            islandPromotedRows = preferences.getBoolean("origin_island_promoted_rows", true),
            captureAllMedia = preferences.getBoolean("capture_all_media", false),
            captureAllProgress = preferences.getBoolean("capture_all_progress", false),
            showIntro = !preferences.getBoolean("intro_seen", false)
        )
    )
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
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

    fun reload() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, status = "Finding icon packs…", success = null)
            val loaded = withContext(Dispatchers.IO) { engine.findPacks() to loadIslandApps() }
            val packs = loaded.first
            mutableState.value = mutableState.value.copy(
                packs = packs,
                islandApps = loaded.second,
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

    fun setPixelGoogleIdentity(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setPixelGoogleIdentity(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(pixelGoogleIdentity = enabled)
        }
    }

    fun setPowerButtonGemini(enabled: Boolean) {
        val application = getApplication<Application>()
        if (enabled && !BundledAppInstaller.isInstalled(application, "com.parallelc.vistrigger")) {
            runCatching { BundledAppInstaller.requestInstall(application, "vis_trigger.apk", "VIS-Trigger.apk") }
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setPowerButtonGemini(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(powerButtonGemini = enabled)
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

    fun setAospControlCenter(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setAospControlCenter(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(aospControlCenter = enabled)
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

    fun setAllowAllMediaPlayers(enabled: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installer.setAllowAllMediaPlayers(enabled) }
            if (result.success) mutableState.value = mutableState.value.copy(allowAllMediaPlayers = enabled)
        }
    }

    fun setOriginIsland(enabled: Boolean) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Applying Origin Island settings…", success = null)
            val selected = mutableState.value.islandApps.filter { it.selected }.mapTo(linkedSetOf()) { it.packageName }
            val result = withContext(Dispatchers.IO) {
                installer.setOriginIsland(
                    enabled,
                    selected,
                    mutableState.value.islandKeepAfterUnlock,
                    mutableState.value.islandPromotedRows,
                    mutableState.value.captureAllMedia,
                    mutableState.value.captureAllProgress
                )
            }
            if (result.success) {
                preferences.edit().putBoolean("origin_island_enabled", enabled).apply()
                mutableState.value = mutableState.value.copy(originIslandEnabled = enabled)
                if (!enabled) {
                    getApplication<Application>().getSystemService(android.app.NotificationManager::class.java).cancelAll()
                }
            }
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Origin Island applied" else "Unable to apply Origin Island",
                success = result.success
            )
        }
    }

    fun setIslandKeepAfterUnlock(enabled: Boolean) {
        preferences.edit().putBoolean("origin_island_keep_after_unlock", enabled).apply()
        mutableState.value = mutableState.value.copy(islandKeepAfterUnlock = enabled)
        viewModelScope.launch(Dispatchers.IO) { installer.setOriginIslandOption("keep_after_unlock", enabled) }
    }

    fun setIslandPromotedRows(enabled: Boolean) {
        preferences.edit().putBoolean("origin_island_promoted_rows", enabled).apply()
        mutableState.value = mutableState.value.copy(islandPromotedRows = enabled)
        viewModelScope.launch(Dispatchers.IO) { installer.setOriginIslandOption("promoted_rows", enabled) }
    }

    fun setCaptureAllMedia(enabled: Boolean) {
        viewModelScope.launch {
            val previous = mutableState.value.captureAllMedia
            preferences.edit().putBoolean("capture_all_media", enabled).commit()
            mutableState.value = mutableState.value.copy(captureAllMedia = enabled)
            val result = withContext(Dispatchers.IO) { installer.setOriginIslandCaptureOption("media", enabled) }
            if (!result.success) {
                preferences.edit().putBoolean("capture_all_media", previous).apply()
                mutableState.value = mutableState.value.copy(captureAllMedia = previous)
            }
        }
    }

    fun setCaptureAllProgress(enabled: Boolean) {
        viewModelScope.launch {
            val previous = mutableState.value.captureAllProgress
            preferences.edit().putBoolean("capture_all_progress", enabled).commit()
            mutableState.value = mutableState.value.copy(captureAllProgress = enabled)
            val result = withContext(Dispatchers.IO) { installer.setOriginIslandCaptureOption("progress", enabled) }
            if (!result.success) {
                preferences.edit().putBoolean("capture_all_progress", previous).apply()
                mutableState.value = mutableState.value.copy(captureAllProgress = previous)
            }
        }
    }

    fun toggleIslandApp(packageName: String) {
        val updated = mutableState.value.islandApps.map {
            if (it.packageName == packageName) it.copy(selected = !it.selected) else it
        }
        mutableState.value = mutableState.value.copy(islandApps = updated)
        val selected = updated.filter { it.selected }.mapTo(linkedSetOf()) { it.packageName }
        preferences.edit().putStringSet("origin_island_apps", selected).apply()
        viewModelScope.launch(Dispatchers.IO) { installer.setOriginIslandPackages(selected) }
    }

    fun restartOriginIsland() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(applying = true, status = "Restarting Origin Island…")
            val result = withContext(Dispatchers.IO) { installer.restartOriginIsland() }
            mutableState.value = mutableState.value.copy(
                applying = false,
                status = if (result.success) "Origin Island restarted" else "Restart failed",
                success = result.success
            )
        }
    }

    fun openOriginIslandSettings() {
        viewModelScope.launch(Dispatchers.IO) { installer.openOriginIslandSettings() }
    }

    fun acceptIntro() {
        preferences.edit().putBoolean("intro_seen", true).apply()
        mutableState.value = mutableState.value.copy(showIntro = false)
        requestRoot()
    }

    fun requestRoot() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(status = "Requesting root access…")
            val result = withContext(Dispatchers.IO) { installer.check() }
            mutableState.value = mutableState.value.copy(
                status = if (result.success) "Root access granted" else "Root access denied",
                success = result.success
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

    private fun loadIslandApps(): List<IslandApp> {
        val application = getApplication<Application>()
        val selected = preferences.getStringSet("origin_island_apps", emptySet()).orEmpty()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= 33) {
            application.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            application.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return activities.distinctBy { it.activityInfo.packageName }.mapNotNull { resolveInfo ->
            runCatching {
                val packageName = resolveInfo.activityInfo.packageName
                IslandApp(
                    packageName,
                    resolveInfo.loadLabel(application.packageManager).toString(),
                    resolveInfo.loadIcon(application.packageManager),
                    packageName in selected
                )
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }
}
