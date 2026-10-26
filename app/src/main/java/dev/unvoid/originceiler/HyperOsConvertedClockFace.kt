package dev.unvoid.originceiler

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

/**
 * 100% native converted HyperOS Lockscreen Clock Face.
 * Implements all authentic HyperOS presets (classic, all_in_one, rhombus,
 * eastern, magazine, doodle) with pure native typography and auto-scaling.
 */
class HyperOsConvertedClockFace @JvmOverloads constructor(
    context: Context,
    val styleId: String,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    private val hyperStyle = NativeHyperOsStyles.find(styleId) ?: NativeHyperOsStyles.ALL[0]
    private val font: Typeface = resolveTypeface(context, hyperStyle)

    private val digitViews = ArrayList<TextView>()
    private val allViews = ArrayList<TextView>()

    private val dateView: TextView
    private var weekView: TextView? = null
    private var singleTimeView: TextView? = null
    private var hourView: TextView? = null
    private var minuteView: TextView? = null

    // Doodle extra badges
    private var doodlePillContainer: LinearLayout? = null
    private var doodleDayView: TextView? = null
    private var doodleMonthView: TextView? = null
    private var rhombusBadgeDrawable: GradientDrawable? = null
    private var doodleMonthDrawable: GradientDrawable? = null
    private var doodleDayDrawable: GradientDrawable? = null

    private var userColor: Int = Color.WHITE

    init {
        clipChildren = false
        clipToPadding = false

        val dp = resources.displayMetrics.density
        val isVertical = hyperStyle.vertical
        val isMagazine = hyperStyle.template.startsWith("magazine")
        val isEastern = hyperStyle.template.startsWith("eastern")
        val isDoodle = hyperStyle.template == "doodle"
        val isRhombus = hyperStyle.template == "rhombus"

        dateView = TextView(context).apply {
            typeface = if (isMagazine) font else Typeface.create("sans-serif", Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (isEastern) 16f else 14f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = true
            isFallbackLineSpacing = true
            maxLines = 1
            setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
        }
        allViews.add(dateView)

        val timeSizeDp = when {
            isRhombus -> 96f
            isDoodle -> 94f
            hyperStyle.id == "hyperos.all_in_one.4" -> 130f
            hyperStyle.id == "hyperos.all_in_one.7" -> 118f
            hyperStyle.id == "hyperos.all_in_one.1" -> 116f
            isVertical -> 112f
            isEastern -> 96f
            isMagazine -> 100f
            else -> 102f
        }

        when {
            // Rhombus / Diamond arrangement
            isRhombus -> {
                hourView = createDigitView(timeSizeDp)
                minuteView = createDigitView(timeSizeDp)

                val diamondLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                container.addView(hourView, diamondLp)

                val dateBadge = FrameLayout(context).apply {
                    val bg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 8 * dp
                        setColor(Color.argb(50, 255, 255, 255))
                    }
                    rhombusBadgeDrawable = bg
                    background = bg
                    val pad = (6 * dp).toInt()
                    setPadding(pad * 2, pad, pad * 2, pad)
                }
                dateBadge.addView(dateView)
                val badgeLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (4 * dp).toInt()
                    bottomMargin = (4 * dp).toInt()
                }
                container.addView(dateBadge, badgeLp)
                container.addView(minuteView, diamondLp)
            }

            // Doodle style
            isDoodle -> {
                val timeRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    clipChildren = false
                    clipToPadding = false
                }
                hourView = createDigitView(timeSizeDp)
                minuteView = createDigitView(timeSizeDp)
                val dotView = TextView(context).apply {
                    typeface = font
                    text = " · "
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, timeSizeDp * 0.7f)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                }
                allViews.add(dotView)

                timeRow.addView(hourView)
                timeRow.addView(dotView)
                timeRow.addView(minuteView)
                container.addView(timeRow)

                val pills = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    clipChildren = false
                    clipToPadding = false
                }
                doodlePillContainer = pills

                doodleMonthView = TextView(context).apply {
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
                    setTextColor(Color.WHITE)
                    val bg = GradientDrawable().apply {
                        cornerRadius = 12 * dp
                        setColor(Color.argb(60, 255, 255, 255))
                    }
                    doodleMonthDrawable = bg
                    background = bg
                    setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                }
                allViews.add(doodleMonthView!!)

                doodleDayView = TextView(context).apply {
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
                    setTextColor(Color.WHITE)
                    val bg = GradientDrawable().apply {
                        cornerRadius = 12 * dp
                        setColor(Color.argb(60, 255, 255, 255))
                    }
                    doodleDayDrawable = bg
                    background = bg
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = (8 * dp).toInt() }
                    layoutParams = lp
                    setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                }
                allViews.add(doodleDayView!!)

                pills.addView(doodleMonthView)
                pills.addView(doodleDayView)

                val pillLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (8 * dp).toInt()
                }
                container.addView(pills, pillLp)
            }

            // Eastern vertical styles (eastern_a, eastern_c)
            isEastern && isVertical -> {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    clipChildren = false
                    clipToPadding = false
                }
                val digitsCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                }
                hourView = createDigitView(timeSizeDp)
                minuteView = createDigitView(timeSizeDp)
                digitsCol.addView(hourView)
                digitsCol.addView(minuteView)
                row.addView(digitsCol)

                val dateCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                    setPadding((14 * dp).toInt(), 0, 0, 0)
                }
                dateCol.addView(dateView)
                row.addView(dateCol)

                container.addView(row)
            }

            // General vertical stacked (all_in_one.3, all_in_one.4, all_in_one.5, all_in_one.7, all_in_one.8, classic.vertical, magazine_a, magazine_c)
            isVertical -> {
                hourView = createDigitView(timeSizeDp)
                minuteView = createDigitView(timeSizeDp)

                val hLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                val mLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (-6 * dp).toInt()
                }
                container.addView(hourView, hLp)
                container.addView(minuteView, mLp)

                val dateLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (6 * dp).toInt()
                }
                container.addView(dateView, dateLp)
            }

            // Horizontal styles (classic.horizontal, all_in_one.1, all_in_one.2, all_in_one.6, eastern_b, magazine_b)
            else -> {
                singleTimeView = createDigitView(timeSizeDp)
                val timeLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                container.addView(singleTimeView, timeLp)

                val dateLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (4 * dp).toInt()
                }
                container.addView(dateView, dateLp)
            }
        }

        val containerLp = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        addView(container, containerLp)

        update(System.currentTimeMillis(), TimeZone.getDefault(), true)
    }

    private fun createDigitView(sizeDp: Float): TextView {
        val dp = resources.displayMetrics.density
        return TextView(context).apply {
            typeface = font
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = true
            isFallbackLineSpacing = true
            maxLines = 1
            setHorizontallyScrolling(false)
            setPadding((8 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt(), (2 * dp).toInt())
            digitViews.add(this)
            allViews.add(this)
        }
    }

    fun update(time: Long, zone: TimeZone, format24: Boolean) {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = time }
        var hour = cal.get(if (format24) Calendar.HOUR_OF_DAY else Calendar.HOUR)
        if (!format24 && hour == 0) hour = 12
        val minute = cal.get(Calendar.MINUTE)

        val hh = String.format(Locale.ROOT, "%02d", hour)
        val mm = String.format(Locale.ROOT, "%02d", minute)

        singleTimeView?.text = "$hh:$mm"
        hourView?.text = hh
        minuteView?.text = mm

        val isEastern = hyperStyle.template.startsWith("eastern")
        val isDoodle = hyperStyle.template == "doodle"

        if (isEastern) {
            val dateStr = SimpleDateFormat("M月d日 EEEE", Locale.CHINESE).apply { timeZone = zone }.format(cal.time)
            dateView.text = dateStr
        } else {
            val dateStr = SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).apply { timeZone = zone }.format(cal.time)
            dateView.text = dateStr
        }

        if (isDoodle) {
            doodleMonthView?.text = SimpleDateFormat("MMM", Locale.getDefault()).apply { timeZone = zone }.format(cal.time).uppercase()
            doodleDayView?.text = SimpleDateFormat("d EEEE", Locale.getDefault()).apply { timeZone = zone }.format(cal.time)
        }

        requestLayout()
        invalidate()
    }

    fun color(color: Int) {
        userColor = color
        for (v in allViews) {
            v.setTextColor(color)
        }
        val r = if (color != 0) Color.red(color) else 255
        val g = if (color != 0) Color.green(color) else 255
        val b = if (color != 0) Color.blue(color) else 255
        val badgeColor = Color.argb(50, r, g, b)
        rhombusBadgeDrawable?.setColor(badgeColor)
        doodleMonthDrawable?.setColor(badgeColor)
        doodleDayDrawable?.setColor(badgeColor)
    }

    fun materialViews(): List<TextView> = allViews

    fun glassView(view: View): Boolean = digitViews.contains(view)

    fun getDigitViews(): List<View> = digitViews.toList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val safetyMargin = (24 * resources.displayMetrics.density).toInt()
        val maxAllowedWidth = max(1, availableWidth - safetyMargin * 2)

        container.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )

        val contentWidth = container.measuredWidth
        val contentHeight = container.measuredHeight

        if (contentWidth > maxAllowedWidth && contentWidth > 0) {
            val scale = maxAllowedWidth.toFloat() / contentWidth.toFloat()
            container.pivotX = contentWidth / 2f
            container.pivotY = 0f
            container.scaleX = scale
            container.scaleY = scale
            setMeasuredDimension(availableWidth, (contentHeight * scale + 16 * resources.displayMetrics.density).toInt())
        } else {
            container.scaleX = 1f
            container.scaleY = 1f
            setMeasuredDimension(availableWidth, contentHeight + (16 * resources.displayMetrics.density).toInt())
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val childWidth = container.measuredWidth
        val childHeight = container.measuredHeight
        val childLeft = (width - childWidth) / 2
        container.layout(childLeft, 0, childLeft + childWidth, childHeight)
    }

    companion object {
        internal fun resolveTypeface(context: Context, style: NativeHyperOsStyles.Style): Typeface {
            val weight = style.weight
            val variation = "'wght' $weight"

            val assetNames = when {
                style.template.startsWith("magazine") -> listOf("MiSerifFVF.ttf", "MiSansVF.ttf")
                style.template.startsWith("eastern") -> listOf("MiClock-Light.otf", "MiSansVF.ttf")
                style.template == "doodle" -> listOf("MiSansRoundedFVF.ttf", "MiSansVF.ttf")
                style.font == 22 -> listOf("MiSansRoundedFVF.ttf", "MiSansVF.ttf")
                style.font == 23 -> listOf("MiSansRCFVF.ttf", "MiSansVF.ttf")
                else -> listOf("MiSansVF.ttf")
            }

            for (asset in assetNames) {
                val fullPath = "native-clock/hyperos/$asset"
                val tf = ClockFontManager.createTypeface(context, fullPath, variation)
                if (tf != Typeface.DEFAULT) return tf
            }

            return Typeface.create("sans-serif", Typeface.BOLD)
        }
    }
}
