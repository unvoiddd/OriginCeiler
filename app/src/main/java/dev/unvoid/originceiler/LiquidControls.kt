package dev.unvoid.originceiler

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest

/** Liquid Glass page tweak: OriginSwitch / OriginSlider draw Kyant's liquid toggle and slider. */
internal val LocalLiquidControls = staticCompositionLocalOf { false }

/** Swallows the gesture once the liquid control has seen it, so a clickable row behind it does not toggle too. */
private val consumeGesture = Modifier.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(false).consume()
        do {
            val event = awaitPointerEvent(PointerEventPass.Main)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** Kyant catalog LiquidToggle: the thumb turns into a lens over the track while it is held. */
@Composable
internal fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor = if (isLightTheme) Color(0xFF34C759) else Color(0xFF30D158)
    val trackColor = if (isLightTheme) Color(0xFF787878).copy(0.2f) else Color(0xFF787880).copy(0.36f)
    val currentChecked by rememberUpdatedState(checked)
    val currentOnChange by rememberUpdatedState(onCheckedChange)

    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(androidx.compose.ui.platform.LocalDensity.current) { 20f.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    val dampedDragAnimation = remember(animationScope) {
        LiquidDampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                if (didDrag) {
                    fraction = if (targetValue >= 0.5f) 1f else 0f
                    didDrag = false
                } else {
                    fraction = if (currentChecked) 0f else 1f
                }
                if ((fraction == 1f) != currentChecked) currentOnChange(fraction == 1f)
            },
            onDrag = { _, dragAmount ->
                if (!didDrag) didDrag = dragAmount.x != 0f
                val delta = dragAmount.x / dragWidth
                fraction = if (isLtr) (fraction + delta).fastCoerceIn(0f, 1f) else (fraction - delta).fastCoerceIn(0f, 1f)
            }
        )
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }.collectLatest { dampedDragAnimation.updateValue(it) }
    }
    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        if (target != fraction) {
            fraction = target
            dampedDragAnimation.animateToValue(target)
        }
    }

    val trackBackdrop = rememberLayerBackdrop()
    Box(
        modifier.alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind { drawRect(lerp(trackColor, accentColor, dampedDragAnimation.value)) }
                .size(64f.dp, 28f.dp)
        )
        Box(
            Modifier
                .graphicsLayer {
                    val padding = 2f.dp.toPx()
                    translationX =
                        if (isLtr) lerp(padding, padding + dragWidth, dampedDragAnimation.value)
                        else lerp(-padding, -(padding + dragWidth), dampedDragAnimation.value)
                }
                .semantics { role = Role.Switch }
                .then(if (enabled) consumeGesture.then(dampedDragAnimation.modifier) else Modifier)
                .drawBackdrop(
                    backdrop = rememberBackdrop(trackBackdrop) { drawBackdrop ->
                        val progress = dampedDragAnimation.pressProgress
                        scale(lerp(2f / 3f, 0.75f, progress), lerp(0f, 0.75f, progress)) { drawBackdrop() }
                    },
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        lens(5f.dp.toPx() * progress, 10f.dp.toPx() * progress, chromaticAberration = true)
                    },
                    highlight = {
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = dampedDragAnimation.pressProgress
                        )
                    },
                    shadow = { Shadow(radius = 4f.dp, color = Color.Black.copy(alpha = 0.05f)) },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(radius = 4f.dp * progress, alpha = progress)
                    },
                    layerBlock = { squish(dampedDragAnimation, 50f) },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - dampedDragAnimation.pressProgress)) }
                )
                .size(40f.dp, 24f.dp)
        )
    }
}

