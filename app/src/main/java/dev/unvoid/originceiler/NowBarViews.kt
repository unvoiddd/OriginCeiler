package dev.unvoid.originceiler

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Now Bar views: HyperLight's lockscreen capsules (gx0 slot, ry0 music capsule, zz0/yz0
 * status capsule) redrawn for OriginOS. Sizes, timings and springs follow HyperLight; the
 * glass is our Kyant glass refracting the lock wallpaper.
 */
internal class NowBarHost(context: Context) : FrameLayout(context) {
    val music = MusicCapsule(context)
    val status = StatusCapsule(context)

    /** 0 = side by side, 1 = stacked (music above status). */
    var capsuleLayout = 0
        set(value) { if (field != value) { field = value; requestLayout() } }
    /** Distance of the capsules' bottom edge from the screen bottom. */
    var bottomOffsetPx = 0
        set(value) { if (field != value) { field = value; requestLayout() } }

    private val d = resources.displayMetrics.density
    init {
        clipChildren = false
        clipToPadding = false
        isClickable = false
        isFocusable = false
        addView(music, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        music.host = this
        status.host = this
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        music.measure(unspecified, unspecified)
        status.measure(unspecified, unspecified)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val gap = 8f * d
        val showMusic = music.present
        val showStatus = status.present
        val mw = music.measuredWidth.toFloat()
        val mh = music.measuredHeight.toFloat()
        val sw = status.measuredWidth.toFloat()
        val sh = status.measuredHeight.toFloat()
        val bottom = h - bottomOffsetPx.toFloat()
        var mx = (w - mw) / 2f
        var my = bottom - mh
        var sx = (w - sw) / 2f
        var sy = bottom - sh
        if (showMusic && showStatus) {
            if (capsuleLayout == 1) {
                my = sy - gap - mh
            } else {
                val total = mw + gap + sw
                mx = (w - total) / 2f
                sx = mx + mw + gap
                val center = bottom - max(mh, sh) / 2f
                my = center - mh / 2f
                sy = center - sh / 2f
            }
        }
        place(music, mx, my, showMusic)
        place(status, sx, sy, showStatus)
    }

    /** Placed straight from the animated widths: the springs live in the capsules. */
    private fun place(view: View, x: Float, y: Float, visible: Boolean) {
        val l = x.roundToInt()
        val t = y.roundToInt()
        view.layout(l, t, l + view.measuredWidth, t + view.measuredHeight)
    }

    companion object {
        val SLOT_SPRING = LiquidPressPhysics.Spring(0.78f, 7.5f)
    }
}

/** Common capsule behaviour: pill glass, width spring, appear / leave, liquid press. */
internal abstract class CapsuleView(context: Context) : View(context) {
    var host: NowBarHost? = null
    protected val d = resources.displayMetrics.density
    /** Size scale (HyperLight lockscreen_*_capsule_size_scale). */
    var sizeScale = 1f
        set(value) { if (field != value) { field = value; onMetricsChanged(); requestLayout(); invalidate() } }
    var widthScale = 1f
        set(value) { if (field != value) { field = value; onMetricsChanged(); requestLayout(); invalidate() } }
    var textColor = Color.WHITE
        set(value) { if (field != value) { field = value; onColorChanged(); invalidate() } }
    var useGlass = true
        set(value) { if (field != value) { field = value; applyBackground() } }

    /** Whether the capsule takes a slot (stays true while its leave animation runs). */
    var present = false
        private set
    private var wanted = false
    private var appearAnim: ValueAnimator? = null

    private var shownWidth = -1f
    private var widthFrom = 0f
    private var widthTo = 0f
    private var widthAnim: ValueAnimator? = null

    private val glass by lazy {
        KyantGlass.GlassDrawable(
            this, "",
            backdrop = { v -> ScreenBackdropSource.getLockBackdrop(v.context) },
            zone = KyantGlass.Zone.LOCKSCREEN,
            radius = { _, w, h -> min(w, h) / 2f }
        )
    }
    private val plainPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    protected val press = LiquidPress(this).apply { dragBlurDp = 6f }
    private var glow: PressGlow? = null
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var dragging = false

    init {
        visibility = GONE
        alpha = 0f
        isClickable = true
        isFocusable = false
        // HyperLight ry0: the capsule clips to its pill outline (glass shadow and press stretch included).
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        clipToOutline = true
        applyBackground()
    }

    private fun applyBackground() {
        if (useGlass) {
            KyantGlass.ensureConfig(context)
            KyantGlass.track(this)
            background = glass
        } else {
            background = null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!useGlass) {
            val h = height.toFloat()
            plainPaint.color = if (isDarkText()) 0x73FFFFFF else 0x59000000
            canvas.drawRoundRect(0f, 0f, width.toFloat(), h, h / 2f, h / 2f, plainPaint)
        }
        drawContent(canvas)
    }

    protected fun isDarkText() = Color.luminance(textColor) < 0.5f

