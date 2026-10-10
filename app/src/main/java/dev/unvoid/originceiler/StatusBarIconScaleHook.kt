package dev.unvoid.originceiler

import android.content.Context
import android.database.ContentObserver
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class StatusBarIconScaleHook {
    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return

        val containerClass = runCatching {
            Class.forName("com.android.systemui.statusbar.phone.StatusIconContainer", false, classLoader)
        }.getOrNull()
        if (containerClass != null) {
            XposedBridge.hookAllMethods(containerClass, "onAttachedToWindow", object : XC_MethodHook(Int.MAX_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val container = param.thisObject as? ViewGroup ?: return
                    trackedContainers[container] = true
                    ensureObserver(container.context)
                    val wifiScale = getWifiScale(container.context)
                    val wifiX = getWifiX(container.context)
                    val iconsScale = getSystemIconsScale(container.context)
                    applyContainerChildren(container, wifiScale, iconsScale, wifiX)
                }
            })
            XposedBridge.hookAllMethods(containerClass, "onViewAdded", object : XC_MethodHook(Int.MAX_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val container = param.thisObject as? ViewGroup ?: return
                    val child = param.args.firstOrNull() as? View ?: return
                    trackedContainers[container] = true
                    ensureObserver(container.context)
                    val isWifi = isWifiView(child)
                    val targetScale = if (isWifi) getWifiScale(container.context) else getSystemIconsScale(container.context)
                    child.pivotX = child.width / 2f
                    child.pivotY = child.height / 2f
                    child.scaleX = targetScale
                    child.scaleY = targetScale
                }
            })
            XposedBridge.hookAllMethods(containerClass, "calculateIconTranslations", object : XC_MethodHook(Int.MAX_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val container = param.thisObject as? ViewGroup ?: return
                    trackedContainers[container] = true
                    ensureObserver(container.context)
                    val wifiScale = getWifiScale(container.context)
                    val wifiX = getWifiX(container.context)
                    val iconsScale = getSystemIconsScale(container.context)
                    val count = container.childCount
                    for (i in 0 until count) {
                        val child = container.getChildAt(i) ?: continue
                        val isWifi = isWifiView(child)
                        val targetScale = if (isWifi) wifiScale else iconsScale
                        val state = getViewState(container, child)
                        if (state != null) {
                            setFloatField(state, "scaleX", targetScale)
                            setFloatField(state, "scaleY", targetScale)
                            if (isWifi && wifiX != 0f) {
                                val current = (readField(state, "translationX") as? Number)?.toFloat() ?: 0f
                                setFloatField(state, "translationX", current + wifiX)
                            }
                        }
                    }
                }
            })
            XposedBridge.hookAllMethods(containerClass, "applyIconStates", object : XC_MethodHook(Int.MAX_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val container = param.thisObject as? ViewGroup ?: return
                    trackedContainers[container] = true
                    ensureObserver(container.context)
                    val wifiScale = getWifiScale(container.context)
                    val iconsScale = getSystemIconsScale(container.context)
                    val count = container.childCount
                    for (i in 0 until count) {
                        val child = container.getChildAt(i) ?: continue
                        val isWifi = isWifiView(child)
                        val targetScale = if (isWifi) wifiScale else iconsScale
                        child.pivotX = child.width / 2f
                        child.pivotY = child.height / 2f
                        child.scaleX = targetScale
                        child.scaleY = targetScale
                    }
                }
            })
        }

        val stateClass = runCatching {
            Class.forName("com.android.systemui.statusbar.phone.StatusIconContainer\$StatusIconState", false, classLoader)
        }.getOrNull()
        if (stateClass != null) {
            XposedBridge.hookAllMethods(stateClass, "applyToView", object : XC_MethodHook(Int.MAX_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val child = param.args.firstOrNull() as? View ?: return
                    val parent = child.parent as? ViewGroup
                    if (parent != null) trackedContainers[parent] = true
                    ensureObserver(child.context)
                    val isWifi = isWifiView(child)
                    val targetScale = if (isWifi) getWifiScale(child.context) else getSystemIconsScale(child.context)
                    child.pivotX = child.width / 2f
                    child.pivotY = child.height / 2f
                    child.scaleX = targetScale
                    child.scaleY = targetScale
                }
            })
        }

        val horBatteryClass = runCatching {
            Class.forName("com.vivo.systemui.battery.VivoHorBatteryIconView", false, classLoader)
        }.getOrNull()
        if (horBatteryClass != null) {
            XposedBridge.hookAllMethods(horBatteryClass, "onAttachedToWindow", object : XC_MethodHook(Int.MAX_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    trackedInnerBatteryViews[view] = true
                    ensureObserver(view.context)
                    applyInnerBatteryText(view, getBatteryTextScale(view.context), getBatteryTextWeight(view.context))
                }
            })
            XposedBridge.hookAllMethods(horBatteryClass, "loadDimen", object : XC_MethodHook(Int.MAX_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val raw = (readField(view, "mTextSize") as? Number)?.toFloat()
                    if (raw != null && raw > 0f) {
                        baseBatteryInnerSizes[view] = raw
                    }
                    applyInnerBatteryText(view, getBatteryTextScale(view.context), getBatteryTextWeight(view.context))
                }
            })
            XposedBridge.hookAllMethods(horBatteryClass, "createBatteryLevelTextPaint", object : XC_MethodHook(Int.MAX_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    applyInnerBatteryText(view, getBatteryTextScale(view.context), getBatteryTextWeight(view.context))
                }
            })
            XposedBridge.hookAllMethods(horBatteryClass, "createClearColorTextPaint", object : XC_MethodHook(Int.MAX_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    applyInnerBatteryText(view, getBatteryTextScale(view.context), getBatteryTextWeight(view.context))
                }
            })
            XposedBridge.hookAllMethods(horBatteryClass, "scaleBattery", object : XC_MethodHook(Int.MAX_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    trackedInnerBatteryViews[view] = true
                    applyInnerBatteryText(view, getBatteryTextScale(view.context), getBatteryTextWeight(view.context))
                }
            })
        }

        val batteryMeterClass = runCatching {
            Class.forName("com.android.systemui.battery.BatteryMeterView", false, classLoader)
        }.getOrNull()
        val batteryMeterHook = object : XC_MethodHook(Int.MAX_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                trackedBatteryViews[view] = true
                ensureObserver(view.context)
                applyBatteryScale(view, getBatteryScale(view.context), getBatteryX(view.context))
                applyBatteryMeterPercent(view, getBatteryTextScale(view.context), getBatteryTextWeight(view.context))
            }
        }
        if (batteryMeterClass != null) {
            XposedBridge.hookAllMethods(batteryMeterClass, "onAttachedToWindow", batteryMeterHook)
            XposedBridge.hookAllMethods(batteryMeterClass, "scaleBatteryMeterViews", batteryMeterHook)
            XposedBridge.hookAllMethods(batteryMeterClass, "updatePercentText", batteryMeterHook)
            XposedBridge.hookAllMethods(batteryMeterClass, "addPercentView", batteryMeterHook)
        }

        val vivoBatteryClass = runCatching {
            Class.forName("com.vivo.systemui.battery.VivoBatteryMeterView", false, classLoader)
        }.getOrNull()
        if (vivoBatteryClass != null && vivoBatteryClass != batteryMeterClass) {
            XposedBridge.hookAllMethods(vivoBatteryClass, "onAttachedToWindow", batteryMeterHook)
            XposedBridge.hookAllMethods(vivoBatteryClass, "scaleBatteryMeterViews", batteryMeterHook)
            XposedBridge.hookAllMethods(vivoBatteryClass, "updatePercentText", batteryMeterHook)
            XposedBridge.hookAllMethods(vivoBatteryClass, "addPercentView", batteryMeterHook)
        }

        val clockClass = runCatching {
            Class.forName("com.android.systemui.statusbar.policy.Clock", false, classLoader)
        }.getOrNull()
        if (clockClass != null) {
            XposedBridge.hookAllMethods(clockClass, "onAttachedToWindow", object : XC_MethodHook(Int.MAX_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? TextView ?: return
                    if (!isStatusBarClock(view)) return
                    trackedClocks[view] = view.textSize
                    ensureObserver(view.context)
                    applyClockScale(view, getClockScale(view.context), getClockX(view.context))
                }
            })
            XposedBridge.hookAllMethods(clockClass, "updateClockVisibility", object : XC_MethodHook(Int.MAX_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? TextView ?: return
                    if (!isStatusBarClock(view)) return
                    trackedClocks.getOrPut(view) { view.textSize }
                    ensureObserver(view.context)
                    applyClockScale(view, getClockScale(view.context), getClockX(view.context))
                }
            })
            XposedBridge.hookAllMethods(clockClass, "updateClockDirectly", object : XC_MethodHook(Int.MAX_VALUE) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? TextView ?: return
                    if (!isStatusBarClock(view)) return
                    trackedClocks.getOrPut(view) { view.textSize }
                    ensureObserver(view.context)
                    applyClockScale(view, getClockScale(view.context), getClockX(view.context))
                }
            })
        }

        XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook(Int.MAX_VALUE) {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (isSuperXView(view)) {
                    trackedOtherIcons[view] = true
                    ensureObserver(view.context)
                    applyOtherIconScale(view, getSystemIconsScale(view.context))
                }
            }
        })
    }

    private fun ensureObserver(context: Context) {
        if (!observerInstalled.compareAndSet(false, true)) return
        val resolver = context.applicationContext.contentResolver
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                applyAllTracked(context)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(SETTING_BATTERY_SCALE), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(SETTING_BATTERY_X), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(SETTING_BATTERY_TEXT_SCALE), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(SETTING_BATTERY_TEXT_WEIGHT), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(SETTING_WIFI_SCALE), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(SETTING_WIFI_X), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(SETTING_CLOCK_SCALE), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(SETTING_CLOCK_X), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(SETTING_SYSTEM_ICONS_SCALE), false, observer)
    }

    private fun applyAllTracked(context: Context) {
        val batScale = getBatteryScale(context)
        val batX = getBatteryX(context)
        val batTextScale = getBatteryTextScale(context)
        val batTextWeight = getBatteryTextWeight(context)
        val wifiScale = getWifiScale(context)
        val wifiX = getWifiX(context)
        val clockScale = getClockScale(context)
        val clockX = getClockX(context)
        val iconsScale = getSystemIconsScale(context)

        val batteryList = synchronized(trackedBatteryViews) { trackedBatteryViews.keys.toList() }
        for (battery in batteryList) {
            if (battery.isAttachedToWindow) {
                applyBatteryScale(battery, batScale, batX)
                applyBatteryMeterPercent(battery, batTextScale, batTextWeight)
            }
        }

        val innerList = synchronized(trackedInnerBatteryViews) { trackedInnerBatteryViews.keys.toList() }
        for (inner in innerList) {
            if (inner.isAttachedToWindow) {
                applyInnerBatteryText(inner, batTextScale, batTextWeight)
            }
        }

        val percentList = synchronized(trackedBatteryPercentViews) { trackedBatteryPercentViews.keys.toList() }
        for (percent in percentList) {
            if (percent.isAttachedToWindow) {
                applyPercentViewText(percent, batTextScale, batTextWeight)
            }
        }

        val clockList = synchronized(trackedClocks) { trackedClocks.keys.toList() }
        for (clock in clockList) {
            if (clock.isAttachedToWindow) {
                applyClockScale(clock, clockScale, clockX)
            }
        }

        val containerList = synchronized(trackedContainers) { trackedContainers.keys.toList() }
        for (container in containerList) {
            if (container.isAttachedToWindow) {
                applyContainerChildren(container, wifiScale, iconsScale, wifiX)
            }
        }

        val otherList = synchronized(trackedOtherIcons) { trackedOtherIcons.keys.toList() }
        for (icon in otherList) {
            if (icon.isAttachedToWindow) {
                applyOtherIconScale(icon, iconsScale)
            }
        }
    }

    private fun applyBatteryScale(view: View, scale: Float, translationX: Float) {
        view.pivotX = view.width.toFloat()
        view.pivotY = view.height / 2f
        view.scaleX = scale
        view.scaleY = scale
        view.translationX = translationX
        view.requestLayout()
        view.invalidate()
    }

    private fun applyBatteryMeterPercent(view: View, textScale: Float, weight: Int) {
        val percentView = (readField(view, "mBatteryPercentView") as? TextView)
            ?: runCatching {
                val method = view.javaClass.getMethod("getBatteryPercentView")
                method.invoke(view) as? TextView
            }.getOrNull()
        if (percentView != null) {
            trackedBatteryPercentViews[percentView] = trackedBatteryPercentViews[percentView] ?: percentView.textSize
            applyPercentViewText(percentView, textScale, weight)
        }
        val iconView = readField(view, "mBatteryIconView") as? View
        if (iconView != null) {
            trackedInnerBatteryViews[iconView] = true
            applyInnerBatteryText(iconView, textScale, weight)
        }
    }

    private fun applyPercentViewText(view: TextView, textScale: Float, weight: Int) {
        val baseSize = trackedBatteryPercentViews.getOrPut(view) { view.textSize }
        view.includeFontPadding = false
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseSize * textScale)
        view.typeface = createWeightedTypeface(view.typeface, weight)
        view.requestLayout()
        view.postInvalidate()
    }

    private fun applyInnerBatteryText(view: View, textScale: Float, weight: Int) {
        val baseSize = baseBatteryInnerSizes.getOrPut(view) {
            val current = (readField(view, "mTextSize") as? Number)?.toFloat()
            if (current != null && current > 0f) current else 24f
        }
        val targetSize = baseSize * textScale
        setIntField(view, "mTextSize", targetSize.toInt())
        val textPaint = readField(view, "mTextPaint") as? TextPaint
        if (textPaint != null) {
            textPaint.textSize = targetSize
            textPaint.typeface = createWeightedTypeface(textPaint.typeface, weight)
        }
        val clearPaint = readField(view, "mClearColorTextPaint") as? TextPaint
        if (clearPaint != null) {
            clearPaint.textSize = targetSize
            clearPaint.typeface = createWeightedTypeface(clearPaint.typeface, weight)
        }
        view.postInvalidate()
    }

    private fun createWeightedTypeface(base: Typeface?, weight: Int): Typeface {
        return runCatching {
            Typeface.create(base ?: Typeface.DEFAULT, weight.coerceIn(100, 900), false)
        }.getOrElse {
            if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun applyClockScale(view: TextView, scale: Float, translationX: Float) {
        val baseSize = trackedClocks[view] ?: view.textSize.also { trackedClocks[view] = it }
        view.includeFontPadding = false
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseSize * scale)
        view.translationX = translationX
        view.requestLayout()
        view.invalidate()
    }

    private fun applyOtherIconScale(view: View, scale: Float) {
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f
        view.scaleX = scale
        view.scaleY = scale
        view.requestLayout()
        view.invalidate()
    }

    private fun applyContainerChildren(container: ViewGroup, wifiScale: Float, iconsScale: Float, wifiX: Float) {
        val count = container.childCount
        for (i in 0 until count) {
            val child = container.getChildAt(i) ?: continue
            val isWifi = isWifiView(child)
            val targetScale = if (isWifi) wifiScale else iconsScale
            child.pivotX = child.width / 2f
            child.pivotY = child.height / 2f
            child.scaleX = targetScale
            child.scaleY = targetScale
            val state = getViewState(container, child)
            if (state != null) {
                setFloatField(state, "scaleX", targetScale)
                setFloatField(state, "scaleY", targetScale)
            }
        }
        runCatching {
            var cls: Class<*>? = container.javaClass
            var calcMethod: java.lang.reflect.Method? = null
            var applyMethod: java.lang.reflect.Method? = null
            while (cls != null && (calcMethod == null || applyMethod == null)) {
                if (calcMethod == null) {
                    calcMethod = runCatching { cls.getDeclaredMethod("calculateIconTranslations").apply { isAccessible = true } }.getOrNull()
                }
                if (applyMethod == null) {
                    applyMethod = runCatching { cls.getDeclaredMethod("applyIconStates").apply { isAccessible = true } }.getOrNull()
                }
                cls = cls.superclass
            }
            calcMethod?.invoke(container)
            applyMethod?.invoke(container)
        }
        container.requestLayout()
        container.invalidate()
    }

    private fun getViewState(container: ViewGroup, child: View): Any? {
        if (statusBarViewStateTag == 0) {
            val id = container.context.resources.getIdentifier("status_bar_view_state_tag", "id", container.context.packageName)
            if (id != 0) statusBarViewStateTag = id
        }
        val fromTag = if (statusBarViewStateTag != 0) child.getTag(statusBarViewStateTag) else null
        if (fromTag != null) return fromTag
        val fromHardcoded = child.getTag(TAG_STATUS_BAR_VIEW_STATE)
        if (fromHardcoded != null) return fromHardcoded
        return runCatching {
            var cls: Class<*>? = container.javaClass
            var method: java.lang.reflect.Method? = null
            while (cls != null && method == null) {
                method = runCatching { cls.getDeclaredMethod("getViewStateFromChild", View::class.java).apply { isAccessible = true } }.getOrNull()
                cls = cls.superclass
            }
            method?.invoke(container, child)
        }.getOrNull()
    }

    private fun isWifiView(view: View): Boolean {
        val className = view.javaClass.name.lowercase()
        if (className.contains("wifi")) return true
        val idName = if (view.id != View.NO_ID && view.id != 0) {
            runCatching { view.resources.getResourceEntryName(view.id).lowercase() }.getOrNull().orEmpty()
        } else {
            ""
        }
        if (idName.contains("wifi")) return true
        val slot = runCatching {
            val method = view.javaClass.getMethod("getSlot")
            method.invoke(view) as? String
        }.getOrNull()?.lowercase().orEmpty()
        if (slot == "wifi" || slot == "wifi_deputy" || slot.contains("wifi")) return true
        val state = runCatching { view.getTag(TAG_STATUS_BAR_VIEW_STATE) }.getOrNull()
        if (state != null) {
            val stateSlot = (readField(state, "slot") as? String)?.lowercase().orEmpty()
            if (stateSlot == "wifi" || stateSlot == "wifi_deputy" || stateSlot.contains("wifi")) return true
        }
        return false
    }

    private fun isStatusBarClock(view: View): Boolean {
        if (view !is TextView) return false
        val className = view.javaClass.name.lowercase()
        val idName = if (view.id != View.NO_ID && view.id != 0) {
            runCatching { view.resources.getResourceEntryName(view.id).lowercase() }.getOrNull().orEmpty()
        } else {
            ""
        }
        val isClockName = className.contains("statusbar.policy.clock") ||
            className.contains("statusbar.widget.statclock") ||
            idName == "clock" ||
            idName == "fake_bar_clock" ||
            idName == "vivo_hood_clock"
        if (!isClockName) return false
        val ancestry = generateSequence(view.parent) { (it as? View)?.parent }
            .take(6)
            .map { it.javaClass.name.lowercase() }
            .toList()
        return ancestry.any {
            it.contains("statusbar") || it.contains("phonestatusbar") || it.contains("keyguardstatusbar")
        }
    }

    private fun isSuperXView(view: View): Boolean {
        val idName = if (view.id != View.NO_ID && view.id != 0) {
            runCatching { view.resources.getResourceEntryName(view.id).lowercase() }.getOrNull().orEmpty()
        } else {
            ""
        }
        return idName.contains("superx")
    }

    private fun getBatteryScale(context: Context): Float {
        return Settings.Global.getInt(context.contentResolver, SETTING_BATTERY_SCALE, 100).toFloat().coerceIn(50f, 150f) / 100f
    }

    private fun getBatteryX(context: Context): Float {
        return Settings.Global.getInt(context.contentResolver, SETTING_BATTERY_X, 0).toFloat().coerceIn(-150f, 150f)
    }

    private fun getBatteryTextScale(context: Context): Float {
        return Settings.Global.getInt(context.contentResolver, SETTING_BATTERY_TEXT_SCALE, 100).toFloat().coerceIn(50f, 150f) / 100f
    }

    private fun getBatteryTextWeight(context: Context): Int {
        return Settings.Global.getInt(context.contentResolver, SETTING_BATTERY_TEXT_WEIGHT, 700).coerceIn(100, 900)
    }

    private fun getWifiScale(context: Context): Float {
        return Settings.Global.getInt(context.contentResolver, SETTING_WIFI_SCALE, 100).toFloat().coerceIn(50f, 150f) / 100f
    }

    private fun getWifiX(context: Context): Float {
        return Settings.Global.getInt(context.contentResolver, SETTING_WIFI_X, 0).toFloat().coerceIn(-150f, 150f)
    }

    private fun getClockScale(context: Context): Float {
        return Settings.Global.getInt(context.contentResolver, SETTING_CLOCK_SCALE, 100).toFloat().coerceIn(50f, 150f) / 100f
    }

    private fun getClockX(context: Context): Float {
        return Settings.Global.getInt(context.contentResolver, SETTING_CLOCK_X, 0).toFloat().coerceIn(-150f, 150f)
    }

    private fun getSystemIconsScale(context: Context): Float {
        return Settings.Global.getInt(context.contentResolver, SETTING_SYSTEM_ICONS_SCALE, 100).toFloat().coerceIn(50f, 150f) / 100f
    }

    private fun readField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            if (field != null) return runCatching { field.get(instance) }.getOrNull()
            type = type.superclass
        }
        return null
    }

    private fun setFloatField(instance: Any, name: String, value: Float) {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            if (field != null) {
                runCatching { field.setFloat(instance, value) }
                return
            }
            type = type.superclass
        }
    }

    private fun setIntField(instance: Any, name: String, value: Int) {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            if (field != null) {
                runCatching { field.setInt(instance, value) }
                return
            }
            type = type.superclass
        }
    }

    companion object {
        const val SETTING_BATTERY_SCALE = "originroottoolbox_statusbar_battery_scale"
        const val SETTING_BATTERY_X = "originroottoolbox_statusbar_battery_x"
        const val SETTING_BATTERY_TEXT_SCALE = "originroottoolbox_statusbar_battery_text_scale"
        const val SETTING_BATTERY_TEXT_WEIGHT = "originroottoolbox_statusbar_battery_text_weight"
        const val SETTING_WIFI_SCALE = "originroottoolbox_statusbar_wifi_scale"
        const val SETTING_WIFI_X = "originroottoolbox_statusbar_wifi_x"
        const val SETTING_CLOCK_SCALE = "originroottoolbox_statusbar_clock_scale"
        const val SETTING_CLOCK_X = "originroottoolbox_statusbar_clock_x"
        const val SETTING_SYSTEM_ICONS_SCALE = "originroottoolbox_statusbar_system_icons_scale"
        private const val TAG_STATUS_BAR_VIEW_STATE = 0x7f0b08b0
        @Volatile
        private var statusBarViewStateTag = 0

        private val installed = AtomicBoolean(false)
        private val observerInstalled = AtomicBoolean(false)
        private val mainHandler = Handler(Looper.getMainLooper())

        private val trackedBatteryViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
        private val trackedInnerBatteryViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
        private val trackedBatteryPercentViews = Collections.synchronizedMap(WeakHashMap<TextView, Float>())
        private val baseBatteryInnerSizes = Collections.synchronizedMap(WeakHashMap<View, Float>())
        private val trackedClocks = Collections.synchronizedMap(WeakHashMap<TextView, Float>())
        private val trackedContainers = Collections.synchronizedMap(WeakHashMap<ViewGroup, Boolean>())
        private val trackedOtherIcons = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    }
}
