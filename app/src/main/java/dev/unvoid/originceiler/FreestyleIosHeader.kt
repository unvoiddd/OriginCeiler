package dev.unvoid.originceiler

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * iOS 26 control center header over vivo's: "+" (edit) at the top left, the power button at the
 * top right, the services capsule ("System Services") centred under them, and vivo's status row
 * (carriers left, battery right) moved below it. vivo's own settings / pencil buttons and its
 * services row are hidden; ours forward to them.
 */
@SuppressLint("ViewConstructor")
internal class FreestyleIosHeader(context: Context, private val panel: ViewGroup) : View(context) {
    private val engine = FreestyleControlCenter
    private val d = resources.displayMetrics.density
    private val root: ViewGroup? get() = panel.parent as? ViewGroup

    private var headerInner: View? = null
    private var vivoHeader: View? = null
    private var services: View? = null
    private var servicesText: TextView? = null
    private var servicesIcon: ImageView? = null
    private var statusContainer: View? = null
    /** vivo's status row: it moves and fades with the shade itself, so the header follows it. */
    private var statusRow: View? = null


    private val glyphColor = Color.WHITE
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13.5f * resources.displayMetrics.scaledDensity
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false) else Typeface.DEFAULT_BOLD
    }
    private val pill = RectF()

    private var plusX = 0f
    private var powerX = 0f
    private var topY = 0f
    private var pressed = 0
    private var pressT = 0f
    private var shown = 0f

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        enforce()
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        find()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        restore()
    }

    private fun find() {
        val r = root ?: return
        vivoHeader = engine.findByIdName(r, "qs_header")
        headerInner = engine.findByIdName(r, "qs_header_inner")
        services = engine.findByIdName(r, "split_qs_privacy_and_service")
        servicesText = engine.findByIdName(r, "front_service_text") as? TextView
        servicesIcon = engine.findByIdName(r, "front_service_image") as? ImageView
        statusContainer = engine.findByIdName(r, "vivo_split_qs_status_icons_container")
        statusRow = engine.findByIdName(r, "first_line_qs_status_icon")
    }

    /** vivo keeps animating its header: every frame its buttons and services row stay hidden, the status row lower. */
    private fun enforce() {
        if (headerInner == null || statusContainer == null) find()
        headerInner?.let { if (it.alpha != 0f) it.alpha = 0f }
        services?.let { if (it.alpha != 0f) it.alpha = 0f }
        statusContainer?.let { val shift = STATUS_SHIFT_DP * d; if (it.translationY != shift) it.translationY = shift }
        // Bound to the shade: the status row's own fade and slide (not the header's, which lags).
        val row = statusRow
        val rowAlpha = row?.let { it.alpha * (if (it.isShown) 1f else 0f) } ?: (vivoHeader?.alpha ?: 1f)
        // Travel with the shade: the offset vivo's open / pull spring gives the top of the grid
        // (the status row moves about half as far as the first row). The spring always ends at
        // 0, so there is no resting point to guess and nothing jumps at the end.
        val dy = (engine.springFor(0) ?: 0f) * HEADER_SPRING
        if (translationY != dy) translationY = dy
        val a = rowAlpha * (if (engine.editor != null) 0f else 1f)
        if (abs(a - shown) > 0.004f) { shown = a; invalidate() }
        else if (servicesText?.text?.toString() != lastText) invalidate()
    }

    fun restore() {
        headerInner?.alpha = 1f
        services?.alpha = 1f
        statusContainer?.translationY = 0f
    }

    private var lastText: String? = null

    override fun onDraw(canvas: Canvas) {
        if (shown <= 0.01f) return
        val m = engine.metrics
        val origin = engine.gridOrigin(this)
        val left = origin?.get(0) ?: 38f * d
        val right = if (m != null) left + m.gridW else width - 38f * d
        val r = 15f * d
        plusX = left + r * 0.6f
        powerX = right - r * 0.6f
        topY = 40f * d
        val alpha = (shown * 255).toInt().coerceIn(0, 255)
        for ((i, x) in listOf(plusX, powerX).withIndex()) {
            val p = if (pressed == i + 1) pressT else 0f
            if (p > 0.01f) {
                fill.color = Color.argb((p * 50 * shown).toInt(), 255, 255, 255)
                canvas.drawCircle(x, topY, r * 1.25f, fill)
            }
            val color = (glyphColor and 0x00FFFFFF) or (alpha shl 24)
            FsGlyphs.draw(canvas, context, if (i == 0) "add" else "power_settings_new", x, topY, 21f * d * (1f - 0.1f * p), color)
        }
        // Services capsule.
        val text = servicesText?.text?.toString()?.takeIf { it.isNotBlank() && (services?.visibility == VISIBLE) }
        lastText = servicesText?.text?.toString()
        if (text != null) {
            val h = 30f * d
            val iconW = 20f * d
            val maxW = (right - left) * 0.7f
            val t = TextUtils.ellipsize(text, pillText, maxW - iconW - 28f * d, TextUtils.TruncateAt.END).toString()
            val w = iconW + pillText.measureText(t) + 30f * d
            val cy = topY + 32f * d
            pill.set(width / 2f - w / 2f, cy - h / 2f, width / 2f + w / 2f, cy + h / 2f)
            val p = if (pressed == 3) pressT else 0f
            if (p > 0.01f) {
                fill.color = Color.argb((p * 40).toInt(), 255, 255, 255)
                canvas.drawRoundRect(pill, h / 2f, h / 2f, fill)
            }
            pillText.alpha = alpha
            val icon = servicesIcon?.drawable
            val ix = pill.left + 14f * d + iconW / 2f
            if (icon != null) {
                val s = (iconW * 0.8f).toInt()
                icon.setBounds((ix - s / 2).toInt(), (cy - s / 2).toInt(), (ix + s / 2).toInt(), (cy + s / 2).toInt())
                icon.alpha = alpha
                icon.draw(canvas)
            } else {
                FsGlyphs.draw(canvas, context, "near_me", ix, cy, iconW * 0.85f, (0xFF0A84FF.toInt() and 0x00FFFFFF) or (alpha shl 24))
            }
            canvas.drawText(t, ix + iconW / 2f + 6f * d, cy - (pillText.descent() + pillText.ascent()) / 2f, pillText)
        } else pill.setEmpty()
        val target = if (pressed != 0) 1f else 0f
        if (abs(pressT - target) > 0.01f) { pressT += (target - pressT) * 0.35f; postInvalidateOnAnimation() } else pressT = target
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (shown < 0.5f) return false
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = 26f * d
                pressed = when {
                    hypot(x - plusX, y - topY) < r -> 1
                    hypot(x - powerX, y - topY) < r -> 2
                    !pill.isEmpty && pill.contains(x, y) -> 3
                    else -> 0
                }
                if (pressed == 0) {
                    // vivo's hidden header buttons are still under here: do not let them take the tap.
                    return hiddenButtonsContain(x, y)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val which = pressed
                pressed = 0
                invalidate()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                when (which) {
                    1 -> engine.headerEdit(panel)
                    2 -> showPowerMenu()
                    3 -> services?.performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = 0; invalidate() }
        }
        return true
    }

    private fun hiddenButtonsContain(x: Float, y: Float): Boolean {
        val inner = headerInner ?: return false
        val a = IntArray(2)
        val b = IntArray(2)
        inner.getLocationInWindow(a)
        getLocationInWindow(b)
        val l = a[0] - b[0]
        val t = a[1] - b[1]
        return x >= l && x <= l + inner.width && y >= t && y <= t + inner.height
    }

    private fun showPowerMenu() {
        runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val wms = wmg.getMethod("getWindowManagerService").invoke(null)
            wms.javaClass.getMethod("showGlobalActions").invoke(wms)
        }.onFailure { engine.log("power menu: $it") }
    }

    companion object {
        /** How far vivo's status row moves down, under the services capsule. */
        const val STATUS_SHIFT_DP = 55f

        /** Share of the first grid row's spring offset the header takes. */
        const val HEADER_SPRING = 0.5f

        @Suppress("unused")
        private fun clamp(v: Float) = min(1f, v)
    }
}
