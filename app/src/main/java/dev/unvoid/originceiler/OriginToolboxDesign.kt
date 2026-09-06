package dev.unvoid.originceiler

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class AppScope(
    val id: String,
    val title: String,
    val summary: String,
    val packageName: String
)

private val appScopes = listOf(
    AppScope("system_ui", "System UI", "Status bar and volume", "com.android.systemui"),
    AppScope("system_ui_plugin", "System UI Plugin", "Notifications and Control Center", "com.vivo.systemuiplugin"),
    AppScope("android", "Android System", "Private DNS and VPN behavior", "android"),
    AppScope("launcher", "BBK Launcher", "Icon packs, themes and home screen", "com.bbk.launcher2"),
    AppScope("google", "Google", "Gemini access and assistant behavior", "com.google.android.googlequicksearchbox"),
    AppScope("gboard", "Gboard", "Keyboard background and frosted blur", "com.google.android.inputmethod.latin"),
    AppScope("bluelm", "BlueLM", "Power button assistant and VIS Trigger", "com.vivo.ai.copilot"),
    AppScope("player", "Origin Player", "Media-session package policies", "com.vivo.musicwidgetmix"),
    AppScope("installer", "Package Installer", "System installer and third-party handoff", "com.android.packageinstaller"),
    AppScope("island", "Origin Island", "Live Updates and flashlight controls", "com.vivo.systemuiplugin")
)

private data class MainTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val mainTabs = listOf(
    MainTab("Home", Icons.Default.Home),
    MainTab("Modules", Icons.Default.Apps),
    MainTab("Settings", Icons.Default.Settings)
)

@Composable
private fun toolboxCardColors(): CardColors = CardDefaults.defaultColors(
    color = MiuixTheme.colorScheme.surfaceContainerHigh,
    contentColor = MiuixTheme.colorScheme.onSurfaceContainerHigh
)

@Composable
internal fun OriginToolboxNavigation(state: UiState, model: MainViewModel) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedScope = appScopes.firstOrNull { it.id == selectedId }
    val pagerState = rememberPagerState(pageCount = { mainTabs.size })
    val coroutineScope = rememberCoroutineScope()
    BackHandler(selectedScope != null) { selectedId = null }

    val homeListState = rememberLazyListState()
    val modulesListState = rememberLazyListState()
    val settingsListState = rememberLazyListState()
    val navigationBackdrop = rememberLayerBackdrop()

    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        AnimatedContent(
            targetState = selectedScope,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 5 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 5 } + fadeOut())
                }
            },
            label = "screen",
            modifier = Modifier.fillMaxSize().layerBackdrop(navigationBackdrop)
        ) { scope ->
            if (scope == null) {
                HorizontalPager(state = pagerState, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
                    when (page) {
                        0 -> HomeScreen(state, homeListState) { coroutineScope.launch { pagerState.animateScrollToPage(1) } }
                        1 -> ModulesScreen(modulesListState) { selectedId = it }
                        else -> SettingsScreen(state, settingsListState, model)
                    }
                }
            } else {
                ScopeScreen(scope, state, model) { selectedId = null }
            }
        }

        AnimatedVisibility(
            visible = selectedScope == null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            LiquidBottomTabs(
                selectedTabIndex = { pagerState.currentPage },
                onTabSelected = { coroutineScope.launch { pagerState.animateScrollToPage(it) } },
                backdrop = navigationBackdrop,
                accentColor = MiuixTheme.colorScheme.primary,
                containerColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                glassEnabled = state.liquidGlassBar,
                tabsCount = mainTabs.size,
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .widthIn(max = 340.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 6.dp)
            ) {
                mainTabs.forEachIndexed { index, tab ->
                    LiquidBottomTab(onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } }) {
                        Icon(tab.icon, tab.title, Modifier.size(21.dp))
                        Text(tab.title, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberScrollingUp(listState: LazyListState): State<Boolean> {
    val previousIndex = remember { mutableStateOf(listState.firstVisibleItemIndex) }
    val previousOffset = remember { mutableStateOf(listState.firstVisibleItemScrollOffset) }
    val scrollingUp = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            val previous = previousIndex.value to previousOffset.value
            when {
                index < previous.first || (index == previous.first && offset < previous.second) -> scrollingUp.value = true
                index > previous.first || (index == previous.first && offset > previous.second) -> scrollingUp.value = false
            }
            previousIndex.value = index
            previousOffset.value = offset
        }
    }
    return scrollingUp
}

@Composable
private fun HomeScreen(state: UiState, listState: LazyListState, onOpenModules: () -> Unit) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = 28.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item { SectionTitle("OriginCeiler", "OriginOS customization module") }
        item { ModuleStatusCard(state.moduleActive, onOpenModules) }
        item { SectionTitle("System information") }
        item { SystemInformationCard() }
    }
}

