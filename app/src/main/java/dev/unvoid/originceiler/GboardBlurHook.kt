package dev.unvoid.originceiler

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

class GboardBlurHook {
    private val observedRoots = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val inputRoots = Collections.synchronizedMap(WeakHashMap<InputMethodService, WeakReference<View>>())
    private val backgrounds = Collections.synchronizedMap(WeakHashMap<InputMethodService, FrameLayout>())
    private var snapshot: Bitmap? = null

    fun install() {
        XposedBridge.hookAllMethods(
            InputMethodService::class.java,
            "onStartInput",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val service = param.thisObject as? InputMethodService ?: return
                    if (enabled(service) && snapshot == null) captureSnapshot(service)
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
                    if (inputView != null) inputRoots[service] = WeakReference(inputView)
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
                    removeBackground(param.thisObject as? InputMethodService ?: return)
                    snapshot = null
                }
            }
        )
    }

    private fun captureSnapshot(service: InputMethodService) {
        runCatching {
            service.contentResolver.openFileDescriptor(CAPTURE_URI, "r")?.use { descriptor ->
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
            }
        }.onSuccess { bitmap ->
            if (bitmap != null) snapshot = bitmap
        }.onFailure(XposedBridge::log)
    }

    private fun apply(service: InputMethodService, inputView: View?) {
        if (!enabled(service)) {
            removeBackground(service)
            return
        }
        val window = service.window?.window ?: return
        clearWindowBlur(window)
        val root = inputView ?: inputRoots[service]?.get() ?: return
        makeTransparent(root, root.width)
        root.post {
            if (!enabled(root)) return@post
            clearWindowBlur(window)
            makeTransparent(root, root.width)
            installBackground(service, window, resolveKeyboardHeight(root, window))
        }
        root.postDelayed({
            if (!enabled(root)) return@postDelayed
            clearWindowBlur(window)
            makeTransparent(root, root.width)
            installBackground(service, window, resolveKeyboardHeight(root, window))
        }, 180L)
        if (observedRoots.put(root, true) == null) {
            root.viewTreeObserver.addOnGlobalLayoutListener {
                if (root.isAttachedToWindow && enabled(root)) {
                    clearWindowBlur(window)
                    makeTransparent(root, root.width)
                    installBackground(service, window, resolveKeyboardHeight(root, window))
                }
            }
        }
    }

    private fun clearWindowBlur(window: Window) {
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setBackgroundBlurRadius(0)
        val attributes = window.attributes
        attributes.format = PixelFormat.TRANSLUCENT
        attributes.flags = attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) attributes.blurBehindRadius = 0
        window.attributes = attributes
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun installBackground(service: InputMethodService, window: Window, height: Int) {
        val bitmap = snapshot ?: return
        if (height <= 0) return
        val parent = window.decorView.findViewById<View>(android.R.id.content) as? FrameLayout
            ?: window.decorView as? FrameLayout
            ?: return
        val container = backgrounds[service] ?: createBackground(service).also {
            parent.addView(it, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM))
            backgrounds[service] = it
        }
        val params = container.layoutParams as FrameLayout.LayoutParams
        params.height = height
        params.gravity = Gravity.BOTTOM
        container.layoutParams = params
        val image = container.getChildAt(0) as ImageView
        val cropHeight = height.coerceAtMost(bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, 0, bitmap.height - cropHeight, bitmap.width, cropHeight)
        image.setImageBitmap(crop)
    }

    private fun createBackground(service: InputMethodService): FrameLayout {
        val image = ImageView(service).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(RenderEffect.createBlurEffect(34f, 34f, Shader.TileMode.CLAMP))
            }
        }
        val tint = View(service).apply {
            setBackgroundColor(Color.argb(76, 34, 35, 43))
        }
        return FrameLayout(service).apply {
            addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(tint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun removeBackground(service: InputMethodService) {
        val background = backgrounds.remove(service) ?: return
        (background.parent as? ViewGroup)?.removeView(background)
    }

    private fun resolveKeyboardHeight(root: View, window: Window): Int {
        val decor = window.decorView
        val screenHeight = decor.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        val screenWidth = decor.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
        if (root !== decor && root.height in (screenHeight * 0.24f).toInt()..(screenHeight * 0.72f).toInt()) return root.height
        var candidate = 0
        fun inspect(view: View, depth: Int) {
            if (depth > 9) return
            val location = IntArray(2)
            view.getLocationInWindow(location)
            val height = view.height
            val wide = view.width >= screenWidth * 0.82f
            val nearBottom = location[1] + height >= screenHeight * 0.9f
            val suitableHeight = height in (screenHeight * 0.24f).toInt()..(screenHeight * 0.72f).toInt()
            if (wide && nearBottom && suitableHeight && height > candidate) candidate = height
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) inspect(view.getChildAt(index), depth + 1)
            }
        }
        inspect(root, 0)
        return candidate.takeIf { it > 0 } ?: (screenHeight * 0.44f).toInt()
    }

    private fun makeTransparent(root: View, rootWidth: Int) {
        if (rootWidth <= 0) {
            root.setBackgroundColor(Color.TRANSPARENT)
            return
        }
        clearLargeSurfaces(root, rootWidth, 0)
    }

    private fun clearLargeSurfaces(view: View, rootWidth: Int, depth: Int) {
        if (depth == 0 || view.width >= rootWidth * 0.88f) view.setBackgroundColor(Color.TRANSPARENT)
        if (view !is ViewGroup || depth >= 9) return
        for (index in 0 until view.childCount) clearLargeSurfaces(view.getChildAt(index), rootWidth, depth + 1)
    }

    private fun enabled(view: View): Boolean = enabled(view.context)

    private fun enabled(context: android.content.Context): Boolean =
        Settings.Global.getInt(context.contentResolver, SETTING_ENABLED, 0) == 1

    companion object {
        const val SETTING_ENABLED = "originceiler_gboard_blur_background"
        private val CAPTURE_URI = Uri.parse("content://dev.unvoid.originceiler.gboardblur/capture")
    }
}
