package dev.unvoid.originceiler

import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup

/** Diagnostics: the view tree of every window in this process, with what matters for the glass. */
object KyantGlassDebug {
    private const val TAG = "OriginGlassDump"
    /** vivo drops app logs from logcat: the dump also goes to <data dir>/files/glass_dump.txt. */
    private val out = StringBuilder()

    private fun log(line: String) {
        Log.i(TAG, line)
        out.append(line).append('\n')
    }

    /** One line to the dump file, for events seen while running. */
    fun note(context: android.content.Context?, line: String) {
        runCatching {
            val dir = (context?.applicationContext ?: context)?.filesDir ?: return
            java.io.File(dir, "glass_events.txt").appendText(line + "\n")
        }
    }

    fun dumpWindows(context: android.content.Context? = null) {
        out.setLength(0)
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val global = wmg.getMethod("getInstance").invoke(null)
        val rootsField = wmg.getDeclaredField("mRoots").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val roots = (rootsField.get(global) as? List<Any>)?.toList() ?: return
        log("==== ${roots.size} windows")
        for (root in roots) {
            val view = runCatching { root.javaClass.getMethod("getView").invoke(root) as? View }.getOrNull() ?: continue
            if (!view.isShown) continue
            log("==== window ${view.javaClass.name} ${view.width}x${view.height}")
            dump(view, 0)
        }
        log("==== end")
        runCatching {
            val dir = context?.applicationContext?.filesDir ?: return
            java.io.File(dir, "glass_dump.txt").writeText(out.toString())
        }
    }

    private val rect = Rect()
    private val loc = IntArray(2)

    private fun dump(view: View, depth: Int) {
        if (view.visibility != View.VISIBLE || depth > 40) return
        val id = if (view.id == View.NO_ID) "" else runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("?")
        view.getLocationOnScreen(loc)
        val flags = buildString {
            if (view.background != null) append(" bg=").append(view.background.javaClass.simpleName)
            if (view.foreground != null) append(" fg=").append(view.foreground.javaClass.simpleName)
            if (view.layerType != View.LAYER_TYPE_NONE) append(" layer=").append(view.layerType)
            if (view.clipToOutline) append(" clipOutline")
            if (view.clipBounds != null) append(" clipBounds=").append(view.clipBounds)
            if (view is ViewGroup && view.clipChildren) append(" clipChildren")
            if (view.animationMatrix != null) append(" animMatrix")
            if (view.isClickable) append(" clickable")
            if (view.alpha != 1f) append(" a=").append(view.alpha)
            if (view.scaleX != 1f || view.scaleY != 1f) append(" s=").append(view.scaleX).append('x').append(view.scaleY)
            if (view.translationX != 0f || view.translationY != 0f) append(" t=").append(view.translationX).append(',').append(view.translationY)
        }
        log("${"  ".repeat(depth)}${view.javaClass.name} #$id [${loc[0]},${loc[1]} ${view.width}x${view.height}]$flags")
        if (view is ViewGroup) for (i in 0 until view.childCount) dump(view.getChildAt(i), depth + 1)
    }
}
