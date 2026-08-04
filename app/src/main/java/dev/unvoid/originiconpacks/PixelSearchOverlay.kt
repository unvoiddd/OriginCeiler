package com.autonavi.minimap

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.PathParser
import androidx.core.graphics.ColorUtils

class PixelSearchOverlay(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var glyphColor = Color.WHITE

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val outer = h / 2f
        val tint = appliedAospTint()
        glyphColor = tint?.let { ColorUtils.blendARGB(Color.WHITE, it, 0.26f) } ?: Color.WHITE
        paint.color = tint?.let { ColorUtils.blendARGB(Color.rgb(68, 67, 70), it, 0.16f) } ?: Color.rgb(68, 67, 70)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), h, outer, outer, paint)
        val inset = 8f * density
        bounds.set(inset, inset, width - h - inset, h - inset)
        paint.color = tint?.let { ColorUtils.blendARGB(Color.rgb(35, 34, 37), it, 0.16f) } ?: Color.rgb(35, 34, 37)
        canvas.drawRoundRect(bounds, bounds.height() / 2f, bounds.height() / 2f, paint)
        val actionInset = 5f * density
        bounds.set(width - h + actionInset, actionInset, width - actionInset, h - actionInset)
        canvas.drawOval(bounds, paint)
        drawGoogleMark(canvas, 28f * density, h / 2f)
        drawMicrophone(canvas, width - h * 2.38f, h / 2f)
        drawLens(canvas, width - h * 1.43f - 5f * density, h / 2f)
        drawAiMode(canvas, width - h / 2f, h / 2f)
    }

    private fun drawGoogleMark(canvas: Canvas, x: Float, y: Float) {
        bounds.set(x - 14f * density, y - 14f * density, x + 14f * density, y + 14f * density)
        drawVector(
            canvas,
            "M21.35 11.1H12.18v2.98h5.28c-.49 2.08-2.26 3.63-5.28 3.63-3.22 0-5.83-2.6-5.83-5.81s2.61-5.81 5.83-5.81c1.45 0 2.76.53 3.76 1.41l2.05-2.05C17.73 3.28 15.12 2.29 12.18 2.29 6.89 2.29 2.6 6.58 2.6 11.87s4.29 9.58 9.58 9.58c5.53 0 9.17-3.89 9.17-9.37 0-.63-.07-1.24-.18-1.8z",
            bounds,
            24f,
            glyphColor
        )
    }

    private fun drawMicrophone(canvas: Canvas, x: Float, y: Float) {
        bounds.set(x - 14f * density, y - 18f * density, x + 14f * density, y + 18f * density)
        drawVector(canvas, "M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.42 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z", bounds, 24f, glyphColor)
    }

    private fun drawLens(canvas: Canvas, x: Float, y: Float) {
        bounds.set(x - 16f * density, y - 16f * density, x + 16f * density, y + 16f * density)
        drawVector(canvas, "M112,24l-32,0L68,40H56.8C38.69,40,24,54.69,24,72.8V92h16V74c0-9.71,7.2-18,16-18h80L112,24z M24,135.2c0,18.11,14.69,32.8,32.8,32.8H96v-16l-40.1-0.1c-8.8,0-15.9-8.19-15.9-17.9v-18H24V135.2z M168,72.8c0-18.11-14.69-32.8-32.8-32.8H116l20,16c8.8,0,16,8.29,16,18v30h16V72.8z", bounds, 192f, glyphColor)
        paint.apply { color = glyphColor; style = Paint.Style.FILL }
        canvas.drawCircle(x, y + density, 4.2f * density, paint)
    }

    private fun drawVector(canvas: Canvas, data: String, target: RectF, viewport: Float, color: Int) {
        val path = PathParser.createPathFromPathData(data) ?: return
        val scale = minOf(target.width(), target.height()) / viewport
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(target.centerX() - viewport * scale / 2f, target.centerY() - viewport * scale / 2f)
        }
        path.transform(matrix)
        paint.apply { this.color = color; style = Paint.Style.FILL }
        canvas.drawPath(path, paint)
    }

    private fun drawAiMode(canvas: Canvas, x: Float, y: Float) {
        bounds.set(x - 13f * density, y - 13f * density, x + 13f * density, y + 13f * density)
        drawVector(
            canvas,
            "M15.65 11.58c.18-.5.27-1.03.31-1.58h-2c-.1 1.03-.51 1.93-1.27 2.69-.88.87-1.94 1.31-3.19 1.31C7.03 14 5 12.07 5 9.5 5 7.03 6.93 5 9.5 5c.46 0 .89.08 1.3.2l1.56-1.56C11.5 3.22 10.55 3 9.5 3 5.85 3 3 5.85 3 9.5S5.85 16 9.5 16c.56 0 2.26-.06 3.8-1.3l6.3 6.3 1.4-1.4-6.3-6.3c.4-.5.72-1.08.95-1.72z M17.5 12c0-3.04 2.46-5.5 5.5-5.5-3.04 0-5.5-2.46-5.5-5.5 0 3.04-2.46 5.5-5.5 5.5 3.04 0 5.5 2.46 5.5 5.5z",
            bounds,
            24f,
            glyphColor
        )
    }

    private fun appliedAospTint(): Int? {
        val unset = Int.MIN_VALUE
        return Settings.Global.getInt(context.contentResolver, "originicons_pixel_search_aosp_tint", unset)
            .takeUnless { it == unset }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val zone = width / 4f
        when {
            event.x < width - zone * 1.9f -> openSearch()
            event.x < width - zone * 1.18f -> openVoiceSearch()
            event.x < width - zone * 0.58f -> openLens()
            else -> openAiMode()
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun openSearch() {
        launchFirst(
            Intent(Intent.ACTION_WEB_SEARCH).setPackage("com.google.android.googlequicksearchbox"),
            Intent(Intent.ACTION_VIEW, Uri.parse("googlequicksearchbox://search")),
            Intent(Intent.ACTION_MAIN).setComponent(
                ComponentName("com.google.android.googlequicksearchbox", "com.google.android.apps.gsa.searchnow.SearchNowActivity")
            )
        )
    }

    private fun openVoiceSearch() {
        launchFirst(
            Intent(Intent.ACTION_MAIN).setComponent(
                ComponentName("com.google.android.googlequicksearchbox", "com.google.android.googlequicksearchbox.VoiceSearchActivity")
            ),
            Intent(Intent.ACTION_VIEW, Uri.parse("googlequicksearchbox://voice-search")),
            Intent(Intent.ACTION_VIEW, Uri.parse("googleapp://voice-search")),
            Intent("android.speech.action.VOICE_SEARCH_HANDS_FREE").setPackage("com.google.android.googlequicksearchbox")
        )
    }

    private fun openLens() {
        launchFirst(
            Intent(Intent.ACTION_MAIN).setComponent(
                ComponentName("com.google.ar.lens", "com.google.vr.apps.ornament.app.lens.LensLauncherActivity")
            ),
            Intent(Intent.ACTION_VIEW, Uri.parse("googleapp://lens")).setPackage("com.google.android.googlequicksearchbox"),
            Intent(Intent.ACTION_VIEW, Uri.parse("google://lens")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://lens.google/trylens"))
        )
    }

    private fun openAiMode() {
        val direct = Intent(Intent.ACTION_VIEW, Uri.parse("googlequicksearchbox://aimode")).apply {
            component = ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.apps.search.googleapp.homescreen.deeplinks.HomescreenDeeplink"
            )
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("googlequicksearchbox://search?udm=50")).apply {
            component = ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.apps.search.googleapp.homescreen.deeplinks.HomescreenDeeplink"
            )
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(direct) }
            .recoverCatching { context.startActivity(fallback) }
    }

    private fun launchFirst(vararg intents: Intent) {
        intents.firstOrNull { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
}
