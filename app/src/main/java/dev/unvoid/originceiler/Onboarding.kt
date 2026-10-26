package dev.unvoid.originceiler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shown on first launch and after updating to a version that brings a new welcome. */
internal object Onboarding {
    private const val VERSION = "1.2"
    private const val KEY = "onboarding_version"

    /** Whether the welcome is on screen; set from [init] and the debug menu. */
    var visible by mutableStateOf(false)
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        visible = storedVersion(context) != VERSION
    }

    fun storedVersion(context: Context): String? =
        context.getSharedPreferences("settings", 0).getString(KEY, null)

    fun complete(context: Context) {
        context.getSharedPreferences("settings", 0).edit().putString(KEY, VERSION).commit()
        visible = false
    }

    fun restart(context: Context) {
        context.getSharedPreferences("settings", 0).edit().remove(KEY).commit()
        visible = true
    }
}

/**
 * Leads the user to one setting: opens its module page and makes the row with [title] glow.
 * Rows match by their English title, which is what Toggle/ValueSlider receive.
 */
internal object Spotlight {
    var scopeId by mutableStateOf<String?>(null)
    var title by mutableStateOf<String?>(null)

    fun show(scope: String, row: String) {
        title = row
        scopeId = scope
    }
}

/** Pulsing accent outline for the row the spotlight points at; scrolls it into view. */
@Composable
internal fun Modifier.spotlight(title: String, shape: RoundedCornerShape = RoundedCornerShape(18.dp)): Modifier {
    val active = Spotlight.title == title
    val glow = remember { Animatable(0f) }
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        runCatching { requester.bringIntoView() }
        repeat(3) {
            glow.animateTo(1f, tween(420))
            glow.animateTo(0.3f, tween(420))
        }
        glow.animateTo(0f, tween(700))
        if (Spotlight.title == title) Spotlight.title = null
    }
    val accent = OriginAccent
    return this
        .bringIntoViewRequester(requester)
        .background(accent.copy(alpha = 0.16f * glow.value), shape)
        .border(2.dp, accent.copy(alpha = glow.value), shape)
}

private data class Suggestion(val icon: ImageVector, val title: String, val summary: String, val scope: String, val row: String)

private val suggestions = listOf(
    Suggestion(Icons.Default.BlurOn, "Meet OriginCeiler Liquid Glass", "Glass notifications, control center and dock", "glass", "Notifications"),
    Suggestion(Icons.Default.Brush, "Try AOSP themed icons", "Monochrome Material You icons, like on Pixel", "launcher", "Themed icons"),
    Suggestion(Icons.Default.Search, "Try Circle to Search", "Long-press the navigation handle", "launcher", "Circle to Search"),
    Suggestion(Icons.Default.Tune, "Make the status bar yours", "Extended indicators and a rounded font", "system_ui", "Extended statusbar"),
    Suggestion(Icons.Default.Widgets, "Bring Live Updates to Origin Island", "Progress and media right in the island", "island", "Live Updates")
)

private const val HEART_PAGE = 3

private val pageShapes = listOf(ParticleShapes.LOGO, "routine", "translate", "favorite", "auto_awesome")

