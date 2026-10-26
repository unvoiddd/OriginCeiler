package dev.unvoid.originceiler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
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
 * 100% native converted Flyme 3D Perspective Clock View.
 * Implements the authentic polygon perspective projection matrix
 * with PlusJakartaSans-ExtraBold font, without relying on DexClassLoader or external APKs.
 */
class FlymePerspectiveClockView @JvmOverloads constructor(
    context: Context,
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
        assetSubpath = "native-clock/flyme/PlusJakartaSans-ExtraBold.ttf"
    )

    // Time view with 3D perspective slope: top-right corner tilts downwards by 80px relative to 360x200
    private val timeView = PerspectiveTextView(
        context = context,
        srcPoints = floatArrayOf(0f, 0f, 360f, 0f, 0f, 200f, 360f, 200f),
        dstPoints = floatArrayOf(0f, 0f, 360f, 80f, 0f, 200f, 360f, 200f)
    ).apply {
        val dp = context.resources.displayMetrics.density
        typeface = font
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 116f)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        includeFontPadding = true
        isFallbackLineSpacing = true
        setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
    }

    // Date view with complementary 3D perspective slope
    private val dateView = PerspectiveTextView(
        context = context,
        srcPoints = floatArrayOf(0f, 0f, 240f, 0f, 0f, 140f, 240f, 140f),
        dstPoints = floatArrayOf(0f, 0f, 240f, 0f, 0f, 140f, 240f, 70f)
    ).apply {
        val dp = context.resources.displayMetrics.density
        typeface = font
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 62f)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        includeFontPadding = true
        isFallbackLineSpacing = true
        setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
    }

    // Weekday text
    private val weekView = TextView(context).apply {
        val dp = context.resources.displayMetrics.density
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Color.WHITE)
        alpha = 0.85f
        gravity = Gravity.CENTER
        includeFontPadding = true
        isFallbackLineSpacing = true
        setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
    }

    private var userColor: Int = Color.WHITE

    init {
        clipChildren = false
        clipToPadding = false

        val dp = resources.displayMetrics.density

        val timeLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        container.addView(timeView, timeLp)

        val dateLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (4 * dp).toInt()
        }
        container.addView(dateView, dateLp)

        val weekLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (6 * dp).toInt()
        }
        container.addView(weekView, weekLp)

        val containerLp = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        addView(container, containerLp)

        update(System.currentTimeMillis(), TimeZone.getDefault(), true)
    }

    fun update(time: Long, zone: TimeZone, format24: Boolean) {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = time }
        var hour = cal.get(if (format24) Calendar.HOUR_OF_DAY else Calendar.HOUR)
        if (!format24 && hour == 0) hour = 12
        val minute = cal.get(Calendar.MINUTE)

        val timeStr = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
        val dateStr = String.format(Locale.ROOT, "%02d/%02d", cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        val weekStr = SimpleDateFormat("EEEE", Locale.getDefault()).apply { timeZone = zone }.format(cal.time)

        timeView.text = timeStr
        dateView.text = dateStr
        weekView.text = weekStr.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        requestLayout()
        invalidate()
    }

    fun color(color: Int) {
        userColor = color
        timeView.setTextColor(color)
        dateView.setTextColor(color)
        weekView.setTextColor(color)
    }

    fun materialViews(): List<TextView> = listOf(timeView, dateView, weekView)

    fun glassView(view: View): Boolean = view === timeView || view === dateView

    fun getDigitViews(): List<View> = listOf(timeView)

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

    /**
     * TextView that applies a 4-point perspective warp matrix during draw.
     */
    class PerspectiveTextView(
        context: Context,
        private val srcPoints: FloatArray,
        private val dstPoints: FloatArray
    ) : TextView(context) {

        private val polyMatrix = Matrix()
        private val currentDst = FloatArray(8)

        init {
            includeFontPadding = true
            isFallbackLineSpacing = true
            maxLines = 1
            setHorizontallyScrolling(false)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateMatrix(w.toFloat(), h.toFloat())
        }

        private fun updateMatrix(w: Float, h: Float) {
            if (w <= 0f || h <= 0f) return

            val refW = srcPoints[2]
            val refH = srcPoints[5]
            val scaleX = if (refW > 0f) w / refW else 1f
            val scaleY = if (refH > 0f) h / refH else 1f

            val scaledSrc = floatArrayOf(
                0f, 0f,
                w, 0f,
                0f, h,
                w, h
            )

            for (i in 0 until 8) {
                val dst = if (i % 2 == 0) dstPoints[i] * scaleX else dstPoints[i] * scaleY
                currentDst[i] = dst
            }

            polyMatrix.setPolyToPoly(scaledSrc, 0, currentDst, 0, 4)
        }

        override fun onDraw(canvas: Canvas) {
            val count = canvas.save()
            canvas.concat(polyMatrix)
            super.onDraw(canvas)
            canvas.restoreToCount(count)
        }
    }
}
