package dev.unvoid.originceiler

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The iOS 26 "Customize" sheet for home screen icons, shown over the launcher: the four looks
 * with live previews, hue and saturation for Tinted (with wallpaper colour, system colour and an
 * eyedropper), Light / Dark / Auto, a quick appearance switch and small / large icons. Every
 * change repaints the home screen behind the sheet immediately.
 */
@SuppressLint("ViewConstructor")
class IosIconPanel private constructor(private val activity: Activity) : FrameLayout(activity) {
    private val ru = resources.configuration.locales[0].language == "ru"
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun dpi(v: Float) = (v * density).roundToInt()

    private val symbols: Typeface? get() = symbolFont(activity)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)

    private val sheet = LinearLayout(activity)
    private val tiles = ArrayList<StyleTile>(4)
    private val tileLabels = ArrayList<TextView>(4)
    private val tintBlock = LinearLayout(activity)
    private lateinit var hueSlider: GlassSlider
    private lateinit var satSlider: GlassSlider
    private val segments = FrameLayout(activity)
    private val segmentRow = LinearLayout(activity)
    private val segmentIndicator = View(activity)
    private val segmentLabels = ArrayList<TextView>(3)
    private val labelPills = ArrayList<GradientDrawable>(4)
    private var segmentCount = 0
    private var segmentSelected = -1
    private var shownStyle = -1
    private lateinit var sizeButton: SizeToggle
    private var dismissing = false
    private val openedWith = IosIconStyles.signature()
    private var downY = 0f
    private var dragging = false

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        clipChildren = false
        setOnClickListener { dismiss() }
        buildSheet()
        refresh(animate = false)
    }

    // ---------------------------------------------------------------- layout

    private fun buildSheet() {
        sheet.orientation = LinearLayout.VERTICAL
        sheet.isClickable = true
        sheet.clipChildren = false
        sheet.setPadding(dpi(18f), dpi(14f), dpi(18f), dpi(16f))
        sheet.background = KyantGlass.GlassDrawable(
            sheet, "",
            backdrop = { v -> runCatching { ScreenBackdropSource.getBackdrop(v.context) }.getOrNull() ?: KyantGlassHook.ShadeBackdrop.get(v, requireFocus = false) },
            surface = { _ -> if (IosIconStyles.isDark(context)) Color.argb(70, 10, 10, 14) else Color.argb(34, 255, 255, 255) },
            zone = KyantGlass.Zone.HOME,
            lensScale = 0.6f,
            radius = { _, _, _ -> dp(38f) }
        )
        KyantGlass.track(sheet)
        sheet.setOnTouchListener { _, e -> dragToDismiss(e) }
        // Height changes (colour block, appearance row) glide instead of jumping.
        sheet.layoutTransition = android.animation.LayoutTransition().apply {
            enableTransitionType(android.animation.LayoutTransition.CHANGING)
            setDuration(240)
        }
        layoutTransition = android.animation.LayoutTransition().apply {
            enableTransitionType(android.animation.LayoutTransition.CHANGING)
            setDuration(240)
        }

        // Header: title and icon size (appearance lives in the row at the bottom).
        val header = FrameLayout(activity)
        val title = text(if (ru) "Настроить" else "Customize", 17f, bold = true)
        header.addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        sizeButton = SizeToggle(activity).apply { setOnClickListener { toggleSize() } }
        header.addView(sizeButton, LayoutParams(dpi(40f), dpi(40f), Gravity.END or Gravity.CENTER_VERTICAL))
        sheet.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(44f)))

        // The four looks.
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val names = if (ru) listOf("Стандарт", "Тёмные", "Прозрачные", "Оттенок") else listOf("Default", "Dark", "Clear", "Tinted")
        names.forEachIndexed { index, name ->
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                clipChildren = false
                setOnClickListener { selectStyle(index) }
            }
            pressable(column)
            val tile = StyleTile(activity, index)
            tiles += tile
            column.addView(tile, LinearLayout.LayoutParams(dpi(66f), dpi(66f)))
            val pillBg = pill(Color.argb(60, 255, 255, 255)).also { it.alpha = 0; labelPills += it }
            val label = text(name, 12.5f).apply {
                setPadding(dpi(8f), dpi(4f), dpi(8f), dpi(5f))
                maxLines = 1
                gravity = Gravity.CENTER
                background = pillBg
            }
            tileLabels += label
            column.addView(label, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })
            row.addView(column, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        sheet.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(14f) })

        // Tinted: hue, saturation and colour sources.
        tintBlock.orientation = LinearLayout.VERTICAL
        tintBlock.clipChildren = false
        hueSlider = GlassSlider(activity, hue = true) { value, done ->
            IosIconStyles.update(context, newHue = (value * 360f).roundToInt().coerceIn(0, 359))
            IosIconStyleHook.syncGlassTint(context, immediate = done)
        }
        satSlider = GlassSlider(activity, hue = false) { value, done ->
            IosIconStyles.update(context, newSat = (value * 100f).roundToInt())
            IosIconStyleHook.syncGlassTint(context, immediate = done)
        }
        tintBlock.addView(hueSlider, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(34f)).apply { topMargin = dpi(18f) })
        tintBlock.addView(satSlider, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(34f)).apply { topMargin = dpi(12f) })
        val sources = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(
            "wallpaper" to { useWallpaperColor() },
            "filter_vintage" to { useSystemColor() },
            "colorize" to { startEyedropper() },
        ).forEachIndexed { i, (icon, action) ->
            val button = symbolButton(icon, 21f).apply {
                background = circle(Color.argb(46, 255, 255, 255))
                setOnClickListener { action() }
            }
            pressable(button)
            sources.addView(button, LinearLayout.LayoutParams(dpi(44f), dpi(44f)).apply { if (i > 0) marginStart = dpi(26f) })
        }
        tintBlock.addView(sources, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(14f) })
        sheet.addView(tintBlock, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Light / Dark / Auto.
        segments.setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
        segments.background = pill(Color.argb(30, 255, 255, 255))
        segmentIndicator.background = pill(Color.argb(64, 255, 255, 255))
        segments.addView(segmentIndicator, LayoutParams(0, dpi(38f)))
        segmentRow.orientation = LinearLayout.HORIZONTAL
        repeat(3) { i ->
            val label = text("", 14.5f).apply {
                gravity = Gravity.CENTER
                setOnClickListener { selectSegment(i) }
            }
            segmentLabels += label
            segmentRow.addView(label, LinearLayout.LayoutParams(0, dpi(38f), 1f))
        }
        segments.addView(segmentRow, LayoutParams(LayoutParams.MATCH_PARENT, dpi(38f)))
        segments.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) placeIndicator(false) }
        sheet.addView(segments, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(18f) })

        val insets = activity.window.decorView.rootWindowInsets
        val bottomInset = insets?.stableInsetBottom ?: 0
        val width = minOf(resources.displayMetrics.widthPixels - dpi(20f), dpi(420f))
        addView(sheet, LayoutParams(width, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = bottomInset + dpi(10f)
        })
    }

    private fun text(value: String, sp: Float, bold: Boolean = false) = TextView(activity).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setShadowLayer(dp(6f), 0f, dp(1f), Color.argb(60, 0, 0, 0))
        includeFontPadding = false
    }

    private fun symbolButton(name: String, sp: Float) = TextView(activity).apply {
        text = name
        typeface = symbols
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.WHITE)
        setShadowLayer(dp(6f), 0f, dp(1f), Color.argb(60, 0, 0, 0))
        isClickable = true
    }

    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(100f) }
    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    // ---------------------------------------------------------------- state

    fun refresh(animate: Boolean = true) {
        val style = IosIconStyles.style
        tileLabels.forEachIndexed { i, label ->
            val on = i == style
            val pillBg = labelPills[i]
            val target = if (on) 255 else 0
            if (animate && pillBg.alpha != target) {
                ValueAnimator.ofInt(pillBg.alpha, target).apply {
                    duration = 200
                    addUpdateListener { pillBg.alpha = it.animatedValue as Int; label.invalidate() }
                }.start()
            } else pillBg.alpha = target
            label.animate().alpha(if (on) 1f else 0.78f).setDuration(if (animate) 200L else 0L).start()
            label.typeface = if (on) bold else regular
        }
        tiles.forEach { it.invalidate() }
        val showTint = style == IosIconStyles.TINTED || style == IosIconStyles.CLEAR
        tintBlock.visibility = if (showTint) View.VISIBLE else View.GONE
        hueSlider.setValue(IosIconStyles.hue / 360f)
        satSlider.setValue(IosIconStyles.saturation / 100f)

        // Default with dark appearance is the Dark tile: no second switch for it.
        segments.visibility = if (style == IosIconStyles.DEFAULT) View.GONE else View.VISIBLE
        val labels = if (style == IosIconStyles.DARK) {
            if (ru) listOf("Всегда", "Авто") else listOf("Always", "Auto")
        } else {
            if (ru) listOf("Светлые", "Тёмные", "Авто") else listOf("Light", "Dark", "Auto")
        }
        val selected = if (style == IosIconStyles.DARK) {
            if (IosIconStyles.appearance == IosIconStyles.APPEARANCE_AUTO) 1 else 0
        } else IosIconStyles.appearance
        val countChanged = labels.size != segmentCount
        segmentCount = labels.size
        segmentLabels.forEachIndexed { i, label ->
            label.visibility = if (i < labels.size) View.VISIBLE else View.GONE
            if (i >= labels.size) return@forEachIndexed
            label.text = labels[i]
            val on = i == selected
            label.animate().alpha(if (on) 1f else 0.6f).setDuration(if (animate) 200L else 0L).start()
            label.typeface = if (on) bold else regular
        }
        val moved = selected != segmentSelected
        segmentSelected = selected
        if (moved || countChanged) placeIndicator(animate && !countChanged && shownStyle == style)
        shownStyle = style
        sizeButton.large = IosIconStyles.large
        sheet.invalidate()
    }

    /** Slides the appearance pill under the selected segment. */
    private fun placeIndicator(animate: Boolean) {
        val inner = segments.width - segments.paddingLeft - segments.paddingRight
        if (inner <= 0 || segmentCount == 0) {
            segments.post { if (segments.width > 0 && segmentCount > 0) placeIndicator(false) }
            return
        }
        val w = inner / segmentCount
        val params = segmentIndicator.layoutParams
        if (params.width != w) { params.width = w; segmentIndicator.layoutParams = params }
        val x = (w * segmentSelected.coerceAtLeast(0)).toFloat()
        if (animate) segmentIndicator.animate().translationX(x).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        else segmentIndicator.translationX = x
    }

    /** Light press feedback: a quick shrink and a soft spring back. */
    @SuppressLint("ClickableViewAccessibility")
    private fun pressable(view: View) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(110).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(OvershootInterpolator(2.2f)).start()
            }
            false
        }
    }

    private fun selectStyle(index: Int) {
        if (index == IosIconStyles.style) return
        // Dark means "always dark" unless set to Auto; the other looks keep their appearance.
        val appearance = when {
            index == IosIconStyles.DARK && IosIconStyles.appearance == IosIconStyles.APPEARANCE_LIGHT -> IosIconStyles.APPEARANCE_DARK
            // Default is the launcher's own icons, day and night (Auto / Dark would turn it into the Dark art).
            index == IosIconStyles.DEFAULT -> IosIconStyles.APPEARANCE_LIGHT
            else -> IosIconStyles.appearance
        }
        IosIconStyles.update(context, newStyle = index, newAppearance = appearance)
        IosIconStyleHook.syncGlassTint(context, immediate = true)
    }

    private fun selectSegment(index: Int) {
        val appearance = if (IosIconStyles.style == IosIconStyles.DARK) {
            if (index == 1) IosIconStyles.APPEARANCE_AUTO else IosIconStyles.APPEARANCE_DARK
        } else index
        IosIconStyles.update(context, newAppearance = appearance)
    }

    private fun toggleSize() {
        IosIconStyles.setLarge(context, !IosIconStyles.large)
        sizeButton.large = IosIconStyles.large
    }

    private fun setTint(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        IosIconStyles.update(context, newHue = hsv[0].roundToInt(), newSat = (hsv[1] * 100f).roundToInt().coerceIn(12, 100))
        IosIconStyleHook.syncGlassTint(context, immediate = true)
    }

    private fun useWallpaperColor() {
        val colors = runCatching { WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
        val color = colors?.let { listOfNotNull(it.primaryColor, it.secondaryColor, it.tertiaryColor) }
            ?.map { it.toArgb() }
            ?.maxByOrNull { c -> FloatArray(3).also { Color.colorToHSV(c, it) }[1] }
            ?: return
        setTint(color)
    }

    private fun useSystemColor() {
        val color = runCatching { context.getColor(android.R.color.system_accent1_400) }.getOrNull() ?: return
        setTint(color)
    }

    /** Hides the sheet, the next touch picks a colour from the wallpaper under the finger. */
    private fun startEyedropper() {
        val backdrop = ScreenBackdropSource.getBackdrop(context) ?: KyantGlassHook.ShadeBackdrop.get(this, requireFocus = false) ?: return
        val picker = EyedropperView(activity, backdrop) { color ->
            if (color != null) setTint(color)
            sheet.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
        sheet.animate().alpha(0f).translationY(dp(40f)).setDuration(180).start()
        addView(picker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // ---------------------------------------------------------------- show / hide

    private fun appear() {
        sheet.alpha = 0f
        sheet.translationY = sheet.height.toFloat().coerceAtLeast(dp(200f)) * 0.6f
        sheet.animate().alpha(1f).translationY(0f)
            .setDuration(320).setInterpolator(DecelerateInterpolator(2.2f)).start()
    }

    fun dismiss() {
        if (dismissing) return
        dismissing = true
        sheet.animate().alpha(0f).translationY(sheet.height * 0.5f)
            .setDuration(200).setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    (parent as? ViewGroup)?.removeView(this@IosIconPanel)
                    if (current === this@IosIconPanel) current = null
                    // The stored icon bitmaps (animations, drag) still wear the old look.
                    if (IosIconStyles.signature() != openedWith) IosIconStyleHook.rebuildIconCache(activity)
                }
            }).start()
    }

    private fun dragToDismiss(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - downY
                if (dy > dp(8f)) dragging = true
                if (dragging) sheet.translationY = dy.coerceAtLeast(0f) * 0.85f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging && sheet.translationY > dp(90f)) dismiss()
                else sheet.animate().translationY(0f).setDuration(200).start()
                dragging = false
            }
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) dismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- previews

    /** One look applied to the weather icon, on glass for Clear and Tinted (as on iOS). */
    private inner class StyleTile(context: Context, private val tileStyle: Int) : View(context) {
        private val renderer = KyantGlass.Renderer(KyantGlass.Zone.HOME)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val rect = RectF()
        private val glass = RectF()
        private val radii = FloatArray(4)
        private val clip = Path()
        private val scrim = Paint(Paint.ANTI_ALIAS_FLAG)
        private var original: Drawable? = null

        override fun onDraw(canvas: Canvas) {
            val size = minOf(width, height)
            if (size <= 0) return
            val dark = IosIconStyles.isDark(context)
            rect.set(0f, 0f, size.toFloat(), size.toFloat())
            when (tileStyle) {
                IosIconStyles.DEFAULT -> {
                    val icon = original ?: runCatching { context.packageManager.getApplicationIcon(SAMPLE.packageName) }.getOrNull()
                        ?.also { original = it }
                    if (icon != null) {
                        // Square legacy icons get the same squircle as the other looks.
                        val inset = size * IosIconStyles.DARK_INSET
                        glass.set(inset, inset, size - inset, size - inset)
                        clip.rewind()
                        clip.addRoundRect(glass, glass.width() * IosIconStyles.PLATE_RADIUS, glass.width() * IosIconStyles.PLATE_RADIUS, Path.Direction.CW)
                        canvas.save()
                        canvas.clipPath(clip)
                        icon.setBounds(inset.toInt(), inset.toInt(), (size - inset).toInt(), (size - inset).toInt())
                        icon.draw(canvas)
                        canvas.restore()
                    } else {
                        IosIconStyles.art(context, SAMPLE, SAMPLE.packageName, size, dark = true)?.let { canvas.drawBitmap(it, null, rect, paint) }
                    }
                }
                IosIconStyles.DARK, IosIconStyles.TINTED -> IosIconStyles.art(context, SAMPLE, SAMPLE.packageName, size, dark = true)
                    ?.let {
                        paint.colorFilter = IosIconStyles.filterFor(tileStyle, dark)
                        canvas.drawBitmap(it, null, rect, paint)
                    }
                else -> {
                    val inset = size * IosIconStyles.CLEAR_INSET
                    glass.set(inset, inset, size - inset, size - inset)
                    val r = glass.width() * IosIconStyles.CLEAR_RADIUS
                    radii.fill(r)
                    val backdrop = runCatching { ScreenBackdropSource.getBackdrop(context) }.getOrNull()
                    renderer.draw(canvas, this, glass, radii, backdrop, withTouchGlow = false, lensScale = 1.2f)
                    if (dark) {
                        scrim.color = Color.HSVToColor(floatArrayOf(IosIconStyles.clearHue.toFloat(), IosIconStyles.clearSat / 100f * 0.7f, 0.10f))
                        scrim.alpha = 150
                        canvas.drawRoundRect(glass, r, r, scrim)
                    }
                    IosIconStyles.art(context, SAMPLE, SAMPLE.packageName, size, dark = false)?.let {
                        paint.colorFilter = IosIconStyles.clearFilter(dark)
                        canvas.drawBitmap(it, null, rect, paint)
                    }
                }
            }
        }
    }

    /** Small / large icons: a small filled square next to a large outlined one. */
    private inner class SizeToggle(context: Context) : View(context) {
        var large = false
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init { isClickable = true }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val small = RectF(cx - dp(11f), cy + dp(1f), cx - dp(3f), cy + dp(9f))
            val big = RectF(cx - dp(3f), cy - dp(11f), cx + dp(12f), cy + dp(4f))
            paint.setShadowLayer(dp(4f), 0f, dp(1f), Color.argb(50, 0, 0, 0))
            paint.color = Color.WHITE
            paint.style = if (large) Paint.Style.STROKE else Paint.Style.FILL
            paint.strokeWidth = dp(1.8f)
            canvas.drawRoundRect(small, dp(2.5f), dp(2.5f), paint)
            paint.style = if (large) Paint.Style.FILL else Paint.Style.STROKE
            canvas.drawRoundRect(big, dp(4.5f), dp(4.5f), paint)
        }
    }

    /** Capsule track with a ring thumb: the hue rainbow, or white → tint in steps. */
    private inner class GlassSlider(
        context: Context,
        private val hue: Boolean,
        private val onChange: (Float, Boolean) -> Unit
    ) : View(context) {
        private var value = 0f
        private val track = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val step = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val clip = Path()
        private var shaderWidth = -1

        fun setValue(v: Float) {
            if (abs(v - value) < 0.001f) { invalidate(); return }
            value = v.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val trackH = dp(26f)
            val top = (h - trackH) / 2f
            rect.set(0f, top, width.toFloat(), top + trackH)
            val r = trackH / 2f
            val dark = IosIconStyles.isDark(context)
            if (hue) {
                if (shaderWidth != width) {
                    val colors = IntArray(13) { Color.HSVToColor(floatArrayOf(it * 30f, 0.82f, 1f)) }
                    track.shader = LinearGradient(rect.left + r, 0f, rect.right - r, 0f, colors, null, Shader.TileMode.CLAMP)
                    shaderWidth = width
                }
                canvas.drawRoundRect(rect, r, r, track)
            } else {
                // Discrete steps from white to the full tint, like the iOS saturation strip.
                clip.rewind()
                clip.addRoundRect(rect, r, r, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clip)
                val steps = 16
                val stepW = rect.width() / steps
                for (i in 0 until steps) {
                    val s = i / (steps - 1f)
                    step.color = Color.HSVToColor(floatArrayOf(IosIconStyles.hue.toFloat(), s * 0.9f, 1f - s * 0.12f))
                    canvas.drawRect(rect.left + i * stepW, rect.top, rect.left + (i + 1) * stepW + 1f, rect.bottom, step)
                }
                canvas.restore()
            }
            val x = rect.left + r + (rect.width() - 2 * r) * value
            val thumbR = dp(13.5f)
            fill.color = if (hue) Color.HSVToColor(floatArrayOf(value * 360f, 0.82f, 1f))
            else Color.HSVToColor(floatArrayOf(IosIconStyles.hue.toFloat(), value * 0.9f, 1f - value * 0.12f))
            canvas.drawCircle(x, h / 2f, thumbR, fill)
            ring.color = Color.WHITE
            ring.strokeWidth = dp(3.5f)
            ring.setShadowLayer(dp(5f), 0f, dp(1f), Color.argb(70, 0, 0, 0))
            canvas.drawCircle(x, h / 2f, thumbR, ring)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val r = dp(13f)
            val v = ((event.x - r) / (width - 2 * r)).coerceIn(0f, 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    value = v; invalidate(); onChange(v, false)
                }
                MotionEvent.ACTION_MOVE -> { value = v; invalidate(); onChange(v, false) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { value = v; invalidate(); onChange(v, true) }
            }
            if (hue) satSlider.invalidate()
            return true
        }
    }

    /** Full-screen picker: a loupe follows the finger, lifting it takes the colour. */
    private inner class EyedropperView(context: Context, private val backdrop: Bitmap, private val done: (Int?) -> Unit) : View(context) {
        private var x = -1f
        private var y = -1f
        private var color = Color.WHITE
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = dp(16f)
            setShadowLayer(dp(6f), 0f, dp(1f), Color.argb(120, 0, 0, 0))
        }

        init { isClickable = true }

        override fun onDraw(canvas: Canvas) {
            if (x < 0f) {
                canvas.drawText(if (ru) "Коснитесь цвета на обоях" else "Touch a colour on the wallpaper", width / 2f, height * 0.42f, hint)
                return
            }
            val cy = y - dp(70f)
            fill.color = color
            canvas.drawCircle(x, cy, dp(34f), fill)
            ring.strokeWidth = dp(4f)
            ring.setShadowLayer(dp(8f), 0f, dp(2f), Color.argb(90, 0, 0, 0))
            canvas.drawCircle(x, cy, dp(34f), ring)
            ring.strokeWidth = dp(2f)
            canvas.drawCircle(x, y, dp(6f), ring)
        }

        private fun sample(px: Float, py: Float) {
            val dm = resources.displayMetrics
            val loc = IntArray(2).also { getLocationOnScreen(it) }
            val bx = ((px + loc[0]) / dm.widthPixels * backdrop.width).toInt().coerceIn(0, backdrop.width - 1)
            val by = ((py + loc[1]) / dm.heightPixels * backdrop.height).toInt().coerceIn(0, backdrop.height - 1)
            color = runCatching { backdrop.getPixel(bx, by) }.getOrDefault(color) or 0xFF000000.toInt()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            x = event.x
            y = event.y
            sample(x, y)
            invalidate()
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                (parent as? ViewGroup)?.removeView(this)
                done(if (event.actionMasked == MotionEvent.ACTION_UP) color else null)
            }
            return true
        }
    }

    companion object {
        private val SAMPLE = ComponentName("com.vivo.weather", "")
        var current: IosIconPanel? = null
            private set
        @Volatile private var module: Context? = null
        @Volatile private var font: Typeface? = null
        private var listening = false

        private fun symbolFont(context: Context): Typeface? = font ?: runCatching {
            Typeface.createFromAsset(moduleContext(context).assets, "MaterialSymbolsRounded.ttf")
        }.getOrNull()?.also { font = it }

        private fun moduleContext(context: Context): Context {
            if (context.packageName == BuildConfig.APPLICATION_ID) return context
            module?.let { return it }
            return context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).also { module = it }
        }

        fun show(activity: Activity) {
            if (current?.isAttachedToWindow == true) return
            val root = activity.window?.decorView as? ViewGroup ?: return
            KyantGlass.ensureConfig(activity)
            IosIconStyles.ensure(activity)
            if (!listening) {
                listening = true
                IosIconStyles.onChanged { current?.let { p -> p.post { if (p.isAttachedToWindow) p.refresh() } } }
            }
            val panel = IosIconPanel(activity)
            current = panel
            root.addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            panel.requestFocus()
            panel.post { panel.appear() }
        }
    }
}
