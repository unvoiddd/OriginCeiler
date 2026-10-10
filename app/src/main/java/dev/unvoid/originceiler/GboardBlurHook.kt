package dev.unvoid.originceiler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Frosted blur behind Gboard.
 *
 * 1. Takes a screencap of the screen behind the keyboard off the UI thread.
 * 2. Pre-blurs the snapshot with fast stack blur (downscaled 3x) for zero-allocation,
 *    smooth 120 FPS rendering.
 * 3. Adds [GboardBlurBackgroundView] at index 0 of the window DecorView, which clips
 *    strictly to the keyboard bounds [topInWindow..height]: the screen above the keyboard
 *    is 100% untouched and live.
 * 4. Strips Gboard's native background on the window, root, inputFrame, and all container
 *    ViewGroups/background ImageViews inside [inputView], while keeping key caps and symbols.
 * 5. Hooks [View.setBackgroundDrawable], [View.setBackgroundColor], and [ImageView.setImageDrawable]
 *    to prevent Gboard's theme engine from restoring opaque backgrounds.
 */
class GboardBlurHook {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inputRoots = Collections.synchronizedMap(WeakHashMap<InputMethodService, WeakReference<View>>())
    private val blurViews = Collections.synchronizedMap(WeakHashMap<InputMethodService, GboardBlurBackgroundView>())
    private val observedInputViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    @Volatile
    private var blurredSnapshot: Bitmap? = null

    @Volatile
    private var snapshotStale = true

    @Volatile
    private var captureInFlight = false

