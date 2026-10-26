package dev.unvoid.originceiler

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.BlendMode
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/**
 * iOS 26 style glass for View-based UIs, ported from Kyant's AndroidLiquidGlass ("backdrop",
 * Apache-2.0): the same rounded-rect SDF refraction (+ chromatic dispersion), vibrancy colour
 * matrix, blur, surface tint and angular rim highlight, rebuilt on RenderNode / RenderEffect.
 *
 * With a backdrop bitmap (launcher: the wallpaper) the full lens pipeline runs. Without one
 * (SystemUI, whose backdrop is a SurfaceFlinger blur we cannot read) the glass keeps the native
 * real-time blur underneath and adds tint, rim highlight and the bright refractive edge.
 */
object KyantGlass {
    const val PREFIX = "originceiler_kg_"
    const val KEY_NOTIFICATIONS = PREFIX + "notifications"
    const val KEY_TILES = PREFIX + "tiles"
    const val KEY_FOLDERS = PREFIX + "folders"
    const val KEY_DOCK = PREFIX + "dock"
    const val KEY_BLUR = PREFIX + "blur"
    const val KEY_REFRACTION_HEIGHT = PREFIX + "refraction_height"
    const val KEY_REFRACTION_AMOUNT = PREFIX + "refraction_amount"
    const val KEY_DISPERSION = PREFIX + "dispersion"
    /** Colour saturation of what is seen through the glass: shade (SystemUI) and home screen. */
    const val KEY_VIBRANCY = PREFIX + "vibrancy"
    const val KEY_TINT = PREFIX + "tint"
    const val KEY_HIGHLIGHT = PREFIX + "highlight"
    const val KEY_HIGHLIGHT_ANGLE = PREFIX + "highlight_angle"
    const val KEY_EDGE_GLOW = PREFIX + "edge_glow"
    /** SystemUI only: darkening that stands in for the shade scrim missing from the capture. */
    const val KEY_DIM = PREFIX + "dim"
    /**
     * SystemUI only: strength of the shade's own background blur, in percent of the ROM value.
     * Independent of the glass; the glass adds the same amount of blur so it matches the shade.
     */
    const val KEY_SHADE_BLUR = PREFIX + "shade_blur"

    /** Glass dock size in percent of vivo's dock plate, and its corner radius in dp (0 = system). */
    const val KEY_DOCK_WIDTH = PREFIX + "dock_width"
    const val KEY_DOCK_HEIGHT = PREFIX + "dock_height"
    const val KEY_DOCK_RADIUS = PREFIX + "dock_radius"

    /** Control-center sliders: 1 = flat level edge, 0 = vivo's rounded (pill) level. */
    const val KEY_SLIDER_FLAT = PREFIX + "slider_flat"

    /** vivo's own lockscreen clock: its digits wear the shared glass instead of vivo's material. */
    const val KEY_CLOCK = PREFIX + "clock"
    /** The clock's own look (independent of the shared one): tint, its colour, blur and lens. */
    const val KEY_CLOCK_TINT = PREFIX + "clock_tint"
    const val KEY_CLOCK_TINT_HUE = PREFIX + "clock_tint_hue"
    const val KEY_CLOCK_TINT_CHROMA = PREFIX + "clock_tint_chroma"
    const val KEY_CLOCK_TINT_LIGHTNESS = PREFIX + "clock_tint_lightness"
    const val KEY_CLOCK_BLUR = PREFIX + "clock_blur"
    const val KEY_CLOCK_SATURATION = PREFIX + "clock_saturation"
    const val KEY_CLOCK_BRIGHTNESS = PREFIX + "clock_brightness"
    const val KEY_CLOCK_DIM = PREFIX + "clock_dim"
    const val KEY_CLOCK_HIGHLIGHT = PREFIX + "clock_highlight"
    const val KEY_CLOCK_REFRACTION = PREFIX + "clock_refraction"
    const val KEY_CLOCK_EDGE = PREFIX + "clock_edge"
    /** Lockscreen PIN pad: every number key becomes a round glass button. */
    const val KEY_PIN_KEYS = PREFIX + "pin_keys"
    /** Heads-up notifications pop in out of a blur. */
    const val KEY_HEADSUP_POP = PREFIX + "headsup_pop"
    /** Home screen: the long-press app menu cards are glass. */
    const val KEY_APP_MENU = PREFIX + "app_menu"
    /** Home screen: no blur over the whole screen behind the long-press app menu. */
    const val KEY_APP_MENU_NO_BLUR = PREFIX + "app_menu_no_blur"
    /** Smart sidebar (long back gesture): its app strip is glass. */
    const val KEY_SIDEBAR = PREFIX + "sidebar"
    /** Window manager (the sidebar's small-window app panel) is glass. */
    const val KEY_WINDOW_MANAGER = PREFIX + "window_manager"
    /** Buttons behind a half-swiped notification: glass drops that form with a wobble. */
    const val KEY_NOTIFICATION_BUTTONS = PREFIX + "notification_buttons"
    /** System (OriginUI) dialogs as iOS 26 alerts: tinted blur, glass rim, stacked capsule buttons. */
    const val KEY_DIALOGS = PREFIX + "dialogs"
    /** Recents: the round "clear all" button is glass. */
    const val KEY_RECENTS_CLEAR = PREFIX + "recents_clear"
    /** Corner radius of those dialogs in dp (0 = vivo's own). */
    const val KEY_DIALOG_RADIUS = PREFIX + "dialog_radius"

    /** 0 = liquid glass (lens), 1 = soft glass (no lens, lit bevel). */
    const val KEY_MATERIAL = PREFIX + "material"
    /** Soft glass: bevel width (dp), light and shadow strength (%), light elevation (°). */
    const val KEY_SOFT_EDGE = PREFIX + "soft_edge"
    const val KEY_SOFT_LIGHT = PREFIX + "soft_light"
    const val KEY_SOFT_SHADOW = PREFIX + "soft_shadow"
    const val KEY_SOFT_ELEVATION = PREFIX + "soft_elevation"

    /** Brightness of what is seen through the glass, % around 0. */
    const val KEY_BRIGHTNESS = PREFIX + "brightness"
    /** Tint strength in dark theme (KEY_TINT is the light theme one). */
    const val KEY_TINT_DARK = PREFIX + "tint_dark"
    /** Tint colour: hue (°) and chroma (% , 0 = neutral white / graphite). */
    const val KEY_TINT_HUE = PREFIX + "tint_hue"
    const val KEY_TINT_CHROMA = PREFIX + "tint_chroma"