    protected open fun onMetricsChanged() {}
    protected open fun onColorChanged() {}
    protected abstract fun drawContent(canvas: Canvas)
    protected abstract fun targetWidth(): Int
    protected abstract fun targetHeight(): Int

    /** Content changed: the width follows on HyperLight's spring (500 ms, 0.78 / 7.5). */
    protected fun updateWidth() {
        val target = targetWidth().toFloat()
        if (shownWidth < 0f || !present || !isAttachedToWindow) {
            widthAnim?.cancel()
            widthAnim = null
            shownWidth = target
            requestLayout()
            return
        }
        if (abs(target - widthTo) < 1f && widthAnim != null) return
        if (abs(target - shownWidth) < 2f) {
            widthAnim?.cancel()
            widthAnim = null
            shownWidth = target
            requestLayout()
            return
        }
        widthAnim?.cancel()
        widthFrom = shownWidth
        widthTo = target
        widthAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500L
            interpolator = NowBarHost.SLOT_SPRING
            addUpdateListener {
                val f = it.animatedValue as Float
                shownWidth = widthFrom + (widthTo - widthFrom) * f
                requestLayout()
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (shownWidth < 0f) targetWidth() else shownWidth.roundToInt()
        setMeasuredDimension(max(0, w), max(0, targetHeight()))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = w / 2f
        pivotY = h / 2f
    }

    /** HyperLight capsule enter (scale 0.6 → 1 with overshoot) and exit (fade + shrink). */
    fun setWanted(show: Boolean) {
        if (wanted == show && (show == present)) return
        wanted = show
        appearAnim?.cancel()
        if (show) {
            if (!present) {
                present = true
                visibility = VISIBLE
                scaleX = 0.6f
                scaleY = 0.6f
                alpha = 0f
                // Grow out of a dot (the pill's own height), like the island.
                shownWidth = if (isAttachedToWindow) targetHeight().toFloat() else -1f
                updateWidth()
                host?.requestLayout()
            }
            appearAnim = ValueAnimator.ofFloat(alpha, 1f).apply {
                duration = 520L
                interpolator = APPEAR_SPRING
                val s0 = scaleX
                addUpdateListener {
                    val f = it.animatedFraction
                    alpha = min(1f, it.animatedValue as Float)
                    val s = s0 + (1f - s0) * APPEAR_SPRING.getInterpolation(f)
                    scaleX = s
                    scaleY = s
                }
                start()
            }
        } else {
            if (!present) return
            appearAnim = ValueAnimator.ofFloat(alpha, 0f).apply {
                duration = 220L
                interpolator = EASE
                val s0 = scaleX
                addUpdateListener {
                    val f = it.animatedFraction
                    alpha = it.animatedValue as Float
                    val s = s0 + (0.7f - s0) * f
                    scaleX = s
                    scaleY = s
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (cancelled || wanted) return
                        present = false
                        visibility = GONE
                        host?.requestLayout()
                    }
                })
                start()
            }
        }
    }

    // ---------------------------------------------------------------- touch

    /** Tap at (x, y) in view coordinates. */
    protected abstract fun onTap(x: Float, y: Float)
    /** Horizontal fling past the threshold: true = towards the left. */
    protected open fun onSwipe(left: Boolean) {}
    /** Vertical pull down past the threshold. */
    protected open fun onPullDown() {}
    protected open val swipeEnabled = false

    private val glowCenter: (View, FloatArray) -> Unit = { v, out -> out[0] = v.width / 2f; out[1] = v.height / 2f }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!present || alpha < 0.5f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downAt = SystemClock.uptimeMillis()
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                host?.let { h -> if (glow?.parentGroup !== h) glow = PressGlow(h, this, glowCenter) }
                glow?.set(true)
                press.press()
                isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (abs(dx) > 8f * d || abs(dy) > 8f * d)) {
                    dragging = true
                    glow?.set(false)
                }
                if (dragging) press.drag(dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                glow?.set(false)
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (dragging) {
                    press.releaseDrag()
                    if (swipeEnabled && abs(dx) > SWIPE_DP * d && abs(dx) > abs(dy) * 1.2f) {
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        onSwipe(dx < 0f)
                    } else if (dy > SWIPE_DP * d && dy > abs(dx) * 1.2f) {
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        onPullDown()
                    }
                } else {
                    press.releaseTap()
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onTap(event.x, event.y)
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                glow?.set(false)
                press.releaseDrag()
                dragging = false
            }
        }
        return true
    }

    companion object {
        const val SWIPE_DP = 36f
        val APPEAR_SPRING = LiquidPressPhysics.Spring(0.72f, 6.5f)
        val EASE = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        /** HyperLight hx0.a: the content crossfade curve. */
        val CROSS = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    }
}

// =================================================================== music

