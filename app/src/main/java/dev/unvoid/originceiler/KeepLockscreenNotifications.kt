package dev.unvoid.originceiler

import android.app.Notification
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.StatusBarNotification
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Keep notifications on lockscreen" (com.android.systemui): vivo normally shows on the lock screen
 * only what arrived while locked and drops everything else from it on unlock. Here nothing is
 * hidden (rows go only when opened / swiped away), and they are folded into vivo's own
 * "N notifications" bubble (keyguard notification style 0, icon style). vivo's own rows, no glass.
 */
object KeepLockscreenNotifications {
    /** Setting key kept from the earlier "iOS lockscreen" tweak, so it stays on for who had it on. */
    const val SETTING_ENABLED = "originroottoolbox_ios_lockscreen"

    private const val TAG = "OriginKeepNotifications"
    private const val VIS_PROVIDER = "com.android.systemui.statusbar.notification.interruption.VivoKeyguardNotificationVisibilityProviderImpl"
    private const val SECURE_UTILS = "com.vivo.common.utils.SettingsUtils\$Secure"
    private const val STYLE_KEY = "keyguard_notification_style"

    /** vivo's keyguard notification style setting value that maps to the "N notifications" bubble. */
    private const val STYLE_BUBBLE = 0

    // ------------------------------------------------------------------ setting

    @Volatile private var cachedEnabled: Boolean? = null
    private val observerInstalled = AtomicBoolean(false)

    fun enabled(context: Context): Boolean {
        cachedEnabled?.let { return it }
        val cr = context.applicationContext?.contentResolver ?: context.contentResolver
        val value = runCatching { Settings.Global.getInt(cr, SETTING_ENABLED, 0) == 1 }.getOrDefault(false)
        if (observerInstalled.compareAndSet(false, true)) {
            runCatching {
                cr.registerContentObserver(Settings.Global.getUriFor(SETTING_ENABLED), false, object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        cachedEnabled = runCatching { Settings.Global.getInt(cr, SETTING_ENABLED, 0) == 1 }.getOrDefault(false)
                    }
                })
                cachedEnabled = value
            }.onFailure { observerInstalled.set(false) }
        }
        return value
    }

    // ------------------------------------------------------------------ hooks

    private val installed = AtomicBoolean(false)
    private var providerRef = WeakReference<Any>(null)

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        runCatching { hookKeyguardFilter(classLoader) }.onFailure { log("filter: $it") }
        runCatching { hookBubbleStyle(classLoader) }.onFailure { log("style: $it") }
        log("installed")
    }

    private fun log(message: String) {
        runCatching { XposedBridge.log("$TAG: $message") }
        KyantGlassDebug.note(appContext(), "$TAG ${System.currentTimeMillis()} $message")
    }

    private fun appContext(): Context? =
        runCatching { Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context? }.getOrNull()

    /** vivo's keyguard filter hides what was seen before locking; here it keeps every ordinary row. */
    private fun hookKeyguardFilter(classLoader: ClassLoader) {
        val type = Class.forName(VIS_PROVIDER, false, classLoader)
        XposedBridge.hookAllMethods(type, "shouldHideNotification", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                providerRef = WeakReference(param.thisObject)
                if (param.result != true) return
                val ctx = appContext() ?: return
                if (!enabled(ctx)) return
                val entry = param.args.firstOrNull() ?: return
                val sbn = sbnOf(entry) ?: return
                if (keepOnKeyguard(sbn)) param.result = false
            }
        })
    }

    /** vivo reads its lock screen notification style through SettingsUtils.Secure: report the bubble. */
    private fun hookBubbleStyle(classLoader: ClassLoader) {
        val type = Class.forName(SECURE_UTILS, false, classLoader)
        XposedBridge.hookAllMethods(type, "getInt", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args.lastOrNull() != STYLE_KEY) return
                val ctx = param.args.firstOrNull() as? Context ?: return
                if (enabled(ctx)) param.result = STYLE_BUBBLE
            }
        })
    }

    private fun sbnOf(entry: Any): StatusBarNotification? {
        var t: Class<*>? = entry.javaClass
        while (t != null) {
            runCatching { t!!.getDeclaredField("mSbn").apply { isAccessible = true } }.getOrNull()?.let { return it.get(entry) as? StatusBarNotification }
            t = t.superclass
        }
        return runCatching { entry.javaClass.getMethod("getSbn").invoke(entry) as? StatusBarNotification }.getOrNull()
    }

    /** Ordinary notifications only: not secret, not ongoing / foreground services. */
    private fun keepOnKeyguard(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (n.visibility == Notification.VISIBILITY_SECRET) return false
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return false
        return true
    }
}