    /** Smaller surfaces get a proportionally thinner lens (small < 72 dp < medium < 160 dp). */
    const val KEY_ADAPTIVE = PREFIX + "adaptive"
    const val KEY_SMALL_SCALE = PREFIX + "small_scale"
    const val KEY_MEDIUM_SCALE = PREFIX + "medium_scale"

    /** Light under the finger (%), its radius (% of the surface), and the glow on neighbours. */
    const val KEY_TOUCH_GLOW = PREFIX + "touch_glow"
    const val KEY_TOUCH_GLOW_RADIUS = PREFIX + "touch_glow_radius"
    const val KEY_NEIGHBOR_GLOW = PREFIX + "neighbor_glow"
    const val KEY_NEIGHBOR_RADIUS = PREFIX + "neighbor_radius"
    /** Rim highlight follows the phone's tilt; how far it swings (%). */
    const val KEY_GYRO = PREFIX + "gyro"
    const val KEY_GYRO_STRENGTH = PREFIX + "gyro_strength"
    /** Glass stretches after the finger (%, 0 = off). */
    const val KEY_ELASTIC = PREFIX + "elastic"

    /** Backdrop capture resolution (% of the screen) and refresh interval (ms) under the shade / at home. */
    const val KEY_CAPTURE_SCALE = PREFIX + "capture_scale"
    const val KEY_REFRESH_MS = PREFIX + "refresh_ms"
    const val KEY_HOME_REFRESH_MS = PREFIX + "home_refresh_ms"

    /** Changing this setting dumps every window of the process to logcat (tag OriginGlassDump). */
    const val KEY_DEBUG_DUMP = PREFIX + "debug_dump"

    /** Blur (dp) of the shade background at 100 %, which the captured backdrop lacks. */
    const val SHADE_BLUR_EQUIVALENT_DP = 28f

    /**
     * Kyant catalog "Clear" LiquidButton: vibrancy() (saturation 1.5), blur(2.dp),
     * lens(12.dp, 24.dp), Highlight.Default (0.5 dp, white at 50 %, 45°), no surface tint.
     */
    val CLEAR_PRESET = mapOf(
        KEY_MATERIAL to 0,
        KEY_BLUR to 2, KEY_REFRACTION_HEIGHT to 12, KEY_REFRACTION_AMOUNT to 24,
        KEY_DISPERSION to 0, KEY_VIBRANCY to 150, KEY_TINT to 0, KEY_TINT_DARK to 0, KEY_DIM to 12,
        KEY_HIGHLIGHT to 50, KEY_HIGHLIGHT_ANGLE to 45, KEY_EDGE_GLOW to 0,
        KEY_BRIGHTNESS to 0, KEY_TINT_HUE to 210, KEY_TINT_CHROMA to 0,
    )

    /** Frosted soft glass: no lens, heavier blur, lit bevel with a soft shadow on the far side. */
    val SOFT_PRESET = mapOf(
        KEY_MATERIAL to 1,
        KEY_BLUR to 10, KEY_REFRACTION_HEIGHT to 12, KEY_REFRACTION_AMOUNT to 24,
        KEY_DISPERSION to 0, KEY_VIBRANCY to 140, KEY_TINT to 14, KEY_TINT_DARK to 10, KEY_DIM to 14,
        KEY_HIGHLIGHT to 35, KEY_HIGHLIGHT_ANGLE to 225, KEY_EDGE_GLOW to 0,
        KEY_BRIGHTNESS to 4, KEY_TINT_HUE to 210, KEY_TINT_CHROMA to 0,
        KEY_SOFT_EDGE to 14, KEY_SOFT_LIGHT to 45, KEY_SOFT_SHADOW to 22, KEY_SOFT_ELEVATION to 40,
    )

    /** Thick crystal: strong lens with dispersion, almost no blur. */
    val CRYSTAL_PRESET = mapOf(
        KEY_MATERIAL to 0,
        KEY_BLUR to 0, KEY_REFRACTION_HEIGHT to 22, KEY_REFRACTION_AMOUNT to 48,
        KEY_DISPERSION to 1, KEY_VIBRANCY to 170, KEY_TINT to 0, KEY_TINT_DARK to 0, KEY_DIM to 10,
        KEY_HIGHLIGHT to 70, KEY_HIGHLIGHT_ANGLE to 45, KEY_EDGE_GLOW to 30,
        KEY_BRIGHTNESS to 0, KEY_TINT_HUE to 210, KEY_TINT_CHROMA to 0,
    )

    val DEFAULTS = mapOf(
        KEY_NOTIFICATIONS to 0, KEY_TILES to 0, KEY_FOLDERS to 0, KEY_DOCK to 0,
        KEY_PIN_KEYS to 0, KEY_HEADSUP_POP to 0, KEY_CLOCK to 0,
        KEY_APP_MENU to 0, KEY_APP_MENU_NO_BLUR to 0, KEY_SIDEBAR to 0, KEY_WINDOW_MANAGER to 0, KEY_NOTIFICATION_BUTTONS to 0, KEY_DIALOGS to 0, KEY_DIALOG_RADIUS to 0, KEY_RECENTS_CLEAR to 0,
        KEY_CLOCK_TINT to 18, KEY_CLOCK_TINT_HUE to 210, KEY_CLOCK_TINT_CHROMA to 0, KEY_CLOCK_TINT_LIGHTNESS to 100,
        KEY_CLOCK_BLUR to 8, KEY_CLOCK_SATURATION to 130, KEY_CLOCK_BRIGHTNESS to 8, KEY_CLOCK_DIM to 0,
        KEY_CLOCK_HIGHLIGHT to 45, KEY_CLOCK_REFRACTION to 60, KEY_CLOCK_EDGE to 14,
        KEY_SHADE_BLUR to 100, KEY_SLIDER_FLAT to 1,
        KEY_DOCK_WIDTH to 100, KEY_DOCK_HEIGHT to 100, KEY_DOCK_RADIUS to 0,
        KEY_SOFT_EDGE to 14, KEY_SOFT_LIGHT to 45, KEY_SOFT_SHADOW to 22, KEY_SOFT_ELEVATION to 40,
        KEY_ADAPTIVE to 1, KEY_SMALL_SCALE to 60, KEY_MEDIUM_SCALE to 85,
        KEY_TOUCH_GLOW to 45, KEY_TOUCH_GLOW_RADIUS to 70, KEY_NEIGHBOR_GLOW to 30, KEY_NEIGHBOR_RADIUS to 90,
        KEY_ELASTIC to 50, KEY_GYRO to 1, KEY_GYRO_STRENGTH to 100,
        KEY_DEBUG_DUMP to 0, KEY_SHORTCUT_SIZE to 60, KEY_CAPTURE_SCALE to 33, KEY_REFRESH_MS to 1500, KEY_HOME_REFRESH_MS to 600,
    ) + CLEAR_PRESET