internal class MusicCapsule(context: Context) : CapsuleView(context) {
    var onPlayPause: (() -> Unit)? = null
    var onBody: (() -> Unit)? = null
    var onSkip: ((next: Boolean) -> Unit)? = null

    var showArtist = true
        set(value) { if (field != value) { field = value; measureTexts(); updateWidth(); invalidate() } }
    var coverScale = 1f
        set(value) { if (field != value) { field = value; updateWidth(); invalidate() } }
    var playScale = 1f
        set(value) { if (field != value) { field = value; updateWidth(); invalidate() } }
    /** 0 = rounded square, 1 = circle. */
    var coverShape = 0
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Corner radius of the rounded cover, % of its size (0..50). */
    var coverCorner = 30
        set(value) { if (field != value) { field = value; invalidate() } }

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.LEFT }
    private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val iconPath = Path()
    private val coverRect = RectF()
    private val playRect = RectF()
    private val tmp = RectF()
    private val camera = Camera()
    private val flipMatrix = Matrix()
    private val shaderMatrix = Matrix()

    private var title: CharSequence = ""
    private var artist: CharSequence = ""
    private var art: Bitmap? = null
    private var appIcon: Drawable? = null
    private var playing = false

    private var oldTitle: CharSequence = ""
    private var oldArtist: CharSequence = ""
    private var oldArt: Bitmap? = null
    private var oldAppIcon: Drawable? = null
    private var titleWidth = 0f
    private var artistWidth = 0f
    private var titleChangedAt = 0L
    private var artistChangedAt = 0L

    /** Track change progress (HyperLight ry0.A0, 350 ms). */
    private var swap = 1f
    private var swapAnim: ValueAnimator? = null
    /** Album flip (HyperLight ry0.R / W / S): rotate around Y, direction of the skip. */
    private var flipping = false
    private var flip = 1f
    private var flipDirection = 1
    private var pendingDirection = 0
    /** Paused shrinks the cover to 0.78 (HyperLight ry0.a0, 320 ms spring 0.85 / 7.5). */
    private var coverPlayScale = 0.78f
    private var coverAnim: ValueAnimator? = null
    /** Play / pause icon crossfade. */
    private var iconSwap = 1f
    private var iconOld = false
    private var iconAnim: ValueAnimator? = null

    override val swipeEnabled = true

    init {
        onMetricsChanged()
        onColorChanged()
    }

    override fun onMetricsChanged() {
        titlePaint.textSize = 14f * d * sizeScale
        artistPaint.textSize = 11f * d * sizeScale
        measureTexts()
    }

    override fun onColorChanged() {
        titlePaint.color = textColor
        artistPaint.color = (textColor and 0x00FFFFFF) or (0xB3 shl 24)
        iconPaint.color = textColor
        placeholderPaint.color = (textColor and 0x00FFFFFF) or (0x33 shl 24)
    }

    private fun pad() = 8f * d * sizeScale
    private fun maxText() = 112f * d * sizeScale * widthScale

    override fun targetHeight(): Int {
        val text = artistPaint.textSize * 0.95f + titlePaint.textSize
        return (pad() * 2f + max(text, 28f * d * sizeScale)).roundToInt()
    }

    override fun targetWidth(): Int {
        if (title.isEmpty()) return targetHeight()
        val inner = targetHeight() - pad() * 2f
        val cover = coverScale.coerceIn(0.5f, 1f) * inner
        val play = playScale.coerceIn(0.5f, 1.5f) * inner
        val textMax = maxText()
        val text = max(titleWidth, if (showArtist) artistWidth else 0f)
        val textW = if (text <= 0f) textMax * 0.5f else min(textMax, text + 0.5f)
        return (cover + play + textW + pad() * 4f).roundToInt()
    }

    private fun measureTexts() {
        titleWidth = if (title.isEmpty()) 0f else titlePaint.measureText(title.toString())
        artistWidth = if (artist.isEmpty()) 0f else artistPaint.measureText(artist.toString())
    }

    /** Direction of the next album flip: the skip just sent (HyperLight setAlbumFlipDirection). */
    fun setFlipDirection(next: Boolean) {
        pendingDirection = if (next) 1 else -1
    }

    fun bind(newTitle: CharSequence?, newArtist: CharSequence?, newArt: Bitmap?, newIcon: Drawable?, newPlaying: Boolean) {
        val t = newTitle ?: ""
        val a = newArtist ?: ""
        val hadContent = title.isNotEmpty()
        val textChanged = !TextUtils.equals(t, title) || !TextUtils.equals(a, artist)
        val artChanged = !sameBitmap(art, newArt)
        val playChanged = playing != newPlaying
        if (hadContent && present && (textChanged || artChanged)) {
            oldTitle = title
            oldArtist = artist
            oldArt = art
            oldAppIcon = appIcon
            startSwap(artChanged || pendingDirection != 0)
        }
        if (!TextUtils.equals(t, title)) titleChangedAt = SystemClock.uptimeMillis()
        if (!TextUtils.equals(a, artist)) artistChangedAt = SystemClock.uptimeMillis()
        title = t
        artist = a
        art = newArt
        appIcon = newIcon
        measureTexts()
        if (playChanged) {
            if (hadContent && present) startPlayChange(newPlaying) else {
                coverPlayScale = if (newPlaying) 1f else 0.78f
                iconSwap = 1f
            }
            playing = newPlaying
        } else if (!hadContent) {
            coverPlayScale = if (newPlaying) 1f else 0.78f
        }
        updateWidth()
        invalidate()
    }

    private fun sameBitmap(a: Bitmap?, b: Bitmap?): Boolean {
        if (a === b) return true
        if (a == null || b == null || a.isRecycled || b.isRecycled) return false
        return a.width == b.width && a.height == b.height && runCatching { a.sameAs(b) }.getOrDefault(false)
    }

    private fun startSwap(withFlip: Boolean) {
        swapAnim?.cancel()
        flipping = withFlip
        flipDirection = if (pendingDirection != 0) pendingDirection else 1
        pendingDirection = 0
        swap = 0f
        flip = 0f
        swapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350L
            interpolator = CROSS
            addUpdateListener {
                swap = it.animatedValue as Float
                flip = swap
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (swapAnim !== animation) return
                    swapAnim = null
                    swap = 1f
                    flip = 1f
                    flipping = false
                    oldArt = null
                    oldAppIcon = null
                    invalidate()
                }
            })
            start()
        }
    }

    private fun startPlayChange(nowPlaying: Boolean) {
        iconAnim?.cancel()
        iconOld = playing
        iconSwap = 0f
        iconAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350L
            interpolator = CROSS
            addUpdateListener { iconSwap = it.animatedValue as Float; invalidate() }
            start()
        }
        coverAnim?.cancel()
        val from = coverPlayScale
        val to = if (nowPlaying) 1f else 0.78f
        coverAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = 320L
            interpolator = COVER_SPRING
            addUpdateListener { coverPlayScale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun layoutParts() {
        val h = height.toFloat()
        val w = width.toFloat()
        val p = pad()
        val inner = h - p * 2f
        val cover = coverScale.coerceIn(0.5f, 1f) * inner
        val cy = (h - cover) / 2f
        coverRect.set(p, cy, p + cover, cy + cover)
        val play = playScale.coerceIn(0.5f, 1.5f) * inner
        val py = (h - play) / 2f
        playRect.set(w - p - play, py, w - p, py + play)
    }

    override fun drawContent(canvas: Canvas) {
        if (title.isEmpty() || width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        layoutParts()
        clip.reset()
        clip.addRoundRect(0f, 0f, w, h, h / 2f, h / 2f, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)

        drawCovers(canvas)

        val p = pad()
        val textLeft = coverRect.right + p
        val textW = max(0f, min(playRect.left - p - textLeft, maxText()))
        if (textW > 0f) {
            if (swap < 0.999f) {
                val out = 1f - swap
                val inn = CROSS.getInterpolation(swap)
                if (out > 0.01f) drawTexts(canvas, oldTitle, oldArtist, textLeft, textW, h, out, -6f * d * swap, false)
                if (inn > 0.01f) drawTexts(canvas, title, artist, textLeft, textW, h, inn, 6f * d * (1f - swap), true)
            } else {
                drawTexts(canvas, title, artist, textLeft, textW, h, 1f, 0f, true)
            }
        }

        if (iconSwap < 0.999f) {
            drawPlayIcon(canvas, iconOld, 1f - iconSwap, 0.6f + 0.4f * (1f - iconSwap))
            drawPlayIcon(canvas, playing, iconSwap, 0.6f + 0.4f * iconSwap)
        } else {
            drawPlayIcon(canvas, playing, 1f, 1f)
        }
        canvas.restoreToCount(save)

        if (marqueeRunning) postInvalidateOnAnimation()
    }

    private var marqueeRunning = false

    private fun drawCovers(canvas: Canvas) {
        val radius = if (coverShape == 1) coverRect.width() / 2f
        else coverRect.width() * (coverCorner.coerceIn(0, 50) / 100f)
        val save = canvas.save()
        if (coverPlayScale < 0.999f) canvas.scale(coverPlayScale, coverPlayScale, coverRect.centerX(), coverRect.centerY())
        if (flipping && flip < 0.999f) {
            // HyperLight ry0.d: the cover turns over around Y; past halfway the new one shows,
            // mirrored back so it reads the right way round.
            val secondHalf = flip >= 0.5f
            val cx = coverRect.centerX()
            val cy = coverRect.centerY()
            camera.save()
            camera.rotateY(flipDirection * 180f * flip)
            camera.getMatrix(flipMatrix)
            camera.restore()
            flipMatrix.preTranslate(-cx, -cy)
            flipMatrix.postTranslate(cx, cy)
            canvas.concat(flipMatrix)
            if (secondHalf) canvas.scale(-1f, 1f, cx, cy)
            if (secondHalf) drawCover(canvas, art, appIcon, radius, 255) else drawCover(canvas, oldArt, oldAppIcon, radius, 255)
        } else if (swap < 0.999f) {
            drawCover(canvas, oldArt, oldAppIcon, radius, ((1f - swap) * 255).roundToInt())
            drawCover(canvas, art, appIcon, radius, (CROSS.getInterpolation(swap) * 255).roundToInt())
        } else {
            drawCover(canvas, art, appIcon, radius, 255)
        }
        canvas.restoreToCount(save)
    }

    private fun drawCover(canvas: Canvas, bitmap: Bitmap?, icon: Drawable?, radius: Float, alpha: Int) {
        if (alpha <= 2) return
        val r = coverRect
        if (bitmap != null && !bitmap.isRecycled) {
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            // centre-crop into the cover square
            val scale = max(r.width() / bitmap.width, r.height() / bitmap.height)
            val dx = r.left + (r.width() - bitmap.width * scale) / 2f
            val dy = r.top + (r.height() - bitmap.height * scale) / 2f
            shaderMatrix.setScale(scale, scale)
            shaderMatrix.postTranslate(dx, dy)
            shader.setLocalMatrix(shaderMatrix)
            coverPaint.shader = shader
            coverPaint.alpha = alpha
            canvas.drawRoundRect(r, radius, radius, coverPaint)
            coverPaint.shader = null
            return
        }
        placeholderPaint.alpha = (Color.alpha(placeholderPaint.color).coerceAtLeast(0x33) * alpha / 255)
        canvas.drawRoundRect(r, radius, radius, placeholderPaint)
        if (icon != null) {
            val inset = r.width() * 0.18f
            icon.setBounds((r.left + inset).roundToInt(), (r.top + inset).roundToInt(), (r.right - inset).roundToInt(), (r.bottom - inset).roundToInt())
            icon.alpha = alpha
            icon.draw(canvas)
            icon.alpha = 255
        }
        placeholderPaint.alpha = 0x33
    }

    private fun drawTexts(canvas: Canvas, t: CharSequence, a: CharSequence, left: Float, maxW: Float, h: Float, alpha: Float, shiftY: Float, live: Boolean) {
        if (t.isEmpty()) return
        val withArtist = showArtist && a.isNotEmpty()
        val tfm = titlePaint.fontMetrics
        val afm = artistPaint.fontMetrics
        val titleH = tfm.descent - tfm.ascent
        val artistH = afm.descent - afm.ascent
        val block = if (withArtist) titleH + artistH * 0.95f else titleH
        val top = (h - block) / 2f + shiftY
        val save = canvas.save()
        canvas.clipRect(left, 0f, left + maxW, h)
        val ta = titlePaint.alpha
        val aa = artistPaint.alpha
        titlePaint.alpha = (ta * alpha).roundToInt()
        artistPaint.alpha = (aa * alpha).roundToInt()
        var running = false
        running = drawMarquee(canvas, t, titlePaint, left, top - tfm.ascent, maxW, live, titleChangedAt) || running
        if (withArtist) {
            running = drawMarquee(canvas, a, artistPaint, left, top + titleH * 0.98f - afm.ascent, maxW, live, artistChangedAt) || running
        }
        titlePaint.alpha = ta
        artistPaint.alpha = aa
        canvas.restoreToCount(save)
        if (live) marqueeRunning = running
    }

    /** HyperLight ry0.f: wait 900 ms, scroll at 0.035 px/ms, hold the end, repeat. */
    private fun drawMarquee(canvas: Canvas, text: CharSequence, paint: TextPaint, x: Float, y: Float, maxW: Float, live: Boolean, since: Long): Boolean {
        val overflow = paint.measureText(text.toString()) - maxW
        if (overflow <= 0f || !live) {
            canvas.drawText(text, 0, text.length, x, y, paint)
            return false
        }
        val scrollMs = overflow / 0.035f
        val cycle = scrollMs.toLong() + 1800L
        val t = max(0L, SystemClock.uptimeMillis() - since) % cycle
        val offset = when {
            t < 900L -> 0f
            t < 900L + scrollMs -> (t - 900L) * 0.035f
            else -> overflow
        }
        canvas.drawText(text, 0, text.length, x - offset, y, paint)
        return true
    }

    private fun drawPlayIcon(canvas: Canvas, isPlaying: Boolean, alpha: Float, scale: Float) {
        if (alpha <= 0.01f) return
        val r = playRect
        val s = r.width() * 0.42f * scale
        val cx = r.centerX()
        val cy = r.centerY()
        iconPaint.alpha = (Color.alpha(textColor) * alpha).roundToInt()
        iconPaint.style = Paint.Style.FILL
        if (isPlaying) {
            val barW = s * 0.34f
            val barH = s * 1.1f
            val gap = s * 0.3f
            tmp.set(cx - gap / 2f - barW, cy - barH / 2f, cx - gap / 2f, cy + barH / 2f)
            canvas.drawRoundRect(tmp, barW * 0.3f, barW * 0.3f, iconPaint)
            tmp.set(cx + gap / 2f, cy - barH / 2f, cx + gap / 2f + barW, cy + barH / 2f)
            canvas.drawRoundRect(tmp, barW * 0.3f, barW * 0.3f, iconPaint)
        } else {
            val hh = s * 0.58f
            val ww = s * 1.0f
            iconPath.reset()
            iconPath.moveTo(cx - ww * 0.4f, cy - hh)
            iconPath.lineTo(cx + ww * 0.6f, cy)
            iconPath.lineTo(cx - ww * 0.4f, cy + hh)
            iconPath.close()
            iconPaint.strokeJoin = Paint.Join.ROUND
            iconPaint.style = Paint.Style.FILL_AND_STROKE
            iconPaint.strokeWidth = s * 0.12f
            canvas.drawPath(iconPath, iconPaint)
        }
        iconPaint.alpha = 255
    }

    override fun onTap(x: Float, y: Float) {
        layoutParts()
        tmp.set(playRect)
        tmp.inset(-pad(), -pad())
        if (tmp.contains(x, y)) onPlayPause?.invoke() else onBody?.invoke()
    }

    override fun onSwipe(left: Boolean) {
        setFlipDirection(left)
        onSkip?.invoke(left)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        swapAnim?.cancel(); swapAnim = null
        iconAnim?.cancel(); iconAnim = null
        coverAnim?.cancel(); coverAnim = null
        swap = 1f; flip = 1f; flipping = false; iconSwap = 1f
        coverPlayScale = if (playing) 1f else 0.78f
        oldArt = null; oldAppIcon = null
        press.reset()
    }

    companion object {
        val COVER_SPRING = LiquidPressPhysics.Spring(0.85f, 7.5f)
    }
}

// =================================================================== status

/** What the status capsule shows (HyperLight yz0.t arguments). */
internal data class StatusContent(
    val live: LiveItem? = null,
    val icons: List<Drawable> = emptyList(),
    val count: Int = 0,
    val countText: String = "",
    val dnd: Boolean = false,
    val dndLabel: String = "",
    val charging: Boolean = false,
    val level: Int = 0,
    val chargingLabel: String = ""
) {
    val empty: Boolean get() = live == null && icons.isEmpty() && countText.isEmpty() && !dnd && !charging
}

/**
 * A live notification for the status capsule (HyperLight's focus slot): vivo's SuperX timer or a
 * chronometer (stopwatch). [base] counts on elapsedRealtime when [elapsedClock], else wall time.
 */
internal data class LiveItem(
    val key: String,
    val icon: Drawable?,
    val text: String?,
    val base: Long,
    val countDown: Boolean,
    val elapsedClock: Boolean,
    val running: Boolean
) {
    val ticking: Boolean get() = running && base > 0L

    fun currentText(): String {
        if (!ticking) return text.orEmpty()
        val now = if (elapsedClock) SystemClock.elapsedRealtime() else System.currentTimeMillis()
        val ms = if (countDown) base - now else now - base
        return format(max(0L, ms))
    }

    /** Milliseconds to the next whole-second change of [currentText]. */
    fun msToNextTick(): Long {
        val now = if (elapsedClock) SystemClock.elapsedRealtime() else System.currentTimeMillis()
        val ms = if (countDown) base - now else now - base
        val rest = Math.floorMod(ms, 1000L)
        return (if (countDown) rest else 1000L - rest).coerceIn(16L, 1000L)
    }

    companion object {
        fun format(ms: Long): String {
            val total = ms / 1000L
            val h = total / 3600L
            val m = (total / 60L) % 60L
            val s = total % 60L
            return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(java.util.Locale.US, "%02d:%02d", m, s)
        }
    }
}

/** A notification small icon tinted with the capsule text color. */
internal class TintedIcon(private val source: Drawable) : Drawable() {
    var color = Color.WHITE
        set(value) {
            if (field == value) return
            field = value
            source.setTint(value)
        }

    init { source.setTint(color) }

    override fun draw(canvas: Canvas) {
        source.bounds = bounds
        source.draw(canvas)
    }

    override fun setAlpha(alpha: Int) { source.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { source.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

internal class StatusCapsule(context: Context) : CapsuleView(context) {
    var onTapped: (() -> Unit)? = null
    var onPulledDown: (() -> Unit)? = null
    private var liveTemplate = ""

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; fontFeatureSettings = "tnum" }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val ringTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconClip = Path()
    private val moon = Path()
    private val ring = RectF()

    private var content = StatusContent()
    private var old: StatusContent? = null
    private var swap = 1f
    private var swapAnim: ValueAnimator? = null

    init {
        onMetricsChanged()
        onColorChanged()
    }

    private fun u(v: Float) = v * d * sizeScale
    private val iconSize get() = u(16f)
    private val iconStep get() = iconSize * 0.55f
    private val ringSize get() = u(20f)
    private val gap get() = u(6f)

    override fun onMetricsChanged() {
        textPaint.textSize = u(15f)
        smallPaint.textSize = u(9.5f) * 0.8f
        ringTrack.strokeWidth = u(2f)
        ringPaint.strokeWidth = u(2f)
    }

    override fun onColorChanged() {
        textPaint.color = textColor
        smallPaint.color = textColor
        fill.color = textColor
        ringPaint.color = textColor
        ringTrack.color = (textColor and 0x00FFFFFF) or (0x40 shl 24)
    }

    override fun targetHeight(): Int {
        val fm = textPaint.fontMetrics
        return (u(8f) * 2f + max(max(fm.descent - fm.ascent, iconSize), ringSize)).roundToInt()
    }

    override fun targetWidth(): Int {
        val h = targetHeight().toFloat()
        val inner = contentWidth(content)
        val padded = max((h / 2f - ringSize / 2f) * 2f + inner, h)
        return min(padded, max(u(198f) * widthScale, h)).roundToInt()
    }

    /** Text shown next to the icons / ring; the label only when that item is alone. */
    private fun alone(c: StatusContent): Int =
        (if (c.live != null) 1 else 0) + (if (c.icons.isNotEmpty() || c.countText.isNotEmpty()) 1 else 0) + (if (c.dnd) 1 else 0) + (if (c.charging) 1 else 0)

    private fun contentWidth(c: StatusContent): Float {
        var w = 0f
        fun add(v: Float) { if (v <= 0f) return; w += (if (w > 0f) gap else 0f) + v }
        c.live?.let { add((if (it.icon != null) iconSize + u(5f) else 0f) + textPaint.measureText(template(it))) }
        if (c.icons.isNotEmpty()) add(iconStep * (c.icons.size - 1) + iconSize)
        if (c.countText.isNotEmpty()) add(textPaint.measureText(c.countText))
        val single = alone(c) == 1
        if (c.dnd) add(iconSize + if (single && c.dndLabel.isNotEmpty()) u(4f) + textPaint.measureText(c.dndLabel) else 0f)
        if (c.charging) add(ringSize + if (single && c.chargingLabel.isNotEmpty()) u(6f) + textPaint.measureText(c.chargingLabel) else 0f)
        return w
    }

    fun bind(next: StatusContent) {
        if (next == content) return
        if (present && !content.empty && !next.empty) {
            old = content
            swapAnim?.cancel()
            swap = 0f
            swapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 350L
                interpolator = CROSS
                addUpdateListener { swap = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (swapAnim !== animation) return
                        swapAnim = null
                        swap = 1f
                        old = null
                        invalidate()
                    }
                })
                start()
            }
        }
        content = next
        updateWidth()
        invalidate()
    }

    override fun drawContent(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        iconClip.reset()
        iconClip.addRoundRect(0f, 0f, w, h, h / 2f, h / 2f, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(iconClip)
        val o = old
        if (o != null && swap < 0.999f) {
            drawItems(canvas, o, w, h, 1f - swap, 1f - 0.15f * swap)
            drawItems(canvas, content, w, h, CROSS.getInterpolation(swap), 0.85f + 0.15f * swap)
        } else {
            drawItems(canvas, content, w, h, 1f, 1f)
        }
        canvas.restoreToCount(save)
        // The timer ticks: redraw on the next second, and re-measure when the format widens.
        content.live?.takeIf { it.ticking && present }?.let { item ->
            val t = template(item)
            if (t != liveTemplate) {
                liveTemplate = t
                post { updateWidth() }
            }
            postInvalidateDelayed(item.msToNextTick())
        }
    }

    /** Width template of the live text: digits as zeros (tabular figures), so it never jitters. */
    private fun template(item: LiveItem): String = item.currentText().replace(Regex("\\d"), "0")

    private fun drawItems(canvas: Canvas, c: StatusContent, w: Float, h: Float, alpha: Float, scale: Float) {
        if (alpha <= 0.01f || c.empty) return
        val total = contentWidth(c)
        val maxInner = w - (h - ringSize)
        val save = canvas.save()
        canvas.scale(scale, scale, w / 2f, h / 2f)
        var x = (w - min(total, maxInner)) / 2f
        val cy = h / 2f
        val a = (alpha * 255).roundToInt()
        val single = alone(c) == 1
        var first = true
        fun step() { if (!first) x += gap; first = false }

        c.live?.let { item ->
            step()
            item.icon?.let { icon ->
                (icon as? TintedIcon)?.color = textColor
                val s = iconSize
                icon.setBounds(x.roundToInt(), (cy - s / 2f).roundToInt(), (x + s).roundToInt(), (cy + s / 2f).roundToInt())
                icon.alpha = a
                icon.draw(canvas)
                icon.alpha = 255
                x += s + u(5f)
            }
            val text = item.currentText()
            drawText(canvas, text, x, cy, a)
            x += textPaint.measureText(template(item))
        }
        if (c.icons.isNotEmpty()) {
            step()
            val s = iconSize
            // HyperLight yz0.h: the app icons overlap, each one cut out of the next.
            c.icons.forEachIndexed { i, icon ->
                val left = x + i * iconStep
                icon.setBounds(left.roundToInt(), (cy - s / 2f).roundToInt(), (left + s).roundToInt(), (cy + s / 2f).roundToInt())
                icon.alpha = a
                icon.draw(canvas)
                icon.alpha = 255
            }
            x += iconStep * (c.icons.size - 1) + s
        }
        if (c.countText.isNotEmpty()) {
            step()
            drawText(canvas, c.countText, x, cy, a)
            x += textPaint.measureText(c.countText)
        }
        if (c.dnd) {
            step()
            drawMoon(canvas, x, cy, iconSize, a)
            x += iconSize
            if (single && c.dndLabel.isNotEmpty()) {
                x += u(4f)
                drawText(canvas, c.dndLabel, x, cy, a)
                x += textPaint.measureText(c.dndLabel)
            }
        }
        if (c.charging) {
            step()
            drawRing(canvas, x, cy, c.level, a)
            x += ringSize
            if (single && c.chargingLabel.isNotEmpty()) {
                x += u(6f)
                drawText(canvas, c.chargingLabel, x, cy, a)
                x += textPaint.measureText(c.chargingLabel)
            }
        }
        canvas.restoreToCount(save)
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, cy: Float, alpha: Int) {
        val fm = textPaint.fontMetrics
        textPaint.alpha = Color.alpha(textColor) * alpha / 255
        canvas.drawText(text, x, cy - (fm.ascent + fm.descent) / 2f, textPaint)
        textPaint.alpha = Color.alpha(textColor)
    }

    /** HyperLight's charging ring: the level as an arc with the percentage inside. */
    private fun drawRing(canvas: Canvas, left: Float, cy: Float, level: Int, alpha: Int) {
        val s = ringSize
        val sw = ringPaint.strokeWidth
        ring.set(left + sw / 2f, cy - s / 2f + sw / 2f, left + s - sw / 2f, cy + s / 2f - sw / 2f)
        val trackAlpha = Color.alpha(ringTrack.color)
        ringTrack.alpha = trackAlpha * alpha / 255
        canvas.drawOval(ring, ringTrack)
        ringTrack.alpha = trackAlpha
        ringPaint.alpha = Color.alpha(textColor) * alpha / 255
        canvas.drawArc(ring, -90f, 360f * level.coerceIn(0, 100) / 100f, false, ringPaint)
        val fm = smallPaint.fontMetrics
        smallPaint.alpha = Color.alpha(textColor) * alpha / 255
        canvas.drawText(level.coerceIn(0, 100).toString(), ring.centerX(), cy - (fm.ascent + fm.descent) / 2f, smallPaint)
    }

    private fun drawMoon(canvas: Canvas, left: Float, cy: Float, s: Float, alpha: Int) {
        val r = s * 0.46f
        val cx = left + s / 2f
        moon.reset()
        moon.addCircle(cx, cy, r, Path.Direction.CW)
        val cut = Path().apply { addCircle(cx + r * 0.62f, cy - r * 0.42f, r * 0.82f, Path.Direction.CW) }
        moon.op(cut, Path.Op.DIFFERENCE)
        fill.alpha = Color.alpha(textColor) * alpha / 255
        canvas.drawPath(moon, fill)
        fill.alpha = 255
    }

    override fun onTap(x: Float, y: Float) {
        onTapped?.invoke()
    }

    override fun onPullDown() {
        onPulledDown?.invoke()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        swapAnim?.cancel()
        swapAnim = null
        swap = 1f
        old = null
        press.reset()
    }
}

/** A round app icon for the status capsule (HyperLight draws the icons as circles). */
internal class RoundIconDrawable(private val source: Drawable, private val ring: Int) : Drawable() {
    private val clip = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var a = 255

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val r = min(b.width(), b.height()) / 2f
        clip.reset()
        clip.addCircle(b.exactCenterX(), b.exactCenterY(), r, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        source.bounds = b
        source.alpha = a
        source.draw(canvas)
        canvas.restoreToCount(save)
        paint.color = ring
        paint.alpha = Color.alpha(ring) * a / 255
        paint.strokeWidth = max(1f, r * 0.14f)
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r - paint.strokeWidth / 2f, paint)
    }

    override fun setAlpha(alpha: Int) { a = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { source.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun equals(other: Any?): Boolean = other is RoundIconDrawable && other.source === source && other.ring == ring
    override fun hashCode(): Int = System.identityHashCode(source) * 31 + ring
}
