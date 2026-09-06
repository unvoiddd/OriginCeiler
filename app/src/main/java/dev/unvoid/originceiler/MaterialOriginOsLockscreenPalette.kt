package dev.unvoid.originceiler

import android.content.Context
import android.content.res.Configuration

object MaterialOriginOsLockscreenPalette {
    data class Colors(val background: Int, val foreground: Int)

    fun resolve(context: Context): Colors {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return if (dark) {
            Colors(
                systemColor(context, "system_accent1_200", 0xffd0bcff.toInt()),
                systemColor(context, "system_accent1_800", 0xff381e72.toInt())
            )
        } else {
            Colors(
                systemColor(context, "system_accent1_600", 0xff6750a4.toInt()),
                systemColor(context, "system_accent1_100", 0xffeaddff.toInt())
            )
        }
    }

    private fun systemColor(context: Context, name: String, fallback: Int): Int {
        val id = context.resources.getIdentifier(name, "color", "android")
        return if (id != 0) runCatching { context.getColor(id) }.getOrDefault(fallback) else fallback
    }
}
