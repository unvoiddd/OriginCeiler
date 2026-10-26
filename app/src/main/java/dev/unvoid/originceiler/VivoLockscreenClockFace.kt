package dev.unvoid.originceiler

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone

class VivoLockscreenClockFace(
    context: Context,
    private val original: OfficialVivoClockUi,
    val style: LockscreenClockStyle,
    private val aod: Boolean = false,
    private val compact: Boolean = false
) : FrameLayout(context) {

    private val content: View
    private val labels = ArrayList<TextView>()
    private val numbers = ArrayList<TextView>()
    private val dimensions = IdentityHashMap<View, HashMap<String, Int>>()
    private var fit = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var nativeHeight = 0
    private val body = RectF()

    init {
        clipChildren = false
        clipToPadding = false

        content = original.inflate(style.group, object : LayoutInflater.Factory2 {
            override fun onCreateView(parent: View?, name: String, c: Context, attrs: AttributeSet): View? {
                return onCreateView(name, c, attrs)
            }

            override fun onCreateView(name: String, c: Context, attrs: AttributeSet): View? {
                val view = original.create(name, attrs)
                if (view != null) {
                    val refs = HashMap<String, Int>()
                    val attrNames = arrayOf(
                        "textSize",
                        "layout_width",
                        "layout_height",
                        "layout_marginTop",
                        "layout_marginBottom",
                        "layout_marginStart",
                        "layout_marginEnd"
                    )
                    for (key in attrNames) {
                        val id = attrs.getAttributeResourceValue("http://schemas.android.com/apk/res/android", key, 0)
                        if (id != 0) refs[key] = id
                    }
                    dimensions[view] = refs
                }
                return view
            }
        })

        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        collect(content)

        val font = resolveTypeface()
        for (text in labels) {
            val name = name(text)
            val digit = name == "hour" || name == "minute" || name == "colon" ||
                    name.startsWith("hour_") || name.startsWith("minute_")
            if (digit) {
                numbers.add(text)
                text.typeface = font
                text.maxLines = 1
                text.setHorizontallyScrolling(false)
            }
            text.setTextColor(Color.WHITE)
            if (name == "info") {
                text.visibility = GONE
            } else if (!digit) {
                text.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
        }

        // Layout corrections matching Vivo TimeComponent
        if (style.group == 8 || style.group == 9 || style.group == 11) {
            for (text in numbers) {
                val p = text.layoutParams
                p.height = dimension("minute_hour_height", p.height)
                if (p is MarginLayoutParams) {
                    p.topMargin = dimension("${name(text)}_margin_top", p.topMargin)
                }
                text.layoutParams = p
            }
        }
        if (style.group == 11) {
            val dateId = original.resources.getIdentifier("ll_date", "id", OfficialVivoClockUi.PACKAGE)
            val dateView = if (dateId != 0) content.findViewById<View>(dateId) else null
            if (dateView?.layoutParams is MarginLayoutParams) {
                val p = dateView.layoutParams as MarginLayoutParams
                p.topMargin = dimension("date_margin_top", p.topMargin)
                dateView.layoutParams = p
            }
        }
        if (style.group == 5) {
            for (name in arrayOf("hour", "ll_info_week")) {
                val id = original.resources.getIdentifier(name, "id", OfficialVivoClockUi.PACKAGE)
                val view = if (id != 0) content.findViewById<View>(id) else null
                if (view?.layoutParams is MarginLayoutParams) {
                    val p = view.layoutParams as MarginLayoutParams
                    runCatching {
                        p.javaClass.getField("bottomToBottom").setInt(p, -1)
                        p.javaClass.getField("topToTop").setInt(p, 0)
                    }
                    val key = if (name == "hour") "vivo_keyguard_s5_hour_margin_top" else "vivo_keyguard_s5_ll_week_info_margin_top"
                    val dimenId = original.resources.getIdentifier(key, "dimen", OfficialVivoClockUi.PACKAGE)
                    if (dimenId != 0) {
                        p.topMargin = original.resources.getDimensionPixelSize(dimenId)
                        view.layoutParams = p
                    }
                }
            }
        }

        update(System.currentTimeMillis(), TimeZone.getDefault(), true)
    }

    private fun resolveTypeface(): Typeface {
        val ytdeSetting = ytde()
        val variation = "'wght' ${style.weight}$ytdeSetting"

        val systemFontFile = File("/system/fonts/${style.font}")
        if (systemFontFile.isFile && systemFontFile.canRead()) {
            val tf = runCatching {
                Typeface.Builder(systemFontFile).setFontVariationSettings(variation).build()
            }.getOrNull()
            if (tf != null && tf != Typeface.DEFAULT) return tf
        }

        val moduleCtx = LockscreenClockRuntime.getModuleContext(context)
        val assetPath = "native-clock/vivo/${style.font}"
        val fromAsset = runCatching {
            Typeface.Builder(moduleCtx.assets, assetPath).setFontVariationSettings(variation).build()
        }.getOrNull()

        if (fromAsset != null && fromAsset != Typeface.DEFAULT) return fromAsset

        return Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun ytde(): String {
        if (style.group != 7 && style.group != 8 && style.group != 9 && style.group != 12) return ""
        val key = if (style.group == 7 || style.group == 12) {
            "vivo_keyguard_s${style.group}_time_${style.grid}_ytde"
        } else {
            "vivo_keyguard_s${style.group}_${style.grid}_time_text_ytde"
        }
        val id = original.resources.getIdentifier(key, "dimen", OfficialVivoClockUi.PACKAGE)
        return if (id != 0) ", 'ytde' ${original.resources.getDimension(id)}" else ""
    }

    private fun dimension(suffix: String, fallback: Int): Int {
        val id = original.resources.getIdentifier("vivo_keyguard_s${style.group}_${style.grid}_$suffix", "dimen", OfficialVivoClockUi.PACKAGE)
        return if (id == 0) fallback else original.resources.getDimensionPixelSize(id)
    }

    private fun name(view: View): String {
        return if (view.id == NO_ID) "" else runCatching { original.resources.getResourceEntryName(view.id) }.getOrDefault("")
    }

    private fun collect(view: View) {
        val refs = dimensions[view]
        if (refs != null) {
            for ((key, valueId) in refs) {
                val resource = runCatching { original.resources.getResourceEntryName(valueId) }.getOrNull() ?: continue
                var chosen = resource.replace(Regex("_[234]x[1-6]_"), "_${style.grid}_")
                if (chosen == resource && !resource.matches(Regex(".*_[234]x[1-6]_.*"))) {
                    chosen = resource.replace("vivo_keyguard_s${style.group}_", "vivo_keyguard_s${style.group}_${style.grid}_")
                }
                var id = original.resources.getIdentifier(chosen, "dimen", OfficialVivoClockUi.PACKAGE)
                if (id == 0) id = valueId
                val value = runCatching { original.resources.getDimensionPixelSize(id) }.getOrDefault(0)
                val p = view.layoutParams
                when (key) {
                    "textSize" -> if (view is TextView && value > 0) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, value.toFloat())
                    "layout_width" -> if (p != null && value > 0) p.width = value
                    "layout_height" -> if (p != null && value > 0) p.height = value
                    else -> if (p is MarginLayoutParams) {
                        when (key) {
                            "layout_marginTop" -> p.topMargin = value
                            "layout_marginBottom" -> p.bottomMargin = value
                            "layout_marginStart" -> p.marginStart = value
                            "layout_marginEnd" -> p.marginEnd = value
                        }
                    }
                }
            }
        }
        if (view is TextView) labels.add(view)
        if (view is ViewGroup) {
            view.clipChildren = false
            view.clipToPadding = false
            for (i in 0 until view.childCount) {
                collect(view.getChildAt(i))
            }
        }
    }

    fun update(time: Long, zone: TimeZone, format24: Boolean) {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = time }
        var hour = cal.get(if (format24) Calendar.HOUR_OF_DAY else Calendar.HOUR)
        if (!format24 && hour == 0) hour = 12
        val hh = String.format(Locale.ROOT, "%02d", hour)
        val mm = String.format(Locale.ROOT, "%02d", cal.get(Calendar.MINUTE))
        val dateFormat = SimpleDateFormat("M月d日 E", Locale.getDefault()).apply { timeZone = zone }

        for (text in labels) {
            val name = name(text)
            var value: String? = null
            when (name) {
                "hour" -> value = if (style.group in listOf(1, 2, 4, 5, 6, 7, 12)) "$hh:$mm" else hh
                "minute" -> value = mm
                "hour_first" -> value = hh.substring(0, 1)
                "hour_second" -> value = hh.substring(1)
                "minute_first" -> value = mm.substring(0, 1)
                "minute_second" -> value = mm.substring(1)
                "colon" -> {
                    value = ":"
                    text.visibility = if (style.group in listOf(3, 8) || style.group >= 10) GONE else VISIBLE
                }
                "date" -> value = if (style.group in listOf(5, 6)) "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}" else dateFormat.format(cal.time)
                "week" -> value = SimpleDateFormat("EEEE", Locale.getDefault()).apply { timeZone = zone }.format(cal.time)
            }
            if (value != null) text.text = value
        }
        requestLayout()
        invalidate()
    }

    fun setColor(color: Int) {
        for (text in labels) text.setTextColor(color)
    }

    private fun bounds(view: View): RectF {
        val r = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        if (view is TextView && view.text.isNotEmpty()) {
            val ink = Rect()
            view.paint.getTextBounds(view.text.toString(), 0, view.text.length, ink)
            r.set(ink)
            r.offset(view.compoundPaddingLeft.toFloat(), view.baseline.toFloat())
            view.layout?.let { r.offset(it.getLineLeft(0), 0f) }
        }
        var node: View? = view
        while (node != null && node !== content && node.parent is View) {
            r.offset(node.left + node.translationX, node.top + node.translationY)
            node = node.parent as? View
        }
        return r
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val width = MeasureSpec.getSize(ws)
        nativeHeight = resources.displayMetrics.heightPixels

        for (label in labels) {
            if (!label.isFallbackLineSpacing) label.isFallbackLineSpacing = true
        }

        content.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(nativeHeight, MeasureSpec.EXACTLY)
        )
        content.layout(0, 0, width, nativeHeight)

        body.setEmpty()
        for (text in labels) {
            if (text.visibility == VISIBLE) body.union(bounds(text))
        }

        val margin = resources.displayMetrics.density * 2f
        fit = (if (body.width() > 0) (width - margin * 2f) / body.width() else 1f).coerceAtMost(1f)

        if (aod || compact) {
            val id = original.resources.getIdentifier("vivo_keyguard_s${style.group}_integration_aod_time_text_size", "dimen", OfficialVivoClockUi.PACKAGE)
            val size = if (id == 0) 48f * resources.displayMetrics.density else original.resources.getDimension(id)
            val base = if (numbers.isNotEmpty()) numbers[0].textSize else size
            fit = fit.coerceAtMost(if (base > 0) size / base else 1f)
        }

        offsetX = if (aod || compact) {
            (width - body.width() * fit) / 2f - body.left * fit
        } else {
            (margin - body.left * fit).coerceAtLeast((width - margin - body.right * fit).coerceAtMost(0f))
        }
        offsetY = -body.top * fit + margin

        val measuredHeight = Math.ceil((body.height() * fit + margin * 2f).toDouble()).toInt().coerceAtLeast(1)
        setMeasuredDimension(width, measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content.layout(0, 0, r - l, nativeHeight)
        content.pivotX = 0f
        content.pivotY = 0f
        content.scaleX = fit
        content.scaleY = fit
        content.translationX = offsetX
        content.translationY = offsetY
    }
}
