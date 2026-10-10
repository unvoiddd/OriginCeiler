package dev.unvoid.originceiler

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.RectangleShape
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

internal val OriginBgDark: Color @Composable get() = MaterialTheme.colorScheme.background
internal val OriginCardBg: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
internal val OriginCardStroke: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
internal val OriginAccent: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val OriginAccentContainer: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
internal val OriginAccentPressed: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val OriginTextPrimary: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val OriginTextSecondary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val OriginTextTertiary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val OriginTextDisabled: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
internal val OriginFillGray: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest
internal val OriginDivider: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
internal val OriginGreen = Color(0xFF2ECC71)
internal val OriginRed = Color(0xFFFF3636)

private data class AppScope(
    val id: String,
    val title: String,
    val summary: String,
    val packageName: String
)

private val appScopes = listOf(
    AppScope("system_ui", "System UI", "Status bar and volume", "com.android.systemui"),
    AppScope("system_ui_plugin", "System UI Plugin", "Notifications and Control Center", "com.vivo.systemuiplugin"),
    AppScope("settings", "Settings", "Adaptive theme and container spacing", "com.android.settings"),
    AppScope("android", "Android System", "Private DNS and VPN behavior", "android"),
    AppScope("launcher", "BBK Launcher", "Icon packs, themes and home screen", "com.bbk.launcher2"),
    AppScope("google", "Google", "Gemini access and assistant behavior", "com.google.android.googlequicksearchbox"),
    // AppScope("gboard", "Gboard", "Keyboard background and frosted blur", "com.google.android.inputmethod.latin"),
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
internal fun OriginCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    insidePadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val cardModifier = if (onClick != null) {
        modifier
            .clip(shape)
            .background(OriginCardBg, shape)
            .clickable(onClick = onClick)
            .padding(insidePadding)
    } else {
        modifier
            .clip(shape)
            .background(OriginCardBg, shape)
            .padding(insidePadding)
    }
    Column(modifier = cardModifier, content = content)
}

@Composable
internal fun OriginCardGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(OriginCardBg, shape),
        content = content
    )
}

@Composable
internal fun OriginCardDivider(startIndentDp: Float = 16f) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startIndentDp.dp, end = 16.dp)
            .height(0.75.dp)
            .background(OriginDivider)
    )
}

@Composable
internal fun OriginSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        color = OriginTextTertiary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.08.sp,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 18.dp, bottom = 6.dp, end = 20.dp)
    )
}

@Composable
internal fun OriginChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    val bg = if (selected) OriginAccentContainer else OriginFillGray
    val fg = if (selected) OriginAccent else OriginTextSecondary
    val stroke = if (selected) OriginAccent.copy(alpha = 0.4f) else Color.Transparent

    Box(
        modifier = modifier
            .height(28.dp)
            .clip(shape)
            .border(1.dp, stroke, shape)
            .background(bg, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
internal fun OriginSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OriginButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled, primary = false)
}

@Composable
internal fun OriginButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 450f),
        label = "btnScale"
    )

    val shape = RoundedCornerShape(24.dp)
    val bgModifier = if (primary) {
        Modifier.background(
            brush = Brush.verticalGradient(
                colors = if (enabled) listOf(OriginAccentPressed, OriginAccent)
                else listOf(OriginAccentPressed.copy(alpha = 0.35f), OriginAccent.copy(alpha = 0.35f))
            ),
            shape = shape
        )
    } else {
        Modifier
            .background(if (enabled) OriginFillGray else OriginFillGray.copy(alpha = 0.35f), shape)
    }

    val accent = OriginAccent
    val shadowModifier = if (primary && enabled) {
        Modifier.graphicsLayer {
            shadowElevation = 8.dp.toPx()
            spotShadowColor = accent.copy(alpha = 0.4f)
            ambientShadowColor = accent.copy(alpha = 0.2f)
            this.shape = shape
            clip = false
        }
    } else Modifier

    val contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else OriginTextPrimary

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(shadowModifier)
            .height(48.dp)
            .clip(shape)
            .then(bgModifier)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) contentColor else contentColor.copy(alpha = 0.35f),
            fontSize = 15.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
