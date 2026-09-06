package dev.unvoid.originceiler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.PowerManager
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ExpressiveLockscreenClockView(context: Context) : FrameLayout(context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val clockTypeface: Typeface by lazy {
        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull()
        runCatching {
            Typeface.Builder(moduleContext!!.assets, FONT_ASSET).build()
        }.getOrDefault(Typeface.create("sans-serif", Typeface.NORMAL))
    }
    private val digits = Array(4) { createDigit() }
    private val date = TextView(context).apply {
        gravity = Gravity.START or Gravity.TOP
        includeFontPadding = false
        maxLines = 1
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(18f))
        setShadowLayer(dp(1.5f), 0f, dp(0.5f), 0x66000000)
        typeface = clockTypeface
        fontVariationSettings = INFORMATION_VARIATION
    }
    private var receiverRegistered = false
    private var nativeClockViews = emptyList<View>()
    private val nativeClockAlphas = LinkedHashMap<View, Float>()
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (powerManager?.isInteractive == true) {
            nativeClockViews.forEach { if (it.alpha != 0f) it.alpha = 0f }
        }
        true
    }
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateVisibility()
            updateTime()
            updateColor()
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        digits.forEach { addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)) }
        addView(date, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        updateColor()
        updateTime()
        updateVisibility()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(clockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(clockReceiver, filter)
            receiverRegistered = true
        }
        updateVisibility()
        updateTime()
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        nativeClockViews.forEach { view -> nativeClockAlphas[view]?.let { view.alpha = it } }
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(clockReceiver) }
            receiverRegistered = false
        }
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateColor()
        updateTime()
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        positionContent()
    }

    fun isActive(): Boolean = parent != null && visibility == View.VISIBLE && powerManager?.isInteractive == true

    fun bindNativeClockViews(views: List<View>) {
        nativeClockViews.forEach { view -> nativeClockAlphas[view]?.let { view.alpha = it } }
        nativeClockViews = views.distinct()
        nativeClockViews.forEach { view -> nativeClockAlphas.putIfAbsent(view, view.alpha) }
        updateVisibility()
    }

    private fun createDigit(): TextView {
        return TextView(context).apply {
            gravity = Gravity.START or Gravity.TOP
            includeFontPadding = false
            maxLines = 1
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            typeface = clockTypeface
            fontVariationSettings = LARGE_VARIATION
            fontFeatureSettings = "'tnum'"
            pivotX = 0f
            pivotY = 0f
        }
    }

    private fun updateVisibility() {
        val interactive = powerManager?.isInteractive == true
        visibility = if (interactive) View.VISIBLE else View.GONE
        nativeClockViews.forEach { view ->
            view.alpha = if (interactive) 0f else nativeClockAlphas[view] ?: 1f
        }
    }

    private fun updateTime() {
        val calendar = Calendar.getInstance()
        var hour = calendar.get(Calendar.HOUR_OF_DAY)
        if (!DateFormat.is24HourFormat(context)) {
            hour %= 12
            if (hour == 0) hour = 12
        }
        val value = String.format(Locale.US, "%02d%02d", hour, calendar.get(Calendar.MINUTE))
        digits.forEachIndexed { index, textView -> textView.text = value[index].toString() }
        val locale = if (Build.VERSION.SDK_INT >= 24) resources.configuration.locales[0] else resources.configuration.locale
        val pattern = when (locale.language) {
            Locale.CHINESE.language -> "M月d日EEE"
            Locale.JAPANESE.language -> "M月d日(E)"
            Locale.ENGLISH.language -> "EEE, MMM d"
            else -> DateFormat.getBestDateTimePattern(locale, "MMMdEEE")
        }
        date.text = SimpleDateFormat(pattern, locale).format(calendar.time)
        post { positionContent() }
    }

    private fun updateColor() {
        val id = resources.getIdentifier("system_accent1_100", "color", "android")
        val color = if (id != 0) runCatching { context.getColor(id) }.getOrDefault(FALLBACK_COLOR) else FALLBACK_COLOR
        digits.forEach { it.setTextColor(color) }
        date.setTextColor(color)
    }

    private fun positionContent() {
        if (width <= 0 || height <= 0) return
        val textSize = width * 0.47f
        digits.forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            it.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        }
        val centerX = width / 2f
        val top = height * 0.215f - dp(10f)
        val secondRow = top + textSize * 0.82f
        positionPair(0, 1, centerX, top, textSize)
        positionPair(2, 3, centerX, secondRow, textSize)
        date.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        date.translationX = ((width - date.measuredWidth) / 2f).coerceAtLeast(dp(16f))
        date.translationY = height * 0.215f + textSize * 1.9f
    }

    private fun positionPair(first: Int, second: Int, centerX: Float, y: Float, textSize: Float) {
        val spacing = textSize * -0.07f
        val firstWidth = digits[first].measuredWidth.toFloat()
        val secondWidth = digits[second].measuredWidth.toFloat()
        val firstX = centerX - (firstWidth + spacing + secondWidth) / 2f
        digits[first].translationX = firstX
        digits[first].translationY = y
        digits[second].translationX = firstX + firstWidth + spacing
        digits[second].translationY = y
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val MODULE_PACKAGE = "dev.unvoid.originceiler"
        private const val FONT_ASSET = "fonts/GoogleSansFlex-VariableFont_GRAD,ROND,opsz,slnt,wdth,wght.ttf"
        private const val LARGE_VARIATION = "'wght' 450, 'wdth' 100, 'ROND' 100, 'GRAD' 0, 'opsz' 144, 'slnt' 0"
        private const val INFORMATION_VARIATION = "'wght' 500, 'wdth' 100, 'ROND' 0, 'GRAD' 0, 'opsz' 18"
        private const val FALLBACK_COLOR = -0x172108
    }
}
