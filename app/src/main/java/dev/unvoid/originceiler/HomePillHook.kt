package dev.unvoid.originceiler

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fades the gesture navigation pill out while the home screen is in front.
 *
 * The pill is drawn by com.vivo.upslide (HomeIndicatorView inside the SideSlideGestureBar-Bottom
 * window), which does not know when the launcher is showing. The launcher process reports that
 * (its activity resumed and holding window focus) with a broadcast; the upslide process fades the
 * pill's window root, so upslide's own indicator alpha animations stay untouched and the swipe
 * area keeps receiving touches.
 */
object HomePillHook {
    const val KEY = "originceiler_hide_home_pill"
    private const val LAUNCHER = "com.bbk.launcher2"
    private const val UPSLIDE = "com.vivo.upslide"
    private const val ACTION_STATE = "dev.unvoid.originceiler.HOME_PILL_STATE"
    private const val ACTION_QUERY = "dev.unvoid.originceiler.HOME_PILL_QUERY"
    private const val EXTRA_HOME = "home"
    private const val INDICATOR = "com.vivo.upslide.navigation.homeindicator.HomeIndicatorView"

    private const val HIDE_DELAY_MS = 220L
    private const val HIDE_DURATION_MS = 420L
    private const val SHOW_DURATION_MS = 200L

    private val main by lazy { Handler(Looper.getMainLooper()) }

    // ---------------------------------------------------------------- launcher process

    private val launcherInstalled = AtomicBoolean(false)
    private val launcherReceiver = AtomicBoolean(false)
    @Volatile private var launcherResumed = false
    @Volatile private var launcherFocused = false
    @Volatile private var lastSent: Boolean? = null
    private var launcherApp: WeakReference<Context>? = null

    fun installLauncher() {
        if (!launcherInstalled.compareAndSet(false, true)) return
        fun isHomeActivity(a: Activity) = a.packageName == LAUNCHER && a.javaClass.name.startsWith("com.bbk.launcher2.Launcher")
        XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val a = param.thisObject as? Activity ?: return
                if (!isHomeActivity(a)) return
                attachLauncher(a)
                launcherResumed = true
                launcherFocused = a.hasWindowFocus() || launcherFocused
                publish(a, force = false)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onPause", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val a = param.thisObject as? Activity ?: return
                if (!isHomeActivity(a)) return
                launcherResumed = false
                publish(a, force = false)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onWindowFocusChanged", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val a = param.thisObject as? Activity ?: return
                if (!isHomeActivity(a)) return
                launcherFocused = param.args.firstOrNull() as? Boolean ?: return
                publish(a, force = false)
            }
        })
        XposedBridge.log("OriginHomePill: launcher hooks installed")
    }

    private fun attachLauncher(activity: Activity) {
        val app = activity.applicationContext
        launcherApp = WeakReference(app)
        if (!launcherReceiver.compareAndSet(false, true)) return
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    launcherApp?.get()?.let { publish(it, force = true) }
                }
            }
            val filter = IntentFilter(ACTION_QUERY)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, filter)
        }.onFailure { XposedBridge.log("OriginHomePill: launcher receiver: $it") }
    }

    private fun publish(context: Context, force: Boolean) {
        val home = launcherResumed && launcherFocused
        if (!force && lastSent == home) return
        lastSent = home
        runCatching {
            context.sendBroadcast(Intent(ACTION_STATE).setPackage(UPSLIDE).putExtra(EXTRA_HOME, home))
        }.onFailure { XposedBridge.log("OriginHomePill: send: $it") }
    }

    // ---------------------------------------------------------------- upslide process

    private val upslideInstalled = AtomicBoolean(false)
    private val upslideWired = AtomicBoolean(false)
    @Volatile private var enabled = false
    @Volatile private var onHome = false
    private var indicator: WeakReference<View>? = null
    private val hideRunnable = Runnable { apply(animated = true) }

    fun installUpslide() {
        if (!upslideInstalled.compareAndSet(false, true)) return
        XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val v = param.thisObject as? View ?: return
                if (v.javaClass.name != INDICATOR) return
                indicator = WeakReference(v)
                wireUpslide(v.context.applicationContext)
                main.post { apply(animated = false) }
            }
        })
        XposedBridge.log("OriginHomePill: upslide hooks installed")
    }

    private fun wireUpslide(app: Context) {
        if (!upslideWired.compareAndSet(false, true)) return
        val cr = app.contentResolver
        fun load() {
            enabled = runCatching { Settings.Global.getInt(cr, KEY, 0) == 1 }.getOrDefault(false)
        }
        load()
        runCatching {
            cr.registerContentObserver(Settings.Global.getUriFor(KEY), false, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    load()
                    if (enabled) app.sendBroadcast(Intent(ACTION_QUERY).setPackage(LAUNCHER))
                    schedule()
                }
            })
        }
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    onHome = intent.getBooleanExtra(EXTRA_HOME, false)
                    schedule()
                }
            }
            val filter = IntentFilter(ACTION_STATE)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, filter)
        }.onFailure { XposedBridge.log("OriginHomePill: upslide receiver: $it") }
        // Upslide may have (re)started while the home screen is already showing.
        runCatching { app.sendBroadcast(Intent(ACTION_QUERY).setPackage(LAUNCHER)) }
    }

    /** Hiding waits a moment (a dialog or a quick focus blip must not flash the pill); showing is immediate. */
    private fun schedule() {
        main.removeCallbacks(hideRunnable)
        if (enabled && onHome) main.postDelayed(hideRunnable, HIDE_DELAY_MS) else apply(animated = true)
    }

    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)

    private fun apply(animated: Boolean) {
        val view = indicator?.get() ?: return
        val target = view.rootView ?: view
        val alpha = if (enabled && onHome) 0f else 1f
        target.animate().cancel()
        if (!animated || !target.isAttachedToWindow) {
            target.alpha = alpha
            return
        }
        if (target.alpha == alpha) return
        target.animate()
            .alpha(alpha)
            .setDuration(if (alpha == 0f) HIDE_DURATION_MS else SHOW_DURATION_MS)
            .setInterpolator(emphasized)
            .start()
    }
}
