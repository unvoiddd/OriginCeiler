package dev.unvoid.originceiler

import android.os.Build

/**
 * BBK Launcher class names that moved between OriginOS 6 (Android 16, launcher 16.x) and
 * OriginOS 7 (Android 17, launcher 17.x): the icon classes left `launcherIcon.ui.icon` for
 * `icon`, and the obfuscated helpers were renamed. Only names are listed here; the hooks keep
 * matching methods by their signatures.
 *
 * OriginOS 7 is detected by Android 17 (API 37). The other set stays as a fallback, since a
 * launcher update can arrive before or after the system update.
 */
object LauncherCompat {
    /** OriginOS 7 ships on Android 17. */
    val originOs7: Boolean get() = Build.VERSION.SDK_INT >= 37

    class Names(
        val itemIcon: String,
        val appIcon: String,
        val componentIcon: String,
        /** The launcher's icon BitmapDrawable (draw + m(Canvas)). */
        val iconDrawable: String,
        /** Launch / close animation icon bounds (RectF a, c; float f, i, j). */
        val iconInfo: String,
        /** Classes whose a(model, String) works those bounds out. */
        val iconInfoHelpers: List<String>,
        /** Classes that rasterize app icons into the icon cache. */
        val rasterizers: List<String>,
    )

    private val OS6 = Names(
        itemIcon = "com.bbk.launcher2.launcherIcon.ui.icon.ItemIcon",
        appIcon = "com.bbk.launcher2.launcherIcon.ui.icon.AppIcon",
        componentIcon = "com.bbk.launcher2.launcherIcon.ui.icon.ComponentIcon",
        iconDrawable = "o7.i0",
        iconInfo = "h.k",
        iconInfoHelpers = listOf("z0.b", "z0.g"),
        rasterizers = listOf(
            "x8.a", "y8.a", "z8.a", "w8.a",
            "com.bbk.launcher2.launcherIcon.LauncherIconImpl",
            "com.bbk.launcher2.launcherIcon.data.iconcache.IconManager"
        ),
    )

    private val OS7 = Names(
        itemIcon = "com.bbk.launcher2.icon.ItemIcon",
        appIcon = "com.bbk.launcher2.icon.AppIcon",
        componentIcon = "com.bbk.launcher2.icon.ComponentIcon",
        iconDrawable = "com.bbk.launcher2.launcherCommon.util.q0",
        iconInfo = "j.l",
        iconInfoHelpers = listOf("e1.b", "e1.g", "e1.i"),
        rasterizers = listOf(
            "h4.a", "y7.b", "v3.a",
            "com.bbk.launcher2.icon.LauncherIconImpl",
            "com.bbk.launcher2.icon.data.iconcache.IconManager"
        ),
    )

    /** Getters of an item model that return the app's ComponentName (OS 6: L4 / X0, OS 7: N2). */
    val componentGetters = listOf("L4", "X0", "N2")

    /** Every icon view class name of both versions (for type checks by name). */
    val iconViewClasses: Set<String> = setOf(
        OS6.itemIcon, OS6.appIcon, OS6.componentIcon,
        OS7.itemIcon, OS7.appIcon, OS7.componentIcon,
    )

    @Volatile private var resolved: Names? = null

    /** The names of the launcher in [classLoader]: the expected version first, the other if it does not fit. */
    fun names(classLoader: ClassLoader): Names {
        resolved?.let { return it }
        val ordered = if (originOs7) listOf(OS7, OS6) else listOf(OS6, OS7)
        val found = ordered.firstOrNull { exists(classLoader, it.itemIcon) } ?: ordered.first()
        resolved = found
        return found
    }

    private fun exists(classLoader: ClassLoader, name: String) =
        runCatching { Class.forName(name, false, classLoader) }.isSuccess
}
