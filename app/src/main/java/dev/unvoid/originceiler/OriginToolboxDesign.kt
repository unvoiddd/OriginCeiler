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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import dev.unvoid.originceiler.LiquidVivoGlassHook.Scene as LvScene
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.em
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

/** Material 3 defaults add 0.5sp tracking and 24sp line height to every Text; vivo UI uses neither. */
internal fun originTypography(): Typography {
    val base = Typography()
    fun TextStyle.tight() = copy(letterSpacing = 0.sp, lineHeight = 1.32.em)
    return base.copy(
        displayLarge = base.displayLarge.tight(), displayMedium = base.displayMedium.tight(), displaySmall = base.displaySmall.tight(),
        headlineLarge = base.headlineLarge.tight(), headlineMedium = base.headlineMedium.tight(), headlineSmall = base.headlineSmall.tight(),
        titleLarge = base.titleLarge.tight(), titleMedium = base.titleMedium.tight(), titleSmall = base.titleSmall.tight(),
        bodyLarge = base.bodyLarge.tight(), bodyMedium = base.bodyMedium.tight(), bodySmall = base.bodySmall.tight(),
        labelLarge = base.labelLarge.tight(), labelMedium = base.labelMedium.tight(), labelSmall = base.labelSmall.tight()
    )
}

/** True while composing rows inside a [SettingsGroup]: rows then drop their own card. */
internal val LocalInGroup = staticCompositionLocalOf { false }

@Composable
internal fun GroupHeader(title: String) {
    Text(
        text = title,
        color = OriginTextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 8.dp)
    )
}

/**
 * vivo-style settings group: optional centred title, then one rounded card holding all rows,
 * separated by hairline dividers.
 */
@Composable
internal fun SettingsGroup(
    title: String? = null,
    footer: String? = null,
    rows: List<@Composable () -> Unit>
) {
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        if (title != null) GroupHeader(title)
        OriginCardGroup {
            CompositionLocalProvider(LocalInGroup provides true) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) OriginCardDivider(startIndentDp = 18f)
                    row()
                }
            }
        }
        if (footer != null) {
            Text(
                footer,
                color = OriginTextTertiary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp)
            )
        }
    }
}

/** A centred accent-coloured action inside a group (vivo "button row"). */
@Composable
internal fun GroupActionRow(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onClick()
            }
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) OriginAccent else OriginTextDisabled,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private data class AppScope(
    val id: String,
    val title: String,
    val summary: String,
    val packageName: String
)

private val appScopes = listOf(
    AppScope("glass", "Liquid Glass", "Glass surfaces and one shared look", "com.android.systemui"),
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
    val body: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = OriginTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${value.toInt()}$suffix", color = OriginAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        OriginSlider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (LocalInGroup.current) {
        Column(modifier.fillMaxWidth().spotlight(title, RoundedCornerShape(0.dp)).padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp), content = body)
    } else {
        OriginCard(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).spotlight(title),
            insidePadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            content = body
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
    val toggle = {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        onChange(!checked)
    }
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = OriginTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (summary.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(summary, color = OriginTextSecondary, fontSize = 12.5.sp)
                }
            }
            Spacer(Modifier.width(14.dp))
            OriginSwitch(
                checked = checked,
                onCheckedChange = onChange
            )
        }
    }
    if (LocalInGroup.current) {
        Box(modifier.fillMaxWidth().spotlight(title, RoundedCornerShape(0.dp)).clickable(onClick = toggle).padding(horizontal = 18.dp, vertical = 14.dp)) { row() }
    } else {
        OriginCard(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).spotlight(title),
            onClick = toggle,
            insidePadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
        ) { row() }
    }
}

