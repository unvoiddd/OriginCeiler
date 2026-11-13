package dev.unvoid.originceiler

/**
 * Freestyle control center: the layout model. Every control-center element (a vivo tile, the
 * connectivity block, the player, the sliders...) is one item of a 4-column grid, identified by
 * its vivo tile spec, with a size in cells. Items flow in order; each takes the first free spot
 * that fits it (dense packing, like iOS), so small tiles fill the holes left by big ones.
 */
internal data class FsSize(val w: Int, val h: Int) {
    override fun toString() = "${w}x$h"

    companion object {
        val S1x1 = FsSize(1, 1)
        val S2x1 = FsSize(2, 1)
        val S2x2 = FsSize(2, 2)
        val S1x2 = FsSize(1, 2)
        val S1x3 = FsSize(1, 3)
        val S4x1 = FsSize(4, 1)
        val S4x2 = FsSize(4, 2)
        val S4x4 = FsSize(4, 4)

        fun parse(text: String): FsSize? {
            val parts = text.trim().split('x')
            if (parts.size != 2) return null
            val w = parts[0].toIntOrNull() ?: return null
            val h = parts[1].toIntOrNull() ?: return null
            if (w !in 1..FsGrid.COLUMNS || h !in 1..8) return null
            return FsSize(w, h)
        }
    }
}

/** What an item is: decides the sizes it may take and how it is drawn when resized. */
internal enum class FsKind {
    /** A plain 1x1 vivo tile, stock or third-party (TileService): 1x1, 2x1, 2x2. */
    TILE,
    /** Wi-Fi / mobile data / airplane block. */
    CONNECT,
    /** Origin player card. */
    MEDIA,
    /** Brightness / volume sliders. */
    SLIDER,
    /** Focus (sleep / DND) card. */
    FOCUS,
    /** Ring / vibrate pair. */
    RINGER,
    /** Anything vivo adds that we do not know: kept at its own size. */
    OTHER;

    fun sizes(native: FsSize): List<FsSize> = when (this) {
        TILE -> listOf(FsSize.S1x1, FsSize.S2x1, FsSize.S2x2)
        MEDIA -> listOf(FsSize.S2x2, FsSize.S4x1, FsSize.S4x2, FsSize.S4x4)
        SLIDER -> listOf(FsSize.S1x2, FsSize.S1x3, FsSize.S2x2)
        FOCUS -> listOf(FsSize.S1x1, FsSize.S2x1)
        CONNECT, RINGER, OTHER -> listOf(native)
    }

    /** Wider than one item of its own native size: removing it from vivo's list is not possible. */
    val isFixedWidget get() = this != TILE
}

/** The saved arrangement: order, size per spec and the widgets the user took out. */
internal class FsLayout(
    val order: MutableList<String> = mutableListOf(),
    val sizes: MutableMap<String, FsSize> = mutableMapOf(),
    val hidden: MutableSet<String> = linkedSetOf()
) {
    fun copy() = FsLayout(order.toMutableList(), sizes.toMutableMap(), hidden.toMutableSet() as MutableSet<String>)

    /** "spec=WxH;spec=WxH;!hiddenSpec=WxH" — specs never hold ';' or '='. */
    fun serialize(): String = buildString {
        append(VERSION)
        for (spec in order) {
            append(';')
            if (spec in hidden) append('!')
            append(spec).append('=').append(sizes[spec] ?: FsSize.S1x1)
        }
        for (spec in hidden) if (spec !in order) append(";!").append(spec).append('=').append(sizes[spec] ?: FsSize.S1x1)
    }

    companion object {
        private const val VERSION = "fs1"

        fun parse(text: String?): FsLayout? {
            if (text.isNullOrBlank()) return null
            val parts = text.split(';')
            if (parts.firstOrNull() != VERSION) return null
            val layout = FsLayout()
            for (part in parts.drop(1)) {
                val eq = part.lastIndexOf('=')
                if (eq <= 0) continue
                var spec = part.substring(0, eq)
                val hidden = spec.startsWith("!")
                if (hidden) spec = spec.substring(1)
                if (spec.isEmpty() || spec in layout.sizes) continue
                val size = FsSize.parse(part.substring(eq + 1)) ?: continue
                layout.order += spec
                layout.sizes[spec] = size
                if (hidden) layout.hidden += spec
            }
            return layout
        }
    }
}

internal data class FsCell(val col: Int, val row: Int, val size: FsSize)

internal object FsGrid {
    const val COLUMNS = 4

    /** Dense first-fit packing of [items] (spec to size) in order. Returns the cell of each spec and the row count. */
    fun pack(items: List<Pair<String, FsSize>>, columns: Int = COLUMNS): Pair<Map<String, FsCell>, Int> {
        val rows = ArrayList<BooleanArray>()
        val out = LinkedHashMap<String, FsCell>()
        fun free(c: Int, r: Int, w: Int, h: Int): Boolean {
            for (y in r until r + h) {
                val row = rows.getOrNull(y) ?: continue
                for (x in c until c + w) if (row[x]) return false
            }
            return true
        }
        for ((spec, raw) in items) {
            val size = FsSize(raw.w.coerceIn(1, columns), raw.h.coerceAtLeast(1))
            var placed = false
            var r = 0
            while (!placed) {
                for (c in 0..columns - size.w) {
                    if (free(c, r, size.w, size.h)) {
                        while (rows.size < r + size.h) rows += BooleanArray(columns)
                        for (y in r until r + size.h) for (x in c until c + size.w) rows[y][x] = true
                        out[spec] = FsCell(c, r, size)
                        placed = true
                        break
                    }
                }
                r++
            }
        }
        return out to rows.size
    }
}
