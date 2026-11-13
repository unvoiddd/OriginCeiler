package dev.unvoid.originceiler

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The controls gallery of the Freestyle editor (iOS 18 style): a sheet with a search field and
 * every control that can be put (back) into the control center, each drawn as the control itself
 * at its own size (tiles as round buttons, sliders as sliders, the player, the cards), packed in
 * the control center's own 4-column grid with its name under it. Sections: hidden widgets and
 * stock vivo tiles, then the Quick Settings tiles of installed apps.
 */
@SuppressLint("ViewConstructor")
internal class FreestyleGalleryView(
    context: Context,
    private val panel: ViewGroup,
    private val onPick: (String) -> Unit,
    private val onVivoEditor: () -> Unit,
    private val onGone: (FreestyleGalleryView) -> Unit
) : FrameLayout(context) {
    private val engine = FreestyleControlCenter
    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    private class Entry(val spec: String, val title: String, val glyph: String, val icon: Drawable?, val kind: FsKind, val size: FsSize)
    private class Section(val title: String?, val entries: List<Entry>)

    /** vivo's own "add tiles" catalogue: real icons and names, only the tiles vivo offers. */
    private val vivo = FreestyleVivoTiles(panel) { post { rebuild() } }
    private var all: List<Section> = buildSections()
    private var sections: List<Section> = all
    private var query = ""

    private var progress = 0f
    private var anim: ValueAnimator? = null
    private var dragOffset = 0f
    private var scrollY = 0f
    private var contentHeight = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null

    private val scrim = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 16f * sp
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 650, false) else Typeface.DEFAULT_BOLD
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 215; textSize = 11f * sp; textAlign = Paint.Align.CENTER }
    private val plateTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 650, false) else Typeface.DEFAULT_BOLD
    }
    private val plateSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 150 }
    private val buttonPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f * sp; textAlign = Paint.Align.CENTER }
    private val searchHint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 130; textSize = 16f * sp }
    private val whiteTint = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    private val sheet = RectF()
    private val r = RectF()
    private val path = Path()
    private val glass = KyantGlass.GlassDrawable(
        this, KyantGlass.KEY_TILES,
        backdrop = { v -> KyantGlassHook.ShadeBackdrop.get(v) },
        dim = true,
        surface = { 0x55141418 },
        radius = { _, _, _ -> 34f * d }
    )

    private val search = EditText(context).apply {
        background = null
        setTextColor(Color.WHITE)
        setHintTextColor(0x82FFFFFF.toInt())
        hint = FsText.search
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        gravity = Gravity.CENTER_VERTICAL
        setPadding((44 * d).toInt(), 0, (14 * d).toInt(), 0)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })
    }

    /** Hit boxes of the current frame. */
    private class Hit(val rect: RectF, val entry: Entry?)
    private val hits = ArrayList<Hit>()
    private var pressed: Hit? = null
    private var pressT = 0f

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, (44 * d).toInt()))
    }

    // ---------------------------------------------------------------- content

    private fun buildSections(): List<Section> {
        val out = ArrayList<Section>()
        val items = engine.items().associateBy { it.spec }
        val present = items.keys
        val controls = ArrayList<Entry>()
        for (spec in engine.hiddenSpecs()) {
            val item = items[spec] ?: continue
            val size = engine.layout?.sizes?.get(spec)?.takeIf { it in item.sizes } ?: item.native
            controls += Entry(spec, FsText.name(spec), FsText.glyph(spec), null, item.kind, size)
        }
        val current = engine.tileList().toSet()
        // Once vivo has answered, exactly the tiles its own menu offers, in its order.
        val stock = if (vivo.systemDone && vivo.available.isNotEmpty()) vivo.available.toList() else engine.stockList()
        for (spec in stock) {
            if (spec in current || spec in present || spec.startsWith("custom(")) continue
            controls += Entry(spec, vivo.labels[spec] ?: FsText.name(spec), FsText.glyph(spec), null, FsKind.TILE, FsSize.S1x1)
        }
        if (controls.isNotEmpty()) out += Section(null, controls)
        val apps = thirdPartyTiles(current + present)
        if (apps.isNotEmpty()) out += Section(FsText.sectionApps, apps)
        return out
    }

    /** Quick Settings tiles (TileService) of installed apps that are not in the control center. */
    private fun thirdPartyTiles(current: Set<String>): List<Entry> {
        val pm = context.packageManager
        val known = current.mapNotNull { spec ->
            if (!spec.startsWith("custom(")) null
            else ComponentName.unflattenFromString(spec.removePrefix("custom(").removeSuffix(")"))
        }.toSet()
        val services = runCatching {
            pm.queryIntentServices(Intent("android.service.quicksettings.action.QS_TILE"), PackageManager.GET_META_DATA)
        }.getOrNull().orEmpty()
        return services.mapNotNull { info ->
            val si = info.serviceInfo ?: return@mapNotNull null
            if (si.permission != "android.permission.BIND_QUICK_SETTINGS_TILE") return@mapNotNull null
            val cn = ComponentName(si.packageName, si.name)
            if (cn in known) return@mapNotNull null
            val label = runCatching { si.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: si.packageName
            val icon = runCatching { si.loadIcon(pm)?.mutate() }.getOrNull()?.apply {
                colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            }
            Entry("custom(" + cn.flattenToShortString() + ")", label, "apps", icon, FsKind.TILE, FsSize.S1x1)
        }.sortedBy { it.title.lowercase() }
    }

    private fun rebuild() {
        all = buildSections()
        val keep = scrollY
        filter(query)
        scrollY = keep.coerceIn(0f, maxScroll())
    }

    private fun filter(text: String) {
        query = text.trim()
        sections = if (query.isEmpty()) all else all.mapNotNull { s ->
            val hits = s.entries.filter { it.title.contains(query, ignoreCase = true) }
            if (hits.isEmpty()) null else Section(s.title, hits)
        }
        scrollY = 0f
        invalidate()
    }

    // ---------------------------------------------------------------- show / dismiss

    fun show() {
        vivo.start()
        animateTo(1f, 540L, PathInterpolator(0.2f, 1.15f, 0.32f, 1f), null)
    }

    fun dismiss(immediate: Boolean) {
        hideKeyboard()
        vivo.stop()
        if (immediate) {
            anim?.cancel()
            onGone(this)
            return
        }
        animateTo(0f, 280L, DecelerateInterpolator()) { onGone(this) }
    }

    private fun hideKeyboard() {
        runCatching {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(windowToken, 0)
        }
        search.clearFocus()
    }

    private fun animateTo(target: Float, duration: Long, interpolator: android.animation.TimeInterpolator, end: (() -> Unit)?) {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(progress, target)
        a.duration = duration
        a.interpolator = interpolator
        a.addUpdateListener { progress = it.animatedValue as Float; invalidate() }
        if (end != null) a.addListener(object : android.animation.AnimatorListenerAdapter() {
            var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) end() }
        })
        anim = a
        a.start()
    }

    // ---------------------------------------------------------------- drawing

    /** iOS: the sheet covers nearly the whole screen. */
    private val sheetHeight get() = height * 0.9f
    private val sheetTop get() = height - sheetHeight * progress + dragOffset
    private val pad get() = 22f * d

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val h = (44 * d).toInt()
        search.layout(pad.toInt(), 0, width - pad.toInt(), h)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val top = sheetTop
        // The search field rides with the sheet.
        search.translationY = top + 26f * d
        search.alpha = progress.coerceIn(0f, 1f)
        drawSheet(canvas, top)
        super.dispatchDraw(canvas)
    }

    private fun drawSheet(canvas: Canvas, top: Float) {
        val p = progress
        scrim.color = Color.argb((p.coerceIn(0f, 1f) * 120).toInt(), 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        sheet.set(0f, top, width.toFloat(), height + 40f * d)
        if (KyantGlass.config.on(KyantGlass.KEY_TILES)) {
            glass.setBounds(sheet.left.toInt(), sheet.top.toInt(), sheet.right.toInt(), sheet.bottom.toInt())
            glass.draw(canvas)
        } else {
            fill.color = 0xF21C1C1F.toInt()
            canvas.drawRoundRect(sheet, 34f * d, 34f * d, fill)
        }
        // Grabber.
        fill.color = 0x66FFFFFF
        r.set(width / 2f - 18f * d, top + 9f * d, width / 2f + 18f * d, top + 14f * d)
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
        // Search field plate (the EditText draws the text over it).
        r.set(pad, top + 26f * d, width - pad, top + 70f * d)
        fill.color = 0x26FFFFFF
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
        FsGlyphs.draw(canvas, context, "search", r.left + 24f * d, r.centerY(), 22f * d, 0x99FFFFFF.toInt())

        val listTop = top + 84f * d
        canvas.save()
        canvas.clipRect(0f, listTop, width.toFloat(), height.toFloat())
        var y = listTop + 10f * d - scrollY
        hits.clear()
        val gridW = width - 2 * pad
        val gap = gridW * 0.06f
        val cell = (gridW - 3 * gap) / 4f
        val labelH = 30f * d
        val stride = cell + labelH
        if (sections.isEmpty()) {
            canvas.drawText(if (query.isEmpty()) FsText.nothingToAdd else FsText.nothingFound, width / 2f, y + 30f * d, labelPaint)
            y += 60f * d
        }
        for (section in sections) {
            section.title?.let { t ->
                fill.color = 0x1FFFFFFF
                canvas.drawRect(pad, y, width - pad, y + 1f * d, fill)
                y += 18f * d
                canvas.drawText(t, pad, y + headerPaint.textSize, headerPaint)
                y += headerPaint.textSize + 16f * d
            }
            val (cells, rows) = FsGrid.pack(section.entries.map { it.spec to it.size })
            for (e in section.entries) {
                val c = cells[e.spec] ?: continue
                val left = pad + c.col * (cell + gap)
                val top2 = y + c.row * stride
                val w = c.size.w * cell + (c.size.w - 1) * gap
                val h = c.size.h * cell + (c.size.h - 1) * (stride - cell)
                val rect = RectF(left, top2, left + w, top2 + h)
                val hit = Hit(RectF(rect.left, rect.top, rect.right, rect.bottom + labelH), e)
                hits += hit
                val press = if (pressed === hit) pressT else 0f
                canvas.save()
                val s = 1f - 0.06f * press
                canvas.scale(s, s, rect.centerX(), rect.centerY())
                drawControl(canvas, e, rect, cell, press)
                canvas.restore()
                drawLabel(canvas, e.title, rect.centerX(), rect.bottom + 6f * d, w + gap * 0.8f)
            }
            y += rows * stride + 14f * d
        }
        // vivo's own editor, for whoever wants it back.
        val bw = buttonPaint.measureText(FsText.vivoEditor) + 40f * d
        r.set(width / 2f - bw / 2f, y + 10f * d, width / 2f + bw / 2f, y + 54f * d)
        val vivoHit = Hit(RectF(r), null)
        hits += vivoHit
        fill.color = Color.argb(if (pressed === vivoHit) (36 + 40 * pressT).toInt() else 36, 255, 255, 255)
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
        canvas.drawText(FsText.vivoEditor, r.centerX(), r.centerY() - (buttonPaint.descent() + buttonPaint.ascent()) / 2f, buttonPaint)
        y += 100f * d
        contentHeight = y + scrollY - (listTop + 10f * d)
        canvas.restore()

        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat()
            postInvalidateOnAnimation()
        }
        val target = if (pressed != null) 1f else 0f
        if (abs(pressT - target) > 0.01f) { pressT += (target - pressT) * 0.35f; postInvalidateOnAnimation() } else pressT = target
    }

    /** The control as it looks in the control center, at its own size. */
    private fun drawControl(canvas: Canvas, e: Entry, rect: RectF, cell: Float, press: Float) {
        val radius = min(min(rect.width(), rect.height()) / 2f, cell / 2f)
        fill.color = Color.argb((46 + 34 * press).toInt(), 255, 255, 255)
        canvas.drawRoundRect(rect, radius, radius, fill)
        val white = Color.WHITE
        when (e.kind) {
            FsKind.SLIDER -> {
                // The level: the lower part of the slider filled white, the icon on it.
                canvas.save()
                path.rewind()
                path.addRoundRect(rect, radius, radius, Path.Direction.CW)
                canvas.clipPath(path)
                fill.color = 0xF2FFFFFF.toInt()
                canvas.drawRect(rect.left, rect.top + rect.height() * 0.52f, rect.right, rect.bottom, fill)
                canvas.restore()
                FsGlyphs.draw(canvas, context, e.glyph, rect.centerX(), rect.bottom - cell * 0.42f, cell * 0.34f, 0xFF3A3A3C.toInt())
            }
            FsKind.MEDIA -> {
                val a = cell * 0.62f
                r.set(rect.left + cell * 0.2f, rect.top + cell * 0.2f, rect.left + cell * 0.2f + a, rect.top + cell * 0.2f + a)
                fill.color = 0x33FFFFFF
                canvas.drawRoundRect(r, a * 0.2f, a * 0.2f, fill)
                FsGlyphs.draw(canvas, context, "music_note", r.centerX(), r.centerY(), a * 0.5f, 0xCCFFFFFF.toInt())
                plateTitle.textSize = cell * 0.15f
                canvas.drawText(TextUtils.ellipsize(FsText.notPlaying, plateTitle, rect.width() - cell * 0.4f, TextUtils.TruncateAt.END).toString(),
                    rect.left + cell * 0.2f, rect.bottom - cell * 0.62f, plateTitle)
                val cy = rect.bottom - cell * 0.3f
                FsGlyphs.draw(canvas, context, "skip_previous", rect.centerX() - cell * 0.55f, cy, cell * 0.26f, 0x99FFFFFF.toInt())
                FsGlyphs.draw(canvas, context, "play_arrow", rect.centerX(), cy, cell * 0.34f, white)
                FsGlyphs.draw(canvas, context, "skip_next", rect.centerX() + cell * 0.55f, cy, cell * 0.26f, 0x99FFFFFF.toInt())
            }
            FsKind.CONNECT -> {
                // Four round buttons in a 2x2 block.
                val names = listOf("flight", "bluetooth", "wifi", "swap_vert")
                val br = cell * 0.36f
                names.forEachIndexed { i, g ->
                    val cx = rect.left + rect.width() * (if (i % 2 == 0) 0.29f else 0.71f)
                    val cy = rect.top + rect.height() * (if (i < 2) 0.29f else 0.71f)
                    fill.color = if (g == "wifi") 0xFF0A84FF.toInt() else 0x33FFFFFF
                    canvas.drawCircle(cx, cy, br, fill)
                    FsGlyphs.draw(canvas, context, g, cx, cy, br * 0.95f, white)
                }
            }
            FsKind.RINGER -> {
                val br = rect.height() * 0.36f
                for ((i, g) in listOf("vibration", "notifications_off").withIndex()) {
                    val cx = rect.left + rect.width() * (if (i == 0) 0.27f else 0.73f)
                    fill.color = 0x33FFFFFF
                    canvas.drawCircle(cx, rect.centerY(), br, fill)
                    FsGlyphs.draw(canvas, context, g, cx, rect.centerY(), br * 0.95f, white)
                }
            }
            else -> {
                val icon = e.icon ?: vivo.icons[e.spec]?.also { it.colorFilter = whiteTint }
                val wide = rect.width() > rect.height() * 1.4f
                val cx = if (wide) rect.left + rect.height() / 2f else rect.centerX()
                val cy = if (rect.height() > cell * 1.4f) rect.top + cell / 2f else rect.centerY()
                val s = cell * 0.4f
                if (icon != null) {
                    val half = (s / 2f).toInt()
                    icon.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
                    icon.draw(canvas)
                } else {
                    FsGlyphs.draw(canvas, context, e.glyph, cx, cy, s, white)
                }
                if (wide) {
                    plateTitle.textSize = cell * 0.17f
                    plateSub.textSize = cell * 0.145f
                    val left = rect.left + rect.height() * 0.92f
                    val avail = rect.right - left - cell * 0.15f
                    canvas.drawText(TextUtils.ellipsize(e.title, plateTitle, avail, TextUtils.TruncateAt.END).toString(), left, rect.centerY() - cell * 0.02f, plateTitle)
                    canvas.drawText(FsText.stateText(1), left, rect.centerY() + plateSub.textSize, plateSub)
                }
            }
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, cx: Float, top: Float, width: Float) {
        val first = TextUtils.ellipsize(text, labelPaint, width, TextUtils.TruncateAt.END).toString()
        if (first == text || !text.contains(' ')) {
            canvas.drawText(first, cx, top + labelPaint.textSize, labelPaint)
            return
        }
        var split = text.length
        while (split > 0 && labelPaint.measureText(text, 0, split) > width) split = text.lastIndexOf(' ', split - 1).takeIf { it > 0 } ?: 0
        if (split <= 0) {
            canvas.drawText(first, cx, top + labelPaint.textSize, labelPaint)
            return
        }
        canvas.drawText(text.substring(0, split), cx, top + labelPaint.textSize, labelPaint)
        val rest = TextUtils.ellipsize(text.substring(split).trim(), labelPaint, width, TextUtils.TruncateAt.END).toString()
        canvas.drawText(rest, cx, top + labelPaint.textSize * 2.15f, labelPaint)
    }

    // ---------------------------------------------------------------- touch

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var moved = false
    private var draggingSheet = false
    private var inSearch = false

    private fun maxScroll() = max(0f, contentHeight - (sheetHeight - 100f * d))

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // The search field takes taps on itself; everything else (scroll, drag) is the sheet's.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val top = sheetTop
            inSearch = event.y in (top + 26f * d)..(top + 70f * d) && event.x in pad..(width - pad)
        }
        if (inSearch) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                search.requestFocus()
                runCatching {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            val copy = MotionEvent.obtain(event)
            copy.offsetLocation(-search.left.toFloat(), -(search.top + search.translationY))
            search.dispatchTouchEvent(copy)
            copy.recycle()
            return true
        }
        return onTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val top = sheetTop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = event.x; downY = event.y; lastY = event.y
                moved = false
                draggingSheet = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                pressed = if (event.y >= top + 84f * d) hits.firstOrNull { it.rect.contains(event.x, event.y) } else null
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (!moved && hypot(event.x - downX, event.y - downY) > slop) {
                    moved = true
                    pressed = null
                    // Pulling down at the top of the list (or on the grabber) drags the sheet itself.
                    draggingSheet = downY >= top && (scrollY <= 0f && event.y > downY || downY < top + 24f * d)
                    if (draggingSheet) hideKeyboard()
                }
                if (moved) {
                    val dy = event.y - lastY
                    if (draggingSheet) dragOffset = max(0f, dragOffset + dy)
                    else scrollY = (scrollY - dy).coerceIn(0f, maxScroll())
                    invalidate()
                }
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000)
                val vy = velocity?.yVelocity ?: 0f
                if (!moved) {
                    val hit = pressed
                    pressed = null
                    when {
                        event.y < top -> dismiss(immediate = false)
                        hit?.entry != null -> pick(hit.entry)
                        hit != null -> onVivoEditor()
                        else -> hideKeyboard()
                    }
                } else if (draggingSheet) {
                    if (dragOffset > sheetHeight * 0.2f || vy > 1800f) {
                        dismiss(immediate = false)
                    } else {
                        ValueAnimator.ofFloat(dragOffset, 0f).apply {
                            duration = 260
                            interpolator = DecelerateInterpolator()
                            addUpdateListener { dragOffset = it.animatedValue as Float; invalidate() }
                            start()
                        }
                    }
                } else {
                    scroller.fling(0, scrollY.toInt(), 0, (-vy).toInt(), 0, 0, 0, maxScroll().toInt())
                    postInvalidateOnAnimation()
                }
                velocity?.recycle(); velocity = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                velocity?.recycle(); velocity = null
                invalidate()
            }
        }
        return true
    }

    private fun pick(entry: Entry) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onPick(entry.spec)
        dismiss(immediate = false)
    }
}