internal fun OriginSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val accent = OriginAccent
    val inactiveTrack = OriginFillGray
    val thumbFill = OriginBgDark
    var isDragging by remember { mutableStateOf(false) }
    val glowProgress by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "glowProgress"
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (isDragging) 1.25f else 1.0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "thumbScale"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val thumbRadiusPx = with(density) { 9.dp.toPx() }
        val usableWidth = (widthPx - thumbRadiusPx * 2f).coerceAtLeast(1f)

        val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        val thumbCenterXPx = thumbRadiusPx + fraction * usableWidth

        fun updateFromX(x: Float) {
            val clampedX = (x - thumbRadiusPx).coerceIn(0f, usableWidth)
            val newFraction = clampedX / usableWidth
            val newValue = valueRange.start + newFraction * (valueRange.endInclusive - valueRange.start)
            onValueChange(newValue)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(enabled, valueRange, usableWidth) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = { offset ->
                            isDragging = true
                            updateFromX(offset.x)
                            tryAwaitRelease()
                            isDragging = false
                        }
                    )
                }
                .pointerInput(enabled, valueRange, usableWidth) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            updateFromX(offset.x)
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onHorizontalDrag = { change, _ ->
                            updateFromX(change.position.x)
                        }
                    )
                }
        ) {
            val centerY = size.height / 2f
            val trackHeight = 4.dp.toPx()
            val trackRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            val inactiveTrackColor = inactiveTrack

            drawRoundRect(
                color = if (enabled) inactiveTrackColor else inactiveTrackColor.copy(alpha = 0.4f),
                topLeft = Offset(thumbRadiusPx, centerY - trackHeight / 2f),
                size = Size(usableWidth, trackHeight),
                cornerRadius = trackRadius
            )

            val activeWidth = (thumbCenterXPx - thumbRadiusPx).coerceAtLeast(0f)
            if (activeWidth > 0f) {
                drawRoundRect(
                    color = if (enabled) accent else accent.copy(alpha = 0.4f),
                    topLeft = Offset(thumbRadiusPx, centerY - trackHeight / 2f),
                    size = Size(activeWidth, trackHeight),
                    cornerRadius = trackRadius
                )
            }

            val glowRadius = 14.dp.toPx() + 10.dp.toPx() * glowProgress
            val glowAlpha = if (enabled) (0.28f + 0.37f * glowProgress) else 0f
            if (glowAlpha > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = glowAlpha),
                            accent.copy(alpha = glowAlpha * 0.35f),
                            Color.Transparent
                        ),
                        center = Offset(thumbCenterXPx, centerY),
                        radius = glowRadius
                    ),
                    radius = glowRadius,
                    center = Offset(thumbCenterXPx, centerY)
                )
            }

            val scaledR = thumbRadiusPx * thumbScale
            val outerStrokeWidth = 3.dp.toPx()
            val thumbRingColor = if (enabled) accent else accent.copy(alpha = 0.4f)

            drawCircle(
                color = thumbFill,
                radius = (scaledR - outerStrokeWidth / 2f).coerceAtLeast(1f),
                center = Offset(thumbCenterXPx, centerY),
                style = Fill
            )
            drawCircle(
                color = thumbRingColor,
                radius = scaledR - outerStrokeWidth / 2f,
                center = Offset(thumbCenterXPx, centerY),
                style = Stroke(width = outerStrokeWidth)
            )
        }
    }
}

@Composable
internal fun ValueSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    OriginCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        insidePadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = OriginTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("${value.toInt()}$suffix", color = OriginAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        OriginSlider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun Toggle(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    OriginCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onChange(!checked)
        },
        insidePadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = OriginTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (summary.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(summary, color = OriginTextSecondary, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            OriginSwitch(
                checked = checked,
                onCheckedChange = onChange
            )
        }
    }
}

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

    Box(Modifier.fillMaxSize().background(OriginBgDark)) {
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
            if (state.liquidGlassBar) {
                val navigationModifier = Modifier
                    .fillMaxWidth(0.68f)
                    .widthIn(max = 288.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 6.dp)
                LiquidBottomTabs(
                    selectedTabIndex = { pagerState.targetPage },
                    onTabSelected = { coroutineScope.launch { pagerState.animateScrollToPage(it) } },
                    backdrop = navigationBackdrop,
                    tabsCount = mainTabs.size,
                    modifier = navigationModifier
                ) {
                    mainTabs.forEachIndexed { index, tab ->
                        LiquidBottomTab(onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } }) {
                            Icon(tab.icon, tab.title, Modifier.size(21.dp), tint = OriginTextPrimary)
                            Text(tab.title, color = OriginTextPrimary, fontSize = 11.sp)
                        }
                    }
                }
            } else {
                OriginBlurNavigationBar(
                    selectedTabIndex = pagerState.currentPage,
                    onTabSelected = { coroutineScope.launch { pagerState.animateScrollToPage(it) } },
                    backdrop = navigationBackdrop
                )
            }
        }
    }
}

