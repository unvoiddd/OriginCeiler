package com.autonavi.minimap

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HsvHueSlider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.util.zip.ZipInputStream
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.MonetSystem) }
            MiuixTheme(controller = controller) {
                OriginIconsApp()
            }
        }
    }
}

@Composable
private fun OriginIconsApp(model: MainViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var islandVisible by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    Surface(modifier = Modifier.fillMaxSize(), color = MiuixTheme.colorScheme.surface) {
        if (islandVisible) {
            IslandSettingsScreen(state, model, onBack = { islandVisible = false })
        } else if (settingsVisible) {
            SettingsScreen(state, model, onBack = { settingsVisible = false })
        } else {
            MainScreen(state, model, selectedTab, { selectedTab = it }, { settingsVisible = true }, { islandVisible = true })
        }
    }
    OverlayDialog(
        show = state.showIntro,
        title = "Root access required",
        summary = "Grant root access before using OriginRootToolbox. Apply icon packs while Minimalistic, Delicate, or another system icon style is active.",
        onDismissRequest = null
    ) {
        Button(onClick = model::acceptIntro, modifier = Modifier.fillMaxWidth()) {
            Text("Grant root access")
        }
    }
}

@Composable
private fun MainScreen(
    state: UiState,
    model: MainViewModel,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onSettings: () -> Unit,
    onIslandSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = if (selectedTab == 0) "OriginRootToolbox" else "Tweaks",
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { onTabSelected(0) },
                    icon = Icons.Outlined.GridView,
                    label = "Icons"
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { onTabSelected(1) },
                    icon = Icons.Default.Tune,
                    label = "Tweaks"
                )
            }
        }
    ) { innerPadding ->
        if (selectedTab == 0) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                val preview = state.coverage?.preview.orEmpty()
                if (preview.isNotEmpty()) item { PreviewSection(preview) }
                item { IconPackSection(state, model) }
                if (state.source == IconSource.PALETTE) item { PaletteEditor(state, model) }
                if (state.source == IconSource.AOSP_THEMED) item { AospThemedEditor(state, model) }
                item { ActionSection(state, model::apply, model::restartLauncher) }
            }
        } else {
            TweaksScreen(state, model, onIslandSettings, Modifier.padding(innerPadding))
        }
    }
}

@Composable
private fun PreviewSection(preview: List<Drawable>) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        insideMargin = PaddingValues(16.dp)
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(preview.take(8)) { drawable ->
                DrawableImage(drawable, null, Modifier.size(58.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(15.dp)))
            }
        }
    }
}

