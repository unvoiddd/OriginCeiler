package dev.unvoid.originceiler

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.format.DateFormat
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
 * 100% native converted Flyme Alive Clock Face (vertical 2x2 and horizontal single-line).
 * Uses FlymeNumber-VF.ttf with authentic proportions and auto-scaling.
 */
class FlymeAliveConvertedClockFace @JvmOverloads constructor(
    context: Context,
    val isVertical: Boolean,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    private val font: Typeface = ClockFontManager.createTypeface(
        context = context,
        assetSubpath = "native-clock/flyme/FlymeNumber-VF.ttf",
        variationSettings = "'wght' 400"
    )

    private val digitViews = ArrayList<TextView>()
    private val allViews = ArrayList<TextView>()
    private val dateView: TextView

    // Vertical views: HH over MM
    private var hourView: TextView? = null
    private var minuteView: TextView? = null

    // Horizontal views: HH:MM
    private var singleTimeView: TextView? = null

    private var userColor: Int = Color.WHITE

    init {
        clipChildren = false
        clipToPadding = false

        val dp = resources.displayMetrics.density

        dateView = TextView(context).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = true
            isFallbackLineSpacing = true
            maxLines = 1
            setPadding((8 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt(), (2 * dp).toInt())
        }
        allViews.add(dateView)

        if (isVertical) {
            // Stacked HH over MM
            val timeSizeDp = 108f
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
                topMargin = (-12 * dp).toInt()
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
        } else {
            // Single line HH:MM
            val timeSizeDp = 86f
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
                topMargin = (6 * dp).toInt()
            }
            container.addView(dateView, dateLp)
        }

        addView(
            container,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )

        update(System.currentTimeMillis(), TimeZone.getDefault(), DateFormat.is24HourFormat(context))
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

        val dateStr = SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).apply { timeZone = zone }.format(cal.time)
        dateView.text = dateStr

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
        val safetyMargin = (20 * resources.displayMetrics.density).toInt()
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
}