@Composable
internal fun OnboardingScreen(state: UiState, model: MainViewModel, onFinish: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    val last = pageShapes.lastIndex
    BackHandler(page > 0) { page-- }

    fun finish(suggestion: Suggestion? = null) {
        suggestion?.let { Spotlight.show(it.scope, it.row) }
        Onboarding.complete(context)
        onFinish()
    }

    Box(Modifier.fillMaxSize().background(OriginBgDark)) {
        ParticleField(
            shape = pageShapes[page],
            modifier = Modifier.fillMaxSize(),
            count = 760,
            shapeCenterY = when (page) { last -> 0.13f; HEART_PAGE -> 0.2f; else -> 0.27f },
            shapeSize = when (page) { last -> 0.28f; HEART_PAGE -> 0.4f; else -> 0.5f },
            particleScale = if (page == last) 0.62f else 1f
        )
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val spacer by animateFloatAsState(when (page) { last -> 0.38f; HEART_PAGE -> 0.66f; else -> 1f }, tween(700), label = "spacer")
            Spacer(Modifier.fillMaxWidth().weight(spacer))
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally(tween(520)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(520, 140))) togetherWith
                        (slideOutHorizontally(tween(420)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(300)))
                },
                label = "onboardingPage",
                modifier = Modifier.fillMaxWidth().weight(2f - spacer)
            ) { current ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (current) {
                        0 -> PageTitle("OriginCeiler", "Make OriginOS truly yours", large = true)
                        1 -> {
                            PageTitle("Choose a color scheme", "You can change it later in Settings")
                            SettingsGroup(rows = listOf(
                                { ThemeSegmentRow(state.appThemeMode, listOf("Auto", "Dark", "Light"), model::setAppThemeMode) },
                                { Toggle("System colors", "Use Android Monet colors throughout the application", state.monetColors, model::setMonetColors) }
                            ))
                        }
                        2 -> {
                            // The subtitle is in the other language, so both readers find the page.
                            PageTitle("Choose a language", if (AppLanguage.current == AppLanguage.RUSSIAN) "Choose your language" else "Выберите язык")
                            SettingsGroup(rows = listOf({
                                ThemeSegmentRow(
                                    selected = if (AppLanguage.current == AppLanguage.RUSSIAN) 1 else 0,
                                    names = listOf("English", "Русский"),
                                    title = "Language",
                                    onSelect = { AppLanguage.set(context, if (it == 1) AppLanguage.RUSSIAN else AppLanguage.ENGLISH) }
                                )
                            }))
                        }
                        3 -> {
                            PageTitle("Free and open source", "Star the project on GitHub or support development if you like")
                            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                LinkButton(Icons.Default.Star, "Star on GitHub") { open(context, GITHUB_URL) }
                                LinkButton(Icons.Default.Favorite, "Support development") { open(context, SUPPORT_URL) }
                            }
                        }
                        else -> {
                            PageTitle("Things to try", "Tap one and we'll take you right there")
                            SettingsGroup(rows = suggestions.map { suggestion ->
                                { SuggestionRow(suggestion) { finish(suggestion) } }
                            })
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PageCounter(page, pageShapes.size, Modifier.weight(1f))
                AnimatedVisibility(visible = page == last, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    OriginButton(text = "Finish setup", onClick = { finish() }, modifier = Modifier.width(220.dp))
                }
                AnimatedVisibility(visible = page < last, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                        WavyProgressRing((page + 1f) / pageShapes.size, Modifier.fillMaxSize())
                        NextButton {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            page++
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String, large: Boolean = false) {
    Text(
        title,
        color = OriginTextPrimary,
        fontSize = if (large) 38.sp else 28.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)
    )
    Spacer(Modifier.height(8.dp))
    Text(
        subtitle,
        color = OriginTextSecondary,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp)
    )
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun NextButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(OriginAccent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.ArrowForward, "Next", tint = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun PageCounter(page: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        AnimatedContent(
            targetState = page + 1,
            transitionSpec = {
                (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
            },
            label = "pageCounter"
        ) { current ->
            Text("$current", color = OriginAccent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Text("/$count", color = OriginTextTertiary, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 3.dp))
    }
}

/**
 * Material 3 Expressive style circular wavy progress: a flat track and a thick, round-capped
 * active arc whose edge ripples as a travelling sine wave.
 */
@Composable
private fun WavyProgressRing(progress: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(progress, tween(700), label = "wavyProgress")
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "wavePhase"
    )
    val active = OriginAccent
    val track = OriginAccentContainer
    Canvas(modifier) {
        val stroke = 4.dp.toPx()
        val amplitude = 2.2.dp.toPx()
        val radius = size.minDimension / 2f - stroke - amplitude
        val center = this.center
        val start = -90f
        val sweep = 360f * animated.coerceIn(0f, 1f)
        val gap = 14f
        // Track: the remaining part of the circle, kept apart from the active arc by small gaps.
        if (sweep < 360f - gap * 2) {
            drawArc(
                color = track,
                startAngle = start + sweep + gap,
                sweepAngle = 360f - sweep - gap * 2,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        if (sweep > 0.5f) {
            val path = Path()
            val waves = 12f
            val steps = (sweep * 1.5f).toInt().coerceAtLeast(8)
            for (step in 0..steps) {
                val angle = Math.toRadians((start + sweep * step / steps).toDouble())
                val wave = sin(angle * waves + phase).toFloat() * amplitude
                val r = radius + wave
                val x = center.x + r * cos(angle).toFloat()
                val y = center.y + r * sin(angle).toFloat()
                if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, active, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun LinkButton(icon: ImageVector, text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OriginCardBg, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = OriginAccent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, color = OriginTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(Icons.Default.KeyboardArrowRight, null, tint = OriginTextTertiary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SuggestionRow(suggestion: Suggestion, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(OriginAccentContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(suggestion.icon, null, tint = OriginAccent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(suggestion.title, color = OriginTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(suggestion.summary, color = OriginTextSecondary, fontSize = 12.5.sp)
        }
        Icon(Icons.Default.KeyboardArrowRight, null, tint = OriginTextTertiary, modifier = Modifier.size(22.dp))
    }
}

/** Home screen header: the logo as a living firefly swarm. */
@Composable
internal fun ParticleHero() {
    Box(Modifier.fillMaxWidth().height(250.dp)) {
        ParticleField(
            shape = ParticleShapes.LOGO,
            modifier = Modifier.fillMaxSize(),
            count = 420,
            shapeCenterY = 0.38f,
            shapeSize = 0.62f,
            particleScale = 0.9f
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("OriginCeiler", color = OriginTextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("OriginOS customization module", color = OriginTextSecondary, fontSize = 14.sp)
        }
    }
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

internal const val GITHUB_URL = "https://github.com/unvoiddd/OriginOSRootToolbox"
internal const val SUPPORT_URL = "https://yoomoney.ru/fundraise/1GT6KC59M2D.260402"