    fun install() {
        XposedBridge.hookAllMethods(
            InputMethodService::class.java,
            "onStartInput",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val service = param.thisObject as? InputMethodService ?: return
                    if (enabled(service)) requestCapture(service)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val service = param.thisObject as? InputMethodService ?: return
                    apply(service, inputRoots[service]?.get())
                }
            }
        )

        XposedBridge.hookAllMethods(
            InputMethodService::class.java,
            "setInputView",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val service = param.thisObject as? InputMethodService ?: return
                    val inputView = param.args.firstOrNull() as? View
                    if (inputView != null) {
                        inputRoots[service] = WeakReference(inputView)
                    }
                    apply(service, inputView)
                }
            }
        )

        listOf("onWindowShown", "onStartInputView").forEach { methodName ->
            XposedBridge.hookAllMethods(
                InputMethodService::class.java,
                methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val service = param.thisObject as? InputMethodService ?: return
                        apply(service, inputRoots[service]?.get())
                    }
                }
            )
        }

        XposedBridge.hookAllMethods(
            InputMethodService::class.java,
            "onWindowHidden",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val service = param.thisObject as? InputMethodService ?: return
                    blurViews[service]?.visibility = View.GONE
                    snapshotStale = true
                }
            }
        )

        XposedBridge.hookAllMethods(
            InputMethodService::class.java,
            "onDestroy",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val service = param.thisObject as? InputMethodService ?: return
                    removeBackground(service)
                    inputRoots.remove(service)
                }
            }
        )

        // Intercept background assignments on Gboard containers to prevent Gboard's theme engine
        // from re-applying opaque panels
        XposedBridge.hookAllMethods(
            View::class.java,
            "setBackgroundDrawable",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val drawable = param.args.firstOrNull() as? Drawable ?: return
                    if (!enabled(view.context)) return
                    if (isKeyboardContainerOrBackground(view)) {
                        param.args[0] = null
                    }
                }
            }
        )

        XposedBridge.hookAllMethods(
            View::class.java,
            "setBackgroundColor",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val color = param.args.firstOrNull() as? Int ?: return
                    if (color == Color.TRANSPARENT) return
                    if (!enabled(view.context)) return
                    if (isKeyboardContainerOrBackground(view)) {
                        param.args[0] = Color.TRANSPARENT
                    }
                }
            }
        )

        XposedBridge.hookAllMethods(
            ImageView::class.java,
            "setImageDrawable",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val imageView = param.thisObject as? ImageView ?: return
                    if (param.args.firstOrNull() == null) return
                    if (!enabled(imageView.context)) return
                    if (isKeyboardBackgroundImageView(imageView)) {
                        param.args[0] = null
                    }
                }
            }
        )
    }

    private fun requestCapture(service: InputMethodService, force: Boolean = false) {
        if (captureInFlight) return
        if (!force && blurredSnapshot != null && !snapshotStale) return
        captureInFlight = true
        Thread({
            var resultBitmap: Bitmap? = null
            try {
                service.contentResolver.openFileDescriptor(CAPTURE_URI, "r")?.use { descriptor ->
                    val raw = BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
                    if (raw != null) {
                        // Downscale 3x: 9x fewer pixels, ultra-fast blur, silky frosted texture, 1.5MB RAM
                        val scale = 3
                        val downscaledW = (raw.width / scale).coerceAtLeast(1)
                        val downscaledH = (raw.height / scale).coerceAtLeast(1)
                        val downscaled = Bitmap.createScaledBitmap(raw, downscaledW, downscaledH, true)
                        if (raw !== downscaled) raw.recycle()
                        resultBitmap = fastStackBlur(downscaled, 22)
                    }
                }
            } catch (error: Throwable) {
                XposedBridge.log("OriginRootToolbox Gboard capture failed: ${error.message}")
            }
            if (resultBitmap != null) {
                blurredSnapshot = resultBitmap
                snapshotStale = false
                mainHandler.post {
                    blurViews[service]?.let { blurView ->
                        blurView.blurredBitmap = resultBitmap
                        blurView.invalidate()
                    }
                }
            }
            captureInFlight = false
        }, "GboardBlurCapture").apply { isDaemon = true }.start()
    }

    private fun apply(service: InputMethodService, inputView: View?) {
        if (!enabled(service)) {
            removeBackground(service)
            return
        }
        val window = service.window?.window ?: return
        runOnUiThread {
            if (!enabled(service)) {
                removeBackground(service)
                return@runOnUiThread
            }
            clearWindowBackground(window)
            val currentInput = inputView
                ?: inputRoots[service]?.get()
                ?: findInternalView(window.decorView, "inputFrame")
                ?: return@runOnUiThread
            ensureBlurView(service, window, currentInput)
            stripGboardBackgrounds(currentInput)
            observeInputView(service, currentInput)
        }
        // One deferred pass to clean views inflated after the initial layout pass
        mainHandler.postDelayed({
            runCatching {
                if (!enabled(service)) return@postDelayed
                val currentInput = inputRoots[service]?.get()
                    ?: inputView
                    ?: findInternalView(window.decorView, "inputFrame")
                    ?: return@postDelayed
                clearWindowBackground(window)
                stripGboardBackgrounds(currentInput)
                blurViews[service]?.invalidate()
            }
        }, 120L)
    }

    private fun observeInputView(service: InputMethodService, inputView: View) {
        if (observedInputViews.put(inputView, true) != null) return
        val listener = ViewTreeObserver.OnPreDrawListener {
            runCatching {
                if (!inputView.isAttachedToWindow || !enabled(inputView)) return@runCatching true
                stripGboardBackgrounds(inputView)
                blurViews[service]?.let { blurView ->
                    if (blurView.visibility != View.VISIBLE) blurView.visibility = View.VISIBLE
                    blurView.invalidate()
                }
            }
            true
        }
        inputView.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun clearWindowBackground(window: Window) {
        runCatching { window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }
        runCatching { window.setDimAmount(0f) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { window.setBackgroundBlurRadius(0) }
        }
        runCatching {
            val attributes = window.attributes
            attributes.format = PixelFormat.TRANSLUCENT
            attributes.flags = attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) attributes.blurBehindRadius = 0
            window.attributes = attributes
        }
        runCatching { window.decorView.setBackgroundColor(Color.TRANSPARENT) }
        runCatching { window.decorView.background = null }
        runCatching {
            listOf("inputArea", "inputFrame", "extractArea").forEach { name ->
                findInternalView(window.decorView, name)?.let { v ->
                    v.background = null
                    v.setBackgroundColor(Color.TRANSPARENT)
                }
            }
        }
    }

    private fun findInternalView(root: View?, name: String): View? {
        if (root == null) return null
        val id = root.resources.getIdentifier(name, "id", "android")
        return if (id != 0) root.findViewById(id) else null
    }

    private fun ensureBlurView(service: InputMethodService, window: Window, inputView: View) {
        val decor = window.decorView as? ViewGroup ?: return
        var blurView = blurViews[service]
        if (blurView == null || blurView.parent !== decor) {
            (blurView?.parent as? ViewGroup)?.removeView(blurView)
            blurView = GboardBlurBackgroundView(service).apply {
                this.inputViewRef = WeakReference(inputView)
                this.blurredBitmap = this@GboardBlurHook.blurredSnapshot
            }
            decor.addView(
                blurView,
                0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            blurViews[service] = blurView
        } else {
            blurView.inputViewRef = WeakReference(inputView)
            blurView.blurredBitmap = blurredSnapshot
        }
        if (blurView.visibility != View.VISIBLE) blurView.visibility = View.VISIBLE
        blurView.invalidate()

        if (blurredSnapshot == null) {
            requestCapture(service)
        }
    }

    private fun removeBackground(service: InputMethodService) {
        val blurView = blurViews.remove(service) ?: return
        (blurView.parent as? ViewGroup)?.removeView(blurView)
        blurView.blurredBitmap = null
    }

    /**
     * Recursively strip opaque background panels from Gboard containers,
     * while preserving individual key caps, ripples, spacebar, and suggestion text.
     */
    private fun stripGboardBackgrounds(view: View) {
        val displayMetrics = view.resources.displayMetrics
        val kbWidth = view.width.takeIf { it > 0 } ?: displayMetrics.widthPixels
        val kbHeight = view.height.takeIf { it > 0 } ?: (displayMetrics.heightPixels * 0.42f).toInt()
        cleanViewHierarchy(view, kbWidth, kbHeight, 0)

        // Clear backgrounds on container parents up to decorView
        var parent = view.parent
        while (parent is ViewGroup) {
            val bg = parent.background
            if (bg != null && (bg !is ColorDrawable || bg.color != Color.TRANSPARENT)) {
                parent.background = null
                parent.setBackgroundColor(Color.TRANSPARENT)
            }
            if (parent === view.rootView) break
            parent = parent.parent
        }
    }

    private fun cleanViewHierarchy(view: View, kbWidth: Int, kbHeight: Int, depth: Int) {
        if (depth > 20) return
        val w = view.width
        val h = view.height
        val lp = view.layoutParams
        val isMatchParentW = lp?.width == ViewGroup.LayoutParams.MATCH_PARENT
        val isMatchParentH = lp?.height == ViewGroup.LayoutParams.MATCH_PARENT
        val isWide = w >= (kbWidth * 0.60f).toInt() || (w == 0 && isMatchParentW)
        val isTall = h >= (kbHeight * 0.35f).toInt() || (h == 0 && isMatchParentH)
        val isHuge = isWide && isTall

        if (view is ImageView) {
            if (isHuge || (isWide && h >= (kbHeight * 0.20f).toInt())) {
                view.setImageDrawable(null)
                view.background = null
                view.visibility = View.INVISIBLE
            }
        } else if (view is ViewGroup) {
            if (isWide || isHuge) {
                if (view.background != null) view.background = null
                view.setBackgroundColor(Color.TRANSPARENT)
            }
        } else {
            if (isHuge) {
                view.background = null
                view.setBackgroundColor(Color.TRANSPARENT)
                view.visibility = View.INVISIBLE
            }
        }

        if (view !is ViewGroup) return
        for (i in 0 until view.childCount) {
            cleanViewHierarchy(view.getChildAt(i), kbWidth, kbHeight, depth + 1)
        }
    }

    private fun isKeyboardContainerOrBackground(view: View): Boolean {
        val name = view.javaClass.name
        if (name.contains("Key", ignoreCase = false) && !name.contains("Keyboard", ignoreCase = true)) {
            // Individual key (e.g. SoftKeyView, KeyView) - keep background
            return false
        }
        val displayMetrics = view.resources.displayMetrics
        val w = view.width
        val h = view.height
        val lp = view.layoutParams
        val isMatchParentW = lp?.width == ViewGroup.LayoutParams.MATCH_PARENT
        val isMatchParentH = lp?.height == ViewGroup.LayoutParams.MATCH_PARENT
        val isWide = w >= (displayMetrics.widthPixels * 0.60f).toInt() || (w == 0 && isMatchParentW)
        val isTall = h >= (displayMetrics.heightPixels * 0.15f).toInt() || (h == 0 && isMatchParentH)
        return isWide && (view is ViewGroup || isTall)
    }

    private fun isKeyboardBackgroundImageView(view: ImageView): Boolean {
        val displayMetrics = view.resources.displayMetrics
        val w = view.width
        val h = view.height
        val lp = view.layoutParams
        val isMatchParentW = lp?.width == ViewGroup.LayoutParams.MATCH_PARENT
        val isMatchParentH = lp?.height == ViewGroup.LayoutParams.MATCH_PARENT
        return (w >= (displayMetrics.widthPixels * 0.60f).toInt() || isMatchParentW) &&
            (h >= (displayMetrics.heightPixels * 0.15f).toInt() || isMatchParentH)
    }

    private fun runOnUiThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private fun enabled(view: View): Boolean = enabled(view.context)

    private fun enabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, SETTING_ENABLED, 0) == 1

    /**
     * Custom view that renders the frosted blur background.
     * Crucially clips canvas strictly to [inputViewRef]'s runtime bounds so that
     * the screen above the keyboard is 100% untouched and live.
     */
    private class GboardBlurBackgroundView(context: Context) : View(context) {
        var blurredBitmap: Bitmap? = null
        var inputViewRef: WeakReference<View>? = null

        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val tintPaint = Paint().apply {
            color = Color.argb(80, 22, 23, 29) // Sleek frosted dark tint
        }
        private val topBorderPaint = Paint().apply {
            color = Color.argb(48, 255, 255, 255) // Subtle 1px frosted glass edge
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        private val fallbackPaint = Paint().apply {
            color = Color.argb(175, 26, 27, 34) // Translucent dark fallback before screencap arrives
        }

        private val srcRect = Rect()
        private val dstRect = Rect()

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            var input = inputViewRef?.get()
            if (input == null || !input.isAttachedToWindow || input.visibility != VISIBLE) {
                val parentView = parent as? View
                val resId = parentView?.resources?.getIdentifier("inputFrame", "id", "android") ?: 0
                if (resId != 0) input = parentView?.findViewById(resId)
            }
            if (input == null || !input.isAttachedToWindow || input.visibility != VISIBLE) return

            val inputLoc = IntArray(2)
            input.getLocationOnScreen(inputLoc)
            val windowLoc = IntArray(2)
            getLocationOnScreen(windowLoc)

            if (inputLoc[1] <= 0 || input.height <= 0) return

            val topInWindow = (inputLoc[1] - windowLoc[1]).coerceAtLeast(0)
            val bottomInWindow = height
            if (topInWindow >= bottomInWindow) return

            // Guard: a keyboard docked to the bottom never begins in the top 20% of the window.
            // If topInWindow is near 0, the view is not yet positioned properly; abort to prevent blurring the app.
            val minReasonableTop = (height * 0.20f).toInt()
            if (topInWindow < minReasonableTop) return

            val leftInWindow = (inputLoc[0] - windowLoc[0]).coerceIn(0, width)
            val rightInWindow = if (input.width > 0) (leftInWindow + input.width).coerceIn(0, width) else width
            if (leftInWindow >= rightInWindow) return

            canvas.save()
            // Strictly clip to the keyboard bounds!
            // Everything outside [leftInWindow..rightInWindow, topInWindow..bottomInWindow] is 100% untouched.
            canvas.clipRect(leftInWindow, topInWindow, rightInWindow, bottomInWindow)

            val bmp = blurredBitmap
            if (bmp != null && !bmp.isRecycled) {
                val displayMetrics = resources.displayMetrics
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                val realMetrics = android.util.DisplayMetrics()
                wm?.defaultDisplay?.getRealMetrics(realMetrics)
                val screenW = if (realMetrics.widthPixels > 0) realMetrics.widthPixels else displayMetrics.widthPixels
                val screenH = if (realMetrics.heightPixels > 0) realMetrics.heightPixels else displayMetrics.heightPixels

                srcRect.set(0, 0, bmp.width, bmp.height)
                dstRect.set(-windowLoc[0], -windowLoc[1], -windowLoc[0] + screenW, -windowLoc[1] + screenH)
                canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
            } else {
                canvas.drawRect(leftInWindow.toFloat(), topInWindow.toFloat(), rightInWindow.toFloat(), bottomInWindow.toFloat(), fallbackPaint)
            }

            // Draw frosted glass dark tint
            canvas.drawRect(leftInWindow.toFloat(), topInWindow.toFloat(), rightInWindow.toFloat(), bottomInWindow.toFloat(), tintPaint)

            // Draw subtle top border highlight
            canvas.drawLine(
                leftInWindow.toFloat(),
                topInWindow.toFloat(),
                rightInWindow.toFloat(),
                topInWindow.toFloat(),
                topBorderPaint
            )

            canvas.restore()
        }
    }

    companion object {
        const val SETTING_ENABLED = "originceiler_gboard_blur_background"
        private val CAPTURE_URI = Uri.parse("content://dev.unvoid.originceiler.gboardblur/capture")

        /**
         * Fast stack blur algorithm (O(1) per pixel with radius).
         */
        fun fastStackBlur(src: Bitmap, radius: Int): Bitmap {
            val w = src.width
            val h = src.height
            val out = src.copy(Bitmap.Config.ARGB_8888, true)
            val pix = IntArray(w * h)
            out.getPixels(pix, 0, w, 0, 0, w, h)

            val wm = w - 1
            val hm = h - 1
            val wh = w * h
            val div = radius + radius + 1

            val r = IntArray(wh)
            val g = IntArray(wh)
            val b = IntArray(wh)
            var rsum: Int; var gsum: Int; var bsum: Int
            var p: Int; var yp: Int; var yi: Int; var yw: Int
            val vmin = IntArray(Math.max(w, h))

            var divsum = (div + 1) shr 1
            divsum *= divsum
            val dv = IntArray(256 * divsum)
            for (idx in 0 until 256 * divsum) {
                dv[idx] = idx / divsum
            }

            yw = 0
            yi = 0

            val stack = Array(div) { IntArray(3) }
            var stackpointer: Int
            var stackstart: Int
            var sir: IntArray
            var rbs: Int
            val r1 = radius + 1
            var routsum: Int; var goutsum: Int; var boutsum: Int
            var rinsum: Int; var ginsum: Int; var binsum: Int

            for (yIdx in 0 until h) {
                rinsum = 0; ginsum = 0; binsum = 0
                routsum = 0; goutsum = 0; boutsum = 0
                rsum = 0; gsum = 0; bsum = 0
                for (idx in -radius..radius) {
                    p = pix[yi + Math.min(wm, Math.max(idx, 0))]
                    sir = stack[idx + radius]
                    sir[0] = (p and 0xff0000) shr 16
                    sir[1] = (p and 0x00ff00) shr 8
                    sir[2] = (p and 0x0000ff)
                    rbs = r1 - Math.abs(idx)
                    rsum += sir[0] * rbs
                    gsum += sir[1] * rbs
                    bsum += sir[2] * rbs
                    if (idx > 0) {
                        rinsum += sir[0]
                        ginsum += sir[1]
                        binsum += sir[2]
                    } else {
                        routsum += sir[0]
                        goutsum += sir[1]
                        boutsum += sir[2]
                    }
                }
                stackpointer = radius

                for (xIdx in 0 until w) {
                    r[yi] = dv[rsum]
                    g[yi] = dv[gsum]
                    b[yi] = dv[bsum]

                    rsum -= routsum
                    gsum -= goutsum
                    bsum -= boutsum

                    stackstart = stackpointer - radius + div
                    sir = stack[stackstart % div]

                    routsum -= sir[0]
                    goutsum -= sir[1]
                    boutsum -= sir[2]

                    if (yIdx == 0) {
                        vmin[xIdx] = Math.min(xIdx + radius + 1, wm)
                    }
                    p = pix[yw + vmin[xIdx]]

                    sir[0] = (p and 0xff0000) shr 16
                    sir[1] = (p and 0x00ff00) shr 8
                    sir[2] = (p and 0x0000ff)

                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]

                    rsum += rinsum
                    gsum += ginsum
                    bsum += binsum

                    stackpointer = (stackpointer + 1) % div
                    sir = stack[stackpointer % div]

                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]

                    rinsum -= sir[0]
                    ginsum -= sir[1]
                    binsum -= sir[2]

                    yi++
                }
                yw += w
            }

            for (xIdx in 0 until w) {
                rinsum = 0; ginsum = 0; binsum = 0
                routsum = 0; goutsum = 0; boutsum = 0
                rsum = 0; gsum = 0; bsum = 0
                yp = -radius * w
                for (idx in -radius..radius) {
                    yi = Math.max(0, yp) + xIdx
                    sir = stack[idx + radius]
                    sir[0] = r[yi]
                    sir[1] = g[yi]
                    sir[2] = b[yi]
                    rbs = r1 - Math.abs(idx)
                    rsum += r[yi] * rbs
                    gsum += g[yi] * rbs
                    bsum += b[yi] * rbs
                    if (idx > 0) {
                        rinsum += sir[0]
                        ginsum += sir[1]
                        binsum += sir[2]
                    } else {
                        routsum += sir[0]
                        goutsum += sir[1]
                        boutsum += sir[2]
                    }
                    if (idx < hm) {
                        yp += w
                    }
                }
                yi = xIdx
                stackpointer = radius
                for (yIdx in 0 until h) {
                    pix[yi] = (0xff000000.toInt()) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                    rsum -= routsum
                    gsum -= goutsum
                    bsum -= boutsum

                    stackstart = stackpointer - radius + div
                    sir = stack[stackstart % div]

                    routsum -= sir[0]
                    goutsum -= sir[1]
                    boutsum -= sir[2]

                    if (xIdx == 0) {
                        vmin[yIdx] = Math.min(yIdx + r1, hm) * w
                    }
                    p = xIdx + vmin[yIdx]

                    sir[0] = r[p]
                    sir[1] = g[p]
                    sir[2] = b[p]

                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]

                    rsum += rinsum
                    gsum += ginsum
                    bsum += binsum

                    stackpointer = (stackpointer + 1) % div
                    sir = stack[stackpointer]

                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]

                    rinsum -= sir[0]
                    ginsum -= sir[1]
                    binsum -= sir[2]

                    yi += w
                }
            }

            out.setPixels(pix, 0, w, 0, 0, w, h)
            return out
        }
    }
}