@Composable
internal fun OriginToolboxNavigation(state: UiState, model: MainViewModel) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedScope = appScopes.firstOrNull { it.id == selectedId }
    val pagerState = rememberPagerState(pageCount = { mainTabs.size })
    val coroutineScope = rememberCoroutineScope()
    BackHandler(selectedScope != null) { selectedId = null }
    // A "Things to try" suggestion from onboarding opens its module page.
    LaunchedEffect(Spotlight.scopeId) {
        Spotlight.scopeId?.let { selectedId = it }
        Spotlight.scopeId = null
    }

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
        contentPadding = PaddingValues(top = 28.dp, bottom = 132.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { ParticleHero() }
        item { ModuleStatusCard(state.moduleActive, onOpenModules) }
        item { GroupHeader("System information") }
        item { SystemInformationCard() }
        item { GroupHeader("Battery") }
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
internal fun DesktopGridCard(state: UiState, model: MainViewModel) {
    val presets = listOf(
        4 to 6, 4 to 7, 5 to 6, 5 to 7,
        5 to 8, 6 to 7, 6 to 8, 7 to 8
    )

    OriginCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        insidePadding = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Desktop Grid",
                        color = OriginTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (state.launcherCustomGridEnabled) "Custom grid active"
                        else "Stock grid (${state.launcherOriginalColumns} × ${state.launcherOriginalRows})",
                        color = if (state.launcherCustomGridEnabled) OriginAccent else OriginTextSecondary,
                        fontSize = 13.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (state.launcherCustomGridEnabled) OriginAccentContainer else OriginFillGray)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "${state.launcherGridColumns} × ${state.launcherGridRows}",
                        color = if (state.launcherCustomGridEnabled) OriginAccent else OriginTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                "Presets",
                color = OriginTextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )

            presets.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { (cols, rows) ->
                        val isSelected = state.launcherGridColumns == cols && state.launcherGridRows == rows
                        OriginChip(
                            text = "$cols × $rows",
                            selected = isSelected,
                            onClick = { model.setLauncherGridPreset(cols, rows) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(0.75.dp).background(OriginDivider))

            GridDimensionStepper(
                label = "Columns (X)",
                value = state.launcherGridColumns,
                onValueChange = model::setLauncherGridColumns
            )

            GridDimensionStepper(
                label = "Rows (Y)",
                value = state.launcherGridRows,
                onValueChange = model::setLauncherGridRows
            )

            Box(Modifier.fillMaxWidth().height(0.75.dp).background(OriginDivider))

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val canReset = state.launcherCustomGridEnabled ||
                    state.launcherGridColumns != state.launcherOriginalColumns ||
                    state.launcherGridRows != state.launcherOriginalRows

                OriginSecondaryButton(
                    text = "Reset (${state.launcherOriginalColumns}×${state.launcherOriginalRows})",
                    onClick = { model.resetLauncherGrid() },
                    modifier = Modifier.weight(1f),
                    enabled = canReset
                )
                OriginButton(
                    text = "Apply",
                    onClick = { model.applyLauncherGrid() },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun GridDimensionStepper(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    var textValue by remember(value) { mutableStateOf(value.toString()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, color = OriginTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("$value cells", color = OriginTextSecondary, fontSize = 12.sp)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(OriginFillGray)
                    .clickable(enabled = value > 1) { onValueChange(value - 1) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "-",
                    color = if (value > 1) OriginTextPrimary else OriginTextSecondary.copy(alpha = 0.4f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .widthIn(min = 48.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(OriginFillGray)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicTextField(
                    value = textValue,
                    onValueChange = { newText ->
                        val digits = newText.filter { it.isDigit() }
                        textValue = digits
                        digits.toIntOrNull()?.let { num ->
                            if (num >= 1) onValueChange(num)
                        }
                    },
                    textStyle = TextStyle(
                        color = OriginTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(OriginFillGray)
                    .clickable { onValueChange(value + 1) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "+",
                    color = OriginTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
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
        insidePadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)
    ) {
        rows.forEachIndexed { index, row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
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
            if (index < rows.lastIndex) Box(Modifier.fillMaxWidth().height(0.75.dp).background(OriginDivider))
        }
    }
}

@Composable
private fun ModulesScreen(listState: LazyListState, onOpenScope: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) appScopes else appScopes.filter {
            it.title.contains(query, true) || it.summary.contains(query, true) || it.packageName.contains(query, true) ||
                tr(it.title).contains(query, true) || tr(it.summary).contains(query, true)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = 28.dp, bottom = 132.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle("Modules", "Select an application to configure") }
        item { ModuleSearchField(query, onQueryChange = { query = it }) }
        if (filtered.isNotEmpty()) {
            item {
                SettingsGroup(rows = filtered.map { scope -> @Composable { ModuleRow(scope) { onOpenScope(scope.id) } } })
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
private fun ModuleRow(scope: AppScope, onClick: () -> Unit) {
    val icon = applicationIcon(scope.packageName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            DrawableImage(icon, scope.title, Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)))
        } else {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(OriginFillGray), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Apps, scope.title, Modifier.size(22.dp), tint = OriginTextSecondary)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(scope.title, color = OriginTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(scope.summary, color = OriginTextSecondary, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.KeyboardArrowRight, null, Modifier.size(22.dp), tint = OriginTextTertiary)
    }
}

@Composable
private fun SettingsScreen(state: UiState, listState: LazyListState, model: MainViewModel) {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.1"
    }
    val themes = listOf("Auto", "Dark", "Light")
    var showDebug by remember { mutableStateOf(false) }
    top.yukonga.miuix.kmp.overlay.OverlayDialog(
        show = showDebug,
        title = tr("Debug menu"),
        summary = tr("Welcome screen version") + ": " + (Onboarding.storedVersion(context) ?: "—"),
        onDismissRequest = { showDebug = false }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OriginButton(
                text = "Restart first-time setup",
                onClick = {
                    showDebug = false
                    Onboarding.restart(context)
                },
                modifier = Modifier.fillMaxWidth()
            )
            OriginButton(text = "Close", onClick = { showDebug = false }, modifier = Modifier.fillMaxWidth(), primary = false)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = 28.dp, bottom = 132.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle("Settings", "Appearance and application information") }
        item {
            SettingsGroup(
                title = "Appearance",
                rows = listOf(
                    { ThemeSegmentRow(state.appThemeMode, themes, model::setAppThemeMode) },
                    {
                        // Language names stay in their own language, so they are not run through tr().
                        ThemeSegmentRow(
                            selected = if (AppLanguage.current == AppLanguage.RUSSIAN) 1 else 0,
                            names = listOf("English", "Русский"),
                            title = "Language",
                            onSelect = { AppLanguage.set(context, if (it == 1) AppLanguage.RUSSIAN else AppLanguage.ENGLISH) }
                        )
                    },
                    { Toggle("System colors", "Use Android Monet colors throughout the application", state.monetColors, model::setMonetColors) },
                    { ValueSlider("Interface scale", state.uiScale, 80f..125f, "%", model::setUiScale) },
                    { Toggle("Liquid Glass navigation", "Refraction and transparency for the navigation panel", state.liquidGlassBar, model::setLiquidGlassBar) }
                )
            )
        }
        item {
            SettingsGroup(
                title = "About",
                rows = listOf(
                    {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Version", color = OriginTextPrimary, modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(version, color = OriginTextSecondary, fontSize = 14.sp)
                        }
                    },
                    {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/oosthemes"))) }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Telegram releases channel", color = OriginTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(2.dp))
                                Text("@oosthemes", color = OriginTextSecondary, fontSize = 12.5.sp)
                            }
                            Icon(Icons.Default.KeyboardArrowRight, null, Modifier.size(22.dp), tint = OriginTextTertiary)
                        }
                    },
                    {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL))) }
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Support development", color = OriginTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(2.dp))
                                Text("Donate via YooMoney", color = OriginTextSecondary, fontSize = 12.5.sp)
                            }
                            Icon(Icons.Default.KeyboardArrowRight, null, Modifier.size(22.dp), tint = OriginTextTertiary)
                        }
                    },
                    {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showDebug = true }
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Debug menu", color = OriginTextPrimary, modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Icon(Icons.Default.KeyboardArrowRight, null, Modifier.size(22.dp), tint = OriginTextTertiary)
                        }
                    }
                )
            )
        }
    }
}

