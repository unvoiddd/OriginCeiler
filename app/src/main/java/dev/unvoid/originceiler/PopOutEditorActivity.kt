package dev.unvoid.originceiler

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Full-screen editor for the Pop Out buttons positions. SystemUI holds all bulges out while
 * [PopOutButtonsHook.SETTING_EDIT] is 1, so dragging a marker moves the real bulge live.
 */
class PopOutEditorActivity : ComponentActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val keys = listOf(
        PopOutButtonsHook.SETTING_VOLUME_UP_Y to PopOutButtonsHook.DEFAULT_VOLUME_UP_Y,
        PopOutButtonsHook.SETTING_VOLUME_DOWN_Y to PopOutButtonsHook.DEFAULT_VOLUME_DOWN_Y,
        PopOutButtonsHook.SETTING_POWER_Y to PopOutButtonsHook.DEFAULT_POWER_Y
    )
    private lateinit var originals: List<Float>
    private var closed = false
    @Volatile
    private var directWrites = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        originals = keys.map { (key, def) -> readFloat(key, def) }
        val leftSide = Settings.Global.getInt(contentResolver, PopOutButtonsHook.SETTING_LEFT_SIDE, 0) == 1

        // Grant WRITE_SECURE_SETTINGS once so drag updates can go straight to Settings.Global.
        io.execute {
            root(
                "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; " +
                    "settings put global ${PopOutButtonsHook.SETTING_EDIT} 1"
            )
            directWrites = runCatching {
                Settings.Global.putString(contentResolver, PopOutButtonsHook.SETTING_EDIT, "1")
            }.getOrDefault(false)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close(save = false)
        })

        setContent {
            val colors = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(this) else darkColorScheme()
            MaterialTheme(colorScheme = colors, typography = originTypography()) {
                PopOutEditor(
                    initial = originals,
                    leftSide = leftSide,
                    onMove = { index, value -> write(keys[index].first, value) },
                    onReset = { keys.map { (key, def) -> write(key, def); def } },
                    onCancel = { close(save = false) },
                    onSave = { close(save = true) }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the editor (home, screen off) cancels it so the bulges never stay stuck out.
        if (!isChangingConfigurations) close(save = false)
    }

    private fun close(save: Boolean) {
        if (closed) return
        closed = true
        val values = if (save) null else originals
        io.execute {
            val restore = values?.let {
                keys.indices.joinToString("; ") { i -> "settings put global ${keys[i].first} ${format(it[i])}" } + "; "
            }.orEmpty()
            if (values != null && directWrites) {
                keys.indices.forEach { i -> putDirect(keys[i].first, format(values[i])) }
            } else if (values != null) {
                root(restore)
            }
            if (!(directWrites && putDirect(PopOutButtonsHook.SETTING_EDIT, "0"))) {
                root("settings put global ${PopOutButtonsHook.SETTING_EDIT} 0")
            }
        }
        io.shutdown()
        setResult(RESULT_OK)
        finish()
    }

    private fun write(key: String, value: Float) {
        val text = format(value)
        if (directWrites && putDirect(key, text)) return
        if (!io.isShutdown) io.execute { root("settings put global $key $text") }
    }

    private fun putDirect(key: String, value: String): Boolean =
        runCatching { Settings.Global.putString(contentResolver, key, value) }.getOrDefault(false)

    private fun readFloat(key: String, def: Float) =
        Settings.Global.getString(contentResolver, key)?.toFloatOrNull() ?: def

    private fun format(value: Float) = String.format(Locale.US, "%.1f", value)

    private fun root(command: String) {
        runCatching { ProcessBuilder("su", "-c", command).redirectErrorStream(true).start().waitFor() }
    }

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Real display height in px; positions are stored as a percentage of it. */
    internal fun screenHeightPx(): Float =
        if (Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.bounds.height().toFloat()
        } else {
            @Suppress("DEPRECATION")
            android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }.heightPixels.toFloat()
        }
}

private val labels = listOf("Volume +", "Volume −", "Power")

@Composable
private fun PopOutEditor(
    initial: List<Float>,
    leftSide: Boolean,
    onMove: (Int, Float) -> Unit,
    onReset: () -> List<Float>,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    val activity = androidx.compose.ui.platform.LocalContext.current as PopOutEditorActivity
    val screenHeight = remember { activity.screenHeightPx() }
    val positions = remember { initial.map { mutableFloatStateOf(it) } }
    var rootTop by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(-1) }
    val density = LocalDensity.current
    val handleHeight = 56.dp
    val handleHeightPx = with(density) { handleHeight.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFF7B267), Color(0xFFF4845F), Color(0xFFF25C54), Color(0xFF7B2CBF))
                )
            )
            .onGloballyPositioned { rootTop = it.positionOnScreen().y }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 64.dp, start = 40.dp, end = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Button positions", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                "Drag each marker along the edge until the bulge sits right next to its physical button.",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
        }

        positions.forEachIndexed { index, state ->
            val centerPx = state.floatValue / 100f * screenHeight - rootTop
            Row(
                modifier = Modifier
                    .align(if (leftSide) Alignment.TopStart else Alignment.TopEnd)
                    .offset { IntOffset(0, (centerPx - handleHeightPx / 2f).roundToInt()) }
                    .height(handleHeight)
                    .pointerInput(index) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = index },
                            onDragEnd = { dragging = -1 },
                            onDragCancel = { dragging = -1 }
                        ) { change, dy ->
                            change.consume()
                            val next = (state.floatValue + dy / screenHeight * 100f).coerceIn(0f, 100f)
                            state.floatValue = next
                            onMove(index, next)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                val active = dragging == index
                val chip: @Composable () -> Unit = {
                    Row(
                        modifier = Modifier
                            .background(
                                if (active) Color.White else Color.Black.copy(alpha = 0.55f),
                                RoundedCornerShape(50)
                            )
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.UnfoldMore,
                            contentDescription = null,
                            tint = if (active) Color.Black else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "${labels[index]}  ${String.format(Locale.US, "%.1f", state.floatValue)}%",
                            color = if (active) Color.Black else Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                val guide: @Composable () -> Unit = {
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(2.dp)
                            .background(Color.White.copy(alpha = if (active) 1f else 0.7f))
                    )
                }
                if (leftSide) { guide(); chip() } else { chip(); guide() }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OriginSecondaryButton("Cancel", onCancel, Modifier.weight(1f))
            OriginSecondaryButton(
                "Reset",
                { onReset().forEachIndexed { i, v -> positions[i].floatValue = v } },
                Modifier.weight(1f)
            )
            OriginButton("Save", onSave, Modifier.weight(1f))
        }
    }
}
