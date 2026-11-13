package dev.unvoid.originceiler

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Freestyle's editor, laid over vivo's control center: every element gets a remove badge and, when
 * it has more than one size, a resize handle on its corner (iOS 18 style). Elements are dragged
 * to a new place (the rest reflows around them with a spring), resized by their corner, and added
 * back from the gallery at the bottom. The elements themselves are vivo's views, so they keep their
 * look (Liquid Glass included) while edited.
 */
@SuppressLint("ViewConstructor")
internal class FreestyleEditorView(context: Context, private val panel: ViewGroup) : FrameLayout(context) {
    private val engine = FreestyleControlCenter
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var appear = 0f
    private var appearAnim: ValueAnimator? = null
    private var closing = false

    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF04A4A4E.toInt() }
    private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x40FFFFFF }
    private val badgeBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val liftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val rect = RectF()
    private val arcRect = RectF()

    private val done = ChromeButton(context, FsText.done, bold = true) { close(save = true) }
    private val resetButton = ChromeButton(context, FsText.reset, bold = false) { engine.reset(); haptic(HapticFeedbackConstants.LONG_PRESS); invalidate() }
    private val addButton = ChromeButton(context, "+  " + FsText.addControl, bold = true) { openGallery() }
    private var gallery: FreestyleGalleryView? = null

    private var header: View? = null
    private var headerAlpha = 1f

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusableInTouchMode = true
        clipChildren = false
        addView(done, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(resetButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(addButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    // ---------------------------------------------------------------- open / close

    private var backCallback: Any? = null

    private fun registerBack() {
        if (Build.VERSION.SDK_INT < 33) return
        runCatching {
            val dispatcher = findOnBackInvokedDispatcher() ?: return
            val cb = android.window.OnBackInvokedCallback { gallery?.dismiss(immediate = false) }
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb)
            backCallback = cb
        }
    }

    private fun unregisterBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val cb = backCallback as? android.window.OnBackInvokedCallback ?: return
        runCatching { findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(cb) }
        backCallback = null
    }

    fun open() {
        post { registerBack() }
        header = (panel.parent as? ViewGroup)?.let { engine.findByIdName(it, "qs_header") }
        headerAlpha = header?.alpha ?: 1f
        requestFocus()
        haptic(HapticFeedbackConstants.LONG_PRESS)
        animateAppear(1f, 520L, OvershootInterpolator(1.05f), null)
        post(watchdog)
    }

    fun close(save: Boolean) {
        if (closing) return
        closing = true
        removeCallbacks(watchdog)
        unregisterBack()
        gallery?.dismiss(immediate = true)
        engine.editorClosed(this, save)
        animateAppear(0f, 260L, DecelerateInterpolator()) {
            header?.alpha = headerAlpha
            (parent as? ViewGroup)?.removeView(this)
        }
    }

    private fun animateAppear(to: Float, duration: Long, interpolator: android.animation.TimeInterpolator, end: (() -> Unit)?) {
        appearAnim?.cancel()
        val anim = ValueAnimator.ofFloat(appear, to)
        anim.duration = duration
        anim.interpolator = interpolator
        anim.addUpdateListener {
            appear = it.animatedValue as Float
            val a = appear.coerceIn(0f, 1f)
            header?.alpha = headerAlpha * (1f - a)
            for (b in listOf(done, resetButton, addButton)) {
                b.alpha = a
                b.scaleX = 0.82f + 0.18f * appear
                b.scaleY = b.scaleX
            }
            invalidate()
        }
        if (end != null) anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = end()
        })
        appearAnim = anim
        anim.start()
    }

    /** The control center was closed under us (swipe, home, screen off): leave edit mode. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (closing) return
            if (!panel.isShown || !isAttachedToWindow || panel.alpha < 0.05f) {
                close(save = true)
                return
            }
            postDelayed(this, 400)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                // Back only closes the gallery: leaving the editor is "Done".
                gallery?.dismiss(immediate = false)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- chrome layout

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val origin = engine.gridOrigin(this)
        val m = engine.metrics
        val gridLeft = origin?.get(0) ?: dp(38f)
        val gridRight = if (m != null) gridLeft + m.gridW else width - dp(38f)
        // vivo's pencil row: our buttons sit where its header buttons were.
        val editIcon = engine.findByIdName(panel.rootView, "vivo_icon_edit")
        var cy = dp(96f)
        if (editIcon != null && editIcon.height > 0) {
            val a = IntArray(2)
            val b = IntArray(2)
            editIcon.getLocationInWindow(a)
            getLocationInWindow(b)
            cy = a[1] - b[1] + editIcon.height / 2f
        }
        // A little higher than vivo's buttons and out towards the screen edges; with the iOS 26
        // header, in its top row (where "+" and the power button are).
        cy = if (engine.ios) dp(40f) else cy - dp(16f)
        place(done, min(width - dp(14f), gridRight + dp(14f)) - done.measuredWidth, cy)
        place(resetButton, max(dp(14f), gridLeft - dp(14f)), cy)
        val inset = rootWindowInsets?.systemWindowInsetBottom ?: 0
        val bottomY = height - inset - dp(26f)
        place(addButton, (width - addButton.measuredWidth) / 2f, bottomY)
        gallery?.layout(0, 0, width, height)
    }

    private fun place(v: View, x: Float, cy: Float) {
        val l = x.toInt()
        val t = (cy - v.measuredHeight / 2f).toInt()
        v.layout(l, t, l + v.measuredWidth, t + v.measuredHeight)
        v.pivotX = v.measuredWidth / 2f
        v.pivotY = v.measuredHeight / 2f
    }

    // ---------------------------------------------------------------- drawing

    private class Shown(val item: FreestyleControlCenter.Item, val rect: RectF, val radius: Float)

    private var shown: List<Shown> = emptyList()

    private fun collect(): List<Shown> {
        val out = ArrayList<Shown>()
        for (item in engine.items()) {
            val r = RectF()
            if (!engine.rectIn(item, this, r)) continue
            if (r.width() < 2f || r.height() < 2f) continue
            out += Shown(item, r, engine.cornerRadius(item, r.width(), r.height()))
        }
        return out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        shown = collect()
        if (appear <= 0.001f) return
        val m = engine.metrics
        val cell = m?.cell?.toFloat() ?: dp(56f)
        val now = SystemClock.uptimeMillis()
        shown.forEachIndexed { i, s ->
            // Badges pop in one after another.
            val pop = popFor(i)
            if (pop <= 0.001f) return@forEachIndexed
            val r = s.radius
            val rr = s.rect
            val dragged = s.item.spec == dragSpec
            outlinePaint.strokeWidth = dp(1.1f)
            outlinePaint.alpha = (if (dragged) 0 else 58 * pop).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rr, r, r, outlinePaint)
            if (dragged) {
                liftPaint.strokeWidth = dp(2f)
                liftPaint.alpha = 150
                if (Build.VERSION.SDK_INT >= 28) liftPaint.setShadowLayer(dp(14f), 0f, dp(4f), 0x66000000)
                canvas.drawRoundRect(rr, r, r, liftPaint)
            }
            // Resize handle: a short arc that follows the bottom-right corner.
            if (s.item.sizes.size > 1 && !dragged) {
                val active = resizeSpec == s.item.spec
                handlePaint.strokeWidth = dp(if (active) 4.6f else 3.6f)
                handlePaint.alpha = (235 * pop).toInt().coerceIn(0, 255)
                val inset = -handlePaint.strokeWidth * 0.2f
                arcRect.set(rr.right - 2 * r - inset, rr.bottom - 2 * r - inset, rr.right + inset, rr.bottom + inset)
                val sweep = if (r >= min(rr.width(), rr.height()) / 2f - 1f && abs(rr.width() - rr.height()) < 2f) 44f else 56f
                canvas.save()
                canvas.drawArc(arcRect, 45f - sweep / 2f, sweep, false, handlePaint)
                canvas.restore()
            }
            if (!dragged) {
                // Remove badge on the top-left curve.
                val k = 0.2929f
                val cx = rr.left + r * k
                val cy = rr.top + r * k
                val br = cell * 0.135f * pop * (if (pressedBadge == s.item.spec) 0.86f else 1f)
                badgeFill.alpha = (240 * pop.coerceIn(0f, 1f)).toInt()
                canvas.drawCircle(cx, cy, br, badgeFill)
                badgeRing.strokeWidth = dp(0.8f)
                canvas.drawCircle(cx, cy, br, badgeRing)
                rect.set(cx - br * 0.48f, cy - br * 0.09f, cx + br * 0.48f, cy + br * 0.09f)
                canvas.drawRoundRect(rect, br * 0.09f, br * 0.09f, badgeBar)
            }
        }
        if (!closing && (appear < 1f || now - openedAt < 1200 || dragSpec != null || resizeSpec != null)) postInvalidateOnAnimation()
    }

    private val openedAt = SystemClock.uptimeMillis()

    private fun popFor(index: Int): Float {
        val stagger = (index * 0.025f).coerceAtMost(0.4f)
        if (closing) return appear.coerceIn(0f, 1f)
        val t = ((SystemClock.uptimeMillis() - openedAt) / 420f - stagger).coerceIn(0f, 1f)
        // Overshooting pop.
        val o = 1.7f
        val x = t - 1f
        return (x * x * ((o + 1) * x + o) + 1f) * appear.coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- touch

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var mode = Mode.NONE
    private enum class Mode { NONE, BADGE, BODY, RESIZE, DRAG, EMPTY, SCROLL }
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var touched: FreestyleControlCenter.Item? = null
    private var pressedBadge: String? = null
    private var dragSpec: String? = null
    private var resizeSpec: String? = null
    private val dragBase = RectF()
    private var resizeBaseW = 0f
    private var resizeBaseH = 0f
    private var lastReorder = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (closing) return true
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; lastY = y
                mode = Mode.EMPTY
                touched = null
                val m = engine.metrics
                val cell = m?.cell?.toFloat() ?: dp(56f)
                // Badges and handles first: they stick out of their element.
                for (s in shown.asReversed()) {
                    val r = s.radius
                    val k = 0.2929f
                    if (hypot(x - (s.rect.left + r * k), y - (s.rect.top + r * k)) < cell * 0.26f) {
                        mode = Mode.BADGE; touched = s.item; pressedBadge = s.item.spec
                        invalidate()
                        break
                    }
                    if (s.item.sizes.size > 1) {
                        val hx = s.rect.right - r * k
                        val hy = s.rect.bottom - r * k
                        if (hypot(x - hx, y - hy) < cell * 0.32f) {
                            mode = Mode.RESIZE; touched = s.item
                            resizeSpec = s.item.spec
                            resizeBaseW = s.rect.width(); resizeBaseH = s.rect.height()
                            haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                            break
                        }
                    }
                }
                if (mode == Mode.EMPTY) for (s in shown.asReversed()) if (s.rect.contains(x, y)) {
                    mode = Mode.BODY; touched = s.item
                    break
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val moved = hypot(x - downX, y - downY) > slop
                when (mode) {
                    Mode.BODY -> if (moved) startDrag()
                    // No scrolling of the control center while editing: a swipe on empty space does nothing.
                    Mode.EMPTY -> if (moved) mode = Mode.NONE
                    Mode.BADGE -> if (moved) { mode = Mode.NONE; pressedBadge = null; invalidate() }
                    else -> Unit
                }
                when (mode) {
                    Mode.DRAG -> drag(x, y)
                    Mode.RESIZE -> resize(x, y)
                    Mode.SCROLL -> scroll(y - lastY)
                    else -> Unit
                }
                lastY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val up = event.actionMasked == MotionEvent.ACTION_UP
                when (mode) {
                    Mode.BADGE -> if (up) touched?.let { removeItem(it) }
                    Mode.DRAG -> { engine.endDrag(); dragSpec = null; haptic(HapticFeedbackConstants.VIRTUAL_KEY) }
                    Mode.RESIZE -> resizeSpec = null
                    Mode.EMPTY -> if (up) close(save = true)
                    else -> Unit
                }
                pressedBadge = null
                mode = Mode.NONE
                invalidate()
            }
        }
        return true
    }

    private fun removeItem(item: FreestyleControlCenter.Item) {
        haptic(HapticFeedbackConstants.LONG_PRESS)
        engine.remove(item)
    }

    private fun startDrag() {
        val item = touched ?: return
        val base = engine.gridRect(item.spec) ?: return
        mode = Mode.DRAG
        dragSpec = item.spec
        dragBase.set(base)
        engine.startDrag(item.spec)
        haptic(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun drag(x: Float, y: Float) {
        val spec = dragSpec ?: return
        engine.dragBy(x - downX, y - downY, dragBase)
        val now = SystemClock.uptimeMillis()
        if (now - lastReorder < 110) return
        // Which place in the order puts this element's slot closest to where it is held?
        val m = engine.metrics ?: return
        val size = engine.targetSize(spec) ?: return
        val others = engine.visibleOrder().filter { it != spec }
        val heldX = dragBase.centerX() + (x - downX)
        val heldY = dragBase.centerY() + (y - downY)
        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        for (i in 0..others.size) {
            val order = others.toMutableList().apply { add(i, spec) }
            val (cells, _) = FsGrid.pack(order.map { it to (if (it == spec) size else engine.targetSize(it) ?: FsSize.S1x1) })
            val c = cells[spec] ?: continue
            val cx = m.x(c.col) + m.w(c.size.w) / 2f
            val cy = m.y(c.row) + m.h(c.size.h) / 2f
            val d = hypot(cx - heldX, cy - heldY)
            if (d < bestDistance - 0.5f) { bestDistance = d; bestIndex = i }
        }
        val current = engine.visibleOrder().indexOf(spec)
        if (bestIndex < 0 || bestIndex == current) return
        val anchor = others.getOrNull(bestIndex)
        if (anchor != null) engine.move(spec, anchor, after = false) else engine.move(spec, others.lastOrNull(), after = true)
        lastReorder = now
        haptic(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun resize(x: Float, y: Float) {
        val item = touched ?: return
        val m = engine.metrics ?: return
        val wantW = (resizeBaseW + (x - downX) + m.gapH) / (m.cell + m.gapH)
        val wantH = (resizeBaseH + (y - downY) + m.gapV) / (m.cellH + m.gapV)
        val best = item.sizes.minByOrNull { (it.w - wantW) * (it.w - wantW) + (it.h - wantH) * (it.h - wantH) } ?: return
        if (best != engine.targetSize(item.spec)) {
            engine.setSize(item.spec, best)
            haptic(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /** vivo's tile scroller under the editor. */
    private fun scroll(dy: Float) {
        val scroller = engine.findByIdName(panel, "vivo_super_qs_tile_layout_parent_scroller") ?: return
        val amount = (-dy).toInt()
        if (amount == 0 || !scroller.canScrollVertically(if (amount > 0) 1 else -1)) return
        scroller.scrollBy(0, amount)
        invalidate()
    }

    private fun haptic(type: Int) {
        runCatching { performHapticFeedback(type) }
    }

    // ---------------------------------------------------------------- gallery

    private fun openGallery() {
        if (gallery != null) return
        val g = FreestyleGalleryView(context, panel, onPick = { spec ->
            engine.add(spec)
            haptic(HapticFeedbackConstants.CONFIRM.takeIf { Build.VERSION.SDK_INT >= 30 } ?: HapticFeedbackConstants.VIRTUAL_KEY)
        }, onVivoEditor = {
            close(save = true)
            engine.openVivoEditor(panel)
        }, onGone = { view ->
            if (gallery === view) gallery = null
            removeView(view)
            for (b in listOf(done, resetButton, addButton)) b.animate().alpha(1f).setDuration(180).start()
            invalidate()
        })
        gallery = g
        addView(g, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        for (b in listOf(done, resetButton, addButton)) b.animate().alpha(0f).setDuration(160).start()
        g.show()
    }

    // ---------------------------------------------------------------- chrome button

    /** A glass capsule (Liquid Glass when it is on, frosted fill otherwise). */
    @SuppressLint("ViewConstructor")
    class ChromeButton(context: Context, private val text: String, bold: Boolean, private val onClick: () -> Unit) : View(context) {
        private val d = resources.displayMetrics.density
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13.5f * resources.displayMetrics.scaledDensity
            typeface = if (bold) (if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 650, false) else Typeface.DEFAULT_BOLD) else Typeface.DEFAULT
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private var pressT = 0f
        private var pressedNow = false
        private val glass = KyantGlass.GlassDrawable(
            this, KyantGlass.KEY_TILES,
            backdrop = { v -> KyantGlassHook.ShadeBackdrop.get(v) },
            dim = true,
            elastic = { it },
            radius = { _, _, h -> h / 2f }
        )

        init {
            background = glass
            isClickable = true
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val h = (38 * d).toInt()
            val w = (paint.measureText(text) + 30 * d).toInt()
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            r.set(0f, 0f, width.toFloat(), height.toFloat())
            if (!KyantGlass.config.on(KyantGlass.KEY_TILES)) {
                fill.color = 0x4DFFFFFF
                canvas.drawRoundRect(r, height / 2f, height / 2f, fill)
            }
            if (pressT > 0.01f) {
                fill.color = Color.argb((pressT * 60).toInt(), 255, 255, 255)
                canvas.drawRoundRect(r, height / 2f, height / 2f, fill)
            }
            val y = height / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(text, (width - paint.measureText(text)) / 2f, y, paint)
            val target = if (pressedNow) 1f else 0f
            if (abs(pressT - target) > 0.01f) { pressT += (target - pressT) * 0.35f; postInvalidateOnAnimation() } else pressT = target
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressedNow = true; animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start(); invalidate() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val inside = event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()
                    pressedNow = false
                    animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2.2f)).setDuration(260).start()
                    invalidate()
                    if (event.actionMasked == MotionEvent.ACTION_UP && inside && alpha > 0.5f) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onClick()
                    }
                }
            }
            return true
        }
    }

    companion object {
        @Suppress("unused")
        private fun clamp01(v: Float) = max(0f, min(1f, v))
    }
}