@Composable
private fun IconPackSection(state: UiState, model: MainViewModel) {
    var showPopup by remember { mutableStateOf(false) }
    val selectedName = when (state.source) {
        IconSource.SYSTEM -> "System"
        IconSource.PALETTE -> "Custom palette"
        IconSource.AOSP_THEMED -> "AOSP themed"
        IconSource.PACK -> state.selected?.label ?: "Icon pack"
    }
    val optionCount = state.packs.size + 3
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Card(
            onClick = { showPopup = true },
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectedIconPackIcon(state, Modifier.size(36.dp))
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Iconpack", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(selectedName, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
                }
                DropdownArrowEndAction(MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        OverlayListPopup(
            show = showPopup,
            alignment = PopupPositionProvider.Align.TopStart,
            maxHeight = 460.dp,
            onDismissRequest = { showPopup = false }
        ) {
            ListPopupColumn {
                DropdownImpl(
                    item = DropdownItem(
                        text = "System",
                        icon = { modifier -> Icon(Icons.Default.Home, null, modifier, tint = MiuixTheme.colorScheme.onSurfaceContainer) }
                    ),
                    optionSize = optionCount,
                    isSelected = state.source == IconSource.SYSTEM,
                    index = 0,
                    onSelectedIndexChange = {
                        model.selectSystem()
                        showPopup = false
                    }
                )
                DropdownImpl(
                    item = DropdownItem(
                        text = "Custom palette",
                        icon = { modifier -> Icon(Icons.Default.Palette, null, modifier, tint = paletteColor(state.paletteHue)) }
                    ),
                    optionSize = optionCount,
                    isSelected = state.source == IconSource.PALETTE,
                    index = 1,
                    onSelectedIndexChange = {
                        model.selectPalette()
                        showPopup = false
                    }
                )
                DropdownImpl(
                    item = DropdownItem(
                        text = "AOSP themed",
                        icon = { modifier -> Icon(Icons.Default.AutoAwesome, null, modifier, tint = MiuixTheme.colorScheme.primary) }
                    ),
                    optionSize = optionCount,
                    isSelected = state.source == IconSource.AOSP_THEMED,
                    index = 2,
                    onSelectedIndexChange = {
                        model.selectAospThemed()
                        showPopup = false
                    }
                )
                state.packs.forEachIndexed { index, pack ->
                    DropdownImpl(
                        item = DropdownItem(
                            text = pack.label,
                            icon = { modifier -> DrawableImage(pack.icon, null, modifier.size(24.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))) }
                        ),
                        optionSize = optionCount,
                        isSelected = state.source == IconSource.PACK && state.selected?.packageName == pack.packageName,
                        index = index + 3,
                        onSelectedIndexChange = {
                            model.select(pack)
                            showPopup = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedIconPackIcon(state: UiState, modifier: Modifier) {
    when (state.source) {
        IconSource.SYSTEM -> Icon(Icons.Default.Home, "System", modifier, tint = MiuixTheme.colorScheme.onSurfaceContainer)
        IconSource.PALETTE -> Icon(Icons.Default.Palette, "Custom palette", modifier, tint = paletteColor(state.paletteHue))
        IconSource.AOSP_THEMED -> Icon(Icons.Default.AutoAwesome, "AOSP themed", modifier, tint = MiuixTheme.colorScheme.primary)
        IconSource.PACK -> state.selected?.let { DrawableImage(it.icon, it.label, modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))) }
            ?: Icon(Icons.Default.Home, "Icon pack", modifier, tint = MiuixTheme.colorScheme.onSurfaceContainer)
    }
}

@Composable
private fun PaletteEditor(state: UiState, model: MainViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        insideMargin = PaddingValues(18.dp)
    ) {
        Text("Colour", style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        HsvHueSlider(
            currentHue = state.paletteHue,
            onHueChanged = { model.setPaletteHue(it * 360f) }
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TouchApp, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.width(10.dp))
            Text("Shade", style = MiuixTheme.textStyles.body1)
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = state.paletteTone,
            onValueChange = model::setPaletteTone,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Dark details stay dark while the selected colour is blended over each monochrome icon.",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1
        )
    }
}

@Composable
private fun AospThemedEditor(state: UiState, model: MainViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        insideMargin = PaddingValues(18.dp)
    ) {
        Text("Colour", style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(aospMaterialColours) { colour ->
                val selected = state.aospTint == colour.value && state.aospFilter == (colour.value != null)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .border(if (selected) 3.dp else 1.dp, if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline, CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(if (colour.value == null) MiuixTheme.colorScheme.surfaceContainer else Color(colour.value))
                        .clickable { model.setAospTint(colour.value) },
                    contentAlignment = Alignment.Center
                ) {
                    if (colour.value == null) Text("×", style = MiuixTheme.textStyles.title3, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Icon size", style = MiuixTheme.textStyles.body1)
        Spacer(Modifier.height(8.dp))
        Slider(
            value = state.aospScale,
            onValueChange = model::setAospScale,
            valueRange = 0.65f..1.18f,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Invert colours", style = MiuixTheme.textStyles.body1)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Use a dark background and a light glyph before the colour filter is applied.",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1
                )
            }
            Switch(checked = state.aospInverted, onCheckedChange = model::setAospInverted)
        }
    }
}

private data class AospMaterialColour(val value: Int?)

private val aospMaterialColours = listOf(
    AospMaterialColour(null),
    AospMaterialColour(0xFFA5C8E8.toInt()),
    AospMaterialColour(0xFFC0C8FF.toInt()),
    AospMaterialColour(0xFFB9C7FF.toInt()),
    AospMaterialColour(0xFFA8D8FF.toInt()),
    AospMaterialColour(0xFFA4E8E0.toInt()),
    AospMaterialColour(0xFFB9F3B0.toInt()),
    AospMaterialColour(0xFFE3F4A7.toInt()),
    AospMaterialColour(0xFFFFE080.toInt()),
    AospMaterialColour(0xFFFFD0A8.toInt()),
    AospMaterialColour(0xFFFFB4AB.toInt()),
    AospMaterialColour(0xFFFFB1C7.toInt()),
    AospMaterialColour(0xFFF2B8FF.toInt()),
    AospMaterialColour(0xFFD8B2FF.toInt()),
    AospMaterialColour(0xFFD6C2D0.toInt()),
    AospMaterialColour(0xFFC8C8C8.toInt()),
    AospMaterialColour(0xFFB9F2FF.toInt()),
    AospMaterialColour(0xFF9FD8FF.toInt()),
    AospMaterialColour(0xFF8DE6C2.toInt()),
    AospMaterialColour(0xFFFFD6E8.toInt()),
    AospMaterialColour(0xFFFFD6A0.toInt())
)

@Composable
private fun ActionSection(state: UiState, apply: () -> Unit, restart: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Button(
                onClick = apply,
                enabled = !state.loading && !state.applying,
                modifier = Modifier.fillMaxSize(),
                minHeight = 52.dp,
                cornerRadius = 18.dp
            ) {
                Text(if (state.applying) "Applying…" else "Apply")
            }
            if (state.applying) {
                LinearProgressIndicator(
                    progress = state.progress,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    height = 4.dp
                )
            }
        }
        Button(onClick = restart, enabled = !state.applying, modifier = Modifier.fillMaxWidth(), minHeight = 50.dp) {
            Text("Restart Launcher")
        }
        Button(
            onClick = {
                context.startActivity(
                    Intent().setClassName("com.bbk.launcher2", "com.bbk.launcher2.settings.iconstyle.IconStyleDeformSetting")
                )
            },
            enabled = !state.applying,
            modifier = Modifier.fillMaxWidth(),
            minHeight = 50.dp
        ) {
            Text("Open settings")
        }
        Text(
            "If icons are not applied, switch to Minimalistic or Delicate and all icons will be applied.",
            modifier = Modifier.padding(horizontal = 10.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1
        )
    }
}

@Composable
private fun TweaksScreen(state: UiState, model: MainViewModel, onIslandSettings: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card {
                Text("SystemUI", modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
                ToggleRow(
                    "Custom statusbar",
                    "Enable independent SystemUI icon customisation",
                    state.aospStatusbar,
                    model::setExtendedStatusbar
                )
                if (state.aospStatusbar) {
                    StatusbarWifiPackSelector(state, model)
                    StatusbarBatteryPackSelector(state, model)
                    StatusbarMobilePackSelector(state, model)
                }
                ToggleRow(
                    "Hide Vivo service error",
                    "Hide all notifications from com.vivo.daemonService without stopping the service",
                    state.hideVivoServiceError,
                    model::setHideVivoServiceError
                )
                ToggleRow(
                    "AOSP volume bar",
                    "Use the built-in Android volume dialog instead of the Vivo volume plugin",
                    state.aospVolumeBar,
                    model::setAospVolumeBar
                )
                ToggleRow(
                    "AOSP Control Center",
                    "Use the built-in Android Quick Settings, brightness and media panel",
                    state.aospControlCenter,
                    model::setAospControlCenter
                )
            }
        }
        item {
            Card {
                Text("Android system", modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
                ToggleRow(
                    "Do not use Private DNS with VPN",
                    "Disable Private DNS while a VPN is active and restore the configured server afterwards",
                    state.disablePrivateDnsWithVpn,
                    model::setDisablePrivateDnsWithVpn
                )
            }
        }
        item {
            Card {
                Text("Settings", modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
                ToggleRow(
                    "Material adaptive theme for Settings",
                    "Replace section dividers with adaptive spacing between preference containers",
                    state.settingsAdaptiveTheme,
                    model::setSettingsAdaptiveTheme
                )
                if (state.settingsAdaptiveTheme) {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text("Container spacing: ${state.settingsContainerGap.toInt()} dp", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Slider(
                            value = state.settingsContainerGap,
                            onValueChange = model::setSettingsContainerGap,
                            valueRange = 0f..24f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Controls the vertical gap between Settings preference containers.",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1
                        )
                    }
                }
            }
        }
        item {
            Card {
                Text("Launcher", modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
                ToggleRow(
                    "Circle to Search",
                    "Long-press the navigation handle to launch MiCTS",
                    state.circleToSearch,
                    model::setCircleToSearch
                )
                ToggleRow(
                    "Pixel home layout",
                    "Move the icon grid up and pin a Pixel-style Google Search dock below it",
                    state.pixelHomeLayout,
                    model::setPixelHomeLayout
                )
                ToggleRow(
                    "Pixel identity for Google",
                    "Expose a Pixel 9 Pro XL identity only to the Google app for official Pixel widgets",
                    state.pixelGoogleIdentity,
                    model::setPixelGoogleIdentity
                )
            }
        }
        item {
            Card {
                Text("Origin services", modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
                ToggleRow(
                    "Origin Island",
                    "Recast selected notifications as native Origin Island cards",
                    state.originIslandEnabled,
                    model::setOriginIsland
                )
                Card(
                    onClick = onIslandSettings,
                    insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Text("Configure Origin Island", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                    Text(
                        "${state.islandApps.count { it.selected }} apps selected",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote1
                    )
                }
                ToggleRow(
                    "Power Button VIS Trigger",
                    "Open VIS Trigger instead of BlueLM",
                    state.powerButtonGemini,
                    model::setPowerButtonGemini
                )
                ToggleRow(
                    "Allow all media players",
                    "Bypass Origin Player package filters for active media sessions",
                    state.allowAllMediaPlayers,
                    model::setAllowAllMediaPlayers
                )
            }
        }
        item {
            Text(
                "The module also blocks Toast notifications created by BlueLM.",
                modifier = Modifier.padding(horizontal = 10.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1
            )
        }
    }
}

@Composable
private fun IslandSettingsScreen(state: UiState, model: MainViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "Origin Island",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card {
                    ToggleRow(
                        "Origin Island recaster",
                        "Recast selected notifications with the OriginIsland payload",
                        state.originIslandEnabled,
                        model::setOriginIsland
                    )
                    ToggleRow(
                        "Capture all media sessions",
                        "Capture music and audio players even when their apps are not selected",
                        state.captureAllMedia,
                        model::setCaptureAllMedia
                    )
                    ToggleRow(
                        "Capture all progress notifications",
                        "Capture downloads and other progress notifications from every app",
                        state.captureAllProgress,
                        model::setCaptureAllProgress
                    )
                }
            }
            item {
                Card(insideMargin = PaddingValues(18.dp)) {
                    Text("Notification recaster", style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Source notifications are kept alive for updates while their rows are filtered from System UI.",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote1
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = model::openOriginIslandSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Origin Island settings")
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = model::restartOriginIsland, enabled = !state.applying, modifier = Modifier.fillMaxWidth()) {
                        Text("Reconnect recaster")
                    }
                }
            }
            item {
                Text(
                    "Applications",
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(state.islandApps, key = { it.packageName }) { app ->
                Card(
                    onClick = { model.toggleIslandApp(app.packageName) },
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 11.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DrawableImage(app.icon, app.label, Modifier.size(46.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)))
                        Spacer(Modifier.width(13.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                            Text(app.packageName, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote2)
                        }
                        Switch(checked = app.selected, onCheckedChange = { model.toggleIslandApp(app.packageName) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: UiState, model: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "Settings",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card {
                    ToggleRow(
                        "Full conversion",
                        "Convert the entire icon pack, including apps that are not installed",
                        state.fullConversion,
                        model::setFullConversion
                    )
                    ToggleRow(
                        "Auto-colour missing icons",
                        "Convert missing app icons to monochrome before applying the pack colour",
                        state.autoRecolor,
                        model::setAutoRecolor
                    )
                }
            }
            item {
                Card(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/oosthemes"))) },
                    insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    Text("By @oosthemes", style = MiuixTheme.textStyles.title4)
                    Text("Open Telegram channel", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
                }
            }
        }
    }
}

@Composable
private fun StatusbarWifiPackSelector(state: UiState, model: MainViewModel) {
    var showPopup by remember { mutableStateOf(false) }
    val selectedName = wifiPackName(state.statusbarWifiPack)
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
        Card(onClick = { showPopup = true }, modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.statusbarWifiPack == 0) Icon(Icons.Default.Tune, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary)
                else WifiPackPreview(state.statusbarWifiPack, Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Wi-Fi icon pack", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                    Text(selectedName, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
                }
                DropdownArrowEndAction(MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        OverlayListPopup(show = showPopup, alignment = PopupPositionProvider.Align.TopStart, maxHeight = 460.dp, onDismissRequest = { showPopup = false }) {
            ListPopupColumn {
                (0..18).forEach { pack ->
                    val name = wifiPackName(pack)
                    DropdownImpl(
                        item = DropdownItem(text = name, icon = { modifier -> if (pack == 0) Icon(Icons.Default.Tune, null, modifier, tint = MiuixTheme.colorScheme.primary) else WifiPackPreview(pack, modifier) }),
                        optionSize = 19,
                        isSelected = state.statusbarWifiPack == pack,
                        index = pack,
                        onSelectedIndexChange = {
                            model.setStatusbarWifiPack(pack)
                            showPopup = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WifiPackPreview(pack: Int, modifier: Modifier) {
    val context = LocalContext.current
    val tint = MiuixTheme.colorScheme.onSurfaceContainer.toArgb()
    var level by remember(pack) { mutableStateOf(0) }
    LaunchedEffect(pack) {
        while (true) {
            delay(550)
            level = (level + 1) % 5
        }
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE; setColorFilter(tint); setImageDrawable(loadWifiPreview(it, pack, level)) } },
        update = { it.setColorFilter(tint); it.setImageDrawable(loadWifiPreview(context, pack, level)) },
        modifier = modifier
    )
}

private fun loadWifiPreview(context: android.content.Context, pack: Int, level: Int): Drawable? = runCatching {
    val path = "WIFI$pack/${level.coerceIn(0, 4)}.png"
    val bytes = context.assets.open("statusbar_wifi_packs.zip").use { input ->
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == path }?.let { zip.readBytes() }
        }
    } ?: return null
    BitmapDrawable(context.resources, BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
}.getOrNull()

private fun wifiPackName(pack: Int) = listOf(
    "Default", "Shiyang (时央)", "Stacked bars", "Solid drop", "Rounded arcs", "Router", "Rounded shield", "Angular", "Dot matrix", "Minimal arcs", "Orbit", "Solid wedge", "Compact wedge", "Soft wedge", "Wide wedge", "Leaf", "Bold arcs", "Ribbon", "iOS"
).getOrElse(pack) { "Default" }

@Composable
private fun StatusbarBatteryPackSelector(state: UiState, model: MainViewModel) {
    var showPopup by remember { mutableStateOf(false) }
    val selectedName = batteryPackName(state.statusbarBatteryPack)
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
        Card(onClick = { showPopup = true }, modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.statusbarBatteryPack == 0) Icon(Icons.Default.BatteryFull, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary)
                else BatteryPackPreview(state.statusbarBatteryPack, Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Battery icon pack", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                    Text(selectedName, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
                }
                DropdownArrowEndAction(MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        OverlayListPopup(show = showPopup, alignment = PopupPositionProvider.Align.TopStart, maxHeight = 220.dp, onDismissRequest = { showPopup = false }) {
            ListPopupColumn {
                (0..2).forEach { pack ->
                    DropdownImpl(
                        item = DropdownItem(text = batteryPackName(pack), icon = { modifier -> if (pack == 0) Icon(Icons.Default.BatteryFull, null, modifier, tint = MiuixTheme.colorScheme.primary) else BatteryPackPreview(pack, modifier) }),
                        optionSize = 3,
                        isSelected = state.statusbarBatteryPack == pack,
                        index = pack,
                        onSelectedIndexChange = {
                            model.setStatusbarBatteryPack(pack)
                            showPopup = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BatteryPackPreview(pack: Int, modifier: Modifier) {
    val context = LocalContext.current
    val tint = MiuixTheme.colorScheme.onSurfaceContainer.toArgb()
    var level by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            level = (level + 1) % 20
        }
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE; setColorFilter(tint); setImageDrawable(loadBatteryPreview(it, pack, level)) } },
        update = { it.setColorFilter(tint); it.setImageDrawable(loadBatteryPreview(context, pack, level)) },
        modifier = modifier
    )
}

private fun loadBatteryPreview(context: android.content.Context, pack: Int, level: Int): Drawable? = runCatching {
    val path = "PACK$pack/vivo_battery_20_${level.coerceIn(0, 19).toString().padStart(2, '0')}_white.png"
    val bytes = context.assets.open("statusbar_battery_packs.zip").use { input ->
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == path }?.let { zip.readBytes() }
        }
    } ?: return null
    BitmapDrawable(context.resources, BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
}.getOrNull()

private fun batteryPackName(pack: Int) = listOf("Default", "Shiyang (时央)", "iOS").getOrElse(pack) { "Default" }

@Composable
private fun StatusbarMobilePackSelector(state: UiState, model: MainViewModel) {
    var showPopup by remember { mutableStateOf(false) }
    val selectedName = mobilePackName(state.statusbarMobilePack)
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
        Card(onClick = { showPopup = true }, modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.statusbarMobilePack == 0) Icon(Icons.Default.Tune, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary)
                else MobilePackPreview(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("LTE signal icon pack", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                    Text(selectedName, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
                }
                DropdownArrowEndAction(MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        OverlayListPopup(show = showPopup, alignment = PopupPositionProvider.Align.TopStart, maxHeight = 220.dp, onDismissRequest = { showPopup = false }) {
            ListPopupColumn {
                (0..1).forEach { pack ->
                    DropdownImpl(
                        item = DropdownItem(text = mobilePackName(pack), icon = { modifier -> if (pack == 0) Icon(Icons.Default.Tune, null, modifier, tint = MiuixTheme.colorScheme.primary) else MobilePackPreview(modifier) }),
                        optionSize = 2,
                        isSelected = state.statusbarMobilePack == pack,
                        index = pack,
                        onSelectedIndexChange = {
                            model.setStatusbarMobilePack(pack)
                            showPopup = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MobilePackPreview(modifier: Modifier) {
    val context = LocalContext.current
    val tint = MiuixTheme.colorScheme.onSurfaceContainer.toArgb()
    var level by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            level = (level + 1) % 5
        }
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE; setColorFilter(tint); setImageDrawable(loadMobilePreview(it, level)) } },
        update = { it.setColorFilter(tint); it.setImageDrawable(loadMobilePreview(context, level)) },
        modifier = modifier
    )
}

private fun loadMobilePreview(context: android.content.Context, level: Int): Drawable? = runCatching {
    val path = "PACK1/vivo_signal_strength_${level.coerceIn(0, 4)}.png"
    val bytes = context.assets.open("statusbar_mobile_packs.zip").use { input ->
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == path }?.let { zip.readBytes() }
        }
    } ?: return null
    BitmapDrawable(context.resources, BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
}.getOrNull()

private fun mobilePackName(pack: Int) = listOf("Default", "Shiyang (时央)").getOrElse(pack) { "Default" }

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(description, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
        }
        Spacer(Modifier.width(14.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DrawableImage(drawable: Drawable, description: String?, modifier: Modifier = Modifier) {
    Image(
        bitmap = drawableBitmap(drawable).asImageBitmap(),
        contentDescription = description,
        modifier = modifier,
        contentScale = ContentScale.FillBounds
    )
}

private fun drawableBitmap(drawable: Drawable): Bitmap {
    val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, 192, 192)
    drawable.draw(Canvas(bitmap))
    return bitmap
}

private fun paletteColor(hue: Float): Color {
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.92f, 1f)))
}
