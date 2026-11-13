package dev.unvoid.originceiler

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Freestyle control center (iOS 18 style). The pencil of the control center opens our own editor
 * instead of vivo's: every element can be moved, resized and removed, and controls are added from a
 * gallery. vivo's own views stay in place (so Liquid Glass, the active highlight, presses and long
 * presses keep working): both of vivo's grids (fixed cards and custom tiles) are laid over each
 * other and every element is measured and placed in one shared 4-column grid. Plain tiles (stock
 * and third-party) get a 2x1 and 2x2 template with a title and state, the player a 4x1 / 4x2 /
 * 4x4 template, the sliders taller variants.
 */
object FreestyleControlCenter {
    const val SETTING_ENABLED = "originceiler_freestyle_cc"
    const val SETTING_LAYOUT = "originceiler_freestyle_layout"
    const val SETTING_IOS = "originceiler_ios26_cc"
    internal const val TILES_SETTING = "SUPER_QS_CENTER_DEFAULT_TILES"
    internal const val STOCK_SETTING = "SUPER_QS_CENTER_STOCK_TILES"

    private const val PKG = "com.vivo.systemuiplugin.systemui.vivoqscenter."
    private const val TILE_LAYOUT = PKG + "VivoSuperQSTileLayout"
    private const val FIX_LAYOUT = PKG + "VivoSuperQSFixTileLayout"
    private const val CUSTOM_LAYOUT = PKG + "VivoSuperQSCustomTileLayout"
    private const val SCROLL_CONTAINER = PKG + "VivoSuperQSPanelScrollContainer"
    private const val PANEL = PKG + "VivoSuperQSPanel"
    private const val TILE_VIEW = "com.vivo.systemuiplugin.systemui.qs.VivoQSTileView"

    private const val ANIM_MS = 560L

    /** iOS 26: connectivity and player on top, ring + focus left of brightness and volume. */
    private val IOS_ORDER = listOf("connect_group", "music", "volume_group", "light_seek_bar", "volume_seek_bar", "focus_group")

