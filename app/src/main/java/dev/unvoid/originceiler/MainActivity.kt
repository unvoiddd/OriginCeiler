package dev.unvoid.originceiler

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
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
            val colors = if (state.monetColors) {
                systemColors
            } else {
                systemColors.copy(
                    primary = defaultColors.primary,
                    onPrimary = defaultColors.onPrimary,
                    primaryVariant = defaultColors.primaryVariant,
                    onPrimaryVariant = defaultColors.onPrimaryVariant,
                    primaryContainer = defaultColors.primaryContainer,
                    onPrimaryContainer = defaultColors.onPrimaryContainer,
                    sliderKeyPoint = defaultColors.sliderKeyPoint,
                    sliderKeyPointForeground = defaultColors.sliderKeyPointForeground
                )
            }
            val density = LocalDensity.current
            val scale = (state.uiScale / 100f).coerceIn(0.8f, 1.25f)
            CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale * scale)) {
                MiuixTheme(colors = colors) {
                    OriginIconsApp(state, model)
                }
            }
        }
    }
}

@Composable
private fun OriginIconsApp(state: UiState, model: MainViewModel) {
    Scaffold(modifier = Modifier.fillMaxSize(), containerColor = MiuixTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            OriginToolboxNavigation(state, model)
            OverlayDialog(
                show = state.showIntro,
                title = "Root access required",
                summary = "Grant root access before using OriginCeiler. Apply icon packs while Minimalistic, Delicate, or another system icon style is active.",
                onDismissRequest = null
            ) {
                Button(onClick = model::acceptIntro, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant root access")
                }
            }
        }
    }
}

@Composable
internal fun IconPackApplyContainer(state: UiState, model: MainViewModel) {
    var showPopup by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
            val preview = state.coverage?.preview.orEmpty()
            if (preview.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(preview.take(8)) { drawable ->
                        DrawableImage(drawable, null, Modifier.size(58.dp).clip(RoundedCornerShape(15.dp)))
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
                    Text("Iconpack", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(selectedIconPackName(state), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
                }
                DropdownArrowEndAction(MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
                "Convert missing app icons to monochrome before applying the pack colour",
                state.autoRecolor,
                model::setAutoRecolor
            )
            ToggleRow(
                "Full APK to ITZ conversion",
                "Convert the entire APK, including apps that are not installed",
                state.fullConversion,
                model::setFullConversion
            )
            Spacer(Modifier.height(12.dp))
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
                    icon = { modifier -> Icon(Icons.Default.Home, null, modifier, tint = MiuixTheme.colorScheme.onSurfaceContainer) }
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
                    icon = { modifier -> Icon(Icons.Default.AutoAwesome, null, modifier, tint = MiuixTheme.colorScheme.primary) }
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
        IconSource.SYSTEM -> Icon(Icons.Default.Home, "System", modifier, tint = MiuixTheme.colorScheme.onSurfaceContainer)
        IconSource.PALETTE -> Icon(Icons.Default.Palette, "Custom palette", modifier, tint = paletteColor(state.paletteHue))
        IconSource.AOSP_THEMED -> Icon(Icons.Default.AutoAwesome, "AOSP themed", modifier, tint = MiuixTheme.colorScheme.primary)
        IconSource.PACK -> state.selected?.let { DrawableImage(it.icon, it.label, modifier.clip(RoundedCornerShape(9.dp))) }
            ?: Icon(Icons.Default.Home, "Icon pack", modifier, tint = MiuixTheme.colorScheme.onSurfaceContainer)
    }
}

@Composable
internal fun PaletteEditorContent(state: UiState, model: MainViewModel) {
    Column {
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

@Composable
internal fun ActionButtonsSection(state: UiState, apply: () -> Unit, restart: () -> Unit) {
    val context = LocalContext.current
    Column(
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
internal fun ControlCenterIconPackSelector(state: UiState, model: MainViewModel) {
    var showPopup by remember { mutableStateOf(false) }
    val names = listOf("Default", "iOS")
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
        Card(onClick = { showPopup = true }, modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (state.controlCenterIconPack == 0) Icons.Default.Tune else Icons.Default.AutoAwesome, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Control Center custom icons", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                    Text(names.getOrElse(state.controlCenterIconPack) { names[0] }, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
                }
                DropdownArrowEndAction(MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        OverlayListPopup(show = showPopup, alignment = PopupPositionProvider.Align.TopStart, maxHeight = 220.dp, onDismissRequest = { showPopup = false }) {
            ListPopupColumn {
                names.forEachIndexed { pack, name ->
                    DropdownImpl(
                        item = DropdownItem(text = name, icon = { modifier -> Icon(if (pack == 0) Icons.Default.Tune else Icons.Default.AutoAwesome, null, modifier, tint = MiuixTheme.colorScheme.primary) }),
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
internal fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 15.dp),
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
