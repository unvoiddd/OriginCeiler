package dev.unvoid.originceiler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

data class LockscreenClockStyle(
    val id: String,
    val key: String,
    val title: String,
    val category: String, // "Vivo", "HyperOS", "Flyme", "Pixel"
    val previewAsset: String,
    val isVertical: Boolean = false,
    val group: Int = 1,
    val grid: String = "4x2",
    val font: String = "vivoSansClockVF.ttf",
    val weight: Int = 400
)

object LockscreenClockCatalog {
    const val CATEGORY_ALL = "All"
    const val CATEGORY_VIVO = "Vivo"
    const val CATEGORY_HYPEROS = "HyperOS"
    const val CATEGORY_FLYME = "Flyme"
    const val CATEGORY_PIXEL = "Pixel"

    const val DEFAULT_STYLE_ID = "s1-4x2"

    private val VIVO_GROUP_NAMES = arrayOf(
        "",
        "Классический горизонт",
        "Классический вертикал",
        "Левый горизонт",
        "Гравированный горизонт",
        "Гравированный тонкий",
        "Гравированный вертикал",
        "Широкие цифры",
        "Широкий вертикал",
        "Раздельные цифры",
        "Шелковая сетка",
        "Шелковая колонка",
        "Квадратные цифры"
    )

    private val HYPEROS_STYLES = listOf(
        LockscreenClockStyle("hyperos.classic.vertical", "classic.vertical", "HyperOS · 经典纵排", CATEGORY_HYPEROS, "native-clock/previews/hyperos-classic-vertical.png", isVertical = true),
        LockscreenClockStyle("hyperos.classic.horizontal", "classic.horizontal", "HyperOS · 经典横排", CATEGORY_HYPEROS, "native-clock/previews/hyperos-classic-horizontal.png"),
        LockscreenClockStyle("hyperos.all_in_one.1", "all_in_one.1", "HyperOS · 经典大字", CATEGORY_HYPEROS, "native-clock/previews/hyperos-all_in_one-1.png"),
        LockscreenClockStyle("hyperos.all_in_one.2", "all_in_one.2", "HyperOS · 经典横排新款", CATEGORY_HYPEROS, "native-clock/previews/hyperos-all_in_one-2.png"),
        LockscreenClockStyle("hyperos.all_in_one.3", "all_in_one.3", "HyperOS · 经典双行", CATEGORY_HYPEROS, "native-clock/previews/hyperos-all_in_one-3.png", isVertical = true),
        LockscreenClockStyle("hyperos.all_in_one.4", "all_in_one.4", "HyperOS · 大字 A", CATEGORY_HYPEROS, "native-clock/previews/hyperos-all_in_one-4.png", isVertical = true),
        LockscreenClockStyle("hyperos.all_in_one.5", "all_in_one.5", "HyperOS · 大字 B", CATEGORY_HYPEROS, "native-clock/previews/hyperos-all_in_one-5.png"),
        LockscreenClockStyle("hyperos.all_in_one.6", "all_in_one.6", "HyperOS · 杂志数字", CATEGORY_HYPEROS, "native-clock/previews/hyperos-all_in_one-6.png"),
        LockscreenClockStyle("hyperos.all_in_one.7", "all_in_one.7", "HyperOS · 大字 C", CATEGORY_HYPEROS, "native-clock/previews/hyperos-all_in_one-7.png", isVertical = true),
        LockscreenClockStyle("hyperos.all_in_one.8", "all_in_one.8", "HyperOS · 大字 D", CATEGORY_HYPEROS, "native-clock/previews/hyperos-all_in_one-8.png"),
        LockscreenClockStyle("hyperos.rhombus.4", "rhombus.4", "HyperOS · 菱形", CATEGORY_HYPEROS, "native-clock/previews/hyperos-rhombus-4.png", isVertical = true),
        LockscreenClockStyle("hyperos.eastern_a.1", "eastern_a.1", "HyperOS · 东方美学 A", CATEGORY_HYPEROS, "native-clock/previews/hyperos-eastern_a-1.png", isVertical = true),
        LockscreenClockStyle("hyperos.eastern_b.2", "eastern_b.2", "HyperOS · 东方美学 B", CATEGORY_HYPEROS, "native-clock/previews/hyperos-eastern_b-2.png", isVertical = true),
        LockscreenClockStyle("hyperos.eastern_c.1", "eastern_c.1", "HyperOS · 东方美学 C", CATEGORY_HYPEROS, "native-clock/previews/hyperos-eastern_c-1.png", isVertical = true),
        LockscreenClockStyle("hyperos.magazine_a.3", "magazine_a.3", "HyperOS · 杂志 A", CATEGORY_HYPEROS, "native-clock/previews/hyperos-magazine_a-3.png"),
        LockscreenClockStyle("hyperos.magazine_b.1", "magazine_b.1", "HyperOS · 杂志 B", CATEGORY_HYPEROS, "native-clock/previews/hyperos-magazine_b-1.png"),
        LockscreenClockStyle("hyperos.magazine_c.5", "magazine_c.5", "HyperOS · 杂志 C", CATEGORY_HYPEROS, "native-clock/previews/hyperos-magazine_c-5.png"),
        LockscreenClockStyle("hyperos.doodle.1", "doodle.1", "HyperOS · 涂鸦", CATEGORY_HYPEROS, "native-clock/previews/hyperos-doodle-1.png")
    )