@Composable
internal fun OriginBlurNavigationBar(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    val containerColor = OriginCardBg.copy(alpha = 0.8f)
    val dividerColor = OriginDivider

    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = {
                    vibrancy()
                    blur(28.dp.toPx())
                },
                onDrawSurface = {
                    drawRect(containerColor)
                    drawRect(
                        color = dividerColor,
                        topLeft = Offset.Zero,
                        size = Size(size.width, 0.75.dp.toPx())
                    )
                }
            )
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            mainTabs.forEachIndexed { index, tab ->
                val selected = selectedTabIndex == index
                val contentColor = if (selected) OriginAccent else OriginTextSecondary
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onTabSelected(index) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.title,
                            modifier = Modifier.size(22.dp),
                            tint = contentColor
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = tab.title,
                            color = contentColor,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("OriginCeiler", "OriginOS customization module") }
        item { ModuleStatusCard(state.moduleActive, onOpenModules) }
        item { SubSectionTitle("System information") }
        item { SystemInformationCard() }
        item { SubSectionTitle("Battery") }
        item { BatteryInformationCard() }
    }
}

@Composable
private fun ModuleStatusCard(active: Boolean?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val cardPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)
    when (active) {
        true -> {
            val greenBg = OriginGreen.copy(alpha = 0.14f)
            val greenBorder = OriginGreen.copy(alpha = 0.32f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(shape)
                    .background(greenBg, shape)
                    .border(1.dp, greenBorder, shape)
                    .clickable(onClick = onClick)
                    .padding(cardPadding)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = OriginGreen
                    )
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Module active",
                            color = OriginTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "OriginCeiler framework hooked",
                            color = OriginTextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
        false -> {
            val redBg = OriginRed.copy(alpha = 0.14f)
            val redBorder = OriginRed.copy(alpha = 0.32f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(shape)
                    .background(redBg, shape)
                    .border(1.dp, redBorder, shape)
                    .clickable(onClick = onClick)
                    .padding(cardPadding)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = OriginRed
                    )
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Module not active",
                            color = OriginTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Not active",
                            color = OriginRed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
        null -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(shape)
                    .background(OriginCardBg, shape)
                    .border(1.dp, OriginCardStroke, shape)
                    .clickable(onClick = onClick)
                    .padding(cardPadding)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = OriginTextSecondary
                    )
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Module status",
                            color = OriginTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Checking…",
                            color = OriginTextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
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
    InformationCard(rows)
}

@Composable
private fun BatteryInformationCard() {
    val context = LocalContext.current
    var battery by remember { mutableStateOf<Intent?>(null) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                battery = intent
            }
        }
        battery = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val status = when (battery?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unavailable"
    }
    val temperature = battery?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }
        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
    val voltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
    InformationCard(listOf(
        "Charge" to if (level >= 0 && scale > 0) "${(level * 100 / scale).coerceIn(0, 100)}%" else "Unavailable",
        "Status" to status,
        "Temperature" to (temperature?.let { "${it / 10f} °C" } ?: "Unavailable"),
        "Voltage" to if (voltage > 0) "${voltage / 1000f} V" else "Unavailable"
    ))
}

