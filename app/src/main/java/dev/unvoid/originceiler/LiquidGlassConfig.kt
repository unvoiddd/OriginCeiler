package dev.unvoid.originceiler

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

data class LiquidGlassConfig(
    val enableLiquidGlass: Boolean = true,
    val enableBlur: Boolean = true,
    val liquidglassMaterial: Int = 0, // 0 = Regular/Adaptive, 1 = Clear
    val liquidglassPreset: Int = 1,   // 0 = Light, 1 = Standard, 2 = Strong
    val shadeBackdropBlurPercent: Int = 80,
    val headsUpBlurPercent: Int = 100,
    val sidebarBlurPercent: Int = 100,
    val keyguardGlassBlurPercent: Int = 100,
    val islandGlassTransparencyPercent: Int = 70,
    val blurGlassOpacity: Int = 40,
    val blurGlassRadiusPx: Int = 8
) {
    companion object {
        const val PREF_ENABLE_LIQUIDGLASS = "enable_liquidglass"
        const val PREF_ENABLE_BLUR = "enable_blur"
        const val PREF_MATERIAL = "liquidglass_material"
        const val PREF_PRESET = "liquidglass_preset"
        const val PREF_SHADE_BLUR = "shade_backdrop_blur_percent"
        const val PREF_HEADS_UP_BLUR = "heads_up_blur_percent"
        const val PREF_SIDEBAR_BLUR = "sidebar_blur_percent"
        const val PREF_KEYGUARD_BLUR = "keyguard_glass_blur_percent"
        const val PREF_ISLAND_TRANSPARENCY = "island_glass_transparency_percent"
        const val PREF_BLUR_GLASS_OPACITY = "blur_glass_opacity"
        const val PREF_BLUR_GLASS_RADIUS_PX = "blur_glass_radius_px"

        const val SETTING_PREFIX = "originroottoolbox_liquidglass_"

        @Volatile
        private var cachedConfig = LiquidGlassConfig()

        @Volatile
        private var lastReadTime = 0L

        @Volatile
        private var observerRegistered = false

        private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

        fun registerChangeListener(listener: () -> Unit) {
            listeners.add(listener)
        }

        fun get(context: Context?): LiquidGlassConfig {
            val now = SystemClock.uptimeMillis()
            if (now - lastReadTime < 800L) {
                return cachedConfig
            }
            if (context == null) return cachedConfig

            return runCatching {
                val resolver = context.contentResolver ?: return cachedConfig

                registerObserverIfNeeded(context)

                fun getGlobalInt(key: String, default: Int): Int {
                    return Settings.Global.getInt(resolver, SETTING_PREFIX + key, default)
                }
                fun getGlobalBool(key: String, default: Boolean): Boolean {
                    return Settings.Global.getInt(resolver, SETTING_PREFIX + key, if (default) 1 else 0) == 1
                }

                val config = LiquidGlassConfig(
                    enableLiquidGlass = getGlobalBool(PREF_ENABLE_LIQUIDGLASS, true),
                    enableBlur = getGlobalBool(PREF_ENABLE_BLUR, true),
                    liquidglassMaterial = getGlobalInt(PREF_MATERIAL, 0).coerceIn(0, 1),
                    liquidglassPreset = getGlobalInt(PREF_PRESET, 1).coerceIn(0, 2),
                    shadeBackdropBlurPercent = getGlobalInt(PREF_SHADE_BLUR, 80).coerceIn(0, 200),
                    headsUpBlurPercent = getGlobalInt(PREF_HEADS_UP_BLUR, 100).coerceIn(0, 100),
                    sidebarBlurPercent = getGlobalInt(PREF_SIDEBAR_BLUR, 100).coerceIn(0, 100),
                    keyguardGlassBlurPercent = getGlobalInt(PREF_KEYGUARD_BLUR, 100).coerceIn(0, 100),
                    islandGlassTransparencyPercent = getGlobalInt(PREF_ISLAND_TRANSPARENCY, 70).coerceIn(0, 100),
                    blurGlassOpacity = getGlobalInt(PREF_BLUR_GLASS_OPACITY, 40).coerceIn(0, 100),
                    blurGlassRadiusPx = getGlobalInt(PREF_BLUR_GLASS_RADIUS_PX, 8).coerceIn(0, 32)
                )
                cachedConfig = config
                lastReadTime = now
                config
            }.getOrDefault(cachedConfig)
        }

        private fun registerObserverIfNeeded(context: Context) {
            if (observerRegistered) return
            runCatching {
                val resolver = context.contentResolver ?: return
                val uri = Settings.Global.getUriFor(SETTING_PREFIX + PREF_ENABLE_LIQUIDGLASS)
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        lastReadTime = 0L
                        listeners.forEach { runCatching { it() } }
                    }
                }
                resolver.registerContentObserver(uri, false, observer)
                observerRegistered = true
            }
        }

        fun invalidate() {
            lastReadTime = 0L
            listeners.forEach { runCatching { it() } }
        }
    }
}