    private val installed = AtomicBoolean(false)
    private val hookedClasses = Collections.synchronizedSet(HashSet<String>())
    internal val main by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }

    // ---------------------------------------------------------------- state

    /** Our grid drives the control center: Freestyle or the iOS 26 style (or both). */
    @Volatile private var enabled = false
    /** The pencil opens our editor. */
    @Volatile private var freestyle = false
    /** iOS 26 control center geometry: bigger cells, tighter gaps. */
    @Volatile internal var ios = false
        private set
    private var observing = false
    private var appContext: Context? = null
    internal var layout: FsLayout? = null
        private set
    private var lastWritten: String? = null

    private var blockedLogged = false
    private var fixedRef = WeakReference<ViewGroup>(null)
    private var customRef = WeakReference<ViewGroup>(null)
    private var panelRef = WeakReference<ViewGroup>(null)
    private var scrollContainerClass: Class<*>? = null

    /** One control-center element. */
    internal class Item(
        val spec: String,
        val view: View,
        val kind: FsKind,
        /** Native size in px (vivo's own params). */
        val nativeW: Int,
        val nativeH: Int,
        val native: FsSize,
        val inFixed: Boolean
    ) {
        val sizes: List<FsSize> get() = kind.sizes(native)
    }

    internal class Metrics(val cell: Int, val cellH: Int, val gapH: Int, val gapV: Int, val padLeft: Int, val padTop: Int) {
        val gridW get() = cell * FsGrid.COLUMNS + gapH * (FsGrid.COLUMNS - 1)
        fun w(cells: Int) = cells * cell + (cells - 1) * gapH
        fun h(cells: Int) = cells * cellH + (cells - 1) * gapV
        fun x(col: Int) = col * (cell + gapH)
        fun y(row: Int) = row * (cellH + gapV)
    }

    internal var metrics: Metrics? = null
        private set

    /** Target rect of every visible spec, in grid coordinates (both grids share them). */
    private val targets = LinkedHashMap<String, Rect>()
    private val targetSizes = HashMap<String, FsSize>()
    private var targetRows = 0
    private var targetSignature = ""
    private val from = HashMap<String, RectF>()
    private val shown = HashMap<String, RectF>()
    private var fraction = 1f
    private var animator: ValueAnimator? = null

    /** Rect of the element held by the editor's finger (grid coordinates), or null. */
    private var dragSpec: String? = null
    private val dragRect = RectF()

    internal var editor: FreestyleEditorView? = null
        private set
    /** Set while we call vivo's own editor ourselves. */
    private var passThroughEdit = false

    // ---------------------------------------------------------------- install

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        listOf(TILE_LAYOUT, FIX_LAYOUT, CUSTOM_LAYOUT, SCROLL_CONTAINER, PANEL, TILE_VIEW).forEach { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()?.let(::hookClass)
        }
        // The control center lives in the SystemUI plugin's own class loader.
        XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val cls = param.result as? Class<*> ?: return
                if (cls.name.startsWith("com.vivo.systemuiplugin.systemui.")) hookClass(cls)
            }
        })
        hookFrameworkSide()
        log("installed")
    }

    private fun hookClass(cls: Class<*>) {
        val name = cls.name
        if (name !in setOf(TILE_LAYOUT, FIX_LAYOUT, CUSTOM_LAYOUT, SCROLL_CONTAINER, PANEL, TILE_VIEW)) return
        if (!hookedClasses.add(name)) return
        runCatching {
            when (name) {
                TILE_LAYOUT -> XposedBridge.hookAllMethods(cls, "onLayout", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? ViewGroup ?: return
                        runCatching { afterGridLayout(v) }.onFailure { log("layout: $it") }
                    }
                })
                FIX_LAYOUT, CUSTOM_LAYOUT -> {
                    XposedBridge.hookAllMethods(cls, "onMeasure", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val v = param.thisObject as? ViewGroup ?: return
                            remember(v, name == FIX_LAYOUT)
                            runCatching { afterGridMeasure(v) }.onFailure { log("measure: $it") }
                        }
                    })
                }
                SCROLL_CONTAINER -> scrollContainerClass = cls
                PANEL -> XposedBridge.hookAllMethods(cls, "showEdit", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val panel = param.thisObject as? ViewGroup ?: return
                        panelRef = WeakReference(panel)
                        ensureObserver(panel.context)
                        if (!freestyle || passThroughEdit) return
                        if (runCatching { openEditor(panel) }.onFailure { log("editor: $it") }.getOrDefault(false)) {
                            param.result = null
                        }
                    }
                })
                TILE_VIEW -> XposedBridge.hookAllMethods(cls, "handleStateChanged", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        tileDecors[v]?.label?.invalidateSelf()
                    }
                })
            }
            log("hooked ${cls.simpleName}")
        }.onFailure { log("hook $name: $it") }
    }

    /** The scroll container is a plain FrameLayout subclass, and touch routing lives in ViewGroup. */
    private fun hookFrameworkSide() {
        // vivo drags / collapses the shade from an input monitor of its own, not from the view
        // tree: while editing, every input channel of this process but the windows' own is
        // answered "handled" and dropped, so no swipe can move or close the control center.
        runCatching {
            val receiver = Class.forName("android.view.InputEventReceiver")
            val finish = receiver.getDeclaredMethod("finishInputEvent", android.view.InputEvent::class.java, Boolean::class.javaPrimitiveType)
            XposedBridge.hookAllMethods(receiver, "dispatchInputEvent", object : XC_MethodHook(10000) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (editor == null) return
                    val event = param.args.getOrNull(1) as? MotionEvent ?: return
                    val name = param.thisObject.javaClass.name
                    if (name.contains("ViewRootImpl")) return
                    if (!blockedLogged) { blockedLogged = true; log("editing: dropped input of $name") }
                    runCatching { finish.invoke(param.thisObject, event, true) }
                    param.result = null
                }
            })
        }.onFailure { log("input block: $it") }
        // While editing, every touch of the shade window goes straight to the editor: vivo's shade
        // gestures (swipe down / sideways to close or page) and the glass stretch never see it.
        XposedBridge.hookAllMethods(View::class.java, "dispatchPointerEvent", object : XC_MethodHook(10000) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val ed = editor ?: return
                val root = param.thisObject as? View ?: return
                if (!ed.isAttachedToWindow || ed.rootView !== root) return
                val event = param.args.firstOrNull() as? MotionEvent ?: return
                forwardToEditor(ed, event)
                param.result = true
            }
        })
        XposedBridge.hookAllMethods(FrameLayout::class.java, "onMeasure", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val v = param.thisObject as? FrameLayout ?: return
                if (v.javaClass !== scrollContainerClass) return
                runCatching { afterScrollMeasure(v) }.onFailure { log("scroll measure: $it") }
            }
        })
        XposedBridge.hookAllMethods(FrameLayout::class.java, "onLayout", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val v = param.thisObject as? FrameLayout ?: return
                if (v.javaClass !== scrollContainerClass) return
                runCatching { afterScrollLayout(v) }.onFailure { log("scroll layout: $it") }
            }
        })
        XposedBridge.hookAllMethods(ViewGroup::class.java, "dispatchTouchEvent", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val custom = customRef.get() ?: return
                if (param.thisObject !== custom) return
                val event = param.args.firstOrNull() as? MotionEvent ?: return
                if (event.actionMasked != MotionEvent.ACTION_DOWN || !isActive()) return
                // Both grids lie over each other: the tiles grid is on top and would eat every touch
                // meant for a card of the fixed grid. Hand those to the fixed grid below it.
                if (routeToFixed(custom, event.x, event.y)) param.result = false
            }
        })
    }

    private fun remember(v: ViewGroup, fixed: Boolean) {
        if (fixed) { if (fixedRef.get() !== v) fixedRef = WeakReference(v) } else if (customRef.get() !== v) customRef = WeakReference(v)
        if (appContext == null) ensureObserver(v.context)
        if (panelRef.get() == null) {
            runCatching { findField(v.javaClass, "mVivoSuperQSPanel")?.get(v) as? ViewGroup }.getOrNull()?.let { panelRef = WeakReference(it) }
        }
    }

    // ---------------------------------------------------------------- settings

    private fun ensureObserver(context: Context) {
        if (observing) return
        observing = true
        val ctx = context.applicationContext ?: context
        appContext = ctx
        runCatching { KyantGlass.ensureConfig(ctx) }
        val cr = ctx.contentResolver
        fun load() {
            freestyle = Settings.Global.getInt(cr, SETTING_ENABLED, 0) == 1
            val wasIos = ios
            ios = Settings.Global.getInt(cr, SETTING_IOS, 0) == 1
            enabled = freestyle || ios
            if (wasIos != ios) {
                metrics = null
                if (!freestyle) layout = null
            }
            val text = if (freestyle) Settings.Global.getString(cr, SETTING_LAYOUT) else null
            if (!freestyle) {
                // iOS style alone: its own default arrangement, never the saved Freestyle one.
                if (lastWritten != null) { lastWritten = null; layout = null }
                return
            }
            if (text != lastWritten) {
                layout = FsLayout.parse(text)
                lastWritten = text
            }
        }
        runCatching { load() }
        runCatching {
            cr.registerContentObserver(Settings.Global.CONTENT_URI, true, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    val key = uri?.lastPathSegment ?: return
                    if (key != SETTING_ENABLED && key != SETTING_LAYOUT && key != SETTING_IOS) return
                    val was = enabled
                    runCatching { load() }
                    if (was && !enabled) {
                        editor?.close(save = false)
                        restoreAll()
                    }
                    targetSignature = ""
                    requestLayoutAll()
                    syncIosHeader()
                    syncLabelLayer()
                }
            })
        }
    }

    private fun persist() {
        val l = layout ?: return
        val text = l.serialize()
        lastWritten = text
        val cr = appContext?.contentResolver ?: return
        Thread { runCatching { Settings.Global.putString(cr, SETTING_LAYOUT, text) }.onFailure { log("save: $it") } }.start()
    }

    // ---------------------------------------------------------------- items

    private class RecordFields(val tile: Field?, val view: Field?)
    private val recordFields = ConcurrentHashMap<Class<*>, RecordFields>()

    private fun records(grid: ViewGroup, fixed: Boolean, out: MutableList<Item>, seen: MutableSet<String>) {
        val list = runCatching { grid.javaClass.getMethod("getTileRecords").invoke(grid) as? List<*> }.getOrNull() ?: return
        for (record in list) {
            record ?: continue
            val fields = recordFields.getOrPut(record.javaClass) {
                RecordFields(findField(record.javaClass, "tile"), findField(record.javaClass, "tileView"))
            }
            val view = runCatching { fields.view?.get(record) as? View }.getOrNull() ?: continue
            if (view.parent !== grid) continue
            val tile = runCatching { fields.tile?.get(record) }.getOrNull()
            var spec = tile?.let { t -> runCatching { t.javaClass.getMethod("getTileSpec").invoke(t) as? String }.getOrNull() }
                ?: "view:" + view.javaClass.simpleName
            if (spec.isEmpty() || spec.contains(';') || spec.contains('=')) spec = "view:" + view.javaClass.simpleName
            var unique = spec
            var n = 2
            while (!seen.add(unique)) unique = spec + "#" + n++
            out += makeItem(unique, view, fixed)
        }
    }

    private val nativeSizes = WeakHashMap<View, IntArray>()

    private fun makeItem(spec: String, view: View, fixed: Boolean): Item {
        val m = vivoMetrics
        val px = nativeSizes.getOrPut(view) {
            val w = callInt(view, "getViewWidth").takeIf { it > 0 } ?: view.layoutParams?.width?.takeIf { it > 0 } ?: view.measuredWidth
            val h = callInt(view, "getViewHeight").takeIf { it > 0 } ?: view.layoutParams?.height?.takeIf { it > 0 } ?: view.measuredHeight
            intArrayOf(w, h)
        }
        val native = if (m == null) FsSize.S1x1 else FsSize(
            ((px[0] + m.gapH).toFloat() / (m.cell + m.gapH)).roundToInt().coerceIn(1, FsGrid.COLUMNS),
            ((px[1] + m.gapV).toFloat() / (m.cellH + m.gapV)).roundToInt().coerceIn(1, 6)
        )
        val kind = kinds.getOrPut(view) { kindOf(spec, view) }
        return Item(spec, view, kind, px[0], px[1], native, fixed)
    }

    private val kinds = WeakHashMap<View, FsKind>()

    private fun kindOf(spec: String, view: View): FsKind {
        val name = view.javaClass.simpleName
        return when {
            name == "VivoSuperQSConnectGroupViewImpl" -> FsKind.CONNECT
            name == "VivoSuperQSFocusViewImpl" -> FsKind.FOCUS
            name == "VivoSuperQSRingVibrateViewImpl" -> FsKind.RINGER
            name == "VivoSuperQSTileCustomViewImpl" -> when {
                spec == "music" || findByIdName(view, "mix_music_container") != null -> FsKind.MEDIA
                spec.contains("seek_bar") || hasDescendant(view) { it.javaClass.simpleName.contains("Slider") } -> FsKind.SLIDER
                else -> FsKind.OTHER
            }
            name == "VivoSuperQSTileViewImpl" && tileDecor(view) != null -> FsKind.TILE
            else -> FsKind.OTHER
        }
    }

    internal fun items(): List<Item> {
        val fixed = fixedRef.get() ?: return emptyList()
        val custom = customRef.get() ?: return emptyList()
        readMetrics(fixed, custom)
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        records(fixed, true, out, seen)
        records(custom, false, out, seen)
        return out
    }

    private fun readMetrics(fixed: ViewGroup, custom: ViewGroup) {
        val cell = intField(custom, "mCellWidth").takeIf { it > 0 } ?: return
        val gapH = intField(custom, "mCellMarginHorizontal").takeIf { it >= 0 } ?: (cell * 0.357f).toInt()
        val cellH = callInt(custom, "getCellHeight").takeIf { it > 0 } ?: cell
        val gapV = callInt(custom, "getCellMarginVertical").takeIf { it > 0 } ?: gapH
        val native = Metrics(cell, cellH, gapH, gapV, fixed.paddingLeft, 0)
        val wanted = if (ios) {
            // iOS 26: the same grid width, square cells 22.6% of it, gaps of 3.3%.
            val width = native.gridW
            val gap = (width * 0.033f).roundToInt()
            val side = (width - gap * (FsGrid.COLUMNS - 1)) / FsGrid.COLUMNS
            Metrics(side, side, gap, gap, fixed.paddingLeft, 0)
        } else native
        vivoMetrics = native
        val old = metrics
        if (old == null || old.cell != wanted.cell || old.cellH != wanted.cellH || old.gapH != wanted.gapH || old.gapV != wanted.gapV || old.padLeft != wanted.padLeft) {
            metrics = wanted
            targetSignature = ""
        }
    }

    /** vivo's own grid: what native sizes are measured in. */
    private var vivoMetrics: Metrics? = null

    // ---------------------------------------------------------------- plan

    internal fun isActive(): Boolean {
        if (!enabled || layout == null) return false
        val fixed = fixedRef.get() ?: return false
        val custom = customRef.get() ?: return false
        // Landscape / foldable layouts put the grids in different parents: leave those to vivo.
        if (fixed.parent == null || fixed.parent !== custom.parent) return false
        return fixed.parent?.javaClass === scrollContainerClass
    }

    private fun sizeOf(item: Item, l: FsLayout): FsSize {
        val saved = l.sizes[item.spec]
        val allowed = item.sizes
        return if (saved != null && saved in allowed) saved else if (item.native in allowed) item.native else allowed.first()
    }

    /** Recomputes the targets; starts the morph when they moved. */
    private fun plan(items: List<Item>) {
        val l = layout ?: return
        val m = metrics ?: return
        // Specs vivo added since (a tile added in vivo's editor, a widget turned on): at the end.
        for (item in items) if (item.spec !in l.sizes) {
            l.order += item.spec
            l.sizes[item.spec] = sizeOf(item, l)
        }
        val bySpec = items.associateBy { it.spec }
        val entries = l.order.mapNotNull { spec -> bySpec[spec]?.takeIf { spec !in l.hidden }?.let { spec to sizeOf(it, l) } }
        val (cells, rows) = FsGrid.pack(entries)
        val signature = buildString {
            append(m.cell).append('/').append(m.cellH)
            for ((spec, c) in cells) append(';').append(spec).append(c.col).append(',').append(c.row).append(c.size)
            for (item in items) if (item.spec !in cells) append(";-").append(item.spec)
        }
        if (signature == targetSignature) return
        val first = targetSignature.isEmpty() || shown.isEmpty()
        // Where every element is right now: the start of the morph.
        val snapshot = HashMap<String, RectF>()
        for (item in items) currentRect(item.spec)?.let { snapshot[item.spec] = RectF(it) }
        targets.clear()
        targetSizes.clear()
        for ((spec, c) in cells) {
            targets[spec] = Rect(m.x(c.col), m.y(c.row), m.x(c.col) + m.w(c.size.w), m.y(c.row) + m.h(c.size.h))
            targetSizes[spec] = c.size
        }
        // Hidden or new-but-hidden elements fold into their own centre.
        for (item in items) if (item.spec !in targets) {
            val last = snapshot[item.spec]
            targets[item.spec] = if (last != null) Rect(last.centerX().toInt(), last.centerY().toInt(), last.centerX().toInt(), last.centerY().toInt()) else Rect()
        }
        targetRows = rows
        targetSignature = signature
        if (first) {
            shown.clear()
            for ((spec, r) in targets) shown[spec] = RectF(r)
            fraction = 1f
            return
        }
        from.clear()
        for ((spec, r) in targets) {
            // Newcomers grow out of their own centre.
            from[spec] = snapshot[spec] ?: RectF(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY())
        }
        startMorph()
    }

    private fun startMorph() {
        animator?.cancel()
        fraction = 0f
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = ANIM_MS
        anim.interpolator = Spring
        anim.addUpdateListener {
            fraction = it.animatedValue as Float
            requestLayoutAll()
            editor?.invalidate()
        }
        animator = anim
        anim.start()
    }

    /** Critically-damped-ish spring: a soft overshoot, settled at the end. */
    private object Spring : TimeInterpolator {
        private const val ZETA = 0.74f
        private const val OMEGA = 12.5f
        override fun getInterpolation(input: Float): Float {
            if (input >= 1f) return 1f
            val t = input * (ANIM_MS / 1000f) * 1.9f
            val wd = OMEGA * sqrt(1f - ZETA * ZETA)
            val e = exp(-ZETA * OMEGA * t)
            return 1f - e * (cos(wd * t) + (ZETA * OMEGA / wd) * sin(wd * t))
        }
    }

    /** Where [spec] is drawn now, in grid coordinates. */
    private fun currentRect(spec: String): RectF? {
        if (spec == dragSpec) return dragRect
        val to = targets[spec] ?: return null
        if (fraction >= 1f) return RectF(to)
        val f = from[spec] ?: return RectF(to)
        val p = fraction
        return RectF(lerp(f.left, to.left.toFloat(), p), lerp(f.top, to.top.toFloat(), p), lerp(f.right, to.right.toFloat(), p), lerp(f.bottom, to.bottom.toFloat(), p))
    }

    private fun gridHeight(m: Metrics): Int {
        var bottom = if (targetRows > 0) m.h(targetRows) else 0
        if (fraction < 1f || dragSpec != null) for (spec in targets.keys) currentRect(spec)?.let { bottom = max(bottom, it.bottom.toInt()) }
        return bottom
    }

    // ---------------------------------------------------------------- measure & layout

    private fun afterGridMeasure(grid: ViewGroup) {
        if (!isActive()) return
        val items = items()
        if (items.isEmpty()) return
        val m = metrics ?: return
        plan(items)
        val fixed = grid === fixedRef.get()
        for (item in items) {
            if (item.inFixed != fixed) continue
            val r = currentRect(item.spec) ?: continue
            val w = max(0, r.width().roundToInt())
            val h = max(0, r.height().roundToInt())
            applySize(item, w, h, targetSizes[item.spec])
            item.view.measure(exactly(w), exactly(h))
        }
        val height = grid.paddingTop + gridHeight(m) + grid.paddingBottom + m.gapV
        setMeasured(grid, grid.paddingLeft + m.gridW + grid.paddingRight, height)
    }

    private fun afterGridLayout(grid: ViewGroup) {
        val fixed = grid === fixedRef.get()
        if (!fixed && grid !== customRef.get()) return
        if (!enabled) return
        if (layout == null) {
            // First pass with Freestyle on and nothing saved: vivo's own arrangement is the default.
            if (fixed) main.post { captureDefault() }
            return
        }
        if (!isActive()) return
        val m = metrics ?: return
        for (item in items()) {
            if (item.inFixed != fixed) continue
            val r = currentRect(item.spec) ?: continue
            val w = max(0, r.width().roundToInt())
            val h = max(0, r.height().roundToInt())
            if (item.view.measuredWidth != w || item.view.measuredHeight != h) {
                applySize(item, w, h, targetSizes[item.spec])
                item.view.measure(exactly(w), exactly(h))
            }
            val left = grid.paddingLeft + r.left.roundToInt()
            val top = grid.paddingTop + r.top.roundToInt()
            item.view.layout(left, top, left + w, top + h)
            afterItemLayout(item, w, h)
            trackRow(item, m)
        }
        editor?.invalidate()
        if (fixed) main.post { syncIosHeader(); syncLabelLayer() }
    }

    private fun afterScrollMeasure(container: FrameLayout) {
        if (!isActive()) return
        val fixed = fixedRef.get() ?: return
        if (fixed.parent !== container) return
        val top = (fixed.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        val height = container.paddingTop + top + fixed.measuredHeight + container.paddingBottom
        setMeasured(container, container.measuredWidth, height)
    }

    private fun afterScrollLayout(container: FrameLayout) {
        if (!isActive()) return
        val fixed = fixedRef.get() ?: return
        val custom = customRef.get() ?: return
        if (fixed.parent !== container || custom.parent !== container) return
        custom.layout(fixed.left, fixed.top, fixed.left + custom.measuredWidth, fixed.top + custom.measuredHeight)
    }

    private fun routeToFixed(custom: ViewGroup, x: Float, y: Float): Boolean {
        val fixed = fixedRef.get() ?: return false
        for (i in 0 until custom.childCount) {
            val c = custom.getChildAt(i)
            if (c.visibility == View.VISIBLE && c.width > 0 && hit(c, x, y)) return false
        }
        val fx = x + custom.left - fixed.left
        val fy = y + custom.top - fixed.top
        for (i in 0 until fixed.childCount) {
            val c = fixed.getChildAt(i)
            if (c.visibility == View.VISIBLE && c.width > 0 && hit(c, fx, fy)) return true
        }
        return false
    }

    private fun hit(v: View, x: Float, y: Float): Boolean {
        val l = v.left + v.translationX
        val t = v.top + v.translationY
        return x >= l && x < l + v.width && y >= t && y < t + v.height
    }

    private fun captureDefault() {
        if (!enabled || layout != null) return
        val fixed = fixedRef.get() ?: return
        val custom = customRef.get() ?: return
        if (fixed.parent !== custom.parent) return
        val items = items()
        if (items.isEmpty() || metrics == null) return
        // vivo's on-screen order (the fixed cards row by row, then the tiles), or iOS 26's.
        val cards = items.filter { it.inFixed }
        val ordered = (if (ios) cards.sortedBy { IOS_ORDER.indexOf(it.spec).let { i -> if (i < 0) 99 else i } }
            else cards.sortedWith(compareBy({ it.view.top / max(1, metrics!!.cellH / 2) }, { it.view.left }))) +
            items.filter { !it.inFixed }
        val l = FsLayout()
        for (item in ordered) {
            l.order += item.spec
            l.sizes[item.spec] = if (item.native in item.sizes) item.native else item.sizes.first()
        }
        layout = l
        targetSignature = ""
        log("default layout: ${l.serialize()}")
        requestLayoutAll()
    }

    internal fun requestLayoutAll() {
        fixedRef.get()?.let { it.requestLayout(); (it.parent as? View)?.requestLayout() }
        customRef.get()?.requestLayout()
    }

    // ---------------------------------------------------------------- per-element resizing

    /** Fraction of the way from [native] to a bigger [now], 0..1. */
    private fun grown(now: Int, native: Int, stride: Int): Float = ((now - native).toFloat() / stride).coerceIn(0f, 1f)

    private fun applySize(item: Item, w: Int, h: Int, target: FsSize?) {
        when (item.kind) {
            FsKind.TILE -> tileDecor(item.view)?.apply(w, h)
            FsKind.MEDIA -> mediaDecor(item).apply(w, h, target ?: item.native)
            // The rest follows its new size (in the iOS grid every card is a little bigger).
            else -> treeDecor(item).apply(w, h)
        }
    }

    /** Our grid row of every element (vivo's pull spring is mapped onto it). */
    private val viewRows = WeakHashMap<View, Int>()
    /** Last vertical spring offset vivo gave each of its animation rows. */
    private val springRows = HashMap<Int, Float>()
    private val springHooked = Collections.synchronizedSet(HashSet<Class<*>>())

    /**
     * The offset of our [row]: vivo's rows (areas) spread over ours top to bottom, interpolated,
     * so every row moves at least as far as the one above it and nothing overlaps.
     */
    internal fun springFor(row: Int): Float? {
        if (springRows.isEmpty()) return null
        val maxArea = springRows.keys.max()
        val rows = max(1, targetRows - 1)
        val a = row.toFloat() / rows * maxArea
        fun at(i: Int): Float? {
            var k = i
            while (k >= 0) { springRows[k]?.let { return it }; k-- }
            return null
        }
        val lo = at(a.toInt()) ?: return null
        val hi = at(min(maxArea, a.toInt() + 1)) ?: lo
        return lerp(lo, hi, a - a.toInt())
    }


    private fun trackRow(item: Item, m: Metrics) {
        val target = targets[item.spec] ?: return
        viewRows[item.view] = (target.top / (m.cellH + m.gapV).toFloat()).roundToInt()
        hookSpring(item.view.javaClass)
    }

    /**
     * vivo's pull / open spring moves every element by the row it has in vivo's own fixed grid
     * model; ours differs (cards and tiles mixed, sizes changed), so elements dropped crooked or
     * jumped onto others. Each element now takes the offset vivo gives the row it really is in,
     * so the grid moves as one body, row after row, like vivo's own.
     */
    private fun hookSpring(cls: Class<*>) {
        if (!springHooked.add(cls)) return
        var c: Class<*>? = cls
        while (c != null && c != View::class.java) {
            for (m in c.declaredMethods) {
                if (m.parameterTypes.size != 1 || m.parameterTypes[0] != Float::class.javaPrimitiveType) continue
                when (m.name) {
                    "setTranslationVertical" -> XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val v = param.thisObject as? View ?: return
                            val row = viewRows[v] ?: return
                            if (!isActive()) return
                            val value = param.args[0] as Float
                            val area = callInt(v, "getAnimAreaIndex")
                            if (area >= 0) springRows[area] = value
                            v.translationY = springFor(row) ?: value
                            param.result = null
                        }
                    })
                    "setTranslationHorizontal" -> XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val v = param.thisObject as? View ?: return
                            if (viewRows[v] == null || !isActive()) return
                            // Sideways spring follows vivo's columns, which are not ours: none.
                            v.translationX = 0f
                            param.result = null
                        }
                    })
                }
            }
            c = c.superclass
        }
    }

    private fun afterItemLayout(item: Item, w: Int, h: Int) {
        if (item.kind == FsKind.FOCUS) focusFix(item, w)
    }

    // -- plain tiles

    private val tileDecors = WeakHashMap<View, TileDecor>()
    private val tileDecorMisses = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val bgOwners = WeakHashMap<View, TileDecor>()

    private fun tileDecor(view: View): TileDecor? {
        tileDecors[view]?.let { return it }
        if (view in tileDecorMisses) return null
        val icon = runCatching { findField(view.javaClass, "mIcon")?.get(view) as? View }.getOrNull()
        val frame = icon?.parent as? ViewGroup
        val bg = runCatching { findField(view.javaClass, "mBg")?.get(view) as? View }.getOrNull()
        if (icon == null || frame == null || bg == null || frame.layoutParams == null || bg.layoutParams == null || icon.layoutParams !is FrameLayout.LayoutParams) {
            tileDecorMisses += view
            return null
        }
        val decor = TileDecor(view, frame, bg, icon)
        tileDecors[view] = decor
        bgOwners[bg] = decor
        return decor
    }

    internal class TileDecor(val tile: View, val frame: ViewGroup, val bg: View, val icon: View) {
        private val frameW = frame.layoutParams.width
        private val frameH = frame.layoutParams.height
        private val bgW = bg.layoutParams.width
        private val bgH = bg.layoutParams.height
        private val bgGravity = (bg.layoutParams as? FrameLayout.LayoutParams)?.gravity ?: Gravity.CENTER
        private val iconLp = icon.layoutParams as FrameLayout.LayoutParams
        private val iconW = iconLp.width
        private val iconH = iconLp.height
        private val iconGravity = iconLp.gravity
        private val iconMarginL = iconLp.leftMargin
        private val iconMarginT = iconLp.topMargin
        private val nativeW = if (frameW > 0) frameW else tile.width
        private val nativeH = if (frameH > 0) frameH else tile.height
        private val outline = bg.outlineProvider
        private val clip = bg.clipToOutline
        val label = TileLabelDrawable(this)
        var resized = false
            private set
        var radius = 0f
            private set

        fun apply(w: Int, h: Int) {
            val m = metrics ?: return
            if (nativeW <= 0 || nativeH <= 0) return
            if (abs(w - nativeW) <= 1 && abs(h - nativeH) <= 1) {
                restore()
                return
            }
            // How far past one cell of the current grid the tile reaches.
            val tw = (w - m.cell).toFloat().div(m.cell + m.gapH).coerceIn(0f, 1f)
            val th = (h - m.cellH).toFloat().div(m.cellH + m.gapV).coerceIn(0f, 1f)
            cellSide = min(m.cell, m.cellH)
            frame.layoutParams.width = w
            frame.layoutParams.height = h
            bg.layoutParams.width = w
            bg.layoutParams.height = h
            (bg.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.CENTER
            val iw = if (iconW > 0) iconW else nativeW
            val ih = if (iconH > 0) iconH else nativeH
            iconLp.width = iw
            iconLp.height = ih
            iconLp.gravity = Gravity.TOP or Gravity.START
            // The icon slides from the centre into the leading (2x1) or top-leading (2x2) cell.
            iconLp.leftMargin = lerp((w - iw) / 2f, (m.cell - iw) / 2f, tw).roundToInt()
            iconLp.topMargin = lerp((h - ih) / 2f, (m.cellH - ih) / 2f, th).roundToInt()
            radius = min(min(w, h) / 2f, cellSide / 2f)
            if (!resized) {
                resized = true
                bg.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, out: Outline) {
                        out.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                bg.clipToOutline = true
            }
            // vivo's panel bleaches dark content drawn inside it (a black title turns white): titles
            // are drawn by a layer of ours over the whole panel, see [LabelLayer].
            labelDecors += this
            bg.invalidateOutline()
            label.grow = max(tw, th)
            label.wide = tw
            label.tall = th
            label.w = w
            label.h = h
            label.invalidateSelf()
            forceLayout(tile, frame, bg, icon)
        }

        fun restore() {
            if (!resized) return
            resized = false
            frame.layoutParams.width = frameW
            frame.layoutParams.height = frameH
            bg.layoutParams.width = bgW
            bg.layoutParams.height = bgH
            (bg.layoutParams as? FrameLayout.LayoutParams)?.gravity = bgGravity
            iconLp.width = iconW
            iconLp.height = iconH
            iconLp.gravity = iconGravity
            iconLp.leftMargin = iconMarginL
            iconLp.topMargin = iconMarginT
            bg.outlineProvider = outline
            bg.clipToOutline = clip
            labelDecors -= this
            forceLayout(tile, frame, bg, icon)
        }

        /** Side of one cell of the current grid (vivo's or the iOS one). */
        var cellSide = min(nativeW, nativeH)
    }

    /** Title and state of a 2x1 / 2x2 tile, drawn over vivo's tile without touching its views. */
    internal class TileLabelDrawable(private val decor: TileDecor) : Drawable() {
        var grow = 0f
        var wide = 0f
        var tall = 0f
        var w = 0
        var h = 0
        private val title = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val subtitle = TextPaint(Paint.ANTI_ALIAS_FLAG)

        init {
            title.typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 650, false) else Typeface.DEFAULT_BOLD
            subtitle.typeface = Typeface.DEFAULT
        }

        override fun draw(canvas: Canvas) {
            if (grow <= 0.01f) return
            val state = tileState(decor.tile) ?: return
            val label = (stateField(state, "label") as? CharSequence)?.toString()?.takeIf { it.isNotBlank() }
                ?: decor.tile.contentDescription?.toString() ?: return
            val raw = stateField(state, "state") as? Int ?: 1
            // vivo keeps the on/off of boolean tiles in mTileState as well.
            val on = raw == 2 || (runCatching { findField(decor.tile.javaClass, "mTileState")?.getBoolean(decor.tile) }.getOrNull() == true)
            val value = if (on) 2 else raw
                        val secondary = (stateField(state, "secondaryLabel") as? CharSequence)?.toString()?.takeIf { it.isNotBlank() }
                ?: FsText.stateText(value)
            val tile = decor.tile
            if (!tile.isShown || tile.width <= 0) return
            val side = decor.cellSide.toFloat()
            // Active tiles carry a light plate: black text, like iOS.
            val color = if (value == 2) Color.BLACK else Color.WHITE
            val alpha = (grow * grow * 255 * tile.alpha).toInt().coerceIn(0, 255)
            if (alpha <= 0) return
            title.color = color
            title.alpha = alpha
            subtitle.color = color
            subtitle.alpha = (alpha * (if (value == 2) 0.7f else 0.64f)).toInt()
            title.textSize = side * 0.185f
            subtitle.textSize = side * 0.158f
            // 2x1: to the right of the icon, centred. 2x2: under it, at the bottom.
            val left = lerp(side * 0.9f, side * 0.24f, tall)
            val right = w - side * 0.2f
            val avail = right - left
            if (avail <= side * 0.3f) return
            val t = TextUtils.ellipsize(label, title, avail, TextUtils.TruncateAt.END).toString()
            val s = TextUtils.ellipsize(secondary, subtitle, avail, TextUtils.TruncateAt.END).toString()
            val lineGap = side * 0.03f
            val block = title.textSize + lineGap + subtitle.textSize
            val centred = (h - block) / 2f
            val bottom = h - side * 0.2f - block
            val top = lerp(centred, bottom, tall)
            // The layer has moved the canvas onto the tile (its place, press scale, animations).
            val save = canvas.save()
            canvas.drawText(t, left, top + title.textSize * 0.92f, title)
            canvas.drawText(s, left, top + title.textSize + lineGap + subtitle.textSize * 0.92f, subtitle)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private val labelDecors = Collections.newSetFromMap(WeakHashMap<TileDecor, Boolean>())

    /**
     * Draws the titles of resized tiles, over vivo's panel (in its parent): every frame where a
     * tile moved, faded or scaled, the titles follow it.
     */
    internal class LabelLayer(context: Context) : View(context) {
        private var signature = 0L
        private val preDraw = android.view.ViewTreeObserver.OnPreDrawListener {
            val sig = signature()
            if (sig != signature) { signature = sig; invalidate() }
            true
        }

        init {
            isClickable = false; isFocusable = false; setWillNotDraw(false)
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnPreDrawListener(preDraw) }
        override fun onDetachedFromWindow() { super.onDetachedFromWindow(); viewTreeObserver.removeOnPreDrawListener(preDraw) }
        override fun onTouchEvent(event: MotionEvent?) = false

        private val tileMatrix = android.graphics.Matrix()
        private val layerMatrix = android.graphics.Matrix()
        private val inverse = android.graphics.Matrix()
        private val values = FloatArray(9)

        /**
         * Tile → window, the way the tile is drawn: every ancestor's place, scroll, transform and
         * animation matrix (Liquid Glass stretches a tile with one), so the title is one body with it.
         */
        private fun toWindow(view: View, out: android.graphics.Matrix) {
            out.reset()
            var v: View? = view
            while (v != null) {
                if (!v.matrix.isIdentity) out.postConcat(v.matrix)
                v.animationMatrix?.let { out.postConcat(it) }
                out.postTranslate(v.left.toFloat(), v.top.toFloat())
                val p = v.parent as? View ?: break
                out.postTranslate(-p.scrollX.toFloat(), -p.scrollY.toFloat())
                v = p
            }
        }

        private fun signature(): Long {
            var h = 17L
            toWindow(this, layerMatrix)
            for (d in labelDecors.toList()) {
                val t = d.tile
                if (!t.isAttachedToWindow) continue
                toWindow(t, tileMatrix)
                tileMatrix.getValues(values)
                for (v in values) h = h * 31 + (v * 100).toInt()
                h = h * 31 + t.width; h = h * 31 + t.height
                h = h * 31 + (t.alpha * 255).toInt()
                h = h * 31 + (if (t.isShown) 1 else 0)
                h = h * 31 + (tileState(t)?.let { s -> (stateField(s, "label")?.hashCode() ?: 0) + 7 * ((stateField(s, "state") as? Int) ?: 0) } ?: 0)
            }
            return h
        }

        override fun onDraw(canvas: Canvas) {
            toWindow(this, layerMatrix)
            if (!layerMatrix.invert(inverse)) inverse.reset()
            for (d in labelDecors.toList()) {
                val t = d.tile
                if (!t.isAttachedToWindow || !t.isShown) continue
                toWindow(t, tileMatrix)
                tileMatrix.postConcat(inverse)
                val save = canvas.save()
                canvas.concat(tileMatrix)
                withoutNightMode(canvas) { d.label.draw(canvas) }
                canvas.restoreToCount(save)
            }
        }
    }

    private val canvasNightMode by lazy(LazyThreadSafetyMode.NONE) {
        runCatching { Canvas::class.java.getMethod("setNightMode", Int::class.javaPrimitiveType) to Canvas::class.java.getMethod("getNightMode") }.getOrNull()
    }

    /**
     * OriginOS recolours what SystemUI draws for its night mode (black text turns white, per
     * drawing operation). Mode 0 on the canvas draws [block] as it is; the canvas' mode is put back after.
     */
    internal fun withoutNightMode(canvas: Canvas, block: () -> Unit) {
        val api = canvasNightMode
        val was = api?.let { runCatching { it.second.invoke(canvas) as? Int }.getOrNull() }
        runCatching { api?.first?.invoke(canvas, 0) }
        try { block() } finally { if (was != null && was != 0) runCatching { api?.first?.invoke(canvas, was) } }
    }

    private var labelLayer: LabelLayer? = null

    private fun syncLabelLayer() {
        val panel = panelRef.get()
        val host = panel?.parent as? ViewGroup
        if (!isActive() || host == null) {
            labelLayer?.let { (it.parent as? ViewGroup)?.removeView(it) }
            labelLayer = null
            return
        }
        val current = labelLayer
        if (current != null && current.parent === host) return
        current?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val layer = LabelLayer(host.context)
        host.addView(layer, host.indexOfChild(panel) + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        labelLayer = layer
    }

    private fun tileState(tile: View): Any? = runCatching { findField(tile.javaClass, "mQSTileState")?.get(tile) }.getOrNull()

    private fun stateField(state: Any, name: String): Any? = runCatching { findField(state.javaClass, name)?.get(state) }.getOrNull()

    /** Liquid Glass asks here for the corner radius of a resized tile's plate. */
    fun glassRadius(view: View, w: Float, h: Float): Float? {
        if (!enabled) return null
        val decor = bgOwners[view] ?: return null
        if (!decor.resized) return null
        return min(min(w, h) / 2f, decor.cellSide / 2f)
    }

    // -- sliders, focus card: every descendant at the native size follows the new one

    private val treeDecors = WeakHashMap<View, TreeDecor>()

    private fun treeDecor(item: Item): TreeDecor = treeDecors.getOrPut(item.view) { TreeDecor(item.view, item.nativeW, item.nativeH) }

    internal class TreeDecor(private val root: View, private val nativeW: Int, private val nativeH: Int) {
        private class Entry(val view: View, val w: Int, val h: Int, val matchW: Boolean, val matchH: Boolean)
        private val entries = ArrayList<Entry>()
        private var collected = false
        var resized = false
            private set

        private fun collect() {
            collected = true
            fun walk(v: View, depth: Int) {
                if (v !== root) {
                    val lp = v.layoutParams
                    if (lp != null) {
                        val mw = lp.width > 0 && abs(lp.width - nativeW) <= 2
                        val mh = lp.height > 0 && abs(lp.height - nativeH) <= 2
                        if (mw || mh) entries += Entry(v, lp.width, lp.height, mw, mh)
                    }
                }
                if (depth < 5 && v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
            walk(root, 0)
        }

        fun apply(w: Int, h: Int) {
            val native = abs(w - nativeW) <= 1 && abs(h - nativeH) <= 1
            if (native && !resized) return
            if (!collected) collect()
            resized = !native
            for (e in entries) {
                val lp = e.view.layoutParams ?: continue
                if (e.matchW) lp.width = if (native) e.w else w
                if (e.matchH) lp.height = if (native) e.h else h
                e.view.forceLayout()
            }
            root.forceLayout()
        }
    }

    private fun focusFix(item: Item, w: Int) {
        val m = metrics ?: return
        val group = findByIdName(item.view, "super_qs_tile_group") as? ViewGroup ?: return
        val zen = findByIdName(group, "zen_mode_container") ?: return
        val content = findByIdName(group, "qs_focus_content_container") ?: return
        val t = grown(w, m.cell, m.w(2) - m.cell)
        content.alpha = t
        // 1x1: the focus button alone, centred in the circle.
        val centred = (w - zen.width) / 2f - zen.left
        zen.translationX = lerp(centred, 0f, t)
    }

    // -- player

    private val mediaDecors = WeakHashMap<View, MediaDecor>()

    private fun mediaDecor(item: Item): MediaDecor = mediaDecors.getOrPut(item.view) { MediaDecor(item) }

    internal class MediaDecor(item: Item) {
        private val root = item.view
        private val tree = TreeDecor(item.view, item.nativeW, item.nativeH)
        private val nativeW = item.nativeW
        private val nativeH = item.nativeH
        private var overlay: FreestyleMediaView? = null
        private val content: List<View> by lazy {
            listOfNotNull(findByIdName(root, "rl_top_parent"), findByIdName(root, "rl_bottom_parent"))
        }

        fun apply(w: Int, h: Int, target: FsSize) {
            tree.apply(w, h)
            val m = metrics ?: return
            // How far the card is from vivo's own 2x2: our template fades in over it (and out
            // again on the way back).
            val show = max(abs(w - m.w(2)).toFloat() / (m.cell + m.gapH), abs(h - m.h(2)).toFloat() / (m.cellH + m.gapV)).coerceIn(0f, 1f)
            if (show <= 0.001f && overlay == null) return
            val host = (root as? ViewGroup)?.let { r -> (0 until r.childCount).map(r::getChildAt).firstOrNull { it is FrameLayout } as? FrameLayout ?: r as? FrameLayout }
                ?: return
            val view = overlay ?: FreestyleMediaView(root.context).also {
                overlay = it
                host.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            view.sizeClass = target
            view.metrics = m
            view.alpha = show
            view.visibility = if (show <= 0.001f) View.GONE else View.VISIBLE
            for (c in content) c.alpha = 1f - show
        }

        fun restore() {
            tree.apply(nativeW, nativeH)
            overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
            overlay = null
            for (c in content) c.alpha = 1f
        }
    }

    private fun restoreAll() {
        synchronized(tileDecors) { tileDecors.values.toList() }.forEach { runCatching { it.restore() } }
        treeDecors.forEach { (v, d) -> runCatching { d.apply(nativeSizes[v]?.get(0) ?: 0, nativeSizes[v]?.get(1) ?: 0) } }
        mediaDecors.values.forEach { runCatching { it.restore() } }
        items().filter { it.kind == FsKind.FOCUS }.forEach { item ->
            findByIdName(item.view, "qs_focus_content_container")?.alpha = 1f
            findByIdName(item.view, "zen_mode_container")?.translationX = 0f
        }
        targets.clear()
        shown.clear()
        targetSignature = ""
    }

    // ---------------------------------------------------------------- editor API

    // ---------------------------------------------------------------- iOS header

    private var iosHeader: FreestyleIosHeader? = null

    private fun syncIosHeader() {
        val panel = panelRef.get()
        val want = ios && isActive() && panel != null
        val current = iosHeader
        if (!want) {
            if (current != null) {
                current.restore()
                (current.parent as? ViewGroup)?.removeView(current)
                iosHeader = null
            }
            return
        }
        if (current != null && current.isAttachedToWindow && current.parent === panel!!.parent) return
        current?.let { it.restore(); (it.parent as? ViewGroup)?.removeView(it) }
        val host = panel!!.parent as? ViewGroup ?: return
        val view = FreestyleIosHeader(panel.context, panel)
        host.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        iosHeader = view
    }

    /** The header's "+": our editor with Freestyle on, vivo's otherwise. */
    internal fun headerEdit(panel: ViewGroup) {
        if (freestyle && runCatching { openEditor(panel) }.getOrDefault(false)) return
        openVivoEditor(panel)
    }

    private fun openEditor(panel: ViewGroup): Boolean {
        if (editor != null) return true
        if (layout == null) captureDefault()
        if (!isActive()) return false
        val host = panel.parent as? ViewGroup ?: return false
        val view = FreestyleEditorView(panel.context, panel)
        editor = view
        hookRootTouches(panel.rootView)
        host.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.open()
        return true
    }

    private val rootHooked = Collections.synchronizedSet(HashSet<Class<*>>())

    /**
     * The shade window's root view handles vivo's shade gestures (drag, collapse, paging) in its
     * own dispatchTouchEvent. While editing, that is skipped: the event goes to the editor only.
     */
    private fun hookRootTouches(root: View) {
        var c: Class<*>? = root.javaClass
        while (c != null && c != View::class.java) {
            if (rootHooked.add(c)) {
                runCatching { c.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java) }.getOrNull()?.let { m ->
                    XposedBridge.hookMethod(m, object : XC_MethodHook(10000) {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (forwarding) return
                            val ed = editor ?: return
                            val v = param.thisObject as? View ?: return
                            if (v !== ed.rootView || !ed.isAttachedToWindow) return
                            val event = param.args[0] as? MotionEvent ?: return
                            forwardToEditor(ed, event)
                            param.result = true
                        }
                    })
                    log("editor: root touches of ${c.simpleName} hooked")
                }
            }
            c = c.superclass
        }
    }

    private var forwarding = false

    private fun forwardToEditor(ed: FreestyleEditorView, event: MotionEvent) {
        val loc = IntArray(2)
        ed.getLocationInWindow(loc)
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(-loc[0].toFloat(), -loc[1].toFloat())
        forwarding = true
        try {
            ed.dispatchTouchEvent(copy)
        } catch (t: Throwable) {
            log("editor touch: $t")
        } finally {
            forwarding = false
            copy.recycle()
        }
    }

    internal fun editorClosed(view: FreestyleEditorView, save: Boolean) {
        if (editor === view) editor = null
        if (save) persist()
    }

    /** vivo's own editor, from the gallery. */
    internal fun openVivoEditor(panel: ViewGroup) {
        val anchor = findByIdName(panel.rootView, "vivo_icon_edit") ?: panel
        passThroughEdit = true
        try {
            panel.javaClass.methods.firstOrNull { it.name == "showEdit" && it.parameterTypes.size == 1 }?.invoke(panel, anchor)
        } catch (t: Throwable) {
            log("vivo editor: $t")
        } finally {
            passThroughEdit = false
        }
    }

    /** Rect of [spec] in [target]'s coordinates, as drawn now. */
    internal fun rectIn(item: Item, target: View, out: RectF): Boolean {
        val v = item.view
        if (v.width <= 0 || v.height <= 0 || !v.isAttachedToWindow) return false
        val a = IntArray(2)
        val b = IntArray(2)
        (v.parent as? View ?: return false).getLocationInWindow(a)
        target.getLocationInWindow(b)
        val l = a[0] - b[0] + v.left + v.translationX
        val t = a[1] - b[1] + v.top + v.translationY
        out.set(l, t, l + v.width, t + v.height)
        return true
    }

    /** Grid origin in [target]'s coordinates. */
    internal fun gridOrigin(target: View): FloatArray? {
        val fixed = fixedRef.get() ?: return null
        val a = IntArray(2)
        val b = IntArray(2)
        fixed.getLocationInWindow(a)
        target.getLocationInWindow(b)
        return floatArrayOf((a[0] - b[0] + fixed.paddingLeft).toFloat(), (a[1] - b[1] + fixed.paddingTop).toFloat())
    }

    internal fun targetRect(spec: String): Rect? = targets[spec]
    internal fun targetSize(spec: String): FsSize? = targetSizes[spec]

    internal fun cornerRadius(item: Item, w: Float, h: Float): Float {
        val m = metrics
        val side = if (m != null) min(m.cell, m.cellH) else min(item.nativeW, item.nativeH)
        // One rule for every plate: a circle / capsule while a side is one cell, then the corner
        // stays at half a cell, so a growing tile morphs smoothly into a rounded square.
        return min(min(w, h) / 2f, side / 2f)
    }

    internal fun setSize(spec: String, size: FsSize) {
        val l = layout ?: return
        if (l.sizes[spec] == size) return
        l.sizes[spec] = size
        requestLayoutAll()
    }

    /** Puts [spec] right before [anchor] (or at the end when null). */
    internal fun move(spec: String, anchor: String?, after: Boolean) {
        val l = layout ?: return
        if (spec == anchor) return
        val order = l.order
        if (!order.remove(spec)) return
        val i = anchor?.let(order::indexOf)?.takeIf { it >= 0 }
        if (i == null) order += spec else order.add(if (after) i + 1 else i, spec)
        requestLayoutAll()
    }

    internal fun visibleOrder(): List<String> {
        val l = layout ?: return emptyList()
        return l.order.filter { it !in l.hidden && it in targetSizes }
    }

    internal fun startDrag(spec: String) {
        val r = currentRect(spec) ?: return
        dragRect.set(r)
        dragSpec = spec
    }

    internal fun dragBy(dx: Float, dy: Float, base: RectF) {
        dragRect.set(base.left + dx, base.top + dy, base.right + dx, base.bottom + dy)
        requestLayoutAll()
    }

    internal fun gridRect(spec: String): RectF? = currentRect(spec)?.let { RectF(it) }

    internal fun endDrag() {
        val spec = dragSpec ?: return
        // From the finger back into its slot.
        val snapshot = HashMap<String, RectF>()
        for (s in targets.keys) currentRect(s)?.let { snapshot[s] = RectF(it) }
        dragSpec = null
        from.clear()
        from.putAll(snapshot)
        snapshot[spec]?.let { from[spec] = it }
        startMorph()
    }

    /** Takes [spec] out: tiles leave vivo's list (like vivo's editor), cards are hidden. */
    internal fun remove(item: Item) {
        val l = layout ?: return
        l.hidden += item.spec
        requestLayoutAll()
        if (item.kind == FsKind.TILE && !item.spec.startsWith("view:")) {
            main.postDelayed({
                editTileList { it.remove(item.spec) }
                l.hidden -= item.spec
                l.order -= item.spec
                l.sizes -= item.spec
            }, ANIM_MS)
        }
    }

    /** Brings a hidden card back, or adds a tile to vivo's list; it lands at the end. */
    internal fun add(spec: String) {
        val l = layout ?: return
        if (spec in l.hidden) {
            l.hidden -= spec
            l.order -= spec
            l.order += spec
            requestLayoutAll()
            return
        }
        l.order -= spec
        l.order += spec
        l.sizes.getOrPut(spec) { FsSize.S1x1 }
        editTileList { list -> if (spec !in list) list += spec }
    }

    /** Back to vivo's arrangement: the cards as vivo places them, then the tiles, all at native size. */
    internal fun reset() {
        if (layout == null) return
        val items = items()
        val fresh = FsLayout()
        val vivoOrder = if (ios) IOS_ORDER else listOf("connect_group", "music", "volume_group", "volume_seek_bar", "light_seek_bar", "focus_group")
        val sorted = items.sortedWith(compareBy({ !it.inFixed }, { if (it.inFixed) vivoOrder.indexOf(it.spec).let { i -> if (i < 0) 99 else i } else 0 }))
        for (item in sorted) {
            fresh.order += item.spec
            fresh.sizes[item.spec] = if (item.native in item.sizes) item.native else item.sizes.first()
        }
        layout = fresh
        requestLayoutAll()
    }

    internal fun hiddenSpecs(): List<String> = layout?.hidden?.toList() ?: emptyList()

    internal fun tileList(): MutableList<String> {
        val cr = appContext?.contentResolver ?: return mutableListOf()
        return Settings.Secure.getString(cr, TILES_SETTING)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()
    }

    internal fun stockList(): List<String> {
        val cr = appContext?.contentResolver ?: return emptyList()
        return Settings.Secure.getString(cr, STOCK_SETTING)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    private fun editTileList(change: (MutableList<String>) -> Unit) {
        val cr = appContext?.contentResolver ?: return
        val list = tileList()
        if (list.isEmpty()) return
        change(list)
        val text = list.joinToString(",")
        Thread { runCatching { Settings.Secure.putString(cr, TILES_SETTING, text) }.onFailure { log("tiles: $it") } }.start()
    }

    // ---------------------------------------------------------------- helpers

    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val missingFields = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    internal fun findField(cls: Class<*>, name: String): Field? {
        val key = cls.name + "#" + name
        fieldCache[key]?.let { return it }
        if (key in missingFields) return null
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            runCatching { c.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                fieldCache[key] = it
                return it
            }
            c = c.superclass
        }
        missingFields += key
        return null
    }

    private fun intField(obj: Any, name: String): Int = runCatching { findField(obj.javaClass, name)?.getInt(obj) }.getOrNull() ?: -1

    private fun callInt(obj: Any, name: String): Int = runCatching {
        var c: Class<*>? = obj.javaClass
        var m: java.lang.reflect.Method? = null
        while (c != null && m == null) {
            m = runCatching { c.getDeclaredMethod(name) }.getOrNull()
            c = c.superclass
        }
        m?.isAccessible = true
        (m?.invoke(obj) as? Int) ?: -1
    }.getOrDefault(-1)

    private val setMeasuredMethod by lazy(LazyThreadSafetyMode.NONE) {
        View::class.java.getDeclaredMethod("setMeasuredDimension", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
    }

    private fun setMeasured(v: View, w: Int, h: Int) {
        setMeasuredMethod.invoke(v, w, h)
    }

    private fun exactly(px: Int) = View.MeasureSpec.makeMeasureSpec(max(0, px), View.MeasureSpec.EXACTLY)

    private fun forceLayout(vararg views: View) = views.forEach { it.forceLayout() }

    internal fun findByIdName(root: View, name: String): View? {
        fun idName(v: View): String? = if (v.id == View.NO_ID) null else runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
        fun walk(v: View, depth: Int): View? {
            if (idName(v) == name) return v
            if (depth > 12 || v !is ViewGroup) return null
            for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)?.let { return it }
            return null
        }
        return walk(root, 0)
    }

    private fun hasDescendant(root: View, test: (View) -> Boolean): Boolean {
        fun walk(v: View, depth: Int): Boolean {
            if (test(v)) return true
            if (depth > 6 || v !is ViewGroup) return false
            for (i in 0 until v.childCount) if (walk(v.getChildAt(i), depth + 1)) return true
            return false
        }
        return walk(root, 0)
    }

    internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    internal fun log(message: String) {
        XposedBridge.log("OriginFreestyle: $message")
        runCatching {
            val dir = appContext?.filesDir ?: return
            java.io.File(dir, "freestyle_log.txt").appendText(message + "\n")
        }
    }
}