    /** Keys that describe the look (presets, export / import); surface switches stay out. */
    val LOOK_KEYS: Set<String> get() = DEFAULTS.keys - setOf(
        KEY_NOTIFICATIONS, KEY_TILES, KEY_FOLDERS, KEY_DOCK, KEY_PIN_KEYS, KEY_HEADSUP_POP, KEY_DEBUG_DUMP, KEY_CLOCK,
        KEY_APP_MENU, KEY_APP_MENU_NO_BLUR, KEY_SIDEBAR, KEY_WINDOW_MANAGER, KEY_NOTIFICATION_BUTTONS, KEY_DIALOGS, KEY_RECENTS_CLEAR
    )

    // ---------------------------------------------------------------- config

    class Config(private val values: Map<String, Int>) {
        operator fun get(key: String): Int = values[key] ?: DEFAULTS[key] ?: 0
        fun on(key: String) = get(key) == 1
    }

    @Volatile var config = Config(emptyMap())
        private set

    private val observerInstalled = AtomicBoolean(false)
    val configLoaded: Boolean get() = observerInstalled.get()
    private val tracked = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val listeners = mutableListOf<() -> Unit>()

    fun ensureConfig(context: Context) {
        if (!observerInstalled.compareAndSet(false, true)) return
        val cr = context.applicationContext?.contentResolver ?: context.contentResolver
        fun load() {
            config = Config(DEFAULTS.keys.associateWith { key ->
                runCatching { Settings.Global.getInt(cr, key, DEFAULTS[key] ?: 0) }.getOrDefault(DEFAULTS[key] ?: 0)
            })
        }
        load()
        KyantGlassDebug.note(context, "config observer in ${context.packageName}")
        runCatching {
            cr.registerContentObserver(Settings.Global.CONTENT_URI, true, object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    if (uri?.lastPathSegment?.startsWith(PREFIX) != true) return
                    if (uri.lastPathSegment == KEY_DEBUG_DUMP) {
                        load()
                        if (config[KEY_DEBUG_DUMP] != 1) runCatching { KyantGlassDebug.dumpWindows(context) }
                        return
                    }
                    load()
                    synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }
                    synchronized(tracked) { tracked.keys.toList() }.forEach { it.invalidate() }
                }
            })
        }
    }

    /** Views redrawn whenever a glass setting changes. */
    fun track(view: View) {
        tracked[view] = true
    }

    fun invalidateTracked() {
        synchronized(tracked) { tracked.keys.toList() }.forEach { it.invalidate() }
    }

    fun onConfigChanged(listener: () -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    // ---------------------------------------------------------------- shaders (Kyant backdrop)

    private const val ROUNDED_RECT_SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}"""

    private const val REFRACTION = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;
$ROUNDED_RECT_SDF
float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) return content.eval(coord);
    sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    float2 refractedCoord = coord + d * grad;
    if (chromaticAberration <= 0.0) return content.eval(refractedCoord);

    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;
    half4 color = half4(0.0);
    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5; color.a += red.a / 7.0;
    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;
    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;
    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5; color.a += green.a / 7.0;
    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;
    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0; color.a += blue.a / 7.0;
    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;
    return color;
}"""

    /**
     * Kyant HighlightStyle.Default (angular intensity pow(|dot(grad, light)|, falloff), lit on
     * both the light-facing and the opposite side), evaluated from the SDF instead of a blurred
     * stroke: a thin rim line plus a soft sheen fading inwards. Premultiplied white, drawn PLUS.
     */
    private const val HIGHLIGHT = """
uniform float2 size;
uniform float4 cornerRadii;
uniform float angle;
uniform float falloff;
uniform float rimWidth;
uniform float rimAlpha;
uniform float sheenWidth;
uniform float sheenAlpha;
uniform float inset;
$ROUNDED_RECT_SDF
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);
    float sd = sdRoundedRect(centeredCoord, halfSize, radius) + inset;
    if (sd > 0.5) return half4(0.0);
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float lit = pow(abs(dot(grad, normal)), falloff);
    float inside = max(-sd, 0.0);
    float aa = clamp(0.5 - sd, 0.0, 1.0);
    float rim = 1.0 - smoothstep(rimWidth * 0.5, rimWidth * 1.5, inside);
    float sheen = exp(-inside / max(sheenWidth, 0.5));
    float v = clamp((rim * rimAlpha + sheen * sheenAlpha) * lit * aa, 0.0, 1.0);
    return half4(v, v, v, v);
}"""

    /**
     * Refractive edge without a backdrop: the brightening a thick glass rim shows where it bends
     * light, using the same circle map as the lens so it matches the launcher glass.
     */
    private const val EDGE_GLOW = """
uniform float2 size;
uniform float4 cornerRadii;
uniform float thickness;
layout(color) uniform half4 color;
$ROUNDED_RECT_SDF
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (sd > 0.0 || -sd >= thickness) return half4(0.0);
    float x = 1.0 - (-sd / thickness);
    float t = 1.0 - sqrt(max(1.0 - x * x, 0.0));
    return color * t;
}"""

    /**
     * Soft glass: the surface as a pillow with a rounded bevel [edge] px wide. The bevel normal is
     * lit by a light from [light] (unit vector, z towards the viewer); facing it brightens, facing
     * away shades. Relative to a flat surface, so the flat middle stays untouched. Premultiplied,
     * white for light and black for shade, drawn SRC_OVER.
     */
    private const val SOFT = """
uniform float2 size;
uniform float4 cornerRadii;
uniform float edge;
uniform float3 light;
uniform float lightAmount;
uniform float shadowAmount;
$ROUNDED_RECT_SDF
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (sd > 0.5) return half4(0.0);
    float inside = max(-sd, 0.0);
    float t = clamp(inside / edge, 0.0, 1.0);
    float slope = (1.0 - t) * (1.0 - t) * 2.2;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float3 n = normalize(float3(grad * slope, 1.0));
    float rel = dot(n, light) - light.z;
    float aa = clamp(0.5 - sd, 0.0, 1.0);
    float lit = clamp(rel, 0.0, 1.0);
    lit = (lit + pow(lit, 6.0) * 2.0) * lightAmount;
    float shade = clamp(-rel, 0.0, 1.0) * shadowAmount;
    lit = clamp(lit * aa, 0.0, 1.0);
    shade = clamp(shade * aa, 0.0, 1.0 - lit);
    return half4(lit, lit, lit, lit + shade);
}"""

    /**
     * Light under the finger: a gaussian spot at [touch] (may lie outside: neighbours), filling
     * the surface with [fill] and its rim with [rim]. Premultiplied white, drawn PLUS.
     */
    private const val TOUCH_GLOW = """
uniform float2 size;
uniform float4 cornerRadii;
uniform float2 touch;
uniform float radius;
uniform float fill;
uniform float rim;
uniform float rimWidth;
$ROUNDED_RECT_SDF
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float r = radiusAt(centeredCoord, cornerRadii);
    float sd = sdRoundedRect(centeredCoord, halfSize, r);
    if (sd > 0.5) return half4(0.0);
    float d = length(coord - touch) / max(radius, 1.0);
    float g = exp(-d * d * 2.2);
    float inside = max(-sd, 0.0);
    float edgeBand = 1.0 - smoothstep(0.0, rimWidth, inside);
    float aa = clamp(0.5 - sd, 0.0, 1.0);
    float v = clamp(g * (fill + rim * edgeBand) * aa, 0.0, 1.0);
    return half4(v, v, v, v);
}"""

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Lens scale for a surface whose short side is [shortDp]. */
    fun adaptiveScale(cfg: Config, shortDp: Float): Float {
        if (!cfg.on(KEY_ADAPTIVE)) return 1f
        return when {
            shortDp < 72f -> cfg[KEY_SMALL_SCALE].coerceIn(10, 200) / 100f
            shortDp < 160f -> cfg[KEY_MEDIUM_SCALE].coerceIn(10, 200) / 100f
            else -> 1f
        }
    }

    /** Surface tint for the theme, [alpha] 0..255. */
    fun tintColor(cfg: Config, dark: Boolean, alpha: Int): Int {
        val chroma = cfg[KEY_TINT_CHROMA].coerceIn(0, 100) / 100f
        if (chroma <= 0f) return if (dark) Color.argb(alpha, 20, 20, 24) else Color.argb(alpha, 255, 255, 255)
        val hue = cfg[KEY_TINT_HUE].mod(360).toFloat()
        return Color.HSVToColor(alpha, floatArrayOf(hue, chroma * if (dark) 0.8f else 0.35f, if (dark) 0.24f else 1f))
    }

    fun tintAlpha(cfg: Config, dark: Boolean): Int =
        (cfg[if (dark) KEY_TINT_DARK else KEY_TINT].coerceIn(0, 100) * 2.55f).toInt()

    private const val BACKDROP_RETRY_MS = 120L

    /** Lockscreen shortcut buttons: glass circle diameter (dp). */
    const val KEY_SHORTCUT_SIZE = PREFIX + "shortcut_size"

    /** Cross-fade between two backdrop frames. */
    private const val FADE_MS = 220L

    private val preBlurred = Collections.synchronizedMap(WeakHashMap<Bitmap, Boolean>())

    /** Marks a backdrop that already carries the glass blur: the per-frame effect skips it. */
    fun markPreBlurred(bitmap: Bitmap) {
        preBlurred[bitmap] = true
    }

    fun isPreBlurred(bitmap: Bitmap): Boolean = preBlurred.containsKey(bitmap)

    /**
     * CPU Gaussian approximation (three box passes, separable) matching RenderEffect's blur
     * radius. For worker threads: done once per captured frame instead of on the GPU for every
     * glass surface on every frame while they move.
     */
    fun blurSoftware(source: Bitmap, radius: Float): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val scratch = IntArray(w * h)
        val sigma = radius * 0.57735f + 0.5f
        for (box in gaussBoxes(sigma)) {
            val r = (box - 1) / 2
            if (r < 1) continue
            boxPass(pixels, scratch, w, h, r, horizontal = true)
            boxPass(scratch, pixels, w, h, r, horizontal = false)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun gaussBoxes(sigma: Float): IntArray {
        val n = 3
        val ideal = kotlin.math.sqrt(12.0 * sigma * sigma / n + 1)
        var wl = kotlin.math.floor(ideal).toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val m = Math.round((12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4.0)).toInt()
        return IntArray(n) { if (it < m) wl else wu }
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = src[base + i.coerceIn(0, len - 1) * step]
                sa += c ushr 24; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255
            }
            for (i in 0 until len) {
                dst[base + i * step] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[base + (i + r + 1).coerceAtMost(len - 1) * step]
                val rem = src[base + (i - r).coerceAtLeast(0) * step]
                sa += (add ushr 24) - (rem ushr 24)
                sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
                sb += (add and 255) - (rem and 255)
            }
        }
    }

    /** Last time (uptime ms) any glass surface moved: backdrop captures wait for stillness. */
    @Volatile var lastMotionAt = 0L
        private set

    /**
     * Glass blur in px: exactly the Blur setting, under the shade too (0 = a clear lens). The
     * shade's own background blur is vivo's window blur and has its own setting.
     */
    @Suppress("UNUSED_PARAMETER")
    fun blurPx(cfg: Config, density: Float, matchShade: Boolean): Float =
        cfg[KEY_BLUR].coerceAtLeast(0) * density

    // ---------------------------------------------------------------- renderer

    /**
     * One renderer per glass surface (owns its RenderNode and shader instances).
     *
     * The backdrop is recorded into a RenderNode carrying the vibrancy → blur → lens RenderEffect.
     * Both are rebuilt only when their inputs change, so a still surface reuses hwui's cached
     * filtered layer every frame. While the surface moves (shade expansion, scrolling) a pre-draw
     * listener re-records just the backdrop node with the new screen transform, so the glass
     * follows the motion in the same frame without redrawing the host view.
     */
    class Renderer {
        private val contentNode by lazy { RenderNode("KyantGlassContent") }
        private val refraction by lazy { RuntimeShader(REFRACTION) }
        private val highlightShader by lazy { RuntimeShader(HIGHLIGHT) }
        private val edge by lazy { RuntimeShader(EDGE_GLOW) }
        private val softShader by lazy { RuntimeShader(SOFT) }
        private val glowShader by lazy { RuntimeShader(TOUCH_GLOW) }
        private val softPaintOverlay = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
        private val touchGlow = KyantGlassTouch.Glow()
        private val path = Path()
        private val radii8 = FloatArray(8)
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
        private val softwareStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val screenLoc = IntArray(2)
        private val windowLoc = IntArray(2)
        private val toBitmap = Matrix()
        private val toNode = Matrix()
        private val matrixValues = FloatArray(9)
        private val recordedValues = FloatArray(9)
        private val visibleRect = android.graphics.Rect()
        private var recordedBitmap: Bitmap? = null
        private var fadeFrom: Bitmap? = null
        private var fadeStart = 0L
        private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var recordedW = 0
        private var recordedH = 0
        private var vibrancyFilter: ColorFilter? = null
        private var vibrancyValue = -1
        private val effectKey = FloatArray(14)
        private val nextKey = FloatArray(14)
        private var effectValid = false

        // Motion following: what the last hardware draw used.
        private var followView: WeakReference<View>? = null
        private var followObserver: ViewTreeObserver? = null
        private var followBitmap: WeakReference<Bitmap>? = null
        private var followLeft = 0f
        private var followTop = 0f
        private val preDraw = ViewTreeObserver.OnPreDrawListener {
            follow()
            true
        }
        private val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = unfollow()
        }

        /** Rim only (edge glow + highlight) for surfaces that keep their own fill. */
        fun drawRimOnly(canvas: Canvas, view: View, bounds: RectF, radii: FloatArray, edgeGlow: Boolean = true) =
            draw(canvas, view, bounds, radii, null, tint = false, edgeGlow = edgeGlow)

        /**
         * Draws glass into [bounds] (canvas coordinates of [view]) with per-corner [radii]
         * (tl, tr, br, bl). [backdrop] is a screen-sized bitmap sampled under the surface.
         * [surfaceColor] is painted over the glass (Kyant's onDrawSurface), e.g. an active tile.
         */
        fun draw(
            canvas: Canvas,
            view: View,
            bounds: RectF,
            radii: FloatArray,
            backdrop: Bitmap?,
            tint: Boolean = true,
            highlight: Boolean = true,
            dim: Boolean = false,
            edgeGlow: Boolean = true,
            surfaceColor: Int = Color.TRANSPARENT,
            /** Lens strength relative to the settings (small buttons need more to read). */
            lensScale: Float = 1f,
            /** Moves the rim highlight inwards (px): hosts that clip their outer edge. */
            rimInset: Float = 0f,
            /** Light under the finger (off when another layer draws it for this surface). */
            withTouchGlow: Boolean = true
        ) {
            val w = bounds.width()
            val h = bounds.height()
            if (w < 1f || h < 1f) return
            val cfg = config
            val density = view.resources.displayMetrics.density
            val dark = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val maxR = minOf(w, h) / 2f
            for (i in 0 until 4) {
                val r = radii.getOrElse(i) { 0f }.coerceIn(0f, maxR)
                radii8[i * 2] = r; radii8[i * 2 + 1] = r
            }
            path.rewind()
            path.addRoundRect(bounds, radii8, Path.Direction.CW)

            // Software canvases (snapshots, screenshots of the view) cannot run shaders or nodes.
            if (!supported || !canvas.isHardwareAccelerated) {
                drawSoftware(canvas, view, bounds, backdrop, cfg, dark, density, tint, dim, surfaceColor)
                return
            }

            val save = canvas.save()
            canvas.clipPath(path)

            val soft = cfg[KEY_MATERIAL] == 1
            val lens = if (soft) 0f else lensScale * adaptiveScale(cfg, minOf(w, h) / density)

            // 1. Backdrop through vibrancy → blur → lens.
            val refracted = backdrop != null && !backdrop.isRecycled
            if (refracted) {
                drawBackdrop(canvas, view, bounds, backdrop!!, cfg, density, matchShade = dim, lensScale = lens)
            } else {
                followBitmap = null
                // The frame was known to be wrong: the next one appears directly, no fade from it.
                recordedBitmap = null
                fadeFrom = null
            }

            // 1b. Shade scrim stand-in (the capture excludes the shade window and its darkening).
            // One dimming for every glass surface that shows a backdrop.
            val dimAlpha = if (refracted) (cfg[KEY_DIM].coerceIn(0, 90) * 2.55f).toInt() else 0
            if (dimAlpha > 0) {
                fillPaint.color = Color.argb(dimAlpha, 0, 0, 0)
                canvas.drawPath(path, fillPaint)
            }

            // 2. Surface tint, then the surface colour (Kyant onDrawSurface).
            val tintAlpha = tintAlpha(cfg, dark)
            if (tint && tintAlpha > 0) {
                fillPaint.color = tintColor(cfg, dark, tintAlpha)
                canvas.drawPath(path, fillPaint)
            }
            if (Color.alpha(surfaceColor) > 0) {
                fillPaint.color = surfaceColor
                canvas.drawPath(path, fillPaint)
            }

            // 2b. Soft glass: lit bevel instead of the lens.
            if (soft) {
                val edgePx = (cfg[KEY_SOFT_EDGE].coerceIn(2, 60) * density).coerceAtMost(minOf(w, h) / 2f)
                val angle = KyantGlassLight.angle(view.context, cfg[KEY_HIGHLIGHT_ANGLE]).toDouble()
                val elevation = Math.toRadians(cfg[KEY_SOFT_ELEVATION].coerceIn(5, 85).toDouble())
                softShader.setFloatUniform("size", w, h)
                softShader.setFloatUniform("cornerRadii", radii8[0], radii8[2], radii8[4], radii8[6])
                softShader.setFloatUniform("edge", edgePx.coerceAtLeast(1f))
                softShader.setFloatUniform("light",
                    (kotlin.math.cos(angle) * kotlin.math.cos(elevation)).toFloat(),
                    (kotlin.math.sin(angle) * kotlin.math.cos(elevation)).toFloat(),
                    kotlin.math.sin(elevation).toFloat())
                softShader.setFloatUniform("lightAmount", cfg[KEY_SOFT_LIGHT].coerceIn(0, 100) / 100f * if (dark) 0.75f else 1f)
                softShader.setFloatUniform("shadowAmount", cfg[KEY_SOFT_SHADOW].coerceIn(0, 100) / 100f * if (dark) 1.2f else 1f)
                softPaintOverlay.shader = softShader
                canvas.save()
                canvas.translate(bounds.left, bounds.top)
                canvas.drawRect(0f, 0f, w, h, softPaintOverlay)
                canvas.restore()
            }

            // 3. Refractive bright edge, only when there is nothing to refract.
            val glow = if (!edgeGlow || refracted || soft) 0f else cfg[KEY_EDGE_GLOW].coerceIn(0, 100) / 100f
            if (glow > 0f) {
                edge.setFloatUniform("size", w, h)
                edge.setFloatUniform("cornerRadii", radii8[0], radii8[2], radii8[4], radii8[6])
                val thickness = (cfg[KEY_REFRACTION_HEIGHT] * density).coerceAtMost(minOf(w, h) * 0.16f).coerceAtLeast(1f)
                edge.setFloatUniform("thickness", thickness)
                edge.setColorUniform("color", Color.argb((glow * 0.55f * 255).toInt(), 255, 255, 255))
                edgePaint.shader = edge
                canvas.save()
                canvas.translate(bounds.left, bounds.top)
                canvas.drawRect(0f, 0f, w, h, edgePaint)
                canvas.restore()
            }

            // 4. Rim highlight on both lit sides plus a soft inward sheen.
            val hl = if (highlight) cfg[KEY_HIGHLIGHT].coerceIn(0, 100) / 100f else 0f
            if (hl > 0f) {
                highlightShader.setFloatUniform("size", w, h)
                highlightShader.setFloatUniform("cornerRadii", radii8[0], radii8[2], radii8[4], radii8[6])
                highlightShader.setFloatUniform("angle", KyantGlassLight.angle(view.context, cfg[KEY_HIGHLIGHT_ANGLE]))
                highlightShader.setFloatUniform("falloff", 1f)
                highlightShader.setFloatUniform("rimWidth", maxOf(0.75f * density, 1.5f))
                highlightShader.setFloatUniform("rimAlpha", hl)
                highlightShader.setFloatUniform("sheenWidth", (minOf(w, h) * 0.08f).coerceIn(2f * density, 7f * density))
                highlightShader.setFloatUniform("sheenAlpha", hl * 0.22f)
                highlightShader.setFloatUniform("inset", rimInset)
                highlightPaint.shader = highlightShader
                canvas.save()
                canvas.translate(bounds.left, bounds.top)
                canvas.drawRect(0f, 0f, w, h, highlightPaint)
                canvas.restore()
            }

            // 5. Light under the finger (and on the surfaces next to it).
            if (withTouchGlow && KyantGlassTouch.glowFor(view, bounds, cfg, density, touchGlow)) {
                glowShader.setFloatUniform("size", w, h)
                glowShader.setFloatUniform("cornerRadii", radii8[0], radii8[2], radii8[4], radii8[6])
                glowShader.setFloatUniform("touch", touchGlow.x, touchGlow.y)
                glowShader.setFloatUniform("radius", touchGlow.radius)
                glowShader.setFloatUniform("fill", touchGlow.fill * if (dark) 0.7f else 1f)
                glowShader.setFloatUniform("rim", touchGlow.rim)
                glowShader.setFloatUniform("rimWidth", (minOf(w, h) * 0.12f).coerceIn(2f * density, 10f * density))
                glowPaint.shader = glowShader
                canvas.save()
                canvas.translate(bounds.left, bounds.top)
                canvas.drawRect(0f, 0f, w, h, glowPaint)
                canvas.restore()
            }
            canvas.restoreToCount(save)
        }

        /**
         * Software canvases: the launcher snapshots a long-pressed or dragged folder into a bitmap.
         * No RenderEffect there, so the glass is approximated: the backdrop under the surface
         * (upscaled from the low-res capture, which already reads as blurred), saturated, plus rim.
         */
        private fun drawSoftware(
            canvas: Canvas, view: View, bounds: RectF, backdrop: Bitmap?, cfg: Config, dark: Boolean,
            density: Float, tint: Boolean, dim: Boolean, surfaceColor: Int
        ) {
            val soft = backdrop?.let(::softwareCopy)
            if (soft != null) {
                view.getLocationOnScreen(screenLoc)
                val dm = view.resources.displayMetrics
                val sx = soft.width / dm.widthPixels.toFloat().coerceAtLeast(1f)
                val sy = soft.height / dm.heightPixels.toFloat().coerceAtLeast(1f)
                val left = screenLoc[0] + bounds.left
                val top = screenLoc[1] + bounds.top
                softSrc.set((left * sx).toInt(), (top * sy).toInt(), ((left + bounds.width()) * sx).toInt(), ((top + bounds.height()) * sy).toInt())
                val vib = cfg[vibrancyKey(dim)] * 1000 + cfg[KEY_BRIGHTNESS]
                if (vib != softVibrancy) {
                    softPaint.colorFilter = if (vib != 100_000) saturationFilter(cfg[vibrancyKey(dim)] / 100f, cfg[KEY_BRIGHTNESS]) else null
                    softVibrancy = vib
                }
                val save = canvas.save()
                canvas.clipPath(path)
                canvas.drawBitmap(soft, softSrc, bounds, softPaint)
                canvas.restoreToCount(save)
                run {
                    fillPaint.color = Color.argb((cfg[KEY_DIM].coerceIn(0, 90) * 2.55f).toInt(), 0, 0, 0)
                    canvas.drawPath(path, fillPaint)
                }
            }
            val base = if (dim) (cfg[KEY_DIM].coerceIn(0, 90) * 2.55f).toInt().coerceAtLeast(40) else tintAlpha(cfg, dark)
            if (soft == null && (tint || dim)) {
                fillPaint.color = if (dim) Color.argb(base.coerceIn(0, 255), 20, 20, 24) else tintColor(cfg, dark, base.coerceIn(0, 255))
                canvas.drawPath(path, fillPaint)
            }
            if (Color.alpha(surfaceColor) > 0) {
                fillPaint.color = surfaceColor
                canvas.drawPath(path, fillPaint)
            }
            softwareStroke.strokeWidth = density
            softwareStroke.color = Color.argb((cfg[KEY_HIGHLIGHT].coerceIn(0, 100) * 1.2f).toInt(), 255, 255, 255)
            canvas.drawPath(path, softwareStroke)
        }

        private fun drawBackdrop(canvas: Canvas, view: View, bounds: RectF, backdrop: Bitmap, cfg: Config, density: Float, matchShade: Boolean, lensScale: Float) {
            val w = bounds.width()
            val h = bounds.height()
            // Captured frames arrive pre-blurred (worker thread): no blur pass per surface per frame.
            val blur = if (isPreBlurred(backdrop)) 0f else blurPx(cfg, density, matchShade)
            val rh = (cfg[KEY_REFRACTION_HEIGHT] * density * lensScale).coerceAtMost(minOf(w, h) / 2f)
            val ra = cfg[KEY_REFRACTION_AMOUNT] * density * lensScale
            // The lens samples up to `ra` outside the shape and the blur ~2 sigma around it.
            val pad = ceil(blur * 2f + minOf(ra, rh * 2f) + 2f)
            val tw = ceil(w + pad * 2).toInt()
            val th = ceil(h + pad * 2).toInt()

            followLeft = bounds.left - pad
            followTop = bounds.top - pad
            recordIfMoved(view, backdrop, tw, th)
            startFollowing(view, backdrop)

            nextKey[0] = tw.toFloat(); nextKey[1] = th.toFloat(); nextKey[2] = w; nextKey[3] = h
            nextKey[4] = radii8[0]; nextKey[5] = radii8[2]; nextKey[6] = radii8[4]; nextKey[7] = radii8[6]
            nextKey[8] = blur; nextKey[9] = cfg[vibrancyKey(matchShade)].toFloat(); nextKey[10] = rh; nextKey[11] = ra
            nextKey[12] = cfg[KEY_DISPERSION].toFloat(); nextKey[13] = cfg[KEY_BRIGHTNESS].toFloat()
            if (!effectValid || !nextKey.contentEquals(effectKey)) {
                contentNode.setRenderEffect(buildEffect(cfg, w, h, pad, blur, rh, ra, cfg[vibrancyKey(matchShade)]))
                nextKey.copyInto(effectKey)
                effectValid = true
            }
            canvas.save()
            canvas.translate(followLeft, followTop)
            canvas.drawRenderNode(contentNode)
            canvas.restore()
        }

        /** Shade surfaces (the ones matching the shade blur) and home-screen glass saturate separately. */
        /** One saturation for every glass surface. */
        @Suppress("UNUSED_PARAMETER")
        private fun vibrancyKey(matchShade: Boolean) = KEY_VIBRANCY

        private fun buildEffect(cfg: Config, w: Float, h: Float, pad: Float, blur: Float, rh: Float, ra: Float, vib: Int): RenderEffect? {
            var effect: RenderEffect? = null
            val bright = cfg[KEY_BRIGHTNESS]
            if (vib != 100 || bright != 0) {
                val filterKey = vib * 1000 + bright
                if (filterKey != vibrancyValue) {
                    vibrancyFilter = saturationFilter(vib / 100f, bright)
                    vibrancyValue = filterKey
                }
                effect = RenderEffect.createColorFilterEffect(vibrancyFilter!!)
            }
            if (blur > 0.5f) {
                val b = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
                effect = if (effect != null) RenderEffect.createChainEffect(b, effect) else b
            }
            if (rh > 0f && ra > 0f) {
                refraction.setFloatUniform("size", w, h)
                refraction.setFloatUniform("offset", -pad, -pad)
                refraction.setFloatUniform("cornerRadii", radii8[0], radii8[2], radii8[4], radii8[6])
                refraction.setFloatUniform("refractionHeight", rh)
                refraction.setFloatUniform("refractionAmount", -ra)
                refraction.setFloatUniform("depthEffect", 0f)
                refraction.setFloatUniform("chromaticAberration", if (cfg.on(KEY_DISPERSION)) 1f else 0f)
                val lens = RenderEffect.createRuntimeShaderEffect(refraction, "content")
                effect = if (effect != null) RenderEffect.createChainEffect(lens, effect) else lens
            }
            return effect
        }

        /**
         * Maps node pixels to backdrop pixels through the view's full transform (translation,
         * scale and parents included) and re-records the node only when that mapping changed.
         * Sub-pixel exact, so moving glass does not shimmer. Returns true when re-recorded.
         */
        private fun recordIfMoved(view: View, backdrop: Bitmap, tw: Int, th: Int): Boolean {
            val dm = view.resources.displayMetrics
            toBitmap.reset()
            // transformMatrixToGlobal already includes the window's position on screen; the
            // touch transform stops at the window root, so only it needs the window offset.
            val inWindow = KyantGlassTouch.transformToGlobal(view, toBitmap)
            if (!inWindow) view.transformMatrixToGlobal(toBitmap)
            toBitmap.preTranslate(followLeft, followTop)
            if (inWindow) {
                view.getLocationOnScreen(screenLoc)
                view.getLocationInWindow(windowLoc)
                toBitmap.postTranslate((screenLoc[0] - windowLoc[0]).toFloat(), (screenLoc[1] - windowLoc[1]).toFloat())
            }
            toBitmap.postScale(
                backdrop.width / dm.widthPixels.toFloat().coerceAtLeast(1f),
                backdrop.height / dm.heightPixels.toFloat().coerceAtLeast(1f)
            )
            toBitmap.getValues(matrixValues)
            val now = android.os.SystemClock.uptimeMillis()
            // A new frame replaces the old one with a short cross-fade, not a jump (e.g. the live
            // wallpaper having moved on while an app was open).
            val previous = recordedBitmap
            if (previous != null && previous !== backdrop && !previous.isRecycled && previous.width == backdrop.width) {
                fadeFrom = previous
                fadeStart = now
            }
            val fading = fadeFrom?.let { !it.isRecycled && now - fadeStart < FADE_MS } == true
            if (!fading) fadeFrom = null
            // hwui drops the node's display list when the window releases its hardware resources
            // (screen off, launcher hidden behind an app): an unchanged mapping must still re-record
            // then, or the glass draws empty until something moves it.
            if (!fading && contentNode.hasDisplayList() && recordedBitmap === backdrop && recordedW == tw && recordedH == th &&
                matrixValues.contentEquals(recordedValues)) return false
            if (!toBitmap.invert(toNode)) return false
            contentNode.setPosition(0, 0, tw, th)
            val rc = contentNode.beginRecording(tw, th)
            rc.concat(toNode)
            val from = fadeFrom
            if (fading && from != null) {
                rc.drawBitmap(from, 0f, 0f, bitmapPaint)
                fadePaint.alpha = ((now - fadeStart) * 255 / FADE_MS).toInt().coerceIn(0, 255)
                rc.drawBitmap(backdrop, 0f, 0f, fadePaint)
                view.postInvalidateOnAnimation()
            } else {
                rc.drawBitmap(backdrop, 0f, 0f, bitmapPaint)
            }
            contentNode.endRecording()
            matrixValues.copyInto(recordedValues)
            recordedBitmap = backdrop
            recordedW = tw
            recordedH = th
            return true
        }

        private fun startFollowing(view: View, backdrop: Bitmap) {
            if (followBitmap?.get() !== backdrop) followBitmap = WeakReference(backdrop)
            val observer = view.viewTreeObserver
            if (followObserver === observer && observer.isAlive && followView?.get() === view) return
            unfollow()
            if (!observer.isAlive) return
            observer.addOnPreDrawListener(preDraw)
            followObserver = observer
            followView = WeakReference(view)
            view.addOnAttachStateChangeListener(detachListener)
        }

        private fun unfollow() {
            followObserver?.let { if (it.isAlive) it.removeOnPreDrawListener(preDraw) }
            followObserver = null
            followView?.get()?.removeOnAttachStateChangeListener(detachListener)
            followView = null
        }

        private fun follow() {
            val view = followView?.get() ?: return
            val bitmap = followBitmap?.get() ?: return
            if (bitmap.isRecycled || recordedW == 0 || !view.isShown) return
            // Off screen (other launcher pages, rows scrolled away): nothing to keep in sync.
            if (!view.getGlobalVisibleRect(visibleRect)) return
            if (recordIfMoved(view, bitmap, recordedW, recordedH)) {
                lastMotionAt = android.os.SystemClock.uptimeMillis()
                // A parent cached in a hardware layer (launcher pages while they scroll) only
                // re-renders what was invalidated: without this it keeps the old glass.
                view.invalidate()
            }
        }

        private val softSrc = android.graphics.Rect()
        private val softPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var softVibrancy = -1

        /** Captures are hardware bitmaps, which a software canvas cannot draw: one copy per frame. */
        private fun softwareCopy(bitmap: Bitmap): Bitmap? {
            if (bitmap.isRecycled) return null
            if (bitmap.config != Bitmap.Config.HARDWARE) return bitmap
            softCache?.let { (source, copy) -> if (source === bitmap && !copy.isRecycled) return copy }
            val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return null
            softCache = bitmap to copy
            return copy
        }

        private var softCache: Pair<Bitmap, Bitmap>? = null

        private fun saturationFilter(s: Float, brightness: Int = 0): ColorFilter {
            val m = ColorMatrix()
            m.setSaturation(s)
            if (brightness != 0) {
                val k = 1f + brightness.coerceIn(-90, 200) / 100f
                m.postConcat(ColorMatrix().apply { setScale(k, k, k, 1f) })
            }
            return ColorMatrixColorFilter(m)
        }
    }

    /**
     * Glass drawable for backgrounds and foregrounds: fills its bounds. [radius] returns the
     * corner radius for the current size, [surface] an optional colour painted over the glass.
     */
    class GlassDrawable(
        view: View,
        private val enabledKey: String,
        private val backdrop: (View) -> Bitmap?,
        private val dim: Boolean = false,
        private val surface: (View) -> Int = { Color.TRANSPARENT },
        private val highlight: Boolean = true,
        /**
         * Optional exact shape: may move or resize [RectF] (it starts as the bounds, and may
         * extend past them) and returns the corner radius, or null to use [radius].
         */
        private val shape: ((View, RectF) -> Float?)? = null,
        private val lensScale: Float = 1f,
        /** The view that stretches after the finger (the host or a parent), null = never. */
        private val elastic: ((View) -> View?)? = null,
        private val radius: (View, Float, Float) -> Float
    ) : Drawable() {
        private val viewRef = WeakReference(view)
        private val renderer = Renderer()
        private val rect = RectF()
        private val radii = FloatArray(4)

        /** Corner radius used by the last draw (other surfaces align to it). */
        var lastRadius = 0f
            private set

        override fun draw(canvas: Canvas) {
            val view = viewRef.get() ?: return
            // An empty key: the surface is switched by its own setting, not a glass toggle.
            if (enabledKey.isNotEmpty() && !config.on(enabledKey)) return
            rect.set(bounds)
            var r = shape?.invoke(view, rect) ?: radius(view, rect.width(), rect.height())
            // Round buttons in slightly non-square views (padding, rounding of dp): draw a true
            // circle centred in the view instead of a lopsided capsule.
            val w = rect.width()
            val h = rect.height()
            val short = minOf(w, h)
            if (kotlin.math.abs(w - h) <= maxOf(w, h) * 0.12f && r >= short * 0.42f) {
                val cx = rect.centerX()
                val cy = rect.centerY()
                rect.set(cx - short / 2f, cy - short / 2f, cx + short / 2f, cy + short / 2f)
                r = short / 2f
            }
            lastRadius = r
            radii.fill(r)
            elastic?.let { KyantGlassTouch.registerHost(view, it) }
            KyantGlassHook.dropVivoEffect(view)
            val frame = backdrop(view)
            renderer.draw(canvas, view, rect, radii, frame, highlight = highlight, dim = dim, surfaceColor = surface(view), lensScale = lensScale)
            // No backdrop yet (a capture is on its way): look again shortly rather than wait for
            // something else to redraw the surface.
            if (frame == null) view.postInvalidateDelayed(BACKDROP_RETRY_MS)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
