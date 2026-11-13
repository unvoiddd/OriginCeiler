package dev.unvoid.originceiler

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.LruCache
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.RelativeLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Now Bar settings (Settings.Global, live). The names follow HyperLight's
 * lockscreen_*_capsule_* preferences.
 */
object NowBar {
    const val PREFIX = "originceiler_nb_"
    const val KEY_ENABLED = PREFIX + "enabled"
    const val KEY_MUSIC = PREFIX + "music"
    const val KEY_STATUS = PREFIX + "status"
    /** 0 = side by side, 1 = stacked. */
    const val KEY_LAYOUT = PREFIX + "layout"
    const val KEY_SIZE = PREFIX + "size"
    const val KEY_MUSIC_WIDTH = PREFIX + "music_width"
    const val KEY_STATUS_WIDTH = PREFIX + "status_width"
    const val KEY_COVER_SIZE = PREFIX + "cover_size"
    /** 0 = rounded, 1 = circle. */
    const val KEY_COVER_SHAPE = PREFIX + "cover_shape"
    const val KEY_COVER_CORNER = PREFIX + "cover_corner"
    const val KEY_PLAY_SIZE = PREFIX + "play_size"
    const val KEY_SHOW_ARTIST = PREFIX + "show_artist"
    /** The lockscreen media card starts folded into the music capsule; a tap brings it out. */
    const val KEY_AUTO_HIDE_CARD = PREFIX + "auto_hide_card"
    const val KEY_HIDE_AOD = PREFIX + "hide_aod"
    /** 0 = auto (from the wallpaper), 1 = white, 2 = black. */
    const val KEY_TEXT_COLOR = PREFIX + "text_color"
    const val KEY_GLASS = PREFIX + "glass"
    /** A strong swipe up folds the notifications and the media card into the bar. */
    const val KEY_SINK = PREFIX + "sink"
    const val KEY_OFFSET = PREFIX + "offset"
    /** Timers, stopwatches and other live notifications in the status capsule. */
    const val KEY_LIVE = PREFIX + "live"

    val DEFAULTS: Map<String, Int> = mapOf(
        KEY_ENABLED to 0, KEY_MUSIC to 1, KEY_STATUS to 1, KEY_LAYOUT to 0,
        KEY_SIZE to 100, KEY_MUSIC_WIDTH to 100, KEY_STATUS_WIDTH to 100,
        KEY_COVER_SIZE to 100, KEY_COVER_SHAPE to 0, KEY_COVER_CORNER to 30, KEY_PLAY_SIZE to 100,
        KEY_SHOW_ARTIST to 1, KEY_AUTO_HIDE_CARD to 0, KEY_HIDE_AOD to 1, KEY_TEXT_COLOR to 0,
        KEY_GLASS to 1, KEY_SINK to 1, KEY_OFFSET to 8, KEY_LIVE to 1
    )

    class Config(private val values: Map<String, Int>) {
        operator fun get(key: String): Int = values[key] ?: DEFAULTS[key] ?: 0
        fun on(key: String) = get(key) == 1
        fun percent(key: String) = get(key) / 100f
    }
}

/**
 * HyperLight's lockscreen Dynamic Capsule (LockScreenMusicCapsuleHook + LockScreenStatusCapsuleHook)
 * ported to OriginOS as a Now Bar at the bottom of the lockscreen.
 *
 * On OriginOS 6 the lockscreen media card (MediaContainerView) and the notifications are children
 * of SystemUI's NotificationStackScrollLayout. A strong swipe up on them folds them into the bar
 * the way HyperLight sinks MIUI's notifications: the media card flies into the music capsule, the
 * rows into the status capsule, which then counts them ("+n notifications", HyperLight uz0.y).
 * A tap on a capsule brings its part back out of it. The transforms go through
 * View.setAnimationMatrix / setTransitionAlpha, which SystemUI leaves alone.
 *
 * Data: media sessions (instead of MIUI's MediaData), SystemUI's NotificationListener, vivo SuperX
 * capsules and chronometer notifications (timer / stopwatch, HyperLight's focus slot), zen mode and
 * the battery. The capsules live in vivo's keyguard `main_content`, so they fade with vivo's own
 * bouncer / unlock / AOD animations; vivo's charge entrance gives way to the status capsule.
 */
object NowBarHook {
    private const val KEYGUARD_VIEW = "com.vivo.systemuiplugin.keyguard.legacy.keyguard.views.keyguardstyleos5.KeyguardOsView"
    private const val MUSIC_MANAGER = "com.vivo.systemuiplugin.keyguard.legacy.keyguard.presenter.remotewidget.KeyguardMusicWidgetManager"
    private const val STATE_CONTROLLER = "com.android.systemui.statusbar.StatusBarStateControllerImpl"
    private const val NOTIFICATION_LISTENER = "com.android.systemui.statusbar.NotificationListener"
    private const val STACK = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout"
    private const val PLUGIN = "com.vivo.systemuiplugin"
    private const val STATE_KEYGUARD = 1

