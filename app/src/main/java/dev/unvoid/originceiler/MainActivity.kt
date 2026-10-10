package dev.unvoid.originceiler

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.graphics.BitmapFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme as materialDarkColorScheme
import androidx.compose.material3.lightColorScheme as materialLightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import java.util.zip.ZipInputStream
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HsvHueSlider
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.platformDynamicColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: MainViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (state.appThemeMode) {
                2 -> false
                1 -> true
                else -> systemDark
            }
            val systemColors = platformDynamicColors(dark)
            val defaultColors = if (dark) darkColorScheme() else lightColorScheme()
            val colors = if (state.monetColors) systemColors else defaultColors
            val density = LocalDensity.current
            val context = LocalContext.current
            val appColors = if (state.monetColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else if (dark) {
                materialDarkColorScheme(
                    primary = Color(0xFF2785FF), onPrimary = Color.White,
                    primaryContainer = Color(0xFF223E63), onPrimaryContainer = Color(0xFFD5E5FF),
                    background = Color(0xFF171717), surface = Color(0xFF171717),
                    surfaceContainer = Color(0xFF202020), onSurface = Color(0xE6FFFFFF),
                    onSurfaceVariant = Color(0xA6FFFFFF), outlineVariant = Color(0x26FFFFFF)
                )
            } else {
                materialLightColorScheme(
                    primary = Color(0xFF0061CC), onPrimary = Color.White,
                    primaryContainer = Color(0xFFD5E5FF), onPrimaryContainer = Color(0xFF001B3D),
                    background = Color(0xFFF5F5F5), surface = Color(0xFFF5F5F5),
                    surfaceContainer = Color.White
                )
            }
            val scale = (state.uiScale / 100f).coerceIn(0.8f, 1.25f)
            CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale * scale)) {
                MiuixTheme(colors = colors) {
                    MaterialTheme(colorScheme = appColors) {
                        OriginIconsApp(state, model)
                    }
                }
            }
        }
    }
}

@Composable
private fun OriginIconsApp(state: UiState, model: MainViewModel) {
    Scaffold(modifier = Modifier.fillMaxSize(), containerColor = OriginBgDark) {
        Box(modifier = Modifier.fillMaxSize()) {
            OriginToolboxNavigation(state, model)
            OverlayDialog(
                show = state.showIntro,
                title = "Root access required",
                summary = "Grant root access before using OriginCeiler. Apply icon packs while Minimalistic, Delicate, or another system icon style is active.",
                onDismissRequest = null
            ) {
                OriginButton(
                    text = "Grant root access",
                    onClick = model::acceptIntro,
                    modifier = Modifier.fillMaxWidth(),
                    primary = true
                )
            }
        }
    }
}

@Composable
internal fun IconPackApplyContainer(state: UiState, model: MainViewModel) {
    var showPopup by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OriginCard(modifier = Modifier.fillMaxWidth(), insidePadding = PaddingValues(18.dp)) {
            val preview = state.coverage?.preview.orEmpty()
            if (preview.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(preview.take(8)) { drawable ->
                        DrawableImage(drawable, null, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showPopup = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                SelectedIconPackIcon(state, Modifier.size(36.dp))
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Iconpack", color = OriginTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(selectedIconPackName(state), color = OriginTextSecondary, fontSize = 13.sp)
                }
                DropdownArrowEndAction(OriginTextSecondary)
            }
            if (state.source == IconSource.PALETTE) {
                Spacer(Modifier.height(18.dp))
                PaletteEditorContent(state, model)
            }
            if (state.source == IconSource.AOSP_THEMED) {
                Spacer(Modifier.height(18.dp))
                AospThemedEditorContent(state, model)
            }
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                "Auto-colour missing icons",
                "Colour missing app icons using the average icon pack colour",
                state.autoRecolor,
                model::setAutoRecolor
            )
            if (state.source == IconSource.PACK) {
                ToggleRow(
                    "Replace icon backgrounds",
                    "Place adaptive icon foregrounds on the pack's iconmask.png when available",
                    state.replaceIconBackground,
                    model::setReplaceIconBackground
                )
            } else {
                ToggleRow(
                    "Full APK to ITZ conversion",
                    "Convert the entire APK, including apps that are not installed",
                    state.fullConversion,
                    model::setFullConversion
                )
            }
            Spacer(Modifier.height(14.dp))
            ActionButtonsSection(state, model::apply, model::restartLauncher)
        }
        IconPackPopup(showPopup, state, model) { showPopup = false }
    }
}

private fun selectedIconPackName(state: UiState): String = when (state.source) {
    IconSource.SYSTEM -> "System"
    IconSource.PALETTE -> "Custom palette"
    IconSource.AOSP_THEMED -> "AOSP themed"
    IconSource.PACK -> state.selected?.label ?: "Icon pack"
}