@Composable
private fun InformationCard(rows: List<Pair<String, String>>) {
    OriginCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        insidePadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
    ) {
        rows.forEachIndexed { index, row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(row.first, color = OriginTextSecondary, fontSize = 14.sp)
                Spacer(Modifier.width(24.dp))
                Text(
                    row.second,
                    color = OriginTextPrimary,
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (index < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(OriginDivider))
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Modules", "Select an application to configure") }
        item { ModuleSearchField(query, onQueryChange = { query = it }) }
        items(filtered.chunked(3)) { rowScopes ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowScopes.forEach { scope -> ModuleTile(scope, Modifier.weight(1f)) { onOpenScope(scope.id) } }
                repeat(3 - rowScopes.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (filtered.isEmpty()) {
            item {
                Text("Nothing found", modifier = Modifier.fillMaxWidth().padding(40.dp), color = OriginTextSecondary, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ModuleSearchField(query: String, onQueryChange: (String) -> Unit) {
    val shape = RoundedCornerShape(23.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(46.dp)
            .clip(shape)
            .background(OriginCardBg, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, Modifier.size(19.dp), tint = OriginTextSecondary)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(color = OriginTextPrimary, fontSize = 15.sp),
            singleLine = true,
            cursorBrush = SolidColor(OriginAccent),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search modules", color = OriginTextTertiary, fontSize = 15.sp)
                    field()
                }
            }
        )
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(OriginFillGray)
                    .clickable { onQueryChange("") },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, "Clear", Modifier.size(14.dp), tint = OriginTextSecondary)
            }
        }
    }
}

@Composable
private fun ModuleTile(scope: AppScope, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val icon = applicationIcon(scope.packageName)
    OriginCard(onClick = onClick, modifier = modifier, insidePadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                DrawableImage(icon, scope.title, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
            } else {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(OriginFillGray), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Apps, scope.title, Modifier.size(26.dp), tint = OriginTextSecondary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(scope.title, color = OriginTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SettingsScreen(state: UiState, listState: LazyListState, model: MainViewModel) {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.1"
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
            OriginCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), insidePadding = PaddingValues(vertical = 6.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text("Theme", color = OriginTextPrimary, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    themes.forEachIndexed { index, name ->
                        Row(
                            Modifier.fillMaxWidth().clickable { model.setAppThemeMode(index) }.padding(horizontal = 18.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, color = OriginTextPrimary, modifier = Modifier.weight(1f), fontSize = 15.sp)
                            if (state.appThemeMode == index) Icon(Icons.Default.CheckCircle, name, Modifier.size(21.dp), tint = OriginAccent)
                        }
                    }
                }
            }
        }
        item { Toggle("System colors", "Use Android Monet colors throughout the application", state.monetColors, model::setMonetColors) }
        item { ValueSlider("Interface scale", state.uiScale, 80f..125f, "%", model::setUiScale) }
        item { Toggle("Liquid Glass navigation", "Use refraction and transparency for the navigation panel", state.liquidGlassBar, model::setLiquidGlassBar) }
        item {
            OriginCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), insidePadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("About", color = OriginTextPrimary, modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Version $version", color = OriginTextSecondary, fontSize = 14.sp)
                }
            }
        }
        item {
            OriginCard(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/oosthemes")))
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                insidePadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Telegram releases channel", color = OriginTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(3.dp))
                        Text("@oosthemes", color = OriginTextSecondary, fontSize = 14.sp)
                    }
                    Icon(Icons.Default.ArrowBack, null, Modifier.size(22.dp).graphicsLayer { rotationZ = 180f }, tint = OriginAccent)
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
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = OriginTextPrimary)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, fontSize = 13.sp, color = OriginTextSecondary)
        }
    }
}

@Composable
private fun SubSectionTitle(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = OriginTextTertiary,
        letterSpacing = 0.06.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
    )
}