@Composable
internal fun ThemeSegmentRow(selected: Int, names: List<String>, onSelect: (Int) -> Unit, title: String = "Theme") {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text(title, color = OriginTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(OriginFillGray, shape).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            names.forEachIndexed { index, name ->
                val active = selected == index
                Box(
                    Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) OriginCardBg else Color.Transparent)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        name,
                        color = if (active) OriginAccent else OriginTextSecondary,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                    )
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
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = OriginTextPrimary, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, fontSize = 14.sp, color = OriginTextSecondary, textAlign = TextAlign.Center)
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
        Box(
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Default.ArrowBack, "Back", tint = OriginTextPrimary)
            }
            Text(
                scope.title,
                color = OriginTextPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 56.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(
                onClick = { model.restartScope(scope.packageName, scope.title) },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(Icons.Default.Refresh, "Restart ${scope.title}", tint = OriginAccent)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    scope.summary,
                    color = OriginTextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
                )
            }
            when (scope.id) {
                "glass" -> glassScope(state, model)
                "system_ui" -> {
                    item {
                        SettingsGroup("Status bar", rows = listOf(
                            { Toggle("Extended statusbar", "Use extended status indicators", state.materialOriginOsStatusBar, model::setMaterialOriginOsStatusBar) },
                            { Toggle("Google Sans Flex Rounded", "Rounded font for the clock, shade date and battery percentage", state.materialOriginOsGoogleSansFlex, model::setMaterialOriginOsGoogleSansFlex) },
                            { Toggle("Custom statusbar", "Independent SystemUI icon customisation", state.aospStatusbar, model::setExtendedStatusbar) },
                            { Toggle("Hide Vivo service error", "Hide notifications from the Vivo daemon service", state.hideVivoServiceError, model::setHideVivoServiceError) }
                        ))
                    }
                    if (state.aospStatusbar) {
                        item { StatusbarWifiPackSelector(state, model) }
                        item { StatusbarBatteryPackSelector(state, model) }
                        item { StatusbarMobilePackSelector(state, model) }
                    }
                    item {
                        SettingsGroup("Volume", rows = buildList {
                            add { Toggle("Material volume dialog", "Material You styled volume slider", state.materialOriginOsVolume, model::setMaterialOriginOsVolume) }
                            add { Toggle("AOSP volume bar", "Use Android's built-in volume dialog", state.aospVolumeBar, model::setAospVolumeBar) }
                            if (lvOn(model)) {
                                add { LvSlider(model, LV.radiusKey(LvScene.VOLUME), LV.DEFAULT_SCALE, "Volume panel blur") }
                                add { LvSlider(model, LV.alphaKey(LvScene.VOLUME), LV.DEFAULT_SCALE, "Volume panel opacity") }
                            }
                        })
                    }
                    item {
                        SettingsGroup(
                            "Pop Out buttons",
                            footer = if (state.popOutButtons) "Positions are a percentage of the screen height, measured from the top." else null,
                            rows = buildList {
                                add { Toggle("Pop Out buttons", "iOS-style screen edge indent when pressing power and volume keys", state.popOutButtons, model::setPopOutButtons) }
                                if (state.popOutButtons) {
                                    add { ValueSlider("Indent depth", state.popOutDepth, 2f..30f, " dp", model::setPopOutDepth) }
                                    add { ValueSlider("Volume up position", state.popOutVolumeUpY, 0f..100f, "%", model::setPopOutVolumeUpY) }
                                    add { ValueSlider("Volume down position", state.popOutVolumeDownY, 0f..100f, "%", model::setPopOutVolumeDownY) }
                                    add { ValueSlider("Power position", state.popOutPowerY, 0f..100f, "%", model::setPopOutPowerY) }
                                    add { Toggle("Buttons on the left", "Mirror the effect to the left screen edge", state.popOutLeftSide, model::setPopOutLeftSide) }
                                    add { PopOutEditPositionsButton(model) }
                                }
                            }
                        )
                    }
                    item {
                        SettingsGroup("Status bar icon sizes & positions", rows = listOf(
                            { ValueSlider("Battery size", state.statusBarBatteryScale, 50f..150f, "%", model::setStatusBarBatteryScale) },
                            { ValueSlider("Battery percent text size", state.statusBarBatteryTextScale, 50f..150f, "%", model::setStatusBarBatteryTextScale) },
                            { ValueSlider("Battery percent text weight", state.statusBarBatteryTextWeight, 100f..900f, "", model::setStatusBarBatteryTextWeight) },
                            { ValueSlider("Battery X position", state.statusBarBatteryX, -150f..150f, " px", model::setStatusBarBatteryX) },
                            { ValueSlider("Wi-Fi icon size", state.statusBarWifiScale, 50f..150f, "%", model::setStatusBarWifiScale) },
                            { ValueSlider("Wi-Fi X position", state.statusBarWifiX, -150f..150f, " px", model::setStatusBarWifiX) },
                            { ValueSlider("Clock size", state.statusBarClockScale, 50f..150f, "%", model::setStatusBarClockScale) },
                            { ValueSlider("Clock X position", state.statusBarClockX, -150f..150f, " px", model::setStatusBarClockX) },
                            { ValueSlider("Other system icons", state.statusBarSystemIconsScale, 50f..150f, "%", model::setStatusBarSystemIconsScale) },
                            { GroupActionRow("Reset to default", model::resetStatusBarTweakDefaults) }
                        ))
                    }
                }
                "system_ui_plugin" -> {
                    item {
                        SettingsGroup("Notifications", rows = listOf(
                            { Toggle("Material notifications", "Material You layout and styling for notifications", state.materialOriginOsNotifications, model::setMaterialOriginOsNotifications) }
                        ))
                    }
                    item {
                        SettingsGroup("Lockscreen", rows = listOf(
                            { Toggle("Custom lockscreen clock", "Clock styles adapted from OpenAlive", state.customLockscreenClock, model::setCustomLockscreenClock) }
                        ))
                    }
                    if (state.customLockscreenClock) {
                        item { CustomLockscreenClockPicker(state, model) }
                        item { CustomLockscreenClockColorPicker(state, model) }
                        item {
                            SettingsGroup("Clock glass", rows = buildList {
                                add { Toggle("Liquid Glass for clock", "The clock digits wear the same glass as everything else (Liquid Glass page settings)", state.customLockscreenClockGlass, model::setCustomLockscreenClockGlass) }
                            })
                        }
                    }
                    item {
                        SettingsGroup(
                            "Native blur",
                            footer = "vivo's own blur, per surface (ported from LiquidVivo). Glass surfaces are on the Liquid Glass page.",
                            rows = listOf(
                                { LvToggle(model, LV.KEY_ENABLE, 0, "Blur control", "Blur radius and transparency for notifications, control center, volume, lockscreen, popups and the island") }
                            )
                        )
                    }
                    if (lvOn(model)) {
                        item {
                            SettingsGroup("Notifications & control center", rows = listOf(
                                { LvSlider(model, LV.radiusKey(LvScene.SHADE), LV.DEFAULT_SCALE, "Blur radius") },
                                { Toggle("Disable background zoom", "Keep the blurred background at its original scale", state.nativeBlurDisablePanelScale, model::setNativeBlurDisablePanelScale) }
                            ))
                        }
                        item {
                            SettingsGroup("Lockscreen glass", rows = listOf(
                                { LvSlider(model, LV.radiusKey(LvScene.KEYGUARD), LV.DEFAULT_SCALE, "Blur radius") }
                            ))
                        }
                        item {
                            SettingsGroup("System popups", rows = listOf(
                                { LvSlider(model, LV.alphaKey(LvScene.POPUP), LV.DEFAULT_SCALE, "Opacity") }
                            ))
                        }
                        item {
                            SettingsGroup("Other surfaces", rows = listOf(
                                { LvSlider(model, LV.radiusKey(LvScene.OTHER), LV.DEFAULT_SCALE, "Blur radius") },
                                { LvSlider(model, LV.alphaKey(LvScene.OTHER), LV.DEFAULT_SCALE, "Opacity") },
                                { GroupActionRow("Reset glass to defaults", model::resetLiquidVivoTuning) }
                            ))
                        }
                    } else {
                        item {
                            SettingsGroup("Notifications & control center", rows = listOf(
                                { Toggle("Disable background zoom", "Keep the blurred background at its original scale", state.nativeBlurDisablePanelScale, model::setNativeBlurDisablePanelScale) }
                            ))
                        }
                    }
                    item { GroupHeader("Control center icons") }
                    item { ControlCenterIconPackSelector(state, model) }
                    item {
                        SettingsGroup("Notification center clock", rows = buildList {
                            add { Toggle("Centered clock", "Large centered Notification Center clock", state.centeredNotificationClock, model::setCenteredNotificationClock) }
                            if (state.centeredNotificationClock) {
                                add { Toggle("Clock glass", "Liquid Glass clock digits", state.centeredClockGlass, model::setCenteredClockGlass) }
                                add { ValueSlider("Clock size", state.notificationClockSize, 80f..200f, "%", model::setNotificationClockSize) }
                            }
                        })
                    }
                }
                "settings" -> item {
                    SettingsGroup("Appearance", rows = buildList {
                        add { Toggle("Material adaptive theme", "Adaptive spacing between preference containers instead of dividers", state.settingsAdaptiveTheme, model::setSettingsAdaptiveTheme) }
                        if (state.settingsAdaptiveTheme) {
                            add { ValueSlider("Container spacing", state.settingsContainerGap, 0f..24f, " dp", model::setSettingsContainerGap) }
                        }
                    })
                }
                "android" -> item {
                    SettingsGroup("Network", rows = listOf(
                        { Toggle("Private DNS with VPN", "Disable Private DNS while a VPN is connected", state.disablePrivateDnsWithVpn, model::setDisablePrivateDnsWithVpn) }
                    ))
                }
                "launcher" -> {
                    item {
                        SettingsGroup("Gestures", rows = listOf(
                            { Toggle("Circle to Search", "Long-press the navigation handle", state.circleToSearch, model::setCircleToSearch) },
                            { Toggle("Shake to fall", "Shake on the home screen and everything drops with real gravity. Shake again or double tap to put it back", state.shakePhysics, model::setShakePhysics) }
                        ))
                    }
                    item {
                        SettingsGroup("Home screen", rows = buildList {
                            add { Toggle("Pixel home layout", "Google Search dock and icon grid", state.pixelHomeLayout, model::setPixelHomeLayout) }
                            add { Toggle("Themed icons", "Material You colors for launcher icons", state.nativeAospThemedIcons, model::setNativeAospThemedIcons) }
                            if (state.nativeAospThemedIcons) {
                                add { ValueSlider("Themed icon size", state.monetIconScale, 65f..118f, "%", model::setMonetIconScale) }
                            }
                            add { ValueSlider("Icon size", state.launcherIconSize, 70f..150f, "%", model::setLauncherIconSize) }
                        })
                    }
                    item { DesktopGridCard(state, model) }
                    item { Box(Modifier.padding(horizontal = 16.dp)) { IconPackApplyContainer(state, model) } }
                }
                "google" -> item {
                    SettingsGroup("Google", rows = listOf(
                        { Toggle("Bypass Gemini restrictions", "Use the configured DNS for Google AI domains", state.bypassGeminiRegionalRestrictions, model::setBypassGeminiRegionalRestrictions) },
                        { Toggle("Keep Google assistant", "Restore Google as assistant after reboot", state.restoreGoogleAssistant, model::setRestoreGoogleAssistant) }
                    ))
                }
                "bluelm" -> item {
                    SettingsGroup("Assistant", rows = listOf(
                        { Toggle("BlueLM Copilot Russification", "Russian interface and local Whisper speech recognition for every call, including Jovi via the power button", state.blueLmRussian, model::setBlueLmRussian) },
                        { Toggle("Power Button Gemini trigger", "Open VISTrigger instead of Vivo Copilot", state.powerButtonGemini, model::setPowerButtonGemini) }
                    ))
                }
                "player" -> item {
                    SettingsGroup("Media", rows = listOf(
                        { Toggle("Allow all media players", "Bypass Vivo media player allowlists", state.allowAllMediaPlayers, model::setAllowAllMediaPlayers) }
                    ))
                }
                "installer" -> item {
                    SettingsGroup("Installer", rows = listOf(
                        { Toggle("Third-party APK installers", "Remove the forced system installer after reboot", state.removePackageInstallerAfterReboot, model::setRemovePackageInstallerAfterReboot) }
                    ))
                }
                "island" -> {
                    item {
                        SettingsGroup(
                            "Island glass",
                            footer = if (lvOn(model)) "The collapsed capsule turns into glass only while system real-time blur is on and power saving is off." else LV_OFF_FOOTER,
                            rows = if (!lvOn(model)) listOf(
                                { LvToggle(model, LV.KEY_ENABLE, 0, "Blur & glass control", "Master switch shared with System UI Plugin") }
                            ) else listOf(
                                { LvToggle(model, LV.KEY_ISLAND, 1, "Glass capsule", "Give the collapsed island the same live-blur material as the expanded one") },
                                { LvSlider(model, LV.radiusKey(LvScene.ISLAND), LV.DEFAULT_SCALE, "Blur radius") },
                                { LvSlider(model, LV.alphaKey(LvScene.ISLAND), LV.DEFAULT_SCALE, "Opacity") }
                            )
                        )
                    }
                    item {
                    SettingsGroup("Origin Island", rows = listOf(
                        { Toggle("Live Updates", "Use Vivo's native OriginIsland pipeline", state.liveUpdatesInOriginIsland, model::setLiveUpdatesInOriginIsland) },
                        { Toggle("All progress notifications", "Convert progress notifications to Vivo SuperX", state.allProgressInOriginIsland, model::setAllProgressInOriginIsland) },
                        { Toggle("Flashlight controls", "Brightness control in an expanded island", state.flashlightInOriginIsland, model::setFlashlightInOriginIsland) }
                    ))
                    }
                }
            }
        }
    }
}

