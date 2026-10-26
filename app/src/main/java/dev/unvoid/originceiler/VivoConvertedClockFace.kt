package dev.unvoid.originceiler

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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
 * 100% native converted Vivo lockscreen clock face.
 * Implements authentic typography, layout geometry, variable 'ytde'/'wght' font axes,
 * auto-scaling, and date positioning for all 12 Vivo clock groups (all 42 styles).
 */
class VivoConvertedClockFace @JvmOverloads constructor(
    context: Context,
    val style: LockscreenClockStyle,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    private val font: Typeface = resolveTypeface(context, style)
    private val digitViews = ArrayList<TextView>()
    private val allViews = ArrayList<TextView>()
    private val dateView: TextView

    // Views for single-line time (s1, s2, s4, s5, s7, s12)
    private var singleTimeView: TextView? = null

    // Views for stacked time (s3, s6, s8, s10, s11)
    private var hourView: TextView? = null
    private var minuteView: TextView? = null

    // Views for split columns (s9)
    private var h1View: TextView? = null
    private var h2View: TextView? = null
    private var m1View: TextView? = null
    private var m2View: TextView? = null
    private var weekView: TextView? = null

    private var userColor: Int = Color.WHITE

    init {
        clipChildren = false
        clipToPadding = false

        val dp = resources.displayMetrics.density
        val timeSizeDp = getTimeTextSizeDp(style.group, style.grid)
        val dateSizeDp = getDateTextSizeDp(style.group, style.grid)

        dateView = TextView(context).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, dateSizeDp)
            setTextColor(Color.WHITE)
            gravity = if (style.group == 2) Gravity.START else Gravity.CENTER
            includeFontPadding = true
            isFallbackLineSpacing = true
            maxLines = 1
            setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
        }
        allViews.add(dateView)

        when (style.group) {
            // Group 9: Split column digits: [H1 over M1] - [Date/Week] - [H2 over M2]
            9 -> {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    clipChildren = false
                    clipToPadding = false
                }

                val col1 = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                }
                h1View = createDigitView(timeSizeDp)
                m1View = createDigitView(timeSizeDp)
                col1.addView(h1View)
                col1.addView(m1View)

                val centerCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    clipChildren = false
                    clipToPadding = false
                    setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                }
                weekView = TextView(context).apply {
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, dateSizeDp)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    includeFontPadding = true
                    isFallbackLineSpacing = true
                    maxLines = 1
                }
                allViews.add(weekView!!)
                centerCol.addView(dateView)
                centerCol.addView(weekView)

                val col2 = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                }
                h2View = createDigitView(timeSizeDp)
                m2View = createDigitView(timeSizeDp)
                col2.addView(h2View)
                col2.addView(m2View)

                row.addView(col1)
                row.addView(centerCol)
                row.addView(col2)

                container.addView(row)
            }

            // Groups 7 & 12: Large single line horizontal time with Date ABOVE
            7, 12 -> {
                val dateLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = (6 * dp).toInt()
                }
                container.addView(dateView, dateLp)

                singleTimeView = createDigitView(timeSizeDp)
                val timeLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                container.addView(singleTimeView, timeLp)
            }

            // Groups 8 & 10: Stacked HH over MM with Date ABOVE
            8, 10 -> {
                val dateLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = (6 * dp).toInt()
                }
                container.addView(dateView, dateLp)

                val hView = createDigitView(timeSizeDp)
                val mView = createDigitView(timeSizeDp)
                hourView = hView
                minuteView = mView
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
                    topMargin = (-12 * dp).toInt()
                }
                container.addView(hView, hLp)
                container.addView(mView, mLp)
            }

            // Group 11: Columns of HH and MM
            11 -> {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    clipChildren = false
                    clipToPadding = false
                }
                val hView = createDigitView(timeSizeDp)
                val mView = createDigitView(timeSizeDp)
                mView.setPadding((12 * dp).toInt(), 0, 0, 0)
                hourView = hView
                minuteView = mView
                row.addView(hView)
                row.addView(mView)
                container.addView(row)

                val dateLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (6 * dp).toInt()
                }
                container.addView(dateView, dateLp)
            }

            // Groups 3 & 6: Stacked HH over MM with Date below
            3, 6 -> {
                val hView = createDigitView(timeSizeDp)
                val mView = createDigitView(timeSizeDp)
                hourView = hView
                minuteView = mView
                container.addView(hView)
                container.addView(mView)

                val dateLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (6 * dp).toInt()
                }
                container.addView(dateView, dateLp)
            }

            // Group 5: Carved thin horizontal with Date beside
            5 -> {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    clipChildren = false
                    clipToPadding = false
                }
                singleTimeView = createDigitView(timeSizeDp)
                val timeLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                row.addView(singleTimeView, timeLp)

                val dateLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (14 * dp).toInt()
                }
                row.addView(dateView, dateLp)
                container.addView(row)
            }

            // Groups 1, 2, 4: Single-line horizontal styles
            else -> {
                singleTimeView = createDigitView(timeSizeDp)
                val timeLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = if (style.group == 2) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
                }
                container.addView(singleTimeView, timeLp)

                val dateLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = if (style.group == 2) Gravity.START else Gravity.CENTER_HORIZONTAL
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
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
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

        h1View?.text = hh.substring(0, 1)
        h2View?.text = hh.substring(1, 2)
        m1View?.text = mm.substring(0, 1)
        m2View?.text = mm.substring(1, 2)

        val dateFormat = when (style.group) {
            5, 6 -> "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
            1 -> SimpleDateFormat("d MMMM, EEEE", Locale.getDefault()).apply { timeZone = zone }.format(cal.time)
            7, 8, 10, 12 -> SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).apply { timeZone = zone }.format(cal.time)
            else -> SimpleDateFormat("M月d日 E", Locale.getDefault()).apply { timeZone = zone }.format(cal.time)
        }
        dateView.text = dateFormat
        weekView?.text = SimpleDateFormat("EEEE", Locale.getDefault()).apply { timeZone = zone }.format(cal.time)

        requestLayout()
        invalidate()
    }

    fun color(color: Int) {
        userColor = color
        for (v in allViews) {
            v.setTextColor(color)
        }
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
        fun resolveTypeface(context: Context, style: LockscreenClockStyle): Typeface {
            val ytde = getYtdeValue(style.group, style.grid)
            val variation = if (ytde != null) {
                "'wght' ${style.weight}, 'ytde' $ytde"
            } else {
                "'wght' ${style.weight}"
            }
            return ClockFontManager.createTypeface(context, "native-clock/vivo/${style.font}", variation)
        }

        private fun getYtdeValue(group: Int, grid: String): Float? {
            return when (group) {
                12 -> when (grid) {
                    "4x2" -> 1.0f
                    "4x3" -> 19.0f
                    "4x4" -> 39.0f
                    "4x5" -> 59.0f
                    "4x6" -> 79.0f
                    "3x2" -> 8.0f
                    "3x3" -> 37.0f
                    "3x4" -> 65.0f
                    else -> 39.0f
                }
                7 -> when (grid) {
                    "4x2" -> 5.0f
                    "4x3" -> 25.0f
                    "4x4" -> 48.0f
                    "4x5" -> 71.0f
                    "4x6" -> 94.0f
                    "3x2" -> 15.0f
                    "3x3" -> 47.0f
                    "3x4" -> 79.0f
                    else -> 48.0f
                }
                8 -> when (grid) {
                    "2x4" -> 6.0f
                    "2x5" -> 16.0f
                    "2x6" -> 27.0f
                    "3x5" -> 7.0f
                    "3x6" -> 15.0f
                    "4x5" -> 2.0f
                    "4x6" -> 9.0f
                    else -> 9.0f
                }
                9 -> when (grid) {
                    "2x4" -> 3.0f
                    "2x5" -> 14.0f
                    "2x6" -> 24.0f
                    "3x5" -> 5.0f
                    "3x6" -> 13.0f
                    "4x5" -> 2.0f
                    "4x6" -> 9.0f
                    else -> 9.0f
                }
                else -> null
            }
        }

        private fun getTimeTextSizeDp(group: Int, grid: String): Float {
            return when (group) {
                1 -> 86f
                2, 4 -> 96f
                3 -> 96f
                5 -> 42f
                6 -> 40f
                7 -> 142f
                8 -> when (grid) {
                    "3x5", "3x6" -> 190f
                    "4x5", "4x6" -> 220f
                    else -> 152f
                }
                9 -> when (grid) {
                    "3x5", "3x6" -> 190f
                    "4x5", "4x6" -> 210f
                    else -> 152f
                }
                10 -> when (grid) {
                    "4x4" -> 146f
                    "4x5" -> 182f
                    "4x6" -> 192f
                    else -> 160f
                }
                11 -> when (grid) {
                    "2x4" -> 82f
                    "2x5" -> 104f
                    "2x6" -> 128f
                    else -> 100f
                }
                12 -> 162f
                else -> 120f
            }
        }

        private fun getDateTextSizeDp(group: Int, grid: String): Float {
            return when (group) {
                1 -> 18f
                5 -> 42f
                6 -> 40f
                11 -> 14f
                else -> if (grid.startsWith("3x")) 12f else 15f
            }
        }
    }
}