@Composable
private fun SubSectionHeaderWithAction(
    title: String,
    actionText: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = OriginTextTertiary,
            letterSpacing = 0.06.sp
        )
        Text(
            text = actionText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = OriginAccent,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onAction)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ScopeScreen(scope: AppScope, state: UiState, model: MainViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = OriginTextPrimary)
            }
            Text(
                scope.title,
                color = OriginTextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { model.restartScope(scope.packageName, scope.title) }) {
                Icon(Icons.Default.Refresh, "Restart ${scope.title}", tint = OriginAccent)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (scope.id) {
                "system_ui" -> {
                    item { Toggle("Extended statusbar", "Use extended status indicators", state.materialOriginOsStatusBar, model::setMaterialOriginOsStatusBar) }
                    item { Toggle("Google Sans Flex Rounded", "Rounded font for the clock, shade date and battery percentage", state.materialOriginOsGoogleSansFlex, model::setMaterialOriginOsGoogleSansFlex) }
                    item { Toggle("Hide Vivo service error", "Hide notifications from Vivo daemon service", state.hideVivoServiceError, model::setHideVivoServiceError) }
                    item { Toggle("Material volume dialog", "Material You styled volume slider", state.materialOriginOsVolume, model::setMaterialOriginOsVolume) }
                    item { Toggle("AOSP volume bar", "Use Android's built-in volume dialog", state.aospVolumeBar, model::setAospVolumeBar) }
                    item { Toggle("Custom statusbar", "Enable independent SystemUI icon customisation", state.aospStatusbar, model::setExtendedStatusbar) }
                    if (state.aospStatusbar) {
                        item { StatusbarWifiPackSelector(state, model) }
                        item { StatusbarBatteryPackSelector(state, model) }
                        item { StatusbarMobilePackSelector(state, model) }
                    }
                    item {
                        SubSectionHeaderWithAction(
                            title = "Status bar icon sizes & positions",
                            actionText = "Reset",
                            onAction = model::resetStatusBarTweakDefaults
                        )
                    }
                    item {
                        OriginSecondaryButton(
                            text = "Reset to default",
                            onClick = model::resetStatusBarTweakDefaults,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }
                    item { ValueSlider("Battery size", state.statusBarBatteryScale, 50f..150f, "%", model::setStatusBarBatteryScale) }
                    item { ValueSlider("Battery percent text size", state.statusBarBatteryTextScale, 50f..150f, "%", model::setStatusBarBatteryTextScale) }
                    item { ValueSlider("Battery percent text weight", state.statusBarBatteryTextWeight, 100f..900f, "", model::setStatusBarBatteryTextWeight) }
                    item { ValueSlider("Battery X position", state.statusBarBatteryX, -150f..150f, " px", model::setStatusBarBatteryX) }
                    item { ValueSlider("Wi-Fi icon size", state.statusBarWifiScale, 50f..150f, "%", model::setStatusBarWifiScale) }
                    item { ValueSlider("Wi-Fi X position", state.statusBarWifiX, -150f..150f, " px", model::setStatusBarWifiX) }
                    item { ValueSlider("Clock size", state.statusBarClockScale, 50f..150f, "%", model::setStatusBarClockScale) }
                    item { ValueSlider("Clock X position", state.statusBarClockX, -150f..150f, " px", model::setStatusBarClockX) }
                    item { ValueSlider("Other system icons", state.statusBarSystemIconsScale, 50f..150f, "%", model::setStatusBarSystemIconsScale) }
                    item {
                        OriginSecondaryButton(
                            text = "Reset to default",
                            onClick = model::resetStatusBarTweakDefaults,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
                "system_ui_plugin" -> {
                    item { Toggle("Material notifications", "Material You layout and styling for notifications", state.materialOriginOsNotifications, model::setMaterialOriginOsNotifications) }
                    item { Toggle("Material lockscreen", "Material You clock and lockscreen shortcuts", state.materialOriginOsLockscreen, model::setMaterialOriginOsLockscreen) }
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
                        item { ValueSlider("Tiles blur", state.liquidGlassTilesBlur, 0f..200f, "%", model::setLiquidGlassTilesBlur) }
                        item { ValueSlider("Tiles tint", state.liquidGlassTilesTint, 0f..200f, "%", model::setLiquidGlassTilesTint) }
                        item { ValueSlider("Player blur", state.liquidGlassPlayerBlur, 0f..200f, "%", model::setLiquidGlassPlayerBlur) }
                        item { ValueSlider("Player tint", state.liquidGlassPlayerTint, 0f..200f, "%", model::setLiquidGlassPlayerTint) }
                        item { ValueSlider("Sliders blur", state.liquidGlassSliderBlur, 0f..200f, "%", model::setLiquidGlassSliderBlur) }
                        item { ValueSlider("Sliders tint", state.liquidGlassSliderTint, 0f..200f, "%", model::setLiquidGlassSliderTint) }
                        item { ValueSlider("Buttons blur", state.liquidGlassButtonBlur, 0f..200f, "%", model::setLiquidGlassButtonBlur) }
                        item { ValueSlider("Buttons tint", state.liquidGlassButtonTint, 0f..200f, "%", model::setLiquidGlassButtonTint) }
                    }
                    item { SubSectionTitle("Native Vivo blur") }
                    item { ValueSlider("Control center blur", state.nativeBlurControlCenter, 0f..200f, "%", model::setNativeBlurControlCenter) }
                    item { ValueSlider("Notifications blur", state.nativeBlurNotifications, 0f..200f, "%", model::setNativeBlurNotifications) }
                    item { ValueSlider("Shade background blur", state.nativeBlurPanel, 0f..200f, "%", model::setNativeBlurPanel) }
                    item { Toggle("Disable background zoom", "Keep the blurred background at its original scale", state.nativeBlurDisablePanelScale, model::setNativeBlurDisablePanelScale) }
                    item { Toggle("Centered clock", "Large centered Notification Center clock", state.centeredNotificationClock, model::setCenteredNotificationClock) }
                    if (state.centeredNotificationClock) {
                        item { Toggle("Clock glass", "Liquid Glass clock digits", state.centeredClockGlass, model::setCenteredClockGlass) }
                        item { ValueSlider("Clock size", state.notificationClockSize, 80f..200f, "%", model::setNotificationClockSize) }
                    }
                }
                "settings" -> {
                    item { Toggle("Material adaptive theme", "Replace section dividers with adaptive spacing between preference containers", state.settingsAdaptiveTheme, model::setSettingsAdaptiveTheme) }
                    if (state.settingsAdaptiveTheme) {
                        item { ValueSlider("Container spacing", state.settingsContainerGap, 0f..24f, " dp", model::setSettingsContainerGap) }
                    }
                }
                "android" -> item { Toggle("Private DNS with VPN", "Disable Private DNS while a VPN is connected", state.disablePrivateDnsWithVpn, model::setDisablePrivateDnsWithVpn) }
                "launcher" -> {
                    item { Toggle("Circle to Search", "Long-press the navigation handle", state.circleToSearch, model::setCircleToSearch) }
                    item { Toggle("Pixel home layout", "Google Search dock and icon grid", state.pixelHomeLayout, model::setPixelHomeLayout) }
                    item { Toggle("Themed icons", "Material You colors for launcher icons", state.nativeAospThemedIcons, model::setNativeAospThemedIcons) }
                    if (state.nativeAospThemedIcons) {
                        item { ValueSlider("Themed icon size", state.monetIconScale, 65f..118f, "%", model::setMonetIconScale) }
                    }
                    item { ValueSlider("Icon size", state.launcherIconSize, 70f..150f, "%", model::setLauncherIconSize) }
                    item { IconPackApplyContainer(state, model) }
                }
                "google" -> {
                    item { Toggle("Bypass Gemini restrictions", "Use the configured DNS for Google AI domains", state.bypassGeminiRegionalRestrictions, model::setBypassGeminiRegionalRestrictions) }
                    item { Toggle("Keep Google assistant", "Restore Google as assistant after reboot", state.restoreGoogleAssistant, model::setRestoreGoogleAssistant) }
                }
                // "gboard" -> item { Toggle("Blur keyboard background", "Transparent keyboard with frosted blur", state.gboardBlurBackground, model::setGboardBlurBackground) }
                "bluelm" -> {
                    item { Toggle("BlueLM Copilot Russification", "Translates interface to Russian and replaces Vivo speech recognition with local Whisper across all calls, including Jovi via power button", state.blueLmRussian, model::setBlueLmRussian) }
                    item { Toggle("Power Button Gemini trigger", "Open VISTrigger instead of Vivo Copilot", state.powerButtonGemini, model::setPowerButtonGemini) }
                }
                "player" -> {
                    item { Toggle("Allow all media players", "Bypass Vivo media player allowlists", state.allowAllMediaPlayers, model::setAllowAllMediaPlayers) }
                }
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