@Composable
private fun PopOutEditPositionsButton(model: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { model.reloadPopOutPositions() }
    GroupActionRow(
        text = "Edit positions on screen",
        onClick = { launcher.launch(android.content.Intent(context, PopOutEditorActivity::class.java)) }
    )
}

// ---------------------------------------------------------------- LiquidVivo glass rows

private val LV = LiquidVivoGlassHook

@Composable
private fun LvToggle(model: MainViewModel, key: String, default: Int, title: String, summary: String) {
    Toggle(title, summary, model.liquidVivo(key, default) == 1, { model.setLiquidVivo(key, if (it) 1 else 0) })
}

@Composable
private fun LvSlider(
    model: MainViewModel,
    key: String,
    default: Int,
    title: String,
    range: ClosedFloatingPointRange<Float> = 0f..200f,
    suffix: String = "%"
) {
    ValueSlider(title, model.liquidVivo(key, default).toFloat(), range, suffix, { model.setLiquidVivo(key, it.toInt()) })
}

private fun lvOn(model: MainViewModel) = model.liquidVivo(LV.KEY_ENABLE, 0) == 1

private const val LV_OFF_FOOTER = "Turn on Blur & glass in System UI Plugin to use these settings."

// ---------------------------------------------------------------- Kyant iOS 26 glass rows

