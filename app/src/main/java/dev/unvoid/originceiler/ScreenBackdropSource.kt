package dev.unvoid.originceiler

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.SystemClock
import de.robv.android.xposed.XposedBridge

object ScreenBackdropSource {
    @Volatile
    private var cachedBackdrop: Bitmap? = null

    @Volatile
    private var lastCaptureTime: Long = 0L

    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var liveWallpaper = false

    private val lock = Any()
    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    @Volatile
    private var wallpaperId = -1

    /**
     * The home wallpaper scaled to the screen, or null for a live wallpaper. The bitmap is kept
     * until the wallpaper itself changes and is never recycled here: glass surfaces keep it in
     * their recorded display lists, and recycling it made every other folder draw empty glass.
     */
    fun getBackdrop(context: Context): Bitmap? {
        val now = SystemClock.uptimeMillis()
        val current = cachedBackdrop
        if (now - lastCaptureTime < RECHECK_MS) return current?.takeIf { !it.isRecycled }

        synchronized(lock) {
            val fresh = cachedBackdrop
            if (now - lastCaptureTime < RECHECK_MS) return fresh?.takeIf { !it.isRecycled }
            lastCaptureTime = now

            registerReceiverIfNeeded(context)

            val wm = runCatching { WallpaperManager.getInstance(context) }.getOrNull() ?: return fresh
            liveWallpaper = runCatching { wm.wallpaperInfo != null }.getOrDefault(false)
            if (liveWallpaper) {
                // A live wallpaper renders on its own surface; WallpaperManager would hand back an
                // unrelated built-in still. vivo's own live wallpapers (3D photo, etc.) leave a
                // screen-sized still of themselves in /data/bbkcore: use that, else no backdrop.
                val still = vivoLiveStill(context, lock = false)
                cachedBackdrop = still
                wallpaperId = -1
                return still
            }
            // Cheap check first: decode only when the wallpaper really changed.
            val id = runCatching { wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(-1)
            if (fresh != null && !fresh.isRecycled && id == wallpaperId && id != -1) return fresh
            val captured = captureWallpaper(context) ?: return fresh?.takeIf { !it.isRecycled }
            cachedBackdrop = captured
            wallpaperId = id
            return captured
        }
    }

    private const val RECHECK_MS = 2500L

    @Volatile
    private var lockBackdrop: Bitmap? = null

    @Volatile
    private var lockCheckedAt = 0L

    @Volatile
    private var lockWallpaperId = -1

    /**
     * The lockscreen wallpaper scaled to the screen. OriginOS draws it in a SurfaceView inside
     * the notification shade window, so a display capture without that window comes out empty:
     * read the image itself. Without a separate lock wallpaper the home one is used.
     */
    fun getLockBackdrop(context: Context): Bitmap? {
        val now = SystemClock.uptimeMillis()
        if (now - lockCheckedAt < RECHECK_MS) return lockBackdrop?.takeIf { !it.isRecycled }
        synchronized(lock) {
            if (now - lockCheckedAt < RECHECK_MS) return lockBackdrop?.takeIf { !it.isRecycled }
            lockCheckedAt = now
            registerReceiverIfNeeded(context)
            val wm = runCatching { WallpaperManager.getInstance(context) }.getOrNull() ?: return lockBackdrop
            val id = runCatching { wm.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrDefault(-1)
            if (id <= 0) {
                // No separate lock still: a live (vivo) wallpaper on both screens has its lock
                // frame on disk; otherwise the home wallpaper is the lock one.
                val live = runCatching { wm.wallpaperInfo != null }.getOrDefault(false)
                if (live) vivoLiveStill(context, lock = true)?.let { lockBackdrop = it; return it }
                return getBackdrop(context)
            }
            val current = lockBackdrop
            if (current != null && !current.isRecycled && id == lockWallpaperId) return current
            // A lock wallpaper id with no readable still (vivo's live KeyguardSlide fallback after a
            // reset): vivo's lock frame on disk, else the home wallpaper; never no backdrop, which
            // leaves keyguard glass black.
            val decoded = captureWallpaper(context, WallpaperManager.FLAG_LOCK)
                ?: return current?.takeIf { !it.isRecycled }
                    ?: vivoLiveStill(context, lock = true)?.also { lockBackdrop = it }
                    ?: getBackdrop(context)
            lockBackdrop = decoded
            lockWallpaperId = id
            return decoded
        }
    }

    private fun registerReceiverIfNeeded(context: Context) {
        if (receiverRegistered) return
        runCatching {
            val appContext = context.applicationContext ?: context
            val filter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
            appContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    synchronized(lock) {
                        lastCaptureTime = 0L
                        wallpaperId = -1
                        lockCheckedAt = 0L
                        lockWallpaperId = -1
                    }
                }
            }, filter)
            receiverRegistered = true
        }
    }

    private fun captureWallpaper(context: Context, flag: Int? = null): Bitmap? {
        return runCatching {
            val wm = WallpaperManager.getInstance(context) ?: return null
            val dm = context.resources.displayMetrics
            val targetW = dm.widthPixels.coerceAtLeast(1)
            val targetH = dm.heightPixels.coerceAtLeast(1)

            // Try getting wallpaper file descriptor first (fastest, high quality)
            val pfd = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val which = flag ?: if (wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) > 0) {
                        WallpaperManager.FLAG_SYSTEM
                    } else {
                        WallpaperManager.FLAG_LOCK
                    }
                    wm.getWallpaperFile(which)
                } else {
                    null
                }
            }.getOrNull()

            val rawBitmap = if (pfd != null) {
                pfd.use { descriptor ->
                    BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
                }
            } else {
                runCatching {
                    (wm.drawable as? BitmapDrawable)?.bitmap
                }.getOrNull()
            }

            if (rawBitmap != null && !rawBitmap.isRecycled) {
                val scaled = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(scaled)
                val scale = maxOf(
                    targetW.toFloat() / rawBitmap.width.toFloat(),
                    targetH.toFloat() / rawBitmap.height.toFloat()
                )
                val scaledW = rawBitmap.width * scale
                val scaledH = rawBitmap.height * scale
                val left = (targetW - scaledW) / 2f
                val top = (targetH - scaledH) / 2f

                val matrix = Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(left, top)
                }
                canvas.drawBitmap(rawBitmap, matrix, filterPaint)
                if (pfd != null) {
                    runCatching { rawBitmap.recycle() }
                }
                scaled
            } else {
                null
            }
        }.getOrNull()
    }

    private val stills = HashMap<String, Pair<Long, Bitmap>>()

    /**
     * The still frame vivo writes for its live wallpapers (ThreeDimensional / KeyguardSlide…):
     * screen-sized JPEGs in /data/bbkcore/background. Decoded again only when the file changes.
     */
    private fun vivoLiveStill(context: Context, lock: Boolean): Bitmap? {
        val names = if (lock) {
            listOf("livewallpaper_lock.png", "lock_background_frame_first", "livewallpaper.png", "background_frame_first")
        } else {
            listOf("livewallpaper.png", "background_frame_first", "livewallpaper_lock.png", "lock_background_frame_first")
        }
        for (name in names) {
            val file = java.io.File(VIVO_BACKGROUND_DIR, name)
            val modified = runCatching { if (file.canRead()) file.lastModified() else 0L }.getOrDefault(0L)
            if (modified <= 0L) continue
            stills[name]?.let { (at, bmp) -> if (at == modified && !bmp.isRecycled) return bmp }
            val raw = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() ?: continue
            val scaled = fitToScreen(context, raw)
            if (scaled !== raw) runCatching { raw.recycle() }
            stills[name] = modified to scaled
            return scaled
        }
        return null
    }

    private fun fitToScreen(context: Context, raw: Bitmap): Bitmap {
        val dm = context.resources.displayMetrics
        val targetW = dm.widthPixels.coerceAtLeast(1)
        val targetH = dm.heightPixels.coerceAtLeast(1)
        if (raw.width == targetW && raw.height == targetH) return raw
        val scaled = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val scale = maxOf(targetW / raw.width.toFloat(), targetH / raw.height.toFloat())
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate((targetW - raw.width * scale) / 2f, (targetH - raw.height * scale) / 2f)
        }
        Canvas(scaled).drawBitmap(raw, matrix, filterPaint)
        return scaled
    }

    private const val VIVO_BACKGROUND_DIR = "/data/bbkcore/background"

    fun invalidate() {
        synchronized(lock) {
            lastCaptureTime = 0L
            wallpaperId = -1
        }
    }
}
