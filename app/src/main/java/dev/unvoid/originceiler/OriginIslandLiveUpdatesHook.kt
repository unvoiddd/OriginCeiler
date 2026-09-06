package dev.unvoid.originceiler

import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class OriginIslandLiveUpdatesHook {
    fun install(classLoader: ClassLoader) {
        if (overseasHooked.compareAndSet(false, true)) runCatching(::hookOverseasGate).onFailure(XposedBridge::log)
        hookLockIslandVisibility()
        installForLoader(classLoader)
        hookClassLoaderDiscovery()
        hookAlwaysExpanded(classLoader)
    }

    private fun installForLoader(classLoader: ClassLoader) {
        synchronized(hookedLoaders) {
            if (hookedLoaders.containsKey(classLoader)) return
            hookedLoaders[classLoader] = true
        }
        hookAppReminders(classLoader)
        deoptimizeKnownCallers(classLoader)
        runCatching { hookExpandedContentRefresh(classLoader) }.onFailure(XposedBridge::log)
        runCatching { hookIslandUpdateOperation(classLoader) }.onFailure(XposedBridge::log)
        runCatching { hookLiveUpdateDispatch(classLoader) }.onFailure(XposedBridge::log)
        runCatching { hookFlashlightContent(classLoader) }.onFailure(XposedBridge::log)
        registerFlashlightIsland(classLoader)
    }

    private fun hookLockIslandVisibility() {
        if (!lockVisibilityHooked.compareAndSet(false, true)) return
        XposedBridge.hookAllMethods(View::class.java, "setVisibility", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!lockIslandActive.get() || !isLockIslandContainer(view)) return
                if (param.args.firstOrNull() is Int) param.args[0] = View.VISIBLE
            }
        })
        XposedBridge.hookAllMethods(View::class.java, "setAlpha", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!lockIslandActive.get() || !isLockIslandContainer(view)) return
                if (param.args.firstOrNull() is Float) param.args[0] = 1f
            }
        })
    }

    private fun isLockIslandContainer(view: View): Boolean {
        return view.javaClass.name in setOf(
            DYNAMIC_CAPSULE_CLASS,
            "com.vivo.systemuiplugin.superx.statusbar.CapsuleFrameLayout"
        )
    }

    private fun hookClassLoaderDiscovery() {
        if (!classLoaderHooked.compareAndSet(false, true)) return
        XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val loaded = param.getResult() as? Class<*> ?: return
                val name = loaded.name
                if (name == PLUGIN_LISTENER_CLASS || name == LIVE_POLICY_CLASS || name == DYNAMIC_CAPSULE_CLASS) {
                    loaded.classLoader?.let { loader -> runCatching { installForLoader(loader) }.onFailure(XposedBridge::log) }
                }
            }
        })
    }

    private fun hookOverseasGate() {
        val type = Class.forName("android.os.FtBuild", false, null)
        val method = type.getDeclaredMethod("isOverSeas").apply { isAccessible = true }
        deoptimize(method)
        XposedBridge.hookAllMethods(type, "isOverSeas", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (islandFeaturesEnabled() && relevantCaller()) param.setResult(true)
            }
        })
    }

    private fun hookAppReminders(classLoader: ClassLoader) {
        runCatching {
            val type = Class.forName(
                "com.vivo.systemui.statusbar.notification.settings.island.controllers.AppReminderPreferenceController",
                false,
                classLoader
            )
            type.getDeclaredMethod("isAvailable").apply {
                isAccessible = true
                deoptimize(this)
            }
            XposedBridge.hookAllMethods(type, "isAvailable", object : XC_MethodHook(Int.MAX_VALUE) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (islandFeaturesEnabled()) param.setResult(true)
                }
            })
        }.onFailure(XposedBridge::log)
        runCatching {
            val settingsType = Class.forName(
                "com.vivo.systemui.statusbar.notification.settings.AtomIslandSettings",
                false,
                classLoader
            )
            val resourcesType = Class.forName(
                "com.vivo.systemuiplugin.systemuisettings.R\$xml",
                false,
                classLoader
            )
            val resourceId = resourcesType.getDeclaredField("vivo_overseas_island_settings").apply { isAccessible = true }.getInt(null)
            settingsType.getDeclaredMethod("getPreferenceScreenResId").apply {
                isAccessible = true
                deoptimize(this)
            }
            XposedBridge.hookAllMethods(settingsType, "getPreferenceScreenResId", object : XC_MethodHook(Int.MAX_VALUE) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (islandFeaturesEnabled()) param.setResult(resourceId)
                }
            })
        }.onFailure(XposedBridge::log)
    }

    private fun hookExpandedContentRefresh(classLoader: ClassLoader) {
        val type = Class.forName(DYNAMIC_CAPSULE_CLASS, false, classLoader)
        val updateMethod = type.getDeclaredMethod("updateDataDisplay", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
            deoptimize(this)
        }
        XposedBridge.hookAllMethods(type, updateMethod.name, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!islandFeaturesEnabled() || param.args.firstOrNull() != 4) return
                val target = param.thisObject
                if (target !is View) return
                target.postDelayed({
                    runCatching {
                        type.getDeclaredMethod("requestContentUpdate").apply { isAccessible = true }.invoke(target)
                    }
                }, 150L)
            }
        })
    }

    private fun hookLiveUpdateDispatch(classLoader: ClassLoader) {
        val listenerType = Class.forName(PLUGIN_LISTENER_CLASS, false, classLoader)
        synchronized(hookedDispatchClasses) {
            if (hookedDispatchClasses.containsKey(listenerType)) return
            hookedDispatchClasses[listenerType] = true
        }
        XposedBridge.hookAllMethods(listenerType, "onNotificationPosted", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                val sbn = param.args.filterIsInstance<StatusBarNotification>().firstOrNull() ?: return
                val notification = sbn.notification
                if (convertedProgressKeys.contains(sbn.key) &&
                    (!isProgressNotification(notification) || isFinishedProgress(notification))
                ) {
                    runCatching { removeFromNativeIsland(classLoader, sbn.key) }.onFailure(XposedBridge::log)
                    convertedProgressKeys.remove(sbn.key)
                    return
                }
                when {
                    enabled() && isLiveUpdate(notification) -> runCatching { sendToNativeIsland(classLoader, sbn, false) }.onFailure(XposedBridge::log)
                    allProgressEnabled() && isProgressNotification(notification) && !isFinishedProgress(notification) -> runCatching {
                        sendToNativeIsland(classLoader, sbn, true)
                    }.onFailure(XposedBridge::log)
                }
            }
        })
        XposedBridge.hookAllMethods(listenerType, "onNotificationRemoved", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val sbn = param.args.filterIsInstance<StatusBarNotification>().firstOrNull() ?: return
                if (!convertedProgressKeys.remove(sbn.key)) return
                runCatching { removeFromNativeIsland(classLoader, sbn.key) }.onFailure(XposedBridge::log)
            }
        })
    }

    private fun hookFlashlightContent(classLoader: ClassLoader) {
        val contentType = Class.forName(CAPSULE_CONTENT_CLASS, false, classLoader)
        synchronized(hookedFlashlightContentClasses) {
            if (hookedFlashlightContentClasses.containsKey(contentType)) return
            hookedFlashlightContentClasses[contentType] = true
        }
        XposedBridge.hookAllMethods(contentType, "setContentView", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!flashlightEnabled()) return
                val host = param.thisObject as? View ?: return
                val incoming = param.args.firstOrNull() as? View ?: return
                if (incoming is FlashlightIslandControlView) return
                val entryName = runCatching { host.resources.getResourceEntryName(host.id) }.getOrNull() ?: return
                if (entryName != "cap_expanded" || !containsFlashlightMarker(incoming)) return
                param.args[0] = FlashlightIslandControlView(host.context)
            }
        })
    }

    private fun containsFlashlightMarker(view: View): Boolean {
        if (view.contentDescription?.toString()?.contains(FLASHLIGHT_TITLE, true) == true) return true
        if (view is TextView && view.text?.toString()?.contains(FLASHLIGHT_TITLE, true) == true) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsFlashlightMarker(view.getChildAt(index))) return true
            }
        }
        return false
    }

    private fun registerFlashlightIsland(classLoader: ClassLoader) {
        if (!flashlightEnabled()) return
        runCatching { Class.forName(LIVE_POLICY_CLASS, false, classLoader) }.getOrNull() ?: return
        synchronized(flashlightCallbacks) {
            if (flashlightCallbacks.containsKey(classLoader)) return
        }
        val application = currentApplication() ?: return
        val manager = application.getSystemService(Application.CAMERA_SERVICE) as CameraManager
        val cameraId = FlashlightIslandControlView.flashCameraId(application) ?: return
        if (flashlightOffReceiverRegistered.compareAndSet(false, true)) {
            application.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action == ACTION_FLASHLIGHT_OFF) runCatching { manager.setTorchMode(cameraId, false) }
                    }
                },
                IntentFilter(ACTION_FLASHLIGHT_OFF),
                Context.RECEIVER_NOT_EXPORTED
            )
        }
        val handler = Handler(Looper.getMainLooper())
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id != cameraId) return
                handler.post {
                    runCatching {
                        val notification = flashlightNotification()
                        if (enabled) {
                            sendToNativeIsland(classLoader, notification, true)
                        } else {
                            removeFromNativeIsland(classLoader, notification.key)
                            convertedProgressKeys.remove(notification.key)
                        }
                    }.onFailure(XposedBridge::log)
                }
            }
        }
        synchronized(flashlightCallbacks) { flashlightCallbacks[classLoader] = callback }
        runCatching { manager.registerTorchCallback(callback, handler) }.onFailure(XposedBridge::log)
    }

    private fun registerLockScreenIsland(classLoader: ClassLoader) {
        if (!lockScreenStateEnabled()) return
        runCatching { Class.forName(LIVE_POLICY_CLASS, false, classLoader) }.getOrNull() ?: return
        synchronized(lockIslandPollers) {
            if (lockIslandPollers.containsKey(classLoader)) return
        }
        val application = currentApplication() ?: return
        val keyguard = application.getSystemService(KeyguardManager::class.java)
        val power = application.getSystemService(PowerManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        var wasShowing = false
        var lastLocked: Boolean? = null
        var hideScheduled = false
        val hide = Runnable {
            hideScheduled = false
            if (keyguard.isKeyguardLocked) return@Runnable
            runCatching { removeFromNativeIsland(classLoader, lockScreenNotification(false).key) }
            convertedProgressKeys.remove(lockScreenNotification(false).key)
            lockIslandActive.set(false)
            wasShowing = false
            lastLocked = null
        }
        lateinit var poller: Runnable
        poller = Runnable {
            if (!lockScreenStateEnabled()) {
                handler.removeCallbacks(hide)
                runCatching { removeFromNativeIsland(classLoader, lockScreenNotification(false).key) }
                convertedProgressKeys.remove(lockScreenNotification(false).key)
                lockIslandActive.set(false)
                return@Runnable
            }
            if (!power.isInteractive) {
                handler.removeCallbacks(hide)
                hideScheduled = false
                if (lockIslandActive.get()) runCatching { removeFromNativeIsland(classLoader, lockScreenNotification(true).key) }
                convertedProgressKeys.remove(lockScreenNotification(true).key)
                lockIslandActive.set(false)
                wasShowing = false
                lastLocked = null
                handler.postDelayed(poller, LOCK_POLL_INTERVAL)
                return@Runnable
            }
            val showing = keyguard.isKeyguardLocked
            val locked = keyguard.isDeviceLocked
            if (showing) {
                handler.removeCallbacks(hide)
                hideScheduled = false
                wasShowing = true
                if (!lockIslandActive.get() || lastLocked != locked) {
                    lockIslandActive.set(true)
                    runCatching { sendToNativeIsland(classLoader, lockScreenNotification(locked), true) }.onFailure(XposedBridge::log)
                    lastLocked = locked
                }
            } else if (wasShowing || lockIslandActive.get()) {
                if (!lockIslandActive.get() || lastLocked != false) {
                    lockIslandActive.set(true)
                    runCatching { sendToNativeIsland(classLoader, lockScreenNotification(false), true) }.onFailure(XposedBridge::log)
                    lastLocked = false
                }
                if (!hideScheduled) {
                    hideScheduled = true
                    handler.postDelayed(hide, LOCK_HOME_DELAY)
                }
            }
            handler.postDelayed(poller, LOCK_POLL_INTERVAL)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON || intent.action == Intent.ACTION_USER_PRESENT) {
                    handler.removeCallbacks(poller)
                    handler.post(poller)
                }
            }
        }
        application.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            Context.RECEIVER_NOT_EXPORTED
        )
        synchronized(lockIslandPollers) { lockIslandPollers[classLoader] = poller }
        handler.post(poller)
    }

    private fun hookLockScreenIslandBean(classLoader: ClassLoader) {
        val beanType = Class.forName("com.vivo.island.data.AtomIslandsBean", false, classLoader)
        synchronized(hookedLockBeanClasses) {
            if (hookedLockBeanClasses.containsKey(beanType)) return
            hookedLockBeanClasses[beanType] = true
        }
        XposedBridge.hookAllMethods(beanType, "forceShowIsland", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isLockScreenBean(param.thisObject)) param.setResult(true)
            }
        })
        XposedBridge.hookAllMethods(beanType, "forceShowCardInKeyguard", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isLockScreenBean(param.thisObject)) param.setResult(true)
            }
        })
    }

    private fun isLockScreenBean(bean: Any): Boolean {
        if (!lockScreenStateEnabled() || !lockIslandActive.get()) return false
        val sbn = runCatching { bean.javaClass.getMethod("getSbn").invoke(bean) as? StatusBarNotification }.getOrNull()
        if (sbn != null) return sbn.id == LOCK_NOTIFICATION_ID && sbn.tag == "originceiler_lock_state"
        val notificationId = runCatching { bean.javaClass.getMethod("getNotificationId").invoke(bean)?.toString() }.getOrNull()
        return notificationId?.contains("originceiler_lock_state") == true
    }

    private fun lockScreenNotification(locked: Boolean): StatusBarNotification {
        val application = currentApplication() ?: error("Application is unavailable")
        val packageName = "com.android.systemui"
        val packageInfo = application.packageManager.getApplicationInfo(packageName, 0)
        val icon = Icon.createWithResource("dev.unvoid.originceiler", if (locked) R.drawable.island_lock else R.drawable.island_unlock)
        val style = Notification.ProgressStyle()
            .addProgressSegment(Notification.ProgressStyle.Segment(1))
            .setProgress(if (locked) 0 else 1)
            .setStyledByProgress(true)
        val notification = Notification.Builder(application, "originceiler_lock_state")
            .setSmallIcon(icon)
            .setContentTitle("")
            .setContentText("")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(style)
            .build()
        notification.extras.putString(Notification.EXTRA_TEMPLATE, "android.app.Notification\$ProgressStyle")
        notification.extras.putInt("progress_style_business_type", 1)
        notification.extras.putBoolean("notification.superx.showNotify", false)
        notification.extras.putString("island.superx.landingInfo", packageName)
        notification.extras.putParcelable("island.superx.leftInfo.icon", icon)
        notification.extras.putCharSequence("island.superx.leftInfo.content", "")
        return StatusBarNotification(
            packageName,
            packageName,
            LOCK_NOTIFICATION_ID,
            "originceiler_lock_state",
            packageInfo.uid,
            0,
            0,
            notification,
            android.os.UserHandle.getUserHandleForUid(packageInfo.uid),
            System.currentTimeMillis()
        )
    }

    private fun flashlightNotification(): StatusBarNotification {
        val application = currentApplication() ?: error("Application is unavailable")
        val packageName = "com.android.systemui"
        val packageInfo = application.packageManager.getApplicationInfo(packageName, 0)
        val maximum = FlashlightIslandControlView.maximumStrength(application).coerceAtLeast(1)
        val progress = Settings.Global.getInt(application.contentResolver, FlashlightIslandControlView.SETTING_STRENGTH, maximum)
            .coerceIn(1, maximum)
        val icon = Icon.createWithResource("dev.unvoid.originceiler", R.drawable.ic_flashlight_island)
        val offIntent = PendingIntent.getBroadcast(
            application,
            FLASHLIGHT_NOTIFICATION_ID,
            Intent(ACTION_FLASHLIGHT_OFF).setPackage(application.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val style = Notification.ProgressStyle()
            .addProgressSegment(Notification.ProgressStyle.Segment(maximum))
            .setProgress(progress)
            .setStyledByProgress(true)
        val notification = Notification.Builder(application, "originceiler_flashlight")
            .setSmallIcon(icon)
            .setContentTitle(FLASHLIGHT_TITLE)
            .setContentText(FLASHLIGHT_TITLE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(style)
            .build()
        notification.extras.putString(Notification.EXTRA_TEMPLATE, "android.app.Notification\$ProgressStyle")
        notification.extras.putInt("progress_style_business_type", 1)
        notification.extras.putString("island.superx.landingInfo", packageName)
        notification.extras.putParcelable("island.superx.leftInfo.icon", icon)
        notification.extras.putCharSequence("island.superx.leftInfo.content", FLASHLIGHT_TITLE)
        notification.extras.putCharSequence("island.superx.rightInfo.capsuleContent", "Выкл.")
        notification.extras.putInt("island.superx.rightInfo.capsuleBgColor", 0x33ffffff)
        notification.extras.putParcelable("island.superx.rightInfo.clickResp", offIntent)
        return StatusBarNotification(
            packageName,
            packageName,
            FLASHLIGHT_NOTIFICATION_ID,
            "originceiler_flashlight",
            packageInfo.uid,
            0,
            0,
            notification,
            android.os.UserHandle.getUserHandleForUid(packageInfo.uid),
            System.currentTimeMillis()
        )
    }

    private fun removeFromNativeIsland(classLoader: ClassLoader, notificationKey: String) {
        val policyType = Class.forName(LIVE_POLICY_CLASS, false, classLoader)
        val policy = policyType.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
        policyType.getDeclaredMethod("deleteNotification", String::class.java)
            .apply { isAccessible = true }
            .invoke(policy, notificationKey)
    }

    private fun hookIslandUpdateOperation(classLoader: ClassLoader) {
        val proxyType = Class.forName(ISLAND_SERVICE_PROXY_CLASS, false, classLoader)
        synchronized(hookedIslandProxyClasses) {
            if (hookedIslandProxyClasses.containsKey(proxyType)) return
            hookedIslandProxyClasses[proxyType] = true
        }
        XposedBridge.hookAllMethods(proxyType, "postIslandDataBean", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val bean = param.args.firstOrNull() ?: return
                if (isLockScreenBean(bean)) {
                    bean.javaClass.getMethod("setForceShowIsland", Boolean::class.javaPrimitiveType).invoke(bean, true)
                    bean.javaClass.getMethod("setForceShowCardInKeyguard", Boolean::class.javaPrimitiveType).invoke(bean, true)
                }
                val operation = convertedProgressOperation.get() ?: return
                bean.javaClass.methods.firstOrNull {
                    it.name == "setOperation" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
                }?.invoke(bean, operation)
            }
        })
    }

    private fun sendToNativeIsland(classLoader: ClassLoader, source: StatusBarNotification, convertProgress: Boolean) {
        val policyType = Class.forName(LIVE_POLICY_CLASS, false, classLoader)
        val policy = policyType.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
        val tracked = policyType.getDeclaredField("mCurrentShowChipsNotisInfo").apply { isAccessible = true }
            .get(policy) as? Map<*, *>
        if (!convertProgress && tracked?.containsKey(source.key) == true) {
            if (allProgressEnabled() && isProgressNotification(source.notification)) convertedProgressKeys.add(source.key)
            return
        }
        val isConvertedUpdate = convertProgress && convertedProgressKeys.contains(source.key)
        val sbn = if (convertProgress) convertedProgressNotification(source, isConvertedUpdate) else source
        val template = sbn.notification.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        if (convertProgress) convertedProgressOperation.set(if (isConvertedUpdate) 1 else 0)
        try {
            if (template.endsWith("\$ProgressStyle")) {
                policyType.getDeclaredMethod("handleProgressStyleNotification", StatusBarNotification::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(policy, sbn, false)
            } else {
                policyType.getDeclaredMethod("handleOnGoingNotification", StatusBarNotification::class.java)
                    .apply { isAccessible = true }
                    .invoke(policy, sbn)
            }
        } finally {
            if (convertProgress) convertedProgressOperation.remove()
        }
        if (convertProgress || allProgressEnabled() && isProgressNotification(source.notification)) convertedProgressKeys.add(source.key)
    }

    private fun convertedProgressNotification(source: StatusBarNotification, update: Boolean): StatusBarNotification {
        val application = currentApplication() ?: error("Application is unavailable")
        val original = source.notification
        val extras = original.extras
        val maximum = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 100).coerceAtLeast(1)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, 0).coerceIn(0, maximum)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val style = Notification.ProgressStyle()
            .addProgressSegment(Notification.ProgressStyle.Segment(maximum))
            .setProgress(progress)
            .setProgressIndeterminate(indeterminate)
            .setStyledByProgress(true)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: runCatching {
                val info = application.packageManager.getApplicationInfo(source.packageName, 0)
                application.packageManager.getApplicationLabel(info)
            }.getOrNull()
            ?: source.packageName
        val content = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?: if (indeterminate) "In progress" else "$progress / $maximum"
        val icon = original.smallIcon ?: runCatching {
            val info = application.packageManager.getApplicationInfo(source.packageName, 0)
            Icon.createWithResource(source.packageName, info.icon)
        }.getOrNull()
        val builder = Notification.Builder(application, original.channelId.orEmpty())
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(original.contentIntent)
            .setDeleteIntent(original.deleteIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(original.`when`)
            .setShowWhen(original.`when` > 0L)
            .setColor(original.color)
            .setStyle(style)
        if (icon != null) builder.setSmallIcon(icon)
        val converted = builder.build()
        converted.extras.putAll(original.extras)
        converted.extras.putString(Notification.EXTRA_TEMPLATE, "android.app.Notification\$ProgressStyle")
        converted.extras.putInt("notification.superx.operation", if (update) 1 else 0)
        converted.extras.putBoolean("notification.superx.showNotify", false)
        converted.extras.putInt("notification.superx.template", 1)
        converted.extras.putParcelable("notification.superx.clickResp", original.contentIntent)
        converted.extras.putString("notification.superx.scene", "PROGRESS")
        converted.extras.putBundle("notification.superx.baseInfos", Bundle().apply {
            putParcelable("notification.superx.baseInfos.icon", icon)
            putCharSequence("notification.superx.baseInfos.title", title)
            putCharSequence("notification.superx.baseInfos.content", content)
        })
        return StatusBarNotification(
            source.packageName,
            source.opPkg,
            source.id,
            source.tag,
            source.uid,
            0,
            0,
            converted,
            source.user,
            source.postTime
        )
    }

    private fun hookAlwaysExpanded(classLoader: ClassLoader) {
        val rowType = runCatching {
            Class.forName("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", false, classLoader)
        }.getOrNull() ?: return
        synchronized(hookedRowClasses) {
            if (hookedRowClasses.containsKey(rowType)) return
            hookedRowClasses[rowType] = true
        }
        listOf("setUserExpanded", "setSystemExpanded", "setExpandable").forEach { methodName ->
            XposedBridge.hookAllMethods(rowType, methodName, object : XC_MethodHook(Int.MAX_VALUE) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled() || !isLiveUpdateRow(param.thisObject)) return
                    if (param.args.isNotEmpty() && param.args[0] is Boolean) param.args[0] = true
                }
            })
        }
        listOf("onNotificationUpdated", "updateNotification", "resetUserExpansion").forEach { methodName ->
            XposedBridge.hookAllMethods(rowType, methodName, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (enabled() && isLiveUpdateRow(param.thisObject)) forceExpanded(param.thisObject)
                }
            })
        }
    }

    private fun isLiveUpdateRow(row: Any): Boolean {
        val sbn = runCatching {
            row.javaClass.getMethod("getStatusBarNotification").invoke(row) as? StatusBarNotification
        }.getOrNull() ?: return false
        return isLiveUpdate(sbn.notification)
    }

    private fun forceExpanded(row: Any) {
        row.javaClass.methods.firstOrNull {
            it.name == "setSystemExpanded" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }?.invoke(row, true)
        row.javaClass.methods.firstOrNull {
            it.name == "setUserExpanded" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }?.invoke(row, true)
        (row as? View)?.requestLayout()
    }

    private fun isLiveUpdate(notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        if (notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0) return true
        return notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, false) &&
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0
    }

    private fun deoptimizeKnownCallers(classLoader: ClassLoader) {
        val classes = listOf(
            "com.vivo.systemuiplugin.superx.notification.ongoing.SuperXOnGoingNotificationPolicy\$1",
            "com.vivo.systemuiplugin.superx.notification.ongoing.SuperXOnGoingNotificationPolicy",
            "com.vivo.systemuiplugin.superx.statusbar.CapsuleFrameLayout",
            "com.vivo.island.SuperXNotificationDataController",
            "com.vivo.island.template.AtomMainIslandLeftTemplate",
            "com.vivo.island.template.AtomSideIslandTemplate",
            "com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl",
            "com.android.systemui.statusbar.notification.collection.coordinator.HeadsUpCoordinator"
        )
        classes.forEach { name ->
            runCatching {
                Class.forName(name, false, classLoader).declaredMethods
                    .filter { relevantMethod(name, it) }
                    .forEach {
                        it.isAccessible = true
                        deoptimize(it)
                    }
            }.onFailure(XposedBridge::log)
        }
    }

    private fun relevantMethod(className: String, method: Method): Boolean {
        val name = method.name
        return when {
            className.endsWith("SuperXOnGoingNotificationPolicy\$1") -> name == "onSuperXNotificationArrived" || name == "onSuperXNotificationRemoved"
            className.endsWith("SuperXOnGoingNotificationPolicy") -> name == "handlePackageDied" || name == "onHeadsUpNotificationArrived" || name == "isOverseasTravel"
            className.endsWith("CapsuleFrameLayout") -> name.contains("lambda\$new")
            className.endsWith("SuperXNotificationDataController") -> name == "notifyArrived"
            className.endsWith("AtomMainIslandLeftTemplate") || className.endsWith("AtomSideIslandTemplate") -> name.contains("update") || name.contains("lambda\$")
            className.endsWith("HeadsUpManagerImpl") -> name == "showNotification"
            className.endsWith("HeadsUpCoordinator") -> true
            else -> false
        }
    }

    private fun deoptimize(method: Method) {
        runCatching {
            XposedBridge::class.java.getDeclaredMethod("deoptimizeMethod", java.lang.reflect.Member::class.java)
                .invoke(null, method)
        }.onFailure(XposedBridge::log)
    }

    private fun relevantCaller(): Boolean {
        return Thread.currentThread().stackTrace.any { element ->
            val name = element.className
            name.startsWith("com.vivo.island.") ||
                name.startsWith("com.vivo.systemui.statusbar.notification.settings.island.") ||
                name == LIVE_POLICY_CLASS && element.methodName == "isOverseasTravel"
        }
    }

    private fun enabled(): Boolean {
        val application = currentApplication() ?: return false
        return Settings.Global.getInt(application.contentResolver, SETTING_ENABLED, 0) == 1
    }

    private fun allProgressEnabled(): Boolean {
        val application = currentApplication() ?: return false
        return Settings.Global.getInt(application.contentResolver, SETTING_ALL_PROGRESS, 0) == 1
    }

    private fun flashlightEnabled(): Boolean {
        val application = currentApplication() ?: return false
        return Settings.Global.getInt(application.contentResolver, SETTING_FLASHLIGHT, 0) == 1
    }

    private fun lockScreenStateEnabled(): Boolean {
        val application = currentApplication() ?: return false
        return Settings.Global.getInt(application.contentResolver, SETTING_LOCK_SCREEN_STATE, 0) == 1
    }

    private fun islandFeaturesEnabled(): Boolean = enabled() || allProgressEnabled() || flashlightEnabled()

    private fun currentApplication(): Application? {
        return runCatching {
            Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication").invoke(null) as? Application
        }.getOrNull()
    }

    companion object {
        const val SETTING_ENABLED = "originroottoolbox_live_updates_origin_island"
        const val SETTING_ALL_PROGRESS = "originroottoolbox_all_progress_origin_island"
        const val SETTING_FLASHLIGHT = "originroottoolbox_flashlight_origin_island"
        const val SETTING_LOCK_SCREEN_STATE = "originroottoolbox_lock_screen_state_origin_island"
        private const val PLUGIN_LISTENER_CLASS = "com.vivo.systemuiplugin.systemui.statusbar.notification.PluginNotificationListenerImpl"
        private const val LIVE_POLICY_CLASS = "com.vivo.systemuiplugin.superx.notification.ongoing.SuperXOnGoingNotificationPolicy"
        private const val DYNAMIC_CAPSULE_CLASS = "com.vivo.island.view.DynamicCapsuleContainer"
        private const val ISLAND_SERVICE_PROXY_CLASS = "com.vivo.systemuiplugin.systemui.islands.IslandServiceProxy"
        private const val CAPSULE_CONTENT_CLASS = "com.vivo.island.view.CapsuleContentView"
        private const val FLASHLIGHT_TITLE = "Фонарик"
        private const val FLASHLIGHT_NOTIFICATION_ID = 0x4f49
        private const val ACTION_FLASHLIGHT_OFF = "com.android.systemui.action.ORIGINCEILER_FLASHLIGHT_OFF"
        private const val LOCK_NOTIFICATION_ID = 0x4f4c
        private const val LOCK_POLL_INTERVAL = 150L
        private const val LOCK_HOME_DELAY = 1000L
        private val overseasHooked = AtomicBoolean(false)
        private val classLoaderHooked = AtomicBoolean(false)
        private val hookedLoaders = Collections.synchronizedMap(WeakHashMap<ClassLoader, Boolean>())
        private val hookedDispatchClasses = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
        private val hookedRowClasses = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
        private val hookedIslandProxyClasses = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
        private val hookedFlashlightContentClasses = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
        private val hookedLockBeanClasses = Collections.synchronizedMap(WeakHashMap<Class<*>, Boolean>())
        private val flashlightCallbacks = Collections.synchronizedMap(WeakHashMap<ClassLoader, CameraManager.TorchCallback>())
        private val flashlightOffReceiverRegistered = AtomicBoolean(false)
        private val lockVisibilityHooked = AtomicBoolean(false)
        private val lockIslandActive = AtomicBoolean(false)
        private val lockIslandPollers = Collections.synchronizedMap(WeakHashMap<ClassLoader, Runnable>())
        private val convertedProgressKeys = Collections.synchronizedSet(mutableSetOf<String>())
        private val convertedProgressOperation = ThreadLocal<Int>()

        private fun isProgressNotification(notification: Notification): Boolean {
            if (notification.category == Notification.CATEGORY_TRANSPORT || notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false
            if (notification.category == Notification.CATEGORY_MESSAGE ||
                notification.extras.containsKey(Notification.EXTRA_MESSAGES) ||
                notification.extras.containsKey(Notification.EXTRA_HISTORIC_MESSAGES)
            ) return false
            if (notification.actions.orEmpty().any { action ->
                    !action.remoteInputs.isNullOrEmpty() ||
                        action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY ||
                        action.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ
                }
            ) return false
            val template = notification.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
            if (template.endsWith("\$ProgressStyle")) return true
            val hasProgress = notification.extras.containsKey(Notification.EXTRA_PROGRESS)
            val maximum = notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            val indeterminate = notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
            return hasProgress && (maximum > 0 || indeterminate)
        }

        private fun isFinishedProgress(notification: Notification): Boolean {
            if (notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) return false
            val maximum = notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            val progress = notification.extras.getInt(Notification.EXTRA_PROGRESS, 0)
            return maximum > 0 && progress >= maximum
        }
    }
}
