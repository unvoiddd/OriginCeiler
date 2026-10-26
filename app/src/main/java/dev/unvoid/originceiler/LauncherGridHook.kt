package dev.unvoid.originceiler

import android.app.Application
import android.content.Context
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Xposed hook for BBK Launcher (com.bbk.launcher2) desktop grid modification.
 * Bypasses hardcoded screen layout whitelists (b4.g.a) and enforces custom
 * column and row dimensions across all layout managers and cell layouts.
 */
class LauncherGridHook {
    private val installed = AtomicBoolean(false)

    /**
     * The live grid of BBK Launcher 16 lives in LauncherEnvironmentManagerMain.t/u (countX/countY):
     * initialised by y1() from prefs, changed by Ja(II), read through S8()/Z7(). Around that sit
     * device whitelists (b4.g.a, b4.g.s) that snap any "unsupported" size back to 4x6/4x7/5x9,
     * so all of them are overridden while a custom grid is active.
     */
    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        runCatching { hookEnvironment(classLoader) }
            .onFailure { XposedBridge.log("OriginRootToolbox: LauncherGridHook environment hook failed: $it") }
        runCatching { hookLayoutUtils(classLoader) }
            .onFailure { XposedBridge.log("OriginRootToolbox: LauncherGridHook b4.g hook failed: $it") }
        runCatching { hookFolderFit() }
            .onFailure { XposedBridge.log("OriginRootToolbox: LauncherGridHook folder hook failed: $it") }
        runCatching { hookLayoutPrefs(classLoader) }
            .onFailure { XposedBridge.log("OriginRootToolbox: LauncherGridHook o7.h hook failed: $it") }
        XposedBridge.log("OriginRootToolbox: LauncherGridHook installed")
    }

    private fun hookEnvironment(classLoader: ClassLoader) {
        val env = Class.forName("com.bbk.launcher2.environment.LauncherEnvironmentManagerMain", false, classLoader)

        // Grid init from prefs: let it run, then force our size.
        XposedBridge.hookAllMethods(env, "y1", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val grid = getCustomGrid() ?: return
                setIntField(param.thisObject, "t", grid.first)
                setIntField(param.thisObject, "u", grid.second)
            }
        })
        // Runtime grid changes (layout switch, rotation, fold) go through Ja(countX, countY).
        XposedBridge.hookAllMethods(env, "Ja", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val grid = getCustomGrid() ?: return
                if (param.args.size == 2 && param.args[0] is Int && param.args[1] is Int) {
                    param.args[0] = grid.first
                    param.args[1] = grid.second
                }
            }
        })
        // J9() fills cell width/height and gaps (I, J, K, L) from fixed per-mode presets that assume
        // the stock grid. Remember them and compute a re-fit so the custom grid covers the same area.
        XposedBridge.hookAllMethods(env, "J9", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val e = param.thisObject
                envRef = e
                val stock = intArrayOf(
                    getIntField(e, "I") ?: return, getIntField(e, "J") ?: return,
                    getIntField(e, "K") ?: return, getIntField(e, "L") ?: return
                )
                stockMetrics = stock
                fittedMetrics = null
                val grid = getCustomGrid() ?: return
                val base = stockGrid ?: return
                val x = fit(base.first, grid.first, stock[0], stock[2]) ?: (stock[0] to stock[2])
                val y = fit(base.second, grid.second, stock[1], stock[3]) ?: (stock[1] to stock[3])
                fittedMetrics = intArrayOf(x.first, y.first, x.second, y.second)
            }
        })

        // Only desktop pages get the fitted metrics; the dock and everything else keep the stock ones.
        val cellLayout = Class.forName("com.bbk.launcher2.ui.CellLayout", false, classLoader)
        XposedBridge.hookAllMethods(cellLayout, "setCellParams", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.thisObject.javaClass.name.contains("Hotseat")) return
                val e = envRef ?: return
                val fitted = fittedMetrics ?: return
                if (getCustomGrid() == null) return
                writeMetrics(e, fitted)
                swapped.set(true)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (swapped.get() != true) return
                swapped.set(false)
                val e = envRef ?: return
                stockMetrics?.let { writeMetrics(e, it) }
            }
        })

        XposedBridge.hookAllMethods(env, "S8", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val grid = getCustomGrid() ?: return
                if (param.args.isEmpty()) param.result = grid.first
            }
        })
        XposedBridge.hookAllMethods(env, "Z7", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val grid = getCustomGrid() ?: return
                if (param.args.isEmpty()) param.result = grid.second
            }
        })
    }

    /**
     * Folder icons lay their content out at the stock cell size, so in a denser grid they spill into
     * neighbours. Scale the whole folder down uniformly to fit its (smaller) span.
     */
    private fun hookFolderFit() {
        XposedBridge.hookAllMethods(android.view.View::class.java, "layout", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val v = param.thisObject as? android.view.ViewGroup ?: return
                val name = v.javaClass.name
                if (name == WIDGET_VIEW_CLASS) {
                    fitWidget(v)
                    return
                }
                if (name != FOLDER_CONTAINER_CLASS) return
                val grid = getCustomGrid()
                if (grid == null) {
                    if (v.getTag(TAG_FOLDER_FIT) != null) {
                        v.setTag(TAG_FOLDER_FIT, null)
                        v.scaleX = 1f; v.scaleY = 1f
                    }
                    return
                }
                // The mini icons sit at fixed stock offsets; fit their bounding box into the container.
                var left = Int.MAX_VALUE
                var top = Int.MAX_VALUE
                var right = 0
                var bottom = 0
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i)
                    if (c.visibility == android.view.View.GONE || c.width <= 0) continue
                    left = minOf(left, c.left); top = minOf(top, c.top)
                    right = maxOf(right, c.right); bottom = maxOf(bottom, c.bottom)
                }
                if (right <= 0 || left == Int.MAX_VALUE) return
                val scale = minOf(
                    v.width.toFloat() / (right + left),
                    v.height.toFloat() / (bottom + top)
                ).coerceIn(0.3f, 1f)
                v.setTag(TAG_FOLDER_FIT, scale)
                v.pivotX = 0f
                v.pivotY = 0f
                v.scaleX = scale
                v.scaleY = scale
            }
        })
    }

    /**
     * Fixed-size vivo widgets (clock, weather…) keep their stock width inside a narrower host and
     * overflow both sides. Scale them uniformly around the centre to fit the host.
     */
    private fun fitWidget(v: android.view.ViewGroup) {
        // The host is the LauncherAppWidgetHostView cell; there may be a wrapper in between.
        var host: android.view.View? = v.parent as? android.view.View
        var offX = v.left
        var offY = v.top
        while (host != null && !host.javaClass.name.endsWith("LauncherAppWidgetHostView")) {
            offX += host.left
            offY += host.top
            host = host.parent as? android.view.View
        }
        if (host == null) return
        // Visual extent in host coordinates: the widget view plus any child drawn outside it.
        var l = offX
        var t = offY
        var r = offX + v.width
        var b = offY + v.height
        // Children count with their own transform: the launcher scales third-party widget roots
        // (Google search ×1.22) to the stock cell width.
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (c.visibility != android.view.View.VISIBLE || c.width <= 0) continue
            val cl = offX + c.left + c.pivotX * (1f - c.scaleX) + c.translationX
            val ct = offY + c.top + c.pivotY * (1f - c.scaleY) + c.translationY
            l = minOf(l, cl.toInt()); t = minOf(t, ct.toInt())
            r = maxOf(r, (cl + c.width * c.scaleX).toInt()); b = maxOf(b, (ct + c.height * c.scaleY).toInt())
        }
        val scale = if (getCustomGrid() == null || r <= l || b <= t) 1f
        else minOf(1f, host.width.toFloat() / (r - l), host.height.toFloat() / (b - t)).coerceAtLeast(0.3f)
        if (scale >= 0.999f) {
            if (v.getTag(TAG_FOLDER_FIT) != null) {
                v.setTag(TAG_FOLDER_FIT, null)
                v.scaleX = 1f; v.scaleY = 1f
                v.translationX = 0f; v.translationY = 0f
            }
            return
        }
        v.setTag(TAG_FOLDER_FIT, scale)
        // Scale around the content's centre, then shift that centre onto the host's centre.
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        v.pivotX = cx - offX
        v.pivotY = cy - offY
        v.scaleX = scale
        v.scaleY = scale
        v.translationX = host.width / 2f - cx
        v.translationY = host.height / 2f - cy
    }

    private val swapped = ThreadLocal<Boolean>()
    @Volatile private var envRef: Any? = null
    @Volatile private var stockMetrics: IntArray? = null
    @Volatile private var fittedMetrics: IntArray? = null

    private fun writeMetrics(env: Any, m: IntArray) {
        setIntField(env, "I", m[0]); setIntField(env, "J", m[1])
        setIntField(env, "K", m[2]); setIntField(env, "L", m[3])
    }

    /** Splits the stock span (n0 cells of [cell] + gaps) into [n] cells with proportional gaps. */
    private fun fit(n0: Int, n: Int, cell: Int, gap: Int): Pair<Int, Int>? {
        if (n0 <= 0 || n <= 0 || n == n0) return null
        val span = n0 * cell + (n0 - 1) * gap
        val pitch = (span + gap).toFloat() / n
        val newCell = (pitch * cell / (cell + gap)).toInt().coerceAtLeast(1)
        val newGap = if (n > 1) (span - n * newCell) / (n - 1) else 0
        return newCell to newGap
    }

    private fun hookLayoutUtils(classLoader: ClassLoader) {
        val utils = Class.forName("b4.g", false, classLoader)

        // a([I)Z: checkAndCorrectLayoutCountXY – the device whitelist. Write our size, report "no correction".
        XposedBridge.hookAllMethods(utils, "a", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val arr = param.args.singleOrNull() as? IntArray ?: return
                val grid = getCustomGrid() ?: return
                if (arr.size != 2) return
                arr[0] = grid.first
                arr[1] = grid.second
                param.result = false
            }
        })
        // s([I)[I remaps 4x5 -> 4x6 and 5x6/5x7 -> 5x9.
        XposedBridge.hookAllMethods(utils, "s", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val arr = param.args.singleOrNull() as? IntArray ?: return
                val grid = getCustomGrid() ?: return
                if (arr.size != 2) return
                arr[0] = grid.first
                arr[1] = grid.second
                param.result = arr
            }
        })
        // o(Z)[I getLayoutCountFromSPInternal and p(Context,int,int)[I read the stored size.
        val stored = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val grid = getCustomGrid() ?: return
                if (param.result is IntArray) param.result = intArrayOf(grid.first, grid.second)
            }
        }
        XposedBridge.hookAllMethods(utils, "o", stored)
        XposedBridge.hookAllMethods(utils, "p", stored)
    }

    private fun hookLayoutPrefs(classLoader: ClassLoader) {
        val prefs = Class.forName("o7.h", false, classLoader)
        // Getters take the default value: i/j/k/l = countX variants, m/n/o/p = countY variants.
        fun hook(names: List<String>, pick: (Pair<Int, Int>) -> Int) {
            names.forEach { name ->
                XposedBridge.hookAllMethods(prefs, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args.size != 1 || param.args[0] !is Int || param.result !is Int) return
                        val grid = getCustomGrid() ?: return
                        param.result = pick(grid)
                    }
                })
            }
        }
        hook(listOf("i", "j", "k", "l")) { it.first }
        hook(listOf("m", "n", "o", "p")) { it.second }
    }

    @Volatile private var cachedGrid: Pair<Int, Int>? = null
    @Volatile private var cachedAt = 0L

    /** Cached for 2 s: S8()/Z7() run on every layout pass; grid changes restart the launcher anyway. */
    private fun getCustomGrid(): Pair<Int, Int>? {
        val now = android.os.SystemClock.uptimeMillis()
        if (cachedAt != 0L && now - cachedAt < 2000L) return cachedGrid
        val app = currentApplication() ?: return null
        return getCustomGrid(app).also {
            cachedGrid = it
            cachedAt = now
        }
    }

    private fun getCustomGrid(context: Context): Pair<Int, Int>? {
        val cr = context.contentResolver
        val enabled = runCatching {
            Settings.Global.getInt(cr, SETTING_CUSTOM_GRID_ENABLED, 0) == 1
        }.getOrDefault(false)
        if (!enabled) return null

        val cols = runCatching {
            Settings.Global.getInt(cr, SETTING_GRID_COLUMNS, 4)
        }.getOrDefault(4).coerceAtLeast(1)

        val rows = runCatching {
            Settings.Global.getInt(cr, SETTING_GRID_ROWS, 7)
        }.getOrDefault(7).coerceAtLeast(1)

        return Pair(cols, rows)
    }

    private fun currentApplication(): Application? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()

    private fun getIntField(obj: Any, fieldName: String): Int? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            val field = runCatching { clazz.getDeclaredField(fieldName) }.getOrNull()
            if (field != null) return runCatching { field.isAccessible = true; field.getInt(obj) }.getOrNull()
            clazz = clazz.superclass
        }
        return null
    }

    private fun setIntField(obj: Any, fieldName: String, value: Int) {
        runCatching {
            var clazz: Class<*>? = obj.javaClass
            while (clazz != null && clazz != Any::class.java) {
                try {
                    val field = clazz.getDeclaredField(fieldName)
                    field.isAccessible = true
                    field.setInt(obj, value)
                    return
                } catch (e: NoSuchFieldException) {
                    clazz = clazz.superclass
                }
            }
        }
    }

    private val stockGrid: Pair<Int, Int>?
        get() {
            val app = currentApplication() ?: return null
            val cr = app.contentResolver
            val cols = Settings.Global.getInt(cr, SETTING_ORIGINAL_COLUMNS, 4)
            val rows = Settings.Global.getInt(cr, SETTING_ORIGINAL_ROWS, 7)
            return if (cols > 0 && rows > 0) cols to rows else null
        }

    companion object {
        private const val WIDGET_VIEW_CLASS = "com.bbk.launcher2.launcherWidget.LauncherAppWidgetView"
        private const val FOLDER_CONTAINER_CLASS = "com.bbk.launcher2.ui.originfolder.ContainerForIconInFolder"
        private const val TAG_FOLDER_FIT = 0x7e5a0f17 // outside the launcher id space
        /**
         * Icon scale that keeps icons inside the denser cells (1 when the grid is stock or sparser).
         * Used by the launcher icon size hook so it combines with the user's icon size.
         */
        @Volatile private var fitCache = 1f
        @Volatile private var fitCachedAt = 0L

        /** Grid fit for a desktop icon; dock icons and icons inside folder previews keep their size. */
        fun iconFitFactor(view: android.view.View): Float {
            val container = view.parent as? android.view.ViewGroup ?: return 1f
            val cellLayout = container.parent ?: return 1f
            val name = cellLayout.javaClass.name
            if (name.contains("Hotseat") || !name.contains("CellLayout")) return 1f
            return iconFitFactor(view.context)
        }

        fun iconFitFactor(context: Context): Float {
            val now = android.os.SystemClock.uptimeMillis()
            if (fitCachedAt != 0L && now - fitCachedAt < 2000L) return fitCache
            return computeIconFit(context).also { fitCache = it; fitCachedAt = now }
        }

        private fun computeIconFit(context: Context): Float {
            val cr = context.contentResolver
            if (Settings.Global.getInt(cr, SETTING_CUSTOM_GRID_ENABLED, 0) != 1) return 1f
            val cols = Settings.Global.getInt(cr, SETTING_GRID_COLUMNS, 4).coerceAtLeast(1)
            val rows = Settings.Global.getInt(cr, SETTING_GRID_ROWS, 7).coerceAtLeast(1)
            val cols0 = Settings.Global.getInt(cr, SETTING_ORIGINAL_COLUMNS, 4).coerceAtLeast(1)
            val rows0 = Settings.Global.getInt(cr, SETTING_ORIGINAL_ROWS, 7).coerceAtLeast(1)
            return minOf(1f, cols0.toFloat() / cols, rows0.toFloat() / rows)
        }

        const val SETTING_CUSTOM_GRID_ENABLED = "originroottoolbox_launcher_custom_grid_enabled"
        const val SETTING_GRID_COLUMNS = "originroottoolbox_launcher_grid_columns"
        const val SETTING_GRID_ROWS = "originroottoolbox_launcher_grid_rows"
        const val SETTING_ORIGINAL_COLUMNS = "originroottoolbox_launcher_original_grid_columns"
        const val SETTING_ORIGINAL_ROWS = "originroottoolbox_launcher_original_grid_rows"
    }
}