/** Kyant catalog LiquidSlider: a thin track and a glass capsule thumb that magnifies it while held. */
@Composable
internal fun LiquidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val trackColor = if (isLightTheme) Color(0xFF787878).copy(0.2f) else Color(0xFF787880).copy(0.36f)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
    val trackBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier.fillMaxWidth().height(38.dp).alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = constraints.maxWidth
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        // The caller may round what it stores (settings are ints): drag on our own float.
        var dragValue by remember { mutableFloatStateOf(value.coerceIn(valueRange)) }
        var dragging by remember { mutableStateOf(false) }
        val dampedDragAnimation = remember(animationScope, valueRange) {
            LiquidDampedDragAnimation(
                animationScope = animationScope,
                initialValue = value.coerceIn(valueRange),
                valueRange = valueRange,
                visibilityThreshold = span / 1000f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {
                    dragging = true
                    dragValue = targetValue
                },
                onDragStopped = { dragging = false },
                onDrag = { _, dragAmount ->
                    if (dragAmount.x == 0f) return@LiquidDampedDragAnimation
                    val delta = span * (dragAmount.x / trackWidth)
                    dragValue = (if (isLtr) dragValue + delta else dragValue - delta).coerceIn(valueRange)
                    updateValue(dragValue)
                    currentOnChange(dragValue)
                }
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { value }.collectLatest {
                if (!dragging && dampedDragAnimation.targetValue != it) dampedDragAnimation.updateValue(it)
            }
        }
        val progress = { ((dampedDragAnimation.value - valueRange.start) / span).fastCoerceIn(0f, 1f) }

        Box(Modifier.layerBackdrop(trackBackdrop)) {
            Box(
                Modifier
                    .clip(Capsule())
                    .background(trackColor)
                    .pointerInput(animationScope, enabled) {
                        if (!enabled) return@pointerInput
                        detectTapGestures { position ->
                            val delta = span * (position.x / trackWidth)
                            val target = (if (isLtr) valueRange.start + delta else valueRange.endInclusive - delta).coerceIn(valueRange)
                            dampedDragAnimation.animateToValue(target)
                            currentOnChange(target)
                        }
                    }
                    .height(6f.dp)
                    .fillMaxWidth()
            )
            Box(
                Modifier
                    .clip(Capsule())
                    .background(accentColor)
                    .height(6f.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (constraints.maxWidth * progress()).fastRoundToInt()
                        layout(width, placeable.height) {
                            placeable.place(if (isLtr) 0 else constraints.maxWidth - width, 0)
                        }
                    }
            )
        }

        Box(
            Modifier
                .graphicsLayer {
                    translationX = (-size.width / 2f + trackWidth * progress())
                        .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) * if (isLtr) 1f else -1f
                }
                .then(if (enabled) consumeGesture.then(dampedDragAnimation.modifier) else Modifier)
                .drawBackdrop(
                    backdrop = rememberBackdrop(trackBackdrop) { drawBackdrop ->
                        val press = dampedDragAnimation.pressProgress
                        scale(lerp(2f / 3f, 1f, press), lerp(0f, 1f, press)) { drawBackdrop() }
                    },
                    shape = { Capsule() },
                    effects = {
                        val press = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - press))
                        lens(10f.dp.toPx() * press, 14f.dp.toPx() * press, chromaticAberration = true)
                    },
                    highlight = { Highlight.Ambient.copy(alpha = dampedDragAnimation.pressProgress) },
                    shadow = { Shadow(radius = 4f.dp, color = Color.Black.copy(alpha = 0.05f)) },
                    innerShadow = {
                        val press = dampedDragAnimation.pressProgress
                        InnerShadow(radius = 4f.dp * press, alpha = press)
                    },
                    layerBlock = { squish(dampedDragAnimation, 10f) },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - dampedDragAnimation.pressProgress)) }
                )
                .size(40f.dp, 24f.dp)
        )
    }
}

/** Kyant's jelly: pressed scale, stretched along the motion and squashed across it. */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.squish(animation: LiquidDampedDragAnimation, velocityDivisor: Float) {
    scaleX = animation.scaleX
    scaleY = animation.scaleY
    val velocity = animation.velocity / velocityDivisor
    scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
    scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
}