    private val classNames = setOf(KEYGUARD_VIEW, MUSIC_MANAGER, STATE_CONTROLLER, NOTIFICATION_LISTENER, STACK)
    private val installedLoaders = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())
    private val installedClasses = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
    private val watcherInstalled = AtomicBoolean(false)
    private val touchHookInstalled = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var config = NowBar.Config(emptyMap())
    private var appContext: Context? = null
    private var sourcesStarted = false

    private var keyguardRef = WeakReference<View>(null)
    private var hostRef = WeakReference<NowBarHost>(null)
    private var musicManagerRef = WeakReference<Any>(null)
    private var stateControllerRef = WeakReference<Any>(null)
    private var listenerRef = WeakReference<NotificationListenerService>(null)
    private var stackRef = WeakReference<ViewGroup>(null)

    /** Last visibility vivo asked for its legacy music card (themes using KeyguardMusicWidgetManager). */
    private var cardRequested: Int? = null
    private var interactive = true

    private var track: Track? = null
    private var notifications = NotificationSummary(0, emptyList())
    private var live: LiveItem? = null
    private var zen = false
    private var charging = false
    private var level = 0

    private data class Track(val title: String, val artist: String, val art: Bitmap?, val pkg: String, val playing: Boolean)
    private data class NotificationSummary(val count: Int, val packages: List<String>)

    // ---------------------------------------------------------------- install

    fun install(classLoader: ClassLoader) {
        synchronized(installedLoaders) {
            if (installedLoaders.put(classLoader, true) != null) return
        }
        runCatching { LiquidPressPhysics.installMatrixHook() }
        runCatching { KyantGlassTouch.install() }
        classNames.forEach { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()?.let(::installClass)
        }
        if (watcherInstalled.compareAndSet(false, true)) {
            XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook(Int.MIN_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val type = param.result as? Class<*> ?: return
                    if (type.name in classNames) installClass(type)
                }
            })
        }
    }

    private fun installClass(type: Class<*>) {
        synchronized(installedClasses) {
            if (installedClasses.put(type, true) != null) return
        }
        runCatching {
            when (type.name) {
                KEYGUARD_VIEW -> installKeyguardView(type)
                MUSIC_MANAGER -> installMusicManager(type)
                STATE_CONTROLLER -> installStateController(type)
                NOTIFICATION_LISTENER -> installNotificationListener(type)
                STACK -> installStack(type)
            }
        }.onFailure(XposedBridge::log)
    }

    private fun installKeyguardView(type: Class<*>) {
        val attach = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                keyguardRef = WeakReference(view)
                start(view.context)
                main.post { attachHost(view) }
            }
        }
        XposedBridge.hookAllMethods(type, "onFinishInflate", attach)
        XposedBridge.hookAllMethods(type, "onAttachedToWindow", attach)
    }

    /** Older vivo themes keep the music card in KeyguardMusicWidgetManager: fold it the same way. */
    private fun installMusicManager(type: Class<*>) {
        XposedBridge.hookAllMethods(type, "updateMusicWidgetVisible", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val requested = param.args.getOrNull(0) as? Int ?: return
                musicManagerRef = WeakReference(param.thisObject)
                cardRequested = requested
                if (requested == View.VISIBLE && musicOn() && track != null && mediaFolded) param.args[0] = View.GONE
            }
        })
    }

    private fun reapplyLegacyCard() {
        val manager = musicManagerRef.get() ?: return
        val requested = cardRequested ?: return
        main.post {
            runCatching {
                manager.javaClass.getMethod("updateMusicWidgetVisible", Int::class.javaPrimitiveType).invoke(manager, requested)
            }
        }
    }

    private fun installStateController(type: Class<*>) {
        XposedBridge.hookAllConstructors(type, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                stateControllerRef = WeakReference(param.thisObject)
            }
        })
        listOf("setState", "setIsDozing", "setDozeAmount").forEach { name ->
            XposedBridge.hookAllMethods(type, name, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    stateControllerRef = WeakReference(param.thisObject)
                    scheduleRefresh()
                }
            })
        }
    }

    private fun installNotificationListener(type: Class<*>) {
        val changed = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.thisObject as? NotificationListenerService)?.let { listenerRef = WeakReference(it) }
                scheduleNotifications()
            }
        }
        listOf("onListenerConnected", "onNotificationPosted", "onNotificationRemoved", "onNotificationRankingUpdate")
            .forEach { XposedBridge.hookAllMethods(type, it, changed) }
    }

    private fun installStack(type: Class<*>) {
        XposedBridge.hookAllConstructors(type, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val stack = param.thisObject as? ViewGroup ?: return
                stackRef = WeakReference(stack)
                stack.viewTreeObserver.addOnPreDrawListener {
                    if (stack === stackRef.get()) applyStack(stack)
                    true
                }
            }
        })
        if (touchHookInstalled.compareAndSet(false, true)) {
            val touch = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val stack = stackRef.get() ?: return
                    if (param.thisObject !== stack || sendingCancel) return
                    // Once per event: the stack's own override and then ViewGroup's via super.
                    if (param.method.declaringClass == ViewGroup::class.java && stackDeclaresTouch) return
                    val event = param.args.getOrNull(0) as? MotionEvent ?: return
                    val handled = runCatching { onStackTouch(stack, event, param) }.getOrDefault(false)
                    if (handled) param.result = param.result ?: true
                }
            }
            stackDeclaresTouch = type.declaredMethods.any { it.name == "dispatchTouchEvent" }
            if (stackDeclaresTouch) XposedBridge.hookAllMethods(type, "dispatchTouchEvent", touch)
            XposedBridge.hookAllMethods(ViewGroup::class.java, "dispatchTouchEvent", touch)
        }
    }

    // ---------------------------------------------------------------- sources

    private fun start(context: Context) {
        if (sourcesStarted) return
        val app = context.applicationContext ?: context
        appContext = app
        sourcesStarted = true
        val cr = app.contentResolver
        loadConfig(app)
        runCatching {
            cr.registerContentObserver(Settings.Global.CONTENT_URI, true, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    val key = uri?.lastPathSegment ?: return
                    when {
                        key.startsWith(NowBar.PREFIX) -> {
                            loadConfig(app)
                            keyguardRef.get()?.let(::attachHost)
                            if (!foldOn()) unfoldAll(animate = false)
                            reapplyLegacyCard()
                            scheduleNotifications()
                            refresh()
                        }
                        key == "zen_mode" -> {
                            readZen(app)
                            refresh()
                        }
                    }
                }
            })
        }
        readZen(app)
        runCatching {
            cr.registerContentObserver(Settings.Secure.getUriFor("lock_screen_show_notifications"), false, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = scheduleNotifications()
            })
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_BATTERY_CHANGED -> readBattery(intent)
                        Intent.ACTION_SCREEN_OFF -> {
                            interactive = false
                            // HyperLight lockscreen_music_capsule_auto_hide: the card is folded
                            // again every time the lockscreen comes back.
                            if (config.on(NowBar.KEY_AUTO_HIDE_CARD) && foldOn()) setMediaFold(1f, animate = false)
                        }
                        Intent.ACTION_SCREEN_ON -> interactive = true
                        Intent.ACTION_USER_PRESENT -> {
                            interactive = true
                            // Unlocked: the next lockscreen starts unfolded (the card per the setting).
                            setRowsFold(0f, animate = false)
                            setMediaFold(if (config.on(NowBar.KEY_AUTO_HIDE_CARD) && foldOn()) 1f else 0f, animate = false)
                        }
                    }
                    refresh()
                }
            }
            val sticky = if (android.os.Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, null, main, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter, null, main)
            }
            sticky?.let(::readBattery)
        }
        interactive = runCatching { app.getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(true)
        if (config.on(NowBar.KEY_AUTO_HIDE_CARD) && foldOn()) mediaFold = 1f
        startMedia(app)
        scheduleNotifications()
    }

    private fun loadConfig(context: Context) {
        val cr = context.contentResolver
        config = NowBar.Config(NowBar.DEFAULTS.keys.associateWith { key ->
            runCatching { Settings.Global.getInt(cr, key, NowBar.DEFAULTS[key] ?: 0) }.getOrDefault(NowBar.DEFAULTS[key] ?: 0)
        })
    }

    private fun readZen(context: Context) {
        zen = runCatching { Settings.Global.getInt(context.contentResolver, "zen_mode", 0) != 0 }.getOrDefault(false)
    }

    private fun readBattery(intent: Intent) {
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        level = Math.round(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100f / scale)
        charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    // ------------------------------------------------ media (instead of HyperLight's MediaData hooks)

    private var sessionManager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = readTrack()
        override fun onPlaybackStateChanged(state: PlaybackState?) = pickController()
        override fun onSessionDestroyed() = pickController()
    }

    private fun startMedia(context: Context) {
        runCatching {
            val manager = context.getSystemService(MediaSessionManager::class.java) ?: return
            sessionManager = manager
            // SystemUI holds MEDIA_CONTENT_CONTROL, so no listener component is needed.
            manager.addOnActiveSessionsChangedListener({ pickController() }, null as ComponentName?, main)
            pickController()
        }.onFailure(XposedBridge::log)
    }

    private fun pickController() {
        val sessions = runCatching { sessionManager?.getActiveSessions(null as ComponentName?) }.getOrNull().orEmpty()
        fun active(c: MediaController) = c.playbackState?.state in ACTIVE_STATES && !c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && active(it) }
            ?: sessions.firstOrNull { active(it) }
        if (best?.sessionToken != controller?.sessionToken) {
            runCatching { controller?.unregisterCallback(controllerCallback) }
            controller = best
            runCatching { best?.registerCallback(controllerCallback, main) }
        }
        readTrack()
    }

    private fun readTrack() {
        val c = controller
        val meta = c?.metadata
        val state = c?.playbackState?.state
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val had = track != null
        track = if (c == null || title.isEmpty() || state !in ACTIVE_STATES) null else {
            val artist = (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)).orEmpty().trim()
            val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            Track(title, artist, art, c.packageName, state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING)
        }
        // A new player on the lockscreen starts folded when the card is set to hide.
        if (!had && track != null && config.on(NowBar.KEY_AUTO_HIDE_CARD) && foldOn()) setMediaFold(1f, animate = false)
        reapplyLegacyCard()
        refresh()
    }

    private fun playPause() {
        val c = controller ?: return
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        runCatching { if (playing) c.transportControls.pause() else c.transportControls.play() }
    }

    private fun skip(next: Boolean) {
        val c = controller ?: return
        runCatching { if (next) c.transportControls.skipToNext() else c.transportControls.skipToPrevious() }
    }

    // ------------------------------------------------ notifications (instead of NotificationNumStateView)

    private val notificationsRunnable = Runnable { readNotifications() }

    private fun scheduleNotifications() {
        main.removeCallbacks(notificationsRunnable)
        main.postDelayed(notificationsRunnable, 120L)
    }

    private fun readNotifications() {
        val context = appContext ?: return
        val active: Array<StatusBarNotification> = runCatching { listenerRef.get()?.activeNotifications }.getOrNull() ?: emptyArray()
        val allowed = runCatching { Settings.Secure.getInt(context.contentResolver, "lock_screen_show_notifications", 1) != 0 }.getOrDefault(true)
        val liveItems = active.mapNotNull { sbn -> liveItem(context, sbn)?.let { sbn to it } }
        live = liveItems.maxByOrNull { it.first.postTime }?.second
        val liveKeys = liveItems.map { it.first.key }.toSet()
        val shown = if (!allowed) emptyList() else
            active.filter { it.key !in liveKeys && countsOnLockscreen(it) }.sortedByDescending { it.postTime }
        notifications = NotificationSummary(shown.size, shown.map { it.packageName }.distinct().take(3))
        refresh()
    }

    private fun countsOnLockscreen(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (sbn.isOngoing || !sbn.isClearable) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.visibility == Notification.VISIBILITY_SECRET) return false
        if (n.category == Notification.CATEGORY_TRANSPORT || n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return false
        if (sbn.packageName == "android" || sbn.packageName == "com.android.systemui") return false
        return true
    }

    private val iconCache = LruCache<String, Drawable>(16)

    private fun appIcon(pkg: String): Drawable? {
        iconCache.get(pkg)?.let { return it }
        val context = appContext ?: return null
        val icon = runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return null
        return icon.also { iconCache.put(pkg, it) }
    }

    // ------------------------------------------------ live: timers, stopwatches (HyperLight focus slot)

    private val liveIconCache = LruCache<String, Drawable>(8)
    private val timeText = Regex("^\\d{1,2}(:\\d{2}){1,2}([.,]\\d{1,2})?$")

    /**
     * vivo's SuperX capsule (BBKClock timer: content is a time or a TimerFormat JSON
     * {started, base: elapsedRealtime, countDown}) or a plain chronometer notification
     * (timerwidget stopwatch: usesChronometer with `when`).
     */
    private fun liveItem(context: Context, sbn: StatusBarNotification): LiveItem? {
        if (!config.on(NowBar.KEY_LIVE)) return null
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null
        val capsule = runCatching { extras.getBundle("notification.superx.capsule") }.getOrNull()
        if (capsule != null) {
            val content = capsule.getCharSequence("notification.superx.capsule.content")?.toString()?.trim().orEmpty()
            if (content.isEmpty()) return null
            val icon = loadLiveIcon(context, sbn, capsule.parcelable("notification.superx.capsule.icon"), tint = false)
            if (content.startsWith("{")) {
                val json = runCatching { JSONObject(content) }.getOrNull() ?: return null
                val started = json.optBoolean("started", false)
                val base = json.optLong("base", 0L)
                if (base <= 0L) return null
                return LiveItem(sbn.key, icon, null, base, json.optBoolean("countDown", false), elapsedClock = true, running = started)
            }
            return LiveItem(sbn.key, icon, content, 0L, countDown = false, elapsedClock = false, running = false)
        }
        val chronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val category = n.category.orEmpty()
        if (chronometer && n.`when` > 0L) {
            val icon = loadLiveIcon(context, sbn, n.smallIcon, tint = true)
            return LiveItem(sbn.key, icon, null, n.`when`, extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false), elapsedClock = false, running = true)
        }
        if (category == "stopwatch" || category == "timer") {
            val text = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT)
                .mapNotNull { extras.getCharSequence(it)?.toString()?.trim() }
                .firstOrNull { timeText.matches(it) } ?: return null
            return LiveItem(sbn.key, loadLiveIcon(context, sbn, n.smallIcon, tint = true), text, 0L, false, false, false)
        }
        return null
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T> Bundle.parcelable(key: String): T? = runCatching { get(key) as? T }.getOrNull()

    private fun loadLiveIcon(context: Context, sbn: StatusBarNotification, icon: Icon?, tint: Boolean): Drawable? {
        val cacheKey = sbn.key + "|" + tint
        liveIconCache.get(cacheKey)?.let { return it }
        val drawable = runCatching { icon?.loadDrawable(context) }.getOrNull() ?: appIcon(sbn.packageName) ?: return null
        val result = if (tint) TintedIcon(drawable.mutate()) else drawable
        liveIconCache.put(cacheKey, result)
        return result
    }

    // ---------------------------------------------------------------- host

    private fun enabled() = config.on(NowBar.KEY_ENABLED)
    // One NowBar switch: both capsules and folding into them come with it.
    private fun musicOn() = enabled()
    private fun statusOn() = enabled()
    private fun foldOn() = enabled()

    private fun mainContents(keyguard: View): ViewGroup? {
        (findField(keyguard, "mMainContents") as? ViewGroup)?.let { return it }
        val id = keyguard.resources.getIdentifier("main_content", "id", PLUGIN)
        return if (id != 0) keyguard.findViewById(id) else null
    }

    private fun attachHost(keyguard: View) {
        val parent = mainContents(keyguard) ?: return
        val existing = hostRef.get()
        if (!enabled()) {
            existing?.let { (it.parent as? ViewGroup)?.removeView(it) }
            restoreVivoViews(keyguard)
            return
        }
        if (existing != null && existing.parent === parent) {
            refresh()
            return
        }
        existing?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val host = NowBarHost(parent.context)
        host.music.onPlayPause = ::playPause
        host.music.onSkip = ::skip
        host.music.onBody = ::onMusicTap
        host.status.onTapped = ::onStatusTap
        host.status.onPulledDown = { unfoldAll(animate = true) }
        val lp = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        parent.addView(host, lp)
        parent.clipChildren = false
        hostRef = WeakReference(host)
        ensureEnforcer(keyguard)
        refresh()
    }

    private val refreshRunnable = Runnable { refresh() }

    private fun scheduleRefresh() {
        main.removeCallbacks(refreshRunnable)
        main.post(refreshRunnable)
    }

    private fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scheduleRefresh()
            return
        }
        stackRef.get()?.invalidate()
        val host = hostRef.get() ?: return
        if (!host.isAttachedToWindow) return
        val c = config
        val dozing = runCatching { stateControllerRef.get()?.let { it.javaClass.getMethod("isDozing").invoke(it) as? Boolean } }.getOrNull() == true
        val hidden = !enabled() || (c.on(NowBar.KEY_HIDE_AOD) && (!interactive || dozing))
        host.visibility = if (hidden) View.INVISIBLE else View.VISIBLE

        val scale = c.percent(NowBar.KEY_SIZE).coerceIn(0.5f, 2f)
        host.capsuleLayout = c[NowBar.KEY_LAYOUT]
        host.bottomOffsetPx = (c[NowBar.KEY_OFFSET] * host.resources.displayMetrics.density).toInt()
        val color = textColor(host)
        val glass = c.on(NowBar.KEY_GLASS)

        host.music.apply {
            sizeScale = scale
            widthScale = c.percent(NowBar.KEY_MUSIC_WIDTH).coerceIn(0.5f, 2f)
            textColor = color
            useGlass = glass
            showArtist = c.on(NowBar.KEY_SHOW_ARTIST)
            coverScale = c.percent(NowBar.KEY_COVER_SIZE).coerceIn(0.5f, 1f)
            playScale = c.percent(NowBar.KEY_PLAY_SIZE).coerceIn(0.5f, 1.5f)
            coverShape = c[NowBar.KEY_COVER_SHAPE]
            coverCorner = c[NowBar.KEY_COVER_CORNER]
            val t = track
            // The capsule stands in for the card: gone while the card is out (it grows out of
            // the capsule), back once the card has folded into it. Without a vivo card it is the player.
            val cardOut = mediaCardShown() && mediaFold < 0.5f
            if (musicOn() && t != null && !cardOut) {
                bind(t.title, t.artist, t.art, appIcon(t.pkg), t.playing)
                setWanted(true)
            } else {
                if (t != null) bind(t.title, t.artist, t.art, appIcon(t.pkg), t.playing)
                setWanted(false)
            }
        }

        host.status.apply {
            sizeScale = scale
            widthScale = c.percent(NowBar.KEY_STATUS_WIDTH).coerceIn(0.5f, 2f)
            textColor = color
            useGlass = glass
            val ru = Locale.getDefault().language == "ru"
            // HyperLight counts only what is folded away (NotificationNumStateView's hidden count).
            val n = if (rowsFold >= 0.5f) notifications else NotificationSummary(0, emptyList())
            val ring = (color and 0x00FFFFFF) or (0x99 shl 24)
            val content = StatusContent(
                live = live,
                icons = n.packages.mapNotNull { pkg -> appIcon(pkg)?.let { RoundIconDrawable(it, ring) } },
                count = n.count,
                countText = if (n.count > 0) countLabel(n.count, ru) else "",
                dnd = zen,
                dndLabel = if (ru) "Не беспокоить" else "Do not disturb",
                charging = charging,
                level = level,
                chargingLabel = if (ru) "Зарядка" else "Charging"
            )
            bind(content)
            setWanted(statusOn() && !content.empty)
        }
        applyVivoBottom()
        host.requestLayout()
    }

    /** HyperLight uz0.y with its default "+n通知": "+3 notifications". */
    private fun countLabel(n: Int, ru: Boolean): String {
        if (!ru) return if (n == 1) "+1 notification" else "+$n notifications"
        val mod10 = n % 10
        val mod100 = n % 100
        val word = when {
            mod10 == 1 && mod100 != 11 -> "уведомление"
            mod10 in 2..4 && mod100 !in 12..14 -> "уведомления"
            else -> "уведомлений"
        }
        return "+$n $word"
    }

    /** HyperLight lockscreen_capsule_text_color, plus "auto" from the wallpaper behind the bar. */
    private fun textColor(host: View): Int = when (config[NowBar.KEY_TEXT_COLOR]) {
        1 -> Color.WHITE
        2 -> Color.BLACK
        else -> if (wallpaperIsLight(host)) Color.BLACK else Color.WHITE
    }

    private var sampledWallpaper = WeakReference<Bitmap>(null)
    private var sampledLight = false

    private fun wallpaperIsLight(host: View): Boolean {
        val bitmap = runCatching { ScreenBackdropSource.getLockBackdrop(host.context) }.getOrNull() ?: return false
        if (bitmap === sampledWallpaper.get()) return sampledLight
        sampledWallpaper = WeakReference(bitmap)
        sampledLight = runCatching {
            // The bottom fifth, centre half: where the bar sits.
            var sum = 0.0
            var count = 0
            val x0 = bitmap.width / 4
            val x1 = bitmap.width * 3 / 4
            val y0 = bitmap.height * 4 / 5
            val stepX = ((x1 - x0) / 16).coerceAtLeast(1)
            val stepY = ((bitmap.height - y0) / 8).coerceAtLeast(1)
            var y = y0
            while (y < bitmap.height) {
                var x = x0
                while (x < x1) {
                    sum += Color.luminance(bitmap.getPixel(x, y))
                    count++
                    x += stepX
                }
                y += stepY
            }
            count > 0 && sum / count > 0.6
        }.getOrDefault(false)
        return sampledLight
    }

    // ---------------------------------------------------------------- vivo bottom entrance

    private fun ensureEnforcer(keyguard: View) {
        if (enforcers.containsKey(keyguard)) return
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (keyguard.isAttachedToWindow && enabled()) applyVivoBottom()
            true
        }
        enforcers[keyguard] = listener
        keyguard.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun vivoBottom(keyguard: View): View? {
        (findField(keyguard, "bottomWidgetsView") as? View)?.let { return it }
        val id = keyguard.resources.getIdentifier("keyguard_widgets_view", "id", PLUGIN)
        return if (id != 0) keyguard.findViewById(id) else null
    }

    /** The status capsule takes over vivo's "Charging" / focus entrance at the bottom. */
    private fun applyVivoBottom() {
        val keyguard = keyguardRef.get() ?: return
        val bottom = vivoBottom(keyguard) ?: return
        if (statusOn()) {
            if (bottom.alpha != 0f) bottom.alpha = 0f
            if (bottom.translationY != OFFSCREEN) bottom.translationY = OFFSCREEN
            hiddenBottom = WeakReference(bottom)
        } else if (hiddenBottom.get() === bottom) {
            bottom.alpha = 1f
            bottom.translationY = 0f
            hiddenBottom = WeakReference(null)
        }
    }

    private var hiddenBottom = WeakReference<View>(null)

    private fun restoreVivoViews(keyguard: View) {
        hiddenBottom.get()?.let { it.alpha = 1f; it.translationY = 0f }
        hiddenBottom = WeakReference(null)
        enforcers.remove(keyguard)?.let { runCatching { keyguard.viewTreeObserver.removeOnPreDrawListener(it) } }
        unfoldAll(animate = false)
        reapplyLegacyCard()
    }

    private fun onStatusTap() {
        if (rowsFold >= 0.5f && notifications.count > 0) {
            setRowsFold(0f, animate = true)
            return
        }
        if (charging) {
            val keyguard = keyguardRef.get() ?: return
            val id = keyguard.resources.getIdentifier("vivo_keyguard_charge_entrance", "id", PLUGIN)
            if (id != 0) keyguard.findViewById<View>(id)?.performClick()
        }
    }

    /** The card comes out of the capsule; the capsule leaves while it does. */
    private fun onMusicTap() {
        if (mediaCardShown() || cardRequested == View.VISIBLE) {
            setMediaFold(0f, animate = true)
            reapplyLegacyCard()
        }
    }

    // ================================================================ folding the stack

    /** 0 = the media card is out, 1 = folded into the music capsule. */
    private var mediaFold = 0f
    /** 0 = notifications out, 1 = folded into the status capsule. */
    private var rowsFold = 0f
    private val mediaFolded get() = mediaFold >= 0.5f
    private var mediaAnim: ValueAnimator? = null
    private var rowsAnim: ValueAnimator? = null

    private fun setMediaFold(value: Float, animate: Boolean) {
        mediaAnim?.cancel()
        mediaAnim = null
        if (!animate) {
            mediaFold = value
            refresh()
            return
        }
        mediaAnim = foldAnimator(mediaFold, value) { mediaFold = it }
    }

    private fun setRowsFold(value: Float, animate: Boolean) {
        rowsAnim?.cancel()
        rowsAnim = null
        if (!animate) {
            rowsFold = value
            refresh()
            return
        }
        rowsAnim = foldAnimator(rowsFold, value) { rowsFold = it }
    }

    /** HyperLight's drag rebound spring (0.55 / 8) for the fold. */
    private fun foldAnimator(from: Float, to: Float, set: (Float) -> Unit): ValueAnimator =
        ValueAnimator.ofFloat(from, to).apply {
            duration = if (to > from) 420L else 520L
            interpolator = if (to > from) CapsuleView.EASE else LiquidPressPhysics.DRAG_RETURN
            var crossed = false
            addUpdateListener {
                set(it.animatedValue as Float)
                stackRef.get()?.invalidate()
                // Swap capsule state the moment the fold passes halfway.
                val half = (it.animatedValue as Float >= 0.5f) == (to > from)
                if (half && !crossed) {
                    crossed = true
                    refresh()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    set(to)
                    refresh()
                }
            })
            start()
        }

    private fun unfoldAll(animate: Boolean) {
        setRowsFold(0f, animate)
        setMediaFold(0f, animate)
        stackRef.get()?.let(::applyStack)
    }

    private fun onKeyguard(): Boolean = keyguardState() == STATE_KEYGUARD

    private fun keyguardState(): Int =
        runCatching { stateControllerRef.get()?.let { it.javaClass.getMethod("getState").invoke(it) as? Int } }.getOrNull() ?: STATE_KEYGUARD

    private fun bouncerShowing(): Boolean = keyguardRef.get()?.let { findField(it, "mIsBouncerShowing") as? Boolean } == true

    private fun isMedia(child: View) = child.javaClass.name.endsWith("MediaContainerView")

    private fun mediaCardShown(): Boolean {
        val stack = stackRef.get() ?: return false
        for (i in 0 until stack.childCount) {
            val child = stack.getChildAt(i)
            if (isMedia(child) && child.visibility == View.VISIBLE && child.height > 0) return true
        }
        return false
    }

    private val transformed = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val childLoc = IntArray(2)
    private val targetLoc = IntArray(2)
    private val foldMatrix = Matrix()

    /**
     * Each frame: every child of the stack takes its fold. The media card shrinks into the music
     * capsule, the rows (and their section header / shelf) into the status capsule.
     */
    private fun applyStack(stack: ViewGroup) {
        val active = foldOn() && onKeyguard()
        val host = hostRef.get()
        for (i in 0 until stack.childCount) {
            val child = stack.getChildAt(i)
            val media = isMedia(child)
            val p = if (!active) 0f else if (media) mediaFold else rowsFold
            if (p <= 0.001f) {
                if (transformed.remove(child)) {
                    child.animationMatrix = null
                    child.transitionAlpha = 1f
                }
                continue
            }
            val target: View? = if (media) host?.music?.takeIf { it.present } ?: host?.status?.takeIf { it.present }
            else host?.status?.takeIf { it.present } ?: host?.music?.takeIf { it.present }
            foldInto(child, target, host, p)
            transformed += child
        }
    }

    private fun foldInto(child: View, target: View?, host: View?, p: Float) {
        val w = child.width.toFloat().coerceAtLeast(1f)
        val h = actualHeight(child).coerceAtLeast(1f)
        child.getLocationOnScreen(childLoc)
        val cx = childLoc[0] + w / 2f
        val cy = childLoc[1] + h / 2f
        val d = child.resources.displayMetrics.density
        val tx: Float
        val ty: Float
        val tw: Float
        if (target != null && target.width > 0) {
            target.getLocationOnScreen(targetLoc)
            tx = targetLoc[0] + target.width / 2f
            ty = targetLoc[1] + target.height / 2f
            tw = target.width.toFloat()
        } else {
            val screenH = (host?.height ?: child.rootView.height).toFloat()
            tx = child.rootView.width / 2f
            ty = screenH - (config[NowBar.KEY_OFFSET] + 22f) * d
            tw = 44f * d
        }
        val e = CapsuleView.CROSS.getInterpolation(p.coerceIn(0f, 1f))
        val s = 1f + (min(1f, tw / w) - 1f) * e
        foldMatrix.reset()
        foldMatrix.postScale(s, s, w / 2f, h / 2f)
        foldMatrix.postTranslate((tx - cx) * e, (ty - cy) * e)
        child.animationMatrix = foldMatrix
        // Gone before it reaches the capsule, so the capsule seems to swallow it.
        child.transitionAlpha = (1f - p / 0.7f).coerceIn(0f, 1f)
    }

    private val actualHeightMethods = HashMap<Class<*>, java.lang.reflect.Method?>()

    /** ExpandableView draws actualHeight of its height (rows clip while they animate). */
    private fun actualHeight(child: View): Float {
        val method = actualHeightMethods.getOrPut(child.javaClass) {
            runCatching { child.javaClass.getMethod("getActualHeight") }.getOrNull()
        }
        val value = runCatching { method?.invoke(child) as? Int }.getOrNull()
        return (value ?: child.height).toFloat()
    }

    // ---------------------------------------------------------------- the fold gesture

    private var gestureMode = 0 // 0 idle, 1 undecided, 2 folding, 3 pass
    private var sendingCancel = false
    @Volatile private var stackDeclaresTouch = false
    private var downX = 0f
    private var downY = 0f
    private var startMedia = 0f
    private var startRows = 0f
    private var velocity: VelocityTracker? = null

    /**
     * HyperLight's sink: a strong swipe up on the notifications or the media card drags them into
     * the bar (follows the finger), a fling or past a third finishes it, otherwise they spring back.
     * Touches on what is folded away fall through (vivo's own swipe to unlock keeps working).
     */
    private fun onStackTouch(stack: ViewGroup, event: MotionEvent, param: XC_MethodHook.MethodHookParam): Boolean {
        val active = foldOn() && onKeyguard() && !bouncerShowing()
        if (!active) {
            gestureMode = 0
            return false
        }
        val d = stack.resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureMode = 0
                velocity?.recycle()
                velocity = null
                // Our capsules sit under the stack: let their touches through.
                if (hitsCapsule(event.rawX, event.rawY)) {
                    param.result = false
                    return true
                }
                val child = childAt(stack, event.x, event.y)
                if (child == null) return false
                val folded = if (isMedia(child)) mediaFold >= 0.5f else rowsFold >= 0.5f
                if (folded) {
                    param.result = false
                    return true
                }
                gestureMode = 1
                downX = event.rawX
                downY = event.rawY
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (gestureMode == 1) {
                    val slop = ViewConfiguration.get(stack.context).scaledTouchSlop * 0.6f
                    if (dy < -slop && abs(dy) > abs(dx) * 1.3f && !stack.canScrollVertically(1)) {
                        gestureMode = 2
                        startMedia = mediaFold
                        startRows = rowsFold
                        mediaAnim?.cancel(); rowsAnim?.cancel()
                        stack.parent?.requestDisallowInterceptTouchEvent(true)
                        // The stack lets go of whatever it started (row press, scroll).
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        sendingCancel = true
                        try { stack.dispatchTouchEvent(cancel) } finally { sendingCancel = false }
                        cancel.recycle()
                    } else if (abs(dy) > slop * 2f || abs(dx) > slop * 2f) {
                        gestureMode = 3
                    }
                }
                if (gestureMode == 2) {
                    val pull = (-dy / (240f * d)).coerceAtLeast(0f)
                    val p = pull / (1f + pull * 0.15f)
                    mediaFold = if (mediaCardShown()) (startMedia + p).coerceIn(0f, 1f) else mediaFold
                    rowsFold = (startRows + p).coerceIn(0f, 1f)
                    stack.invalidate()
                    refreshThrottled()
                    param.result = true
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val mode = gestureMode
                gestureMode = 0
                if (mode != 2) {
                    velocity?.recycle(); velocity = null
                    return false
                }
                val v = velocity?.let { it.addMovement(event); it.computeCurrentVelocity(1000); it.yVelocity } ?: 0f
                velocity?.recycle(); velocity = null
                val fling = v < -900f * d
                val fold = event.actionMasked == MotionEvent.ACTION_UP && (fling || rowsFold > 0.33f)
                setRowsFold(if (fold) 1f else 0f, animate = true)
                if (mediaCardShown()) setMediaFold(if (fold) 1f else 0f, animate = true)
                param.result = true
                return true
            }
        }
        return false
    }

    private var lastRefresh = 0L

    private fun refreshThrottled() {
        val now = SystemClock.uptimeMillis()
        if (now - lastRefresh < 48L) return
        lastRefresh = now
        refresh()
    }

    private fun hitsCapsule(x: Float, y: Float): Boolean {
        val host = hostRef.get() ?: return false
        if (host.visibility != View.VISIBLE || !host.isAttachedToWindow) return false
        return listOf<View>(host.music, host.status).any { v ->
            val c = v as CapsuleView
            if (!c.present || v.alpha < 0.5f) return@any false
            v.getLocationOnScreen(targetLoc)
            x >= targetLoc[0] && x <= targetLoc[0] + v.width && y >= targetLoc[1] && y <= targetLoc[1] + v.height
        }
    }

    /** The stack's child under (x, y): children sit at their translationY, actualHeight tall. */
    private fun childAt(stack: ViewGroup, x: Float, y: Float): View? {
        for (i in stack.childCount - 1 downTo 0) {
            val child = stack.getChildAt(i)
            if (child.visibility != View.VISIBLE || child.height == 0) continue
            val top = child.top + child.translationY
            val bottom = top + actualHeight(child)
            val left = child.left + child.translationX
            if (x >= left && x <= left + child.width && y >= top && y <= bottom) return child
        }
        return null
    }

    // ---------------------------------------------------------------- util

    private fun findField(owner: Any, name: String): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            if (field != null) return runCatching { field.get(owner) }.getOrNull()
            type = type.superclass
        }
        return null
    }

    private const val OFFSCREEN = 100000f
    private val ACTIVE_STATES = setOf(
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING, PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING
    )
    private val enforcers = Collections.synchronizedMap(WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>())
}
