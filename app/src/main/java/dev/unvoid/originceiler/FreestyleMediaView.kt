package dev.unvoid.originceiler

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The player in Freestyle's 4x1 / 4x2 / 4x4 sizes (vivo's own card is 2x2 only). Drawn in code
 * over vivo's card, whose glass plate stays underneath, so Liquid Glass and its touch light keep
 * working. Reads the active media session the way SystemUI's own media controls do.
 */
@SuppressLint("ViewConstructor")
internal class FreestyleMediaView(context: Context) : View(context) {
    var sizeClass: FsSize = FsSize.S4x2
        set(value) {
            if (field != value) { field = value; invalidate() }
        }
    var metrics: FreestyleControlCenter.Metrics? = null

    private val sessions = runCatching { context.getSystemService(MediaSessionManager::class.java) }.getOrNull()
    private var controller: MediaController? = null
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshArt()
        override fun onPlaybackStateChanged(state: PlaybackState?) = invalidate()
        override fun onSessionDestroyed() = pick()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick() }

    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 650, false) else Typeface.DEFAULT_BOLD
    }
    private val subtitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 160 }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 140 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private val rect = RectF()

    private var art: Bitmap? = null
    private var artIcon: Drawable? = null
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artMatrix = Matrix()

    // Layout of the current size, in px.
    private val artRect = RectF()
    private val textRect = RectF()
    private val trackRect = RectF()
    private val volumeRect = RectF()
    private class Button(val kind: Int) { var x = 0f; var y = 0f; var r = 0f; var press = 0f; var pressed = false }
    private val prev = Button(PREV)
    private val play = Button(PLAY)
    private val next = Button(NEXT)
    private var buttons = listOf(play, next)

    private var downX = 0f
    private var downY = 0f
    private var pressedButton: Button? = null
    private var seeking = false
    private var seekFraction = 0f
    private var adjustingVolume = false
    private var volumeFraction = 0f
    private var tapCandidate = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching { sessions?.addOnActiveSessionsChangedListener(sessionsListener, null, FreestyleControlCenter.main) }
            .onFailure { FreestyleControlCenter.log("media listener: $it") }
        pick()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(callback)
        controller = null
    }

    /** The playing session, else the most recent one. */
    private fun pick() {
        val list = runCatching { sessions?.getActiveSessions(null) }.getOrNull().orEmpty()
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, FreestyleControlCenter.main)
            refreshArt()
        }
        invalidate()
    }

    private fun refreshArt() {
        val md = controller?.metadata
        art = md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        artIcon = if (art == null) controller?.packageName?.let { pkg -> runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull() } else null
        invalidate()
    }

    private val playing get() = controller?.playbackState?.state.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING }

    private fun position(): Pair<Long, Long> {
        val c = controller ?: return 0L to 0L
        val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val state = c.playbackState ?: return 0L to duration
        var pos = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        if (duration > 0) pos = pos.coerceIn(0L, duration)
        return pos to duration
    }

    // ---------------------------------------------------------------- layout

    private fun layoutParts() {
        val w = width.toFloat()
        val h = height.toFloat()
        val c = (metrics?.cell ?: (h / max(1, sizeClass.h))).toFloat().coerceAtLeast(1f)
        val pad = c * 0.21f
        trackRect.setEmpty()
        volumeRect.setEmpty()
        when {
            sizeClass.h <= 1 -> {
                val a = h - pad * 1.5f
                artRect.set(pad * 0.75f, (h - a) / 2f, pad * 0.75f + a, (h + a) / 2f)
                val b = c * 0.19f
                next.r = b; next.x = w - pad - b; next.y = h / 2f
                play.r = b; play.x = next.x - b * 2.6f; play.y = h / 2f
                buttons = listOf(play, next)
                textRect.set(artRect.right + pad * 0.6f, 0f, play.x - b * 1.4f, h)
                title.textSize = c * 0.145f
                subtitle.textSize = c * 0.125f
            }
            sizeClass.h <= 2 -> {
                val a = c * 0.92f
                artRect.set(pad, pad, pad + a, pad + a)
                textRect.set(artRect.right + pad * 0.75f, artRect.top, w - pad, artRect.bottom)
                trackRect.set(pad, artRect.bottom + pad * 0.95f, w - pad, artRect.bottom + pad * 0.95f + c * 0.045f)
                val b = c * 0.2f
                val y = h - pad - b * 0.9f
                play.r = b * 1.15f; play.x = w / 2f; play.y = y
                prev.r = b; prev.x = w / 2f - b * 3.6f; prev.y = y
                next.r = b; next.x = w / 2f + b * 3.6f; next.y = y
                buttons = listOf(prev, play, next)
                title.textSize = c * 0.15f
                subtitle.textSize = c * 0.13f
            }
            else -> {
                val a = min(c * 1.75f, h * 0.42f)
                artRect.set(pad * 1.2f, pad * 1.2f, pad * 1.2f + a, pad * 1.2f + a)
                textRect.set(artRect.right + pad, artRect.top, w - pad * 1.2f, artRect.bottom)
                val volumeY = h - pad * 1.6f
                volumeRect.set(pad * 1.2f + c * 0.32f, volumeY - c * 0.03f, w - pad * 1.2f - c * 0.32f, volumeY + c * 0.03f)
                val b = c * 0.27f
                val buttonsY = (artRect.bottom + volumeY) / 2f + c * 0.18f
                trackRect.set(pad * 1.2f, buttonsY - b * 2.9f, w - pad * 1.2f, buttonsY - b * 2.9f + c * 0.05f)
                play.r = b * 1.15f; play.x = w / 2f; play.y = buttonsY
                prev.r = b; prev.x = w / 2f - b * 3.8f; prev.y = buttonsY
                next.r = b; next.x = w / 2f + b * 3.8f; next.y = buttonsY
                buttons = listOf(prev, play, next)
                title.textSize = c * 0.19f
                subtitle.textSize = c * 0.155f
            }
        }
        small.textSize = c * 0.1f
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        layoutParts()
        val c = controller
        val md = c?.metadata
        val name = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: c?.packageName?.let { pkg -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() }
        drawArt(canvas)
        drawTexts(canvas, name ?: FsText.notPlaying, artist ?: "")
        val (pos, duration) = position()
        if (!trackRect.isEmpty) drawTrack(canvas, if (seeking) seekFraction else if (duration > 0) pos.toFloat() / duration else 0f, pos, duration)
        if (!volumeRect.isEmpty) drawVolume(canvas)
        val enabled = c != null
        for (b in buttons) drawButton(canvas, b, enabled)
        var animating = false
        for (b in buttons) {
            val target = if (b.pressed) 1f else 0f
            if (abs(b.press - target) > 0.01f) {
                b.press += (target - b.press) * 0.32f
                animating = true
            } else b.press = target
        }
        if (animating) postInvalidateOnAnimation() else if (playing && !trackRect.isEmpty) postInvalidateDelayed(500)
    }

    private fun drawArt(canvas: Canvas) {
        val r = artRect.width() * 0.18f
        val bitmap = art
        if (bitmap != null && !bitmap.isRecycled) {
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = max(artRect.width() / bitmap.width, artRect.height() / bitmap.height)
            artMatrix.setScale(scale, scale)
            artMatrix.postTranslate(artRect.centerX() - bitmap.width * scale / 2f, artRect.centerY() - bitmap.height * scale / 2f)
            shader.setLocalMatrix(artMatrix)
            artPaint.shader = shader
            canvas.drawRoundRect(artRect, r, r, artPaint)
            return
        }
        fill.shader = null
        fill.color = 0x33FFFFFF
        canvas.drawRoundRect(artRect, r, r, fill)
        val icon = artIcon
        if (icon != null) {
            val s = artRect.width() * 0.56f
            icon.setBounds((artRect.centerX() - s / 2).toInt(), (artRect.centerY() - s / 2).toInt(), (artRect.centerX() + s / 2).toInt(), (artRect.centerY() + s / 2).toInt())
            icon.draw(canvas)
        } else {
            FsGlyphs.draw(canvas, context, "music_note", artRect.centerX(), artRect.centerY(), artRect.width() * 0.5f, 0xCCFFFFFF.toInt())
        }
    }

    private fun drawTexts(canvas: Canvas, name: String, artist: String) {
        val avail = textRect.width()
        if (avail <= 4f) return
        val t = TextUtils.ellipsize(name, title, avail, TextUtils.TruncateAt.END).toString()
        val s = TextUtils.ellipsize(artist, subtitle, avail, TextUtils.TruncateAt.END).toString()
        val gap = title.textSize * 0.22f
        val block = title.textSize + (if (s.isNotEmpty()) gap + subtitle.textSize else 0f)
        val top = textRect.centerY() - block / 2f
        canvas.drawText(t, textRect.left, top + title.textSize * 0.9f, title)
        if (s.isNotEmpty()) canvas.drawText(s, textRect.left, top + title.textSize + gap + subtitle.textSize * 0.9f, subtitle)
    }

    private fun drawTrack(canvas: Canvas, fraction: Float, pos: Long, duration: Long) {
        val r = trackRect.height() / 2f
        val grow = if (seeking) 1.7f else 1f
        rect.set(trackRect.left, trackRect.centerY() - r * grow, trackRect.right, trackRect.centerY() + r * grow)
        fill.color = 0x40FFFFFF
        canvas.drawRoundRect(rect, r * grow, r * grow, fill)
        rect.right = trackRect.left + trackRect.width() * fraction.coerceIn(0f, 1f)
        fill.color = 0xE6FFFFFF.toInt()
        if (rect.width() > 0f) canvas.drawRoundRect(rect, r * grow, r * grow, fill)
        val y = trackRect.bottom + small.textSize * 1.35f
        val shown = if (seeking && duration > 0) (duration * seekFraction).toLong() else pos
        canvas.drawText(format(shown), trackRect.left, y, small)
        if (duration > 0) {
            val rest = "-" + format(duration - shown)
            canvas.drawText(rest, trackRect.right - small.measureText(rest), y, small)
        }
    }

    private fun drawVolume(canvas: Canvas) {
        val info = controller?.playbackInfo
        val fraction = if (adjustingVolume) volumeFraction else if (info != null && info.maxVolume > 0) info.currentVolume.toFloat() / info.maxVolume else 0f
        val r = volumeRect.height() / 2f
        fill.color = 0x40FFFFFF
        canvas.drawRoundRect(volumeRect, r, r, fill)
        rect.set(volumeRect.left, volumeRect.top, volumeRect.left + volumeRect.width() * fraction.coerceIn(0f, 1f), volumeRect.bottom)
        fill.color = 0xE6FFFFFF.toInt()
        if (rect.width() > 0f) canvas.drawRoundRect(rect, r, r, fill)
        val s = volumeRect.height() * 7f
        FsGlyphs.draw(canvas, context, "volume_mute", volumeRect.left - s * 0.75f, volumeRect.centerY(), s, 0x99FFFFFF.toInt())
        FsGlyphs.draw(canvas, context, "volume_up", volumeRect.right + s * 0.75f, volumeRect.centerY(), s, 0x99FFFFFF.toInt())
    }

    private fun drawButton(canvas: Canvas, b: Button, enabled: Boolean) {
        if (b.press > 0.01f) {
            fill.color = Color.argb((b.press * 46).toInt(), 255, 255, 255)
            canvas.drawCircle(b.x, b.y, b.r * (1.25f + 0.25f * b.press), fill)
        }
        val s = b.r * (1f - 0.12f * b.press)
        glyph.alpha = if (enabled) 255 else 110
        path.rewind()
        when (b.kind) {
            PLAY -> if (playing) {
                val bw = s * 0.36f
                rect.set(b.x - s * 0.62f, b.y - s * 0.8f, b.x - s * 0.62f + bw, b.y + s * 0.8f)
                canvas.drawRoundRect(rect, bw * 0.3f, bw * 0.3f, glyph)
                rect.offset(s * 0.88f, 0f)
                canvas.drawRoundRect(rect, bw * 0.3f, bw * 0.3f, glyph)
            } else {
                triangle(b.x - s * 0.55f, b.y, s * 1.45f, s * 0.86f)
                canvas.drawPath(path, glyph)
            }
            PREV, NEXT -> {
                val dir = if (b.kind == NEXT) 1f else -1f
                val tw = s * 0.82f
                val th = s * 0.6f
                path.rewind()
                triangleDir(b.x - dir * tw * 0.95f, b.y, tw, th, dir)
                triangleDir(b.x - dir * tw * 0.05f, b.y, tw, th, dir)
                canvas.drawPath(path, glyph)
            }
        }
    }

    private fun triangle(left: Float, cy: Float, w: Float, halfH: Float) {
        path.moveTo(left, cy - halfH)
        path.lineTo(left + w, cy)
        path.lineTo(left, cy + halfH)
        path.close()
    }

    private fun triangleDir(start: Float, cy: Float, w: Float, halfH: Float, dir: Float) {
        path.moveTo(start, cy - halfH)
        path.lineTo(start + dir * w, cy)
        path.lineTo(start, cy + halfH)
        path.close()
    }

    private fun format(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE || alpha < 0.5f) return false
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                layoutParts()
                downX = x; downY = y
                tapCandidate = true
                pressedButton = buttons.firstOrNull { hypot(x - it.x, y - it.y) <= it.r * 1.9f }?.also { it.pressed = true }
                if (pressedButton == null && !trackRect.isEmpty && abs(y - trackRect.centerY()) < (metrics?.cell ?: 168) * 0.17f && x >= trackRect.left - 20 && x <= trackRect.right + 20) {
                    seeking = (position().second > 0)
                    if (seeking) { seekFraction = ((x - trackRect.left) / trackRect.width()).coerceIn(0f, 1f); parent?.requestDisallowInterceptTouchEvent(true) }
                }
                if (pressedButton == null && !seeking && !volumeRect.isEmpty && abs(y - volumeRect.centerY()) < (metrics?.cell ?: 168) * 0.2f) {
                    adjustingVolume = controller != null
                    if (adjustingVolume) { volumeFraction = ((x - volumeRect.left) / volumeRect.width()).coerceIn(0f, 1f); parent?.requestDisallowInterceptTouchEvent(true); applyVolume() }
                }
                if (pressedButton != null) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (seeking) {
                    seekFraction = ((x - trackRect.left) / trackRect.width()).coerceIn(0f, 1f)
                    invalidate()
                    return true
                }
                if (adjustingVolume) {
                    volumeFraction = ((x - volumeRect.left) / volumeRect.width()).coerceIn(0f, 1f)
                    applyVolume()
                    invalidate()
                    return true
                }
                if (hypot(x - downX, y - downY) > slop) {
                    tapCandidate = false
                    pressedButton?.pressed = false
                    pressedButton = null
                    invalidate()
                    // A scroll of the control center: let the scroller take it.
                    return false
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val button = pressedButton
                pressedButton = null
                button?.pressed = false
                if (seeking) {
                    seeking = false
                    val duration = position().second
                    if (duration > 0) controller?.transportControls?.seekTo((duration * seekFraction).toLong())
                } else if (adjustingVolume) {
                    adjustingVolume = false
                } else if (button != null && tapCandidate) {
                    press(button)
                } else if (tapCandidate) {
                    openApp()
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedButton?.pressed = false
                pressedButton = null
                seeking = false
                adjustingVolume = false
                invalidate()
            }
        }
        return true
    }

    private fun applyVolume() {
        val info = controller?.playbackInfo ?: return
        if (info.maxVolume <= 0) return
        runCatching { controller?.setVolumeTo((volumeFraction * info.maxVolume).toInt(), 0) }
    }

    private fun press(b: Button) {
        val t = controller?.transportControls ?: return
        when (b.kind) {
            PLAY -> if (playing) t.pause() else t.play()
            PREV -> t.skipToPrevious()
            NEXT -> t.skipToNext()
        }
    }

    private fun openApp() {
        val c = controller ?: return
        val sent = runCatching { c.sessionActivity?.send(); c.sessionActivity != null }.getOrDefault(false)
        if (!sent) {
            val launch = context.packageManager.getLaunchIntentForPackage(c.packageName) ?: return
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launch) }
        }
        runCatching {
            val sbm = context.getSystemService("statusbar")
            sbm?.javaClass?.getMethod("collapsePanels")?.invoke(sbm)
        }
    }

    private companion object {
        const val PREV = 0
        const val PLAY = 1
        const val NEXT = 2
    }
}