@Composable
private fun ModuleStatusCard(active: Boolean?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), insideMargin = PaddingValues(18.dp), colors = toolboxCardColors()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = when (active) {
                true -> Color(0xFF2E9D59)
                false -> Color(0xFFD94B4B)
                null -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            Box(Modifier.size(48.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(if (active == false) Icons.Default.Close else Icons.Default.CheckCircle, null, Modifier.size(27.dp), tint = color)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Module", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when (active) {
                        true -> "Active"
                        false -> "Not active"
                        null -> "Checking…"
                    },
                    color = color,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun SystemInformationCard() {
    val rows = listOf(
        "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android" to Build.VERSION.RELEASE,
        "API level" to Build.VERSION.SDK_INT.toString(),
        "Build" to Build.DISPLAY
    )
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), insideMargin = PaddingValues(horizontal = 18.dp, vertical = 8.dp), colors = toolboxCardColors()) {
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(row.first, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
                Spacer(Modifier.width(24.dp))
                Text(row.second, modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (index < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(0.06f)))
        }
    }
}

@Composable
private fun ModulesScreen(listState: LazyListState, onOpenScope: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) appScopes else appScopes.filter {
            it.title.contains(query, true) || it.summary.contains(query, true) || it.packageName.contains(query, true)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = 28.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { SectionTitle("Modules", "Select an application to configure") }
        item { ModuleSearchField(query, onQueryChange = { query = it }) }
        items(filtered.chunked(3)) { rowScopes ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowScopes.forEach { scope -> ModuleTile(scope, Modifier.weight(1f)) { onOpenScope(scope.id) } }
                repeat(3 - rowScopes.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (filtered.isEmpty()) {
            item {
                Text("Nothing found", modifier = Modifier.fillMaxWidth().padding(40.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ModuleSearchField(query: String, onQueryChange: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), insideMargin = PaddingValues(horizontal = 16.dp), colors = toolboxCardColors()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onBackground),
                singleLine = true,
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Search modules", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 16.sp)
                        field()
                    }
                }
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, "Clear", Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ModuleTile(scope: AppScope, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val icon = applicationIcon(scope.packageName)
    Card(onClick = onClick, modifier = modifier, insideMargin = PaddingValues(horizontal = 8.dp, vertical = 14.dp), colors = toolboxCardColors()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                DrawableImage(icon, scope.title, Modifier.size(58.dp).clip(RoundedCornerShape(17.dp)))
            } else {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(17.dp)).background(MiuixTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Apps, scope.title, Modifier.size(29.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            Spacer(Modifier.height(9.dp))
            Text(scope.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SettingsScreen(state: UiState, listState: LazyListState, model: MainViewModel) {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0"
    }
    val themes = listOf("Auto", "Dark", "Light")
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = 28.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle("Settings", "Appearance and application information") }
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), insideMargin = PaddingValues(vertical = 6.dp), colors = toolboxCardColors()) {
                Column(Modifier.fillMaxWidth()) {
                    Text("Theme", modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    themes.forEachIndexed { index, name ->
                        Row(
                            Modifier.fillMaxWidth().clickable { model.setAppThemeMode(index) }.padding(horizontal = 18.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, modifier = Modifier.weight(1f), fontSize = 15.sp)
                            if (state.appThemeMode == index) Icon(Icons.Default.CheckCircle, name, Modifier.size(21.dp), tint = MiuixTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        item { Toggle("System colors", "Use Android Monet colors throughout the application", state.monetColors, model::setMonetColors) }
        item { ValueSlider("Interface scale", state.uiScale, 80f..125f, "%", model::setUiScale) }
        item { Toggle("Liquid Glass navigation", "Use refraction and transparency for the navigation panel", state.liquidGlassBar, model::setLiquidGlassBar) }
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), insideMargin = PaddingValues(horizontal = 18.dp, vertical = 8.dp), colors = toolboxCardColors()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("About", modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Version $version", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
                }
            }
        }
        item {
            Card(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/oosthemes")))
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                colors = toolboxCardColors()
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Telegram releases channel", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(3.dp))
                        Text("@oosthemes", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
                    }
                    Icon(Icons.Default.ArrowBack, null, Modifier.size(22.dp).graphicsLayer { rotationZ = 180f }, tint = MiuixTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun applicationIcon(packageName: String): Drawable? {
    val packageManager = LocalContext.current.packageManager
    return remember(packageManager, packageName) {
        runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, fontSize = 14.sp, color = MiuixTheme.colorScheme.onBackground.copy(0.58f))
        }
    }
}

@Composable
private fun ScopeScreen(scope: AppScope, state: UiState, model: MainViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SmallTopAppBar(
            title = scope.title,
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { model.restartScope(scope.packageName, scope.title) }) { Icon(Icons.Default.Refresh, "Restart ${scope.title}") } }
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (scope.id) {
                "system_ui" -> {
                    item { Toggle("Extended statusbar", "Use extended status indicators", state.materialOriginOsStatusBar, model::setMaterialOriginOsStatusBar) }
                    item { Toggle("Hide Vivo service error", "Hide notifications from Vivo daemon service", state.hideVivoServiceError, model::setHideVivoServiceError) }
                    item { Toggle("AOSP volume bar", "Use Android's built-in volume dialog", state.aospVolumeBar, model::setAospVolumeBar) }
                }
                "system_ui_plugin" -> {
                    item { Toggle("Liquid Glass control center", "Apply the existing experimental glass effect", state.liquidGlassControlCenter, model::setLiquidGlassControlCenter) }
                    item { ControlCenterIconPackSelector(state, model) }
                    if (state.liquidGlassControlCenter) {
                        item { ValueSlider("Blur", state.liquidGlassBlur, 0f..200f, "%", model::setLiquidGlassBlur) }
                        item { ValueSlider("Refraction", state.liquidGlassRefraction, 0f..200f, "%", model::setLiquidGlassRefraction) }
                        item { ValueSlider("Dispersion", state.liquidGlassDispersion, 0f..200f, "%", model::setLiquidGlassDispersion) }
                        item { ValueSlider("Saturation", state.liquidGlassSaturation, 0f..200f, "%", model::setLiquidGlassSaturation) }
                        item { ValueSlider("Tint", state.liquidGlassTint, 0f..200f, "%", model::setLiquidGlassTint) }
                        item { ValueSlider("Edge", state.liquidGlassEdge, 0f..200f, "%", model::setLiquidGlassEdge) }
                        item { ValueSlider("Quality", state.liquidGlassQuality, 10f..100f, "%", model::setLiquidGlassQuality) }
                        item { ValueSlider("Corner radius", state.liquidGlassCornerRadius, 0f..64f, " dp", model::setLiquidGlassCornerRadius) }
                    }
                    item { Toggle("Centered clock", "Large centered Notification Center clock", state.centeredNotificationClock, model::setCenteredNotificationClock) }
                    if (state.centeredNotificationClock) {
                        item { Toggle("Clock glass", "Liquid Glass clock digits", state.centeredClockGlass, model::setCenteredClockGlass) }
                        item { ValueSlider("Clock size", state.notificationClockSize, 80f..200f, "%", model::setNotificationClockSize) }
                    }
                }
                "android" -> item { Toggle("Private DNS with VPN", "Disable Private DNS while a VPN is connected", state.disablePrivateDnsWithVpn, model::setDisablePrivateDnsWithVpn) }
                "launcher" -> {
                    item { Toggle("Circle to Search", "Long-press the navigation handle", state.circleToSearch, model::setCircleToSearch) }
                    item { Toggle("Pixel home layout", "Google Search dock and icon grid", state.pixelHomeLayout, model::setPixelHomeLayout) }
                    item { Toggle("Themed icons", "Material You colors for launcher icons", state.nativeAospThemedIcons, model::setNativeAospThemedIcons) }
                    item { ValueSlider("Icon size", state.launcherIconSize, 70f..150f, "%", model::setLauncherIconSize) }
                    item { IconPackApplyContainer(state, model) }
                }
                "google" -> {
                    item { Toggle("Bypass Gemini restrictions", "Use the configured DNS for Google AI domains", state.bypassGeminiRegionalRestrictions, model::setBypassGeminiRegionalRestrictions) }
                    item { Toggle("Keep Google assistant", "Restore Google as assistant after reboot", state.restoreGoogleAssistant, model::setRestoreGoogleAssistant) }
                }
                "gboard" -> item { Toggle("Blur keyboard background", "Transparent keyboard with frosted blur", state.gboardBlurBackground, model::setGboardBlurBackground) }
                "bluelm" -> item { Toggle("Power Button Gemini trigger", "Open VISTrigger instead of Vivo Copilot", state.powerButtonGemini, model::setPowerButtonGemini) }
                "player" -> item { Toggle("Allow all media players", "Bypass Vivo media player allowlists", state.allowAllMediaPlayers, model::setAllowAllMediaPlayers) }
                "installer" -> item { Toggle("Third-party APK installers", "Remove the forced system installer after reboot", state.removePackageInstallerAfterReboot, model::setRemovePackageInstallerAfterReboot) }
                "island" -> {
                    item { Toggle("Live Updates", "Use Vivo's native OriginIsland pipeline", state.liveUpdatesInOriginIsland, model::setLiveUpdatesInOriginIsland) }
                    item { Toggle("All progress notifications", "Convert progress notifications to Vivo SuperX", state.allProgressInOriginIsland, model::setAllProgressInOriginIsland) }
                    item { Toggle("Flashlight controls", "Brightness control in an expanded island", state.flashlightInOriginIsland, model::setFlashlightInOriginIsland) }
                }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = toolboxCardColors()) {
        Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Text(summary, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
            }
            Spacer(Modifier.size(12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun ValueSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, suffix: String, onChange: (Float) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = toolboxCardColors()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp)) {
            Text("$title: ${value.toInt()}$suffix", fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
        }
    }
}