private val KG = KyantGlass

@Composable
private fun KgToggle(model: MainViewModel, key: String, title: String, summary: String) {
    Toggle(title, summary, model.kyantGlass(key) == 1, { model.setKyantGlass(key, if (it) 1 else 0) })
}

@Composable
private fun KgSlider(model: MainViewModel, key: String, title: String, range: ClosedFloatingPointRange<Float>, suffix: String) {
    ValueSlider(title, model.kyantGlass(key).toFloat(), range, suffix, { model.setKyantGlass(key, it.toInt()) })
}

/**
 * The Liquid Glass page: every Kyant glass surface, then one shared look (the same values drive
 * SystemUI and the launcher), then what only applies under the shade or on the home screen.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.glassScope(state: UiState, model: MainViewModel) {
    item {
        SettingsGroup("Surfaces", rows = listOf(
            { KgToggle(model, KG.KEY_NOTIFICATIONS, "Notifications", "Notification and media cards") },
            { KgToggle(model, KG.KEY_NOTIFICATION_BUTTONS, "Notification swipe buttons", "Settings and delete behind a half-swiped notification as glass drops that drip off the card") },
            { KgToggle(model, KG.KEY_TILES, "Control center & volume", "Tiles, cards, sliders, shade buttons and the side volume panel") },
            { Toggle("Lockscreen shortcuts", "Round glass flashlight and camera, press and hold for 0.4 s", state.lockscreenQuickToolGlass, model::setLockscreenQuickToolGlass) },
            { KgSlider(model, KG.KEY_SHORTCUT_SIZE, "Lockscreen shortcut size", 36f..100f, " dp") },
            { KgToggle(model, KG.KEY_PIN_KEYS, "PIN pad", "Number keys of the lockscreen password pad as round glass buttons") },
            { KgToggle(model, KG.KEY_CLOCK, "Lockscreen clock", "vivo's clock digits in this glass instead of vivo's own material") },
            { KgToggle(model, KG.KEY_FOLDERS, "Folders & widgets", "Folder plates and system widget backgrounds on the home screen") },
            { KgToggle(model, KG.KEY_DOCK, "Dock", "The home screen dock") },
            { KgToggle(model, KG.KEY_APP_MENU, "App menu", "The menu that opens when you press and hold an app on the home screen") },
            { KgToggle(model, KG.KEY_APP_MENU_NO_BLUR, "No blur behind app menu", "Keep the home screen sharp behind the press-and-hold menu") },
            { KgToggle(model, KG.KEY_SIDEBAR, "Sidebar", "The side app strip opened by holding the back gesture (gesture navigation)") },
            { KgToggle(model, KG.KEY_WINDOW_MANAGER, "Window manager", "The sidebar's panel of apps that open as floating windows") },
            { KgToggle(model, KG.KEY_RECENTS_CLEAR, "Recents clear button", "The round clear-all button under the recent apps") },
            { KgToggle(model, KG.KEY_DIALOGS, "System dialogs", "Pop-up alerts as tinted glass with stacked rounded buttons, like iOS 26") }
        ))
    }
    item {
        SettingsGroup("Presets", footer = "Export copies the look as text you can share; import reads it back from the clipboard.", rows = listOf(
            { GroupActionRow("Clear (Kyant liquid glass)", onClick = { model.applyKyantGlassPreset(KG.CLEAR_PRESET) }) },
            { GroupActionRow("Frosted (soft glass)", onClick = { model.applyKyantGlassPreset(KG.SOFT_PRESET) }) },
            { GroupActionRow("Crystal (thick lens)", onClick = { model.applyKyantGlassPreset(KG.CRYSTAL_PRESET) }) },
            { GroupActionRow("Export to clipboard", model::exportKyantGlass) },
            { GroupActionRow("Import from clipboard", model::importKyantGlass) }
        ))
    }
    item {
        val soft = model.kyantGlass(KG.KEY_MATERIAL) == 1
        SettingsGroup("Look", footer = "Shared by every glass surface.", rows = buildList {
            add { Toggle("Soft glass", "Frosted material without the lens: a lit bevel with a soft shadow", soft, { model.setKyantGlass(KG.KEY_MATERIAL, if (it) 1 else 0) }) }
            add { KgSlider(model, KG.KEY_BLUR, "Blur", 0f..30f, " dp") }
            if (soft) {
                add { KgSlider(model, KG.KEY_SOFT_EDGE, "Bevel width", 2f..40f, " dp") }
                add { KgSlider(model, KG.KEY_SOFT_LIGHT, "Bevel light", 0f..100f, "%") }
                add { KgSlider(model, KG.KEY_SOFT_SHADOW, "Bevel shadow", 0f..100f, "%") }
                add { KgSlider(model, KG.KEY_SOFT_ELEVATION, "Light height", 5f..85f, "°") }
            } else {
                add { KgSlider(model, KG.KEY_REFRACTION_AMOUNT, "Refraction strength", 0f..120f, " dp") }
                add { KgSlider(model, KG.KEY_REFRACTION_HEIGHT, "Edge thickness", 0f..60f, " dp") }
                add { KgToggle(model, KG.KEY_DISPERSION, "Chromatic dispersion", "Rainbow fringes along the curved edge") }
                add { KgSlider(model, KG.KEY_EDGE_GLOW, "Edge glow", 0f..100f, "%") }
                add { KgToggle(model, KG.KEY_ADAPTIVE, "Adaptive lens", "Thinner lens on small buttons and tiles so they do not look warped") }
                if (model.kyantGlass(KG.KEY_ADAPTIVE) == 1) {
                    add { KgSlider(model, KG.KEY_SMALL_SCALE, "Lens on small surfaces", 10f..150f, "%") }
                    add { KgSlider(model, KG.KEY_MEDIUM_SCALE, "Lens on medium surfaces", 10f..150f, "%") }
                }
            }
            add { KgSlider(model, KG.KEY_HIGHLIGHT, "Rim highlight", 0f..100f, "%") }
            add { KgSlider(model, KG.KEY_HIGHLIGHT_ANGLE, "Light angle", 0f..360f, "°") }
            add { KgToggle(model, KG.KEY_GYRO, "Light follows tilt", "The rim highlight moves along the edge as you tilt the phone") }
            if (model.kyantGlass(KG.KEY_GYRO) == 1) add { KgSlider(model, KG.KEY_GYRO_STRENGTH, "Tilt response", 0f..300f, "%") }
            add { KgSlider(model, KG.KEY_VIBRANCY, "Colour saturation", 0f..300f, "%") }
            add { KgSlider(model, KG.KEY_BRIGHTNESS, "Brightness", -50f..50f, "%") }
            add { KgSlider(model, KG.KEY_DIM, "Dimming", 0f..90f, "%") }
        })
    }
    item {
        SettingsGroup("Tint", footer = "Colour 0 % keeps the neutral white / graphite tint.", rows = listOf(
            { KgSlider(model, KG.KEY_TINT, "Tint (light theme)", 0f..60f, "%") },
            { KgSlider(model, KG.KEY_TINT_DARK, "Tint (dark theme)", 0f..60f, "%") },
            { KgSlider(model, KG.KEY_TINT_CHROMA, "Colour", 0f..100f, "%") },
            { KgSlider(model, KG.KEY_TINT_HUE, "Hue", 0f..360f, "°") }
        ))
    }
    item {
        SettingsGroup("Touch", footer = "The glass under the finger lights up and stretches after it, like iOS 26.", rows = listOf(
            { KgSlider(model, KG.KEY_ELASTIC, "Stretch after the finger", 0f..100f, "%") },
            { KgSlider(model, KG.KEY_TOUCH_GLOW, "Touch glow", 0f..100f, "%") },
            { KgSlider(model, KG.KEY_TOUCH_GLOW_RADIUS, "Glow size", 20f..200f, "%") },
            { KgSlider(model, KG.KEY_NEIGHBOR_GLOW, "Glow on nearby glass", 0f..100f, "%") },
            { KgSlider(model, KG.KEY_NEIGHBOR_RADIUS, "Nearby reach", 20f..300f, " dp") },
            { KgToggle(model, KG.KEY_HEADSUP_POP, "Heads-up pop", "Incoming heads-up notifications come in out of a blur") }
        ))
    }
    item {
        SettingsGroup("Performance", footer = "Lower capture resolution and slower refresh save battery; the blur hides most of the difference.", rows = listOf(
            { KgSlider(model, KG.KEY_CAPTURE_SCALE, "Backdrop resolution", 15f..60f, "%") },
            { KgSlider(model, KG.KEY_REFRESH_MS, "Shade backdrop refresh", 250f..4000f, " ms") },
            { KgSlider(model, KG.KEY_HOME_REFRESH_MS, "Home screen backdrop refresh", 200f..3000f, " ms") }
        ))
    }
    if (kyantAnyOn(model, KG.KEY_NOTIFICATIONS, KG.KEY_TILES)) {
        item {
            SettingsGroup("Shade", footer = "Shade background blur is vivo's own blur behind the whole shade; the glass blur is the Blur slider above.", rows = listOf(
                { KgSlider(model, KG.KEY_SHADE_BLUR, "Shade background blur", 0f..200f, "%") },
                { KgToggle(model, KG.KEY_SLIDER_FLAT, "Flat slider level", "Brightness and volume level ends in a straight edge instead of a rounded one") }
            ))
        }
    }
    if (model.kyantGlass(KG.KEY_CLOCK) == 1) {
        item {
            SettingsGroup("Lockscreen clock", footer = "The clock's own glass. Tint colour 0 % keeps a neutral tint of the chosen lightness.", rows = listOf(
                { KgSlider(model, KG.KEY_CLOCK_TINT, "Tint", 0f..100f, "%") },
                { KgSlider(model, KG.KEY_CLOCK_TINT_LIGHTNESS, "Tint lightness", 0f..100f, "%") },
                { KgSlider(model, KG.KEY_CLOCK_TINT_CHROMA, "Tint colour", 0f..100f, "%") },
                { KgSlider(model, KG.KEY_CLOCK_TINT_HUE, "Tint hue", 0f..360f, "°") },
                { KgSlider(model, KG.KEY_CLOCK_BLUR, "Blur", 0f..30f, " dp") },
                { KgSlider(model, KG.KEY_CLOCK_SATURATION, "Colour saturation", 0f..300f, "%") },
                { KgSlider(model, KG.KEY_CLOCK_BRIGHTNESS, "Brightness", -50f..100f, "%") },
                { KgSlider(model, KG.KEY_CLOCK_DIM, "Dimming", 0f..90f, "%") },
                { KgSlider(model, KG.KEY_CLOCK_HIGHLIGHT, "Rim highlight", 0f..100f, "%") },
                { KgSlider(model, KG.KEY_CLOCK_REFRACTION, "Refraction strength", 0f..120f, " dp") },
                { KgSlider(model, KG.KEY_CLOCK_EDGE, "Edge thickness", 0f..40f, " dp") }
            ))
        }
    }
    if (model.kyantGlass(KG.KEY_DIALOGS) == 1) {
        item {
            SettingsGroup("Dialogs", rows = listOf(
                { KgSlider(model, KG.KEY_DIALOG_RADIUS, "Dialog corner radius (0 = system)", 0f..60f, " dp") }
            ))
        }
    }
    if (model.kyantGlass(KG.KEY_DOCK) == 1) {
        item {
            SettingsGroup("Dock", rows = buildList {
                run {
                    add { KgSlider(model, KG.KEY_DOCK_WIDTH, "Dock width", 50f..150f, "%") }
                    add { KgSlider(model, KG.KEY_DOCK_HEIGHT, "Dock height", 50f..200f, "%") }
                    add { KgSlider(model, KG.KEY_DOCK_RADIUS, "Dock corner radius (0 = system)", 0f..60f, " dp") }
                }
            })
        }
    }
}

private fun kyantAnyOn(model: MainViewModel, vararg keys: String) = keys.any { model.kyantGlass(it) == 1 }