@Composable
internal fun IconPackPopup(show: Boolean, state: UiState, model: MainViewModel, onDismiss: () -> Unit) {
    val optionCount = state.packs.size + 3
    OverlayListPopup(
        show = show,
        alignment = PopupPositionProvider.Align.TopStart,
        maxHeight = 460.dp,
        onDismissRequest = onDismiss
    ) {
        ListPopupColumn {
            DropdownImpl(
                item = DropdownItem(
                    text = "System",
                    icon = { modifier -> Icon(Icons.Default.Home, null, modifier, tint = OriginAccent) }
                ),
                optionSize = optionCount,
                isSelected = state.source == IconSource.SYSTEM,
                index = 0,
                onSelectedIndexChange = {
                    model.selectSystem()
                    onDismiss()
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
                    onDismiss()
                }
            )
            DropdownImpl(
                item = DropdownItem(
                    text = "AOSP themed",
                    icon = { modifier -> Icon(Icons.Default.AutoAwesome, null, modifier, tint = OriginAccent) }
                ),
                optionSize = optionCount,
                isSelected = state.source == IconSource.AOSP_THEMED,
                index = 2,
                onSelectedIndexChange = {
                    model.selectAospThemed()
                    onDismiss()
                }
            )
            state.packs.forEachIndexed { index, pack ->
                DropdownImpl(
                    item = DropdownItem(
                        text = pack.label,
                        icon = { modifier -> DrawableImage(pack.icon, null, modifier.size(24.dp).clip(RoundedCornerShape(8.dp))) }
                    ),
                    optionSize = optionCount,
                    isSelected = state.source == IconSource.PACK && state.selected?.packageName == pack.packageName,
                    index = index + 3,
                    onSelectedIndexChange = {
                        model.select(pack)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
internal fun SelectedIconPackIcon(state: UiState, modifier: Modifier) {
    when (state.source) {
        IconSource.SYSTEM -> Icon(Icons.Default.Home, "System", modifier, tint = OriginAccent)
        IconSource.PALETTE -> Icon(Icons.Default.Palette, "Custom palette", modifier, tint = paletteColor(state.paletteHue))
        IconSource.AOSP_THEMED -> Icon(Icons.Default.AutoAwesome, "AOSP themed", modifier, tint = OriginAccent)
        IconSource.PACK -> state.selected?.let { DrawableImage(it.icon, it.label, modifier.clip(RoundedCornerShape(9.dp))) }
            ?: Icon(Icons.Default.Home, "Icon pack", modifier, tint = OriginAccent)
    }
}

@Composable
internal fun PaletteEditorContent(state: UiState, model: MainViewModel) {
    Column {
        Text("Colour", color = OriginTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        HsvHueSlider(
            currentHue = state.paletteHue,
            onHueChanged = { model.setPaletteHue(it * 360f) }
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TouchApp, null, Modifier.size(22.dp), tint = OriginTextSecondary)
            Spacer(Modifier.width(10.dp))
            Text("Shade", color = OriginTextPrimary, fontSize = 15.sp)
        }
        Spacer(Modifier.height(8.dp))
        OriginSlider(
            value = state.paletteTone,
            onValueChange = model::setPaletteTone,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Dark details stay dark while the selected colour is blended over each monochrome icon.",
            color = OriginTextSecondary,
            fontSize = 12.sp
        )
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
internal fun AospThemedEditorContent(state: UiState, model: MainViewModel) {
    Column {
        Text("Colour", color = OriginTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(aospMaterialColours) { colour ->
                val selected = state.aospTint == colour.value && state.aospFilter == (colour.value != null)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .border(if (selected) 3.dp else 1.dp, if (selected) OriginAccent else OriginCardStroke, CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(if (colour.value == null) OriginCardBg else Color(colour.value))
                        .clickable { model.setAospTint(colour.value) },
                    contentAlignment = Alignment.Center
                ) {
                    if (colour.value == null) Text("×", fontSize = 18.sp, color = OriginTextSecondary)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Icon size", color = OriginTextPrimary, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        OriginSlider(
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
                Text("Invert colours", color = OriginTextPrimary, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Use a dark background and a light glyph before the colour filter is applied.",
                    color = OriginTextSecondary,
                    fontSize = 12.sp
                )
            }
            OriginSwitch(
                checked = state.aospInverted,
                onCheckedChange = model::setAospInverted
            )
        }
    }
}

@Composable
internal fun ActionButtonsSection(state: UiState, apply: () -> Unit, restart: () -> Unit) {
    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OriginButton(
                text = if (state.applying) "Applying…" else "Apply",
                onClick = apply,
                enabled = !state.loading && !state.applying,
                modifier = Modifier.fillMaxWidth(),
                primary = true
            )
            if (state.applying) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)),
                    color = Color.White,
                    trackColor = Color.Transparent
                )
            }
        }
        OriginButton(
            text = "Restart Launcher",
            onClick = restart,
            enabled = !state.applying,
            modifier = Modifier.fillMaxWidth(),
            primary = false
        )
        OriginButton(
            text = "Open settings",
            onClick = {
                context.startActivity(
                    Intent().setClassName("com.bbk.launcher2", "com.bbk.launcher2.settings.iconstyle.IconStyleDeformSetting")
                )
            },
            enabled = !state.applying,
            modifier = Modifier.fillMaxWidth(),
            primary = false
        )
        Text(
            "If icons are not applied, switch to Minimalistic or Delicate and all icons will be applied.",
            modifier = Modifier.padding(horizontal = 6.dp),
            color = OriginTextSecondary,
            fontSize = 12.sp
        )
    }
}

@Composable
internal fun ControlCenterIconPackSelector(state: UiState, model: MainViewModel) {
    var showPopup by remember { mutableStateOf(false) }
    val names = listOf("Default", "iOS")
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OriginCard(
            onClick = { showPopup = true },
            modifier = Modifier.fillMaxWidth(),
            insidePadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (state.controlCenterIconPack == 0) Icons.Default.Tune else Icons.Default.AutoAwesome,
                    null,
                    Modifier.size(24.dp),
                    tint = OriginAccent
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Control Center custom icons", color = OriginTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(names.getOrElse(state.controlCenterIconPack) { names[0] }, color = OriginTextSecondary, fontSize = 13.sp)
                }
                DropdownArrowEndAction(OriginTextSecondary)
            }
        }
        OverlayListPopup(show = showPopup, alignment = PopupPositionProvider.Align.TopStart, maxHeight = 220.dp, onDismissRequest = { showPopup = false }) {
            ListPopupColumn {
                names.forEachIndexed { pack, name ->
                    DropdownImpl(
                        item = DropdownItem(text = name, icon = { modifier -> Icon(if (pack == 0) Icons.Default.Tune else Icons.Default.AutoAwesome, null, modifier, tint = OriginAccent) }),
                        optionSize = names.size,
                        isSelected = state.controlCenterIconPack == pack,
                        index = pack,
                        onSelectedIndexChange = {
                            model.setControlCenterIconPack(pack)
                            showPopup = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun StatusbarWifiPackSelector(state: UiState, model: MainViewModel) {
    var showPopup by remember { mutableStateOf(false) }
    val selectedName = wifiPackName(state.statusbarWifiPack)
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OriginCard(
            onClick = { showPopup = true },
            modifier = Modifier.fillMaxWidth(),
            insidePadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.statusbarWifiPack == 0) Icon(Icons.Default.Wifi, null, Modifier.size(24.dp), tint = OriginAccent)
                else WifiPackPreview(state.statusbarWifiPack, Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Wi-Fi icon pack", color = OriginTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(selectedName, color = OriginTextSecondary, fontSize = 13.sp)
                }
                DropdownArrowEndAction(OriginTextSecondary)
            }
        }
        OverlayListPopup(show = showPopup, alignment = PopupPositionProvider.Align.TopStart, maxHeight = 460.dp, onDismissRequest = { showPopup = false }) {
            ListPopupColumn {
                (0..18).forEach { pack ->
                    val name = wifiPackName(pack)
                    DropdownImpl(
                        item = DropdownItem(text = name, icon = { modifier -> if (pack == 0) Icon(Icons.Default.Wifi, null, modifier, tint = OriginAccent) else WifiPackPreview(pack, modifier) }),
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
    var level by remember(pack) { mutableStateOf(0) }
    LaunchedEffect(pack) {
        while (true) {
            delay(550)
            level = (level + 1) % 5
        }
    }
    val bitmap = remember(pack, level) { loadWifiPreview(context, pack, level) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            colorFilter = ColorFilter.tint(OriginTextPrimary)
        )
    } else {
        Icon(Icons.Default.Wifi, null, modifier, tint = OriginAccent)
    }
}

private fun loadWifiPreview(context: android.content.Context, pack: Int, level: Int): Bitmap? = runCatching {
    val path = "WIFI$pack/${level.coerceIn(0, 4)}.png"
    val bytes = context.assets.open("statusbar_wifi_packs.zip").use { input ->
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == path }?.let { zip.readBytes() }
        }
    } ?: return null
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

private fun wifiPackName(pack: Int) = listOf(
    "Default", "Shiyang (时央)", "Stacked bars", "Solid drop", "Rounded arcs", "Router", "Rounded shield", "Angular", "Dot matrix", "Minimal arcs", "Orbit", "Solid wedge", "Compact wedge", "Soft wedge", "Wide wedge", "Leaf", "Bold arcs", "Ribbon", "iOS"
).getOrElse(pack) { "Default" }

@Composable
internal fun StatusbarBatteryPackSelector(state: UiState, model: MainViewModel) {
    var showPopup by remember { mutableStateOf(false) }
    val selectedName = batteryPackName(state.statusbarBatteryPack)
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OriginCard(
            onClick = { showPopup = true },
            modifier = Modifier.fillMaxWidth(),
            insidePadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.statusbarBatteryPack == 0) Icon(Icons.Default.BatteryFull, null, Modifier.size(24.dp), tint = OriginAccent)
                else BatteryPackPreview(state.statusbarBatteryPack, Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Battery icon pack", color = OriginTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(selectedName, color = OriginTextSecondary, fontSize = 13.sp)
                }
                DropdownArrowEndAction(OriginTextSecondary)
            }
        }
        OverlayListPopup(show = showPopup, alignment = PopupPositionProvider.Align.TopStart, maxHeight = 220.dp, onDismissRequest = { showPopup = false }) {
            ListPopupColumn {
                (0..2).forEach { pack ->
                    DropdownImpl(
                        item = DropdownItem(text = batteryPackName(pack), icon = { modifier -> if (pack == 0) Icon(Icons.Default.BatteryFull, null, modifier, tint = OriginAccent) else BatteryPackPreview(pack, modifier) }),
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
    var level by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            level = (level + 1) % 20
        }
    }
    val bitmap = remember(pack, level) { loadBatteryPreview(context, pack, level) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            colorFilter = ColorFilter.tint(OriginTextPrimary)
        )
    } else {
        Icon(Icons.Default.BatteryFull, null, modifier, tint = OriginAccent)
    }
}

private fun loadBatteryPreview(context: android.content.Context, pack: Int, level: Int): Bitmap? = runCatching {
    val path = "PACK$pack/vivo_battery_20_${level.coerceIn(0, 19).toString().padStart(2, '0')}_white.png"
    val bytes = context.assets.open("statusbar_battery_packs.zip").use { input ->
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == path }?.let { zip.readBytes() }
        }
    } ?: return null
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

private fun batteryPackName(pack: Int) = listOf("Default", "Shiyang (时央)", "iOS").getOrElse(pack) { "Default" }

@Composable
internal fun StatusbarMobilePackSelector(state: UiState, model: MainViewModel) {
    var showPopup by remember { mutableStateOf(false) }
    val selectedName = mobilePackName(state.statusbarMobilePack)
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OriginCard(
            onClick = { showPopup = true },
            modifier = Modifier.fillMaxWidth(),
            insidePadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.statusbarMobilePack == 0) Icon(Icons.Default.SignalCellularAlt, null, Modifier.size(24.dp), tint = OriginAccent)
                else MobilePackPreview(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("LTE signal icon pack", color = OriginTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(selectedName, color = OriginTextSecondary, fontSize = 13.sp)
                }
                DropdownArrowEndAction(OriginTextSecondary)
            }
        }
        OverlayListPopup(show = showPopup, alignment = PopupPositionProvider.Align.TopStart, maxHeight = 220.dp, onDismissRequest = { showPopup = false }) {
            ListPopupColumn {
                (0..1).forEach { pack ->
                    DropdownImpl(
                        item = DropdownItem(text = mobilePackName(pack), icon = { modifier -> if (pack == 0) Icon(Icons.Default.SignalCellularAlt, null, modifier, tint = OriginAccent) else MobilePackPreview(modifier) }),
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
    var level by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            level = (level + 1) % 5
        }
    }
    val bitmap = remember(level) { loadMobilePreview(context, level) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            colorFilter = ColorFilter.tint(OriginTextPrimary)
        )
    } else {
        Icon(Icons.Default.SignalCellularAlt, null, modifier, tint = OriginAccent)
    }
}

private fun loadMobilePreview(context: android.content.Context, level: Int): Bitmap? = runCatching {
    val path = "PACK1/vivo_signal_strength_${level.coerceIn(0, 4)}.png"
    val bytes = context.assets.open("statusbar_mobile_packs.zip").use { input ->
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == path }?.let { zip.readBytes() }
        }
    } ?: return null
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

private fun mobilePackName(pack: Int) = listOf("Default", "Shiyang (时央)").getOrElse(pack) { "Default" }

@Composable
internal fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = OriginTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(description, color = OriginTextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(14.dp))
        OriginSwitch(
            checked = checked,
            onCheckedChange = onChange
        )
    }
}

@Composable
internal fun DrawableImage(drawable: Drawable, description: String?, modifier: Modifier = Modifier) {
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