    private val FLYME_STYLES = listOf(
        LockscreenClockStyle(
            id = "flyme.perspective",
            key = "flyme.perspective",
            title = "Flyme · 3D 透视时钟",
            category = CATEGORY_FLYME,
            previewAsset = "native-clock/previews/flyme-perspective.png",
            isVertical = true
        ),
        LockscreenClockStyle(
            id = "flyme.alive.vertical",
            key = "flyme.alive.vertical",
            title = "Flyme · 经典纵排",
            category = CATEGORY_FLYME,
            previewAsset = "",
            isVertical = true
        ),
        LockscreenClockStyle(
            id = "flyme.alive.horizontal",
            key = "flyme.alive.horizontal",
            title = "Flyme · 经典横排",
            category = CATEGORY_FLYME,
            previewAsset = "",
            isVertical = false
        )
    )

    private val PIXEL_STYLES = listOf(
        LockscreenClockStyle(
            id = "pixel.expressive",
            key = "pixel.expressive",
            title = "Pixel · Expressive Flex",
            category = CATEGORY_PIXEL,
            previewAsset = "",
            isVertical = true
        )
    )

    private var cachedVivoStyles: List<LockscreenClockStyle>? = null
    private var cachedAllStyles: List<LockscreenClockStyle>? = null

    @Synchronized
    fun getVivoStyles(context: Context): List<LockscreenClockStyle> {
        cachedVivoStyles?.let { return it }

        val list = mutableListOf<LockscreenClockStyle>()
        runCatching {
            val stream = LockscreenClockRuntime.openAsset(context, "native-clock/vivo/clock_styles.json")
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                bytes.write(buffer, 0, read)
            }
            stream.close()

            val jsonArray = JSONArray(bytes.toString("UTF-8"))
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val key = obj.getString("style_id")
                val groupStr = obj.getString("style_group")
                val group = runCatching { groupStr.substring(1).toInt() }.getOrDefault(1)
                val grid = if (key.contains("-")) key.substring(key.indexOf('-') + 1) else "4x2"
                val fontObj = obj.getJSONObject("default_font")
                val fontPath = fontObj.getString("font_path")
                val fontName = if (fontPath.startsWith("/system/fonts/")) fontPath.substring("/system/fonts/".length) else fontPath
                val weight = fontObj.getInt("font_weight")

                val groupName = VIVO_GROUP_NAMES.getOrElse(group) { "Группа $group" }
                val title = "Vivo · $groupName $grid"
                val previewAsset = "native-clock/vivo/previews/$key.png"
                val isVertical = group in listOf(2, 3, 6, 8, 9, 11)

                list.add(
                    LockscreenClockStyle(
                        id = key,
                        key = key,
                        title = title,
                        category = CATEGORY_VIVO,
                        previewAsset = previewAsset,
                        isVertical = isVertical,
                        group = group,
                        grid = grid,
                        font = fontName,
                        weight = weight
                    )
                )
            }
        }.onFailure {
            val fallbackKeys = arrayOf(
                "s1-4x2", "s2-3x2", "s3-2x3", "s4-3x2", "s5-4x1", "s6-2x2",
                "s7-4x4", "s7-4x3", "s7-4x5", "s7-4x6", "s7-4x2", "s7-3x2", "s7-3x3", "s7-3x4",
                "s8-2x5", "s8-2x4", "s8-2x6", "s8-3x5", "s8-3x6", "s8-4x5", "s8-4x6",
                "s9-2x4", "s9-2x5", "s9-2x6", "s9-3x5", "s9-3x6", "s9-4x5", "s9-4x6",
                "s10-4x4", "s10-4x5", "s10-4x6", "s11-2x4", "s11-2x5", "s11-2x6",
                "s12-4x4", "s12-4x3", "s12-4x5", "s12-4x6", "s12-4x2", "s12-3x2", "s12-3x3", "s12-3x4"
            )
            for (key in fallbackKeys) {
                val group = runCatching { key.substring(1, key.indexOf('-')).toInt() }.getOrDefault(1)
                val grid = key.substring(key.indexOf('-') + 1)
                val groupName = VIVO_GROUP_NAMES.getOrElse(group) { "Группа $group" }
                val font = when (group) {
                    12 -> "vivoSansClockHASquareVF.ttf"
                    10, 11 -> "vivoSansClockSilkVF.ttf"
                    7, 8, 9 -> "vivoSansClockHAVF-OS7.ttf"
                    4, 5, 6 -> "vivoSansClockStencilVF.ttf"
                    else -> "vivoSansClockVF.ttf"
                }
                val weight = when (group) {
                    7, 8, 9, 10, 11, 12 -> 850
                    1 -> 600
                    else -> 500
                }
                list.add(
                    LockscreenClockStyle(
                        id = key,
                        key = key,
                        title = "Vivo · $groupName $grid",
                        category = CATEGORY_VIVO,
                        previewAsset = "native-clock/vivo/previews/$key.png",
                        isVertical = group in listOf(2, 3, 6, 8, 9, 11),
                        group = group,
                        grid = grid,
                        font = font,
                        weight = weight
                    )
                )
            }
        }

        cachedVivoStyles = list
        return list
    }

    @Synchronized
    fun getAllStyles(context: Context): List<LockscreenClockStyle> {
        cachedAllStyles?.let { return it }
        val all = mutableListOf<LockscreenClockStyle>()
        all.addAll(getVivoStyles(context))
        all.addAll(HYPEROS_STYLES)
        all.addAll(FLYME_STYLES)
        all.addAll(PIXEL_STYLES)
        cachedAllStyles = all
        return all
    }

    fun findStyle(context: Context, id: String): LockscreenClockStyle {
        val cleanId = id.removePrefix("org.aliveclean.clock.vivo.")
            .removePrefix("vivo.")
        return getAllStyles(context).firstOrNull {
            it.id == id || it.id == cleanId || it.key == cleanId
        } ?: getVivoStyles(context).firstOrNull() ?: LockscreenClockStyle(
            id = DEFAULT_STYLE_ID,
            key = DEFAULT_STYLE_ID,
            title = "Vivo · 经典横排 4x2",
            category = CATEGORY_VIVO,
            previewAsset = "native-clock/vivo/previews/s1-4x2.png"
        )
    }
}
