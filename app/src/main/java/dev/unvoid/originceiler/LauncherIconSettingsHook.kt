package dev.unvoid.originceiler

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Native "Home screen, lock screen & wallpaper → Icons" page (BBK Launcher IconSettingsActivity).
 *
 * The page is an OriginUI PreferenceFragmentCompat living in the launcher process, so everything is
 * built from the launcher's own androidx.preference / OriginUI classes and looks stock:
 *  - the stock "Size" gear seekbar is taken over and drives originroottoolbox_launcher_icon_size
 *    (70–150 %, 5 % ticks, applied live) instead of the three stock levels;
 *  - an extra group adds icon pack selection (installed ADW/Nova packs) and the module's AOSP
 *    themed icons with color, inversion and glyph size.
 * Icon rendering needs a launcher restart, after which the launcher reopens this page itself.
 */
class LauncherIconSettingsHook {
    private val installed = AtomicBoolean(false)
    private val ownedSeekbars = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val paddedRows = WeakHashMap<View, Boolean>()
    private val internalCall = ThreadLocal<Boolean>()
    private val handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }
    private var pendingRefresh: Runnable? = null
    private var pendingClearIcons = false
    private var refreshAction: (() -> Unit)? = null

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        runCatching { hookSizeSeekbar(classLoader) }
            .onFailure { XposedBridge.log("OriginRootToolbox: icon settings size hook failed: $it") }
        runCatching { hookFragment(classLoader) }
            .onFailure { XposedBridge.log("OriginRootToolbox: icon settings fragment hook failed: $it") }
        runCatching { hookSeekBarCard(classLoader) }
            .onFailure { XposedBridge.log("OriginRootToolbox: icon settings seekbar card hook failed: $it") }
    }

    /** vivo's SeekBarPreference skips the card background its siblings get; give ours the same card. */
    private fun hookSeekBarCard(classLoader: ClassLoader) {
        val seekBarPreference = Class.forName("androidx.preference.SeekBarPreference", false, classLoader)
        XposedBridge.hookAllMethods(seekBarPreference, "onBindViewHolder", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val preference = param.thisObject
                if (call(preference, "getKey") != KEY_PACK_SCALE) return
                val itemView = runCatching {
                    param.args[0].javaClass.getField("itemView").get(param.args[0]) as View
                }.getOrNull() ?: return
                runCatching { call(preference, "setCardBackground", itemView) }
                    .onFailure { XposedBridge.log("OriginRootToolbox: pack scale card failed: $it") }
                // Line the title and bar up with the list rows around it and show the unit.
                val extra = (8 * itemView.resources.displayMetrics.density).roundToInt()
                if (paddedRows.put(itemView, true) == null) {
                    itemView.setPaddingRelative(itemView.paddingStart + extra, itemView.paddingTop, itemView.paddingEnd + extra, itemView.paddingBottom)
                }
                val value = call(preference, "getValue") as? Int ?: return
                itemView.findViewById<TextView>(itemView.resources.getIdentifier("seekbar_value", "id", LAUNCHER))?.text = "$value%"
            }
        })
    }

    // region Size seekbar

    private fun hookSizeSeekbar(classLoader: ClassLoader) {
        val preference = Class.forName(PREFERENCE_CLASS, false, classLoader)
        XposedBridge.hookAllMethods(preference, "initView", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val root = param.args.firstOrNull() as? View ?: return
                runCatching { takeOverSizeSeekbar(root) }
                    .onFailure { XposedBridge.log("OriginRootToolbox: icon size takeover failed: $it") }
            }
        })
        // IconSettingsPreference re-applies its three stock levels on resume and configuration
        // changes. Calls coming from it are dropped for a seekbar we own; the widget's own touch
        // handling never goes through IconSettingsPreference, so dragging is unaffected.
        val seekbar = Class.forName(SEEKBAR_CLASS, false, classLoader)
        listOf("setCurrentTickLevel", "setTickCount", "setTickContentDes", "setOnSeekBarChangeListener").forEach { name ->
            XposedBridge.hookAllMethods(seekbar, name, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (internalCall.get() == true) return
                    val view = param.thisObject as? View ?: return
                    if (!ownedSeekbars.containsKey(view)) return
                    if (Throwable().stackTrace.any { it.className == PREFERENCE_CLASS }) param.result = null
                }
            })
        }
    }

    private fun takeOverSizeSeekbar(root: View) {
        val resources = root.resources
        val seekbar = root.findViewById<View>(resources.getIdentifier("icon_size_seekbar", "id", LAUNCHER)) ?: return
        val title = root.findViewById<TextView>(resources.getIdentifier("icon_size_title", "id", LAUNCHER))
        val baseTitle = title?.text?.toString()?.substringBefore(" · ").orEmpty()
        val listenerMethod = seekbar.javaClass.methods.firstOrNull {
            it.name == "setOnSeekBarChangeListener" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0].isInterface && it.parameterTypes[0].name.startsWith("$SEEKBAR_CLASS$")
        } ?: return
        val resolver = root.context.contentResolver

        fun show(size: Int) {
            if (title != null && baseTitle.isNotEmpty()) title.text = "$baseTitle · $size%"
            seekbar.contentDescription = "$baseTitle $size%"
        }

        val listenerType = listenerMethod.parameterTypes[0]
        val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
            when {
                method.name == "equals" && method.parameterTypes.size == 1 -> proxy === args?.firstOrNull()
                method.name == "hashCode" && method.parameterTypes.isEmpty() -> System.identityHashCode(proxy)
                method.name == "toString" && method.parameterTypes.isEmpty() -> "OriginIconSizeListener"
                else -> {
                    val level = (call(seekbar, "getCurrentTickLevel") as? Int)
                        ?: args?.firstOrNull { it is Int } as? Int
                    if (level != null) {
                        val size = (SIZE_MIN + level * SIZE_STEP).coerceIn(SIZE_MIN, SIZE_MAX)
                        show(size)
                        if (Settings.Global.getInt(resolver, SETTING_ICON_SIZE, 100) != size) {
                            runCatching { Settings.Global.putInt(resolver, SETTING_ICON_SIZE, size) }
                                .onFailure { XposedBridge.log("OriginRootToolbox: icon size write failed: $it") }
                        }
                    }
                    null
                }
            }
        }

        val size = Settings.Global.getInt(resolver, SETTING_ICON_SIZE, 100).coerceIn(SIZE_MIN, SIZE_MAX)
        ownedSeekbars[seekbar] = true
        internal {
            call(seekbar, "setTickCount", SIZE_TICKS)
            call(seekbar, "setTickContentDes", ArrayList((0 until SIZE_TICKS).map { "${SIZE_MIN + it * SIZE_STEP}%" }))
            listenerMethod.invoke(seekbar, listener)
            call(seekbar, "setCurrentTickLevel", ((size - SIZE_MIN + SIZE_STEP / 2) / SIZE_STEP).coerceIn(0, SIZE_TICKS - 1))
        }
        show(size)
    }

    // endregion

    // region Extra preferences

    private fun hookFragment(classLoader: ClassLoader) {
        val fragment = Class.forName(FRAGMENT_CLASS, false, classLoader)
        XposedBridge.hookAllMethods(fragment, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching { injectPreferences(param.thisObject, classLoader) }
                    .onFailure { XposedBridge.log("OriginRootToolbox: icon settings injection failed: $it") }
            }
        })
    }

    private fun injectPreferences(fragment: Any, classLoader: ClassLoader) {
        val screen = call(fragment, "getPreferenceScreen") ?: return
        if (call(screen, "findPreference", KEY_CATEGORY as CharSequence) != null) return
        val context = call(screen, "getContext") as? Context ?: return
        val resolver = context.contentResolver
        val text = Strings(context.resources.configuration.locales[0].language == "ru")

        fun create(name: String): Any = Class.forName("androidx.preference.$name", false, classLoader)
            .getConstructor(Context::class.java).newInstance(context)

        fun Any.setup(key: String, title: String, summary: String? = null): Any = also {
            call(it, "setKey", key)
            call(it, "setTitle", title as CharSequence)
            // Vivo's Preference shows setSummary() as a value on the right; the stock subtitle under
            // the title is setSummaryEx().
            if (summary != null) call(it, "setSummaryEx", summary as CharSequence)
            call(it, "setPersistent", false)
            runCatching { call(it, "setIconSpaceReserved", false) }
        }

        fun Any.list(entries: List<Pair<String, String>>): Any = also {
            call(it, "setEntries", entries.map { entry -> entry.second as CharSequence }.toTypedArray())
            call(it, "setEntryValues", entries.map { entry -> entry.first as CharSequence }.toTypedArray())
            call(it, "setDialogTitle", call(it, "getTitle") as? CharSequence)
            // ListPreference.getSummary() runs the summary through String.format with the entry.
            call(it, "setSummary", "%s" as CharSequence)
        }

        val listenerType = Class.forName("androidx.preference.Preference\$OnPreferenceChangeListener", false, classLoader)
        fun Any.onChange(block: (Any?) -> Unit): Any = also {
            val listener = Proxy.newProxyInstance(classLoader, arrayOf(listenerType)) { proxy, method, args ->
                when (method.name) {
                    "onPreferenceChange" -> {
                        runCatching { block(args?.getOrNull(1)) }
                            .onFailure { e -> XposedBridge.log("OriginRootToolbox: icon setting change failed: $e") }
                        true
                    }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> "OriginIconSettingsListener"
                }
            }
            call(it, "setOnPreferenceChangeListener", listener)
        }

        val packs = findIconPacks(context)
        val colors = listOf("0" to text.colorSystem) + PALETTE.map { (hex, ru, en) -> hex to if (text.ru) ru else en }
        val glyphSizes = (70..115 step 5).map { "$it" to "$it%" }

        val category = create("PreferenceCategory").setup(KEY_CATEGORY, text.category)
        call(screen, "addPreference", category)

        val pack = create("ListPreference").setup(KEY_PACK, text.pack)
            .list(listOf(PACK_NONE to text.packNone) + packs)
        val recolor = create("CheckBoxPreference").setup(KEY_RECOLOR, text.recolor, text.recolorSummary)
        val background = create("CheckBoxPreference").setup(KEY_BACKGROUND, text.background, text.backgroundSummary)
        val themed = create("CheckBoxPreference").setup(KEY_THEMED, text.themed, text.themedSummary)
        val color = create("ListPreference").setup(KEY_COLOR, text.color).list(colors)
        val invert = create("CheckBoxPreference").setup(KEY_INVERT, text.invert, text.invertSummary)
        val glyph = create("ListPreference").setup(KEY_GLYPH, text.glyph).list(glyphSizes)
        val packScale = create("SeekBarPreference").setup(KEY_PACK_SCALE, text.packScale).also {
            call(it, "setMin", LauncherIconPack.SCALE_MIN)
            call(it, "setMax", LauncherIconPack.SCALE_MAX)
            call(it, "setSeekBarIncrement", 5)
            call(it, "setShowSeekBarValue", true)
            call(it, "setUpdatesContinuously", false)
        }
        val packTint = create("ListPreference").setup(KEY_PACK_TINT, text.packTint)
            .list(listOf("0" to text.packTintNone) + PALETTE.map { (hex, ru, en) -> hex to if (text.ru) ru else en })
        val packMono = create("CheckBoxPreference").setup(KEY_PACK_MONO, text.packMono, text.packMonoSummary)
        val packInvert = create("CheckBoxPreference").setup(KEY_PACK_INVERT, text.invert, text.packInvertSummary)
        val packOptions = listOf(recolor, background, packScale, packTint, packMono, packInvert)
        listOf(pack, recolor, background, packScale, packTint, packMono, packInvert, themed, color, invert, glyph)
            .forEach { call(category, "addPreference", it) }

        fun refresh() {
            val activePack = Settings.Global.getString(resolver, LauncherIconPack.SETTING_PACK)
                ?.takeIf { selected -> packs.any { it.first == selected } }
            val themedOn = Settings.Global.getInt(resolver, SETTING_THEMED, 0) == 1 && activePack == null
            call(pack, "setValue", activePack ?: PACK_NONE)
            call(recolor, "setChecked", Settings.Global.getInt(resolver, LauncherIconPack.SETTING_AUTO_RECOLOR, 0) == 1)
            call(background, "setChecked", Settings.Global.getInt(resolver, LauncherIconPack.SETTING_REPLACE_BACKGROUND, 0) == 1)
            packOptions.forEach { call(it, "setVisible", activePack != null) }
            call(packScale, "setValue", Settings.Global.getInt(resolver, LauncherIconPack.SETTING_SCALE, 100)
                .coerceIn(LauncherIconPack.SCALE_MIN, LauncherIconPack.SCALE_MAX))
            val tintValue = Settings.Global.getInt(resolver, LauncherIconPack.SETTING_TINT, 0)
            call(packTint, "setValue", PALETTE.firstOrNull { Color.parseColor(it.first) == tintValue }?.first ?: "0")
            call(packMono, "setChecked", Settings.Global.getInt(resolver, LauncherIconPack.SETTING_MONOCHROME, 0) == 1)
            call(packInvert, "setChecked", Settings.Global.getInt(resolver, LauncherIconPack.SETTING_INVERT, 0) == 1)
            call(themed, "setChecked", themedOn)

            val colorValue = Settings.Global.getInt(resolver, MonetThemedIconFactory.SETTING_THEMED_COLOR, 0)
            val colorKey = PALETTE.firstOrNull { Color.parseColor(it.first) == colorValue }?.first ?: "0"
            call(color, "setValue", colorKey)
            call(invert, "setChecked", Settings.Global.getInt(resolver, MonetThemedIconFactory.SETTING_THEMED_INVERT, 0) == 1)
            val scale = Settings.Global.getInt(resolver, SETTING_GLYPH_SCALE, 100)
            val glyphKey = glyphSizes.minByOrNull { abs(it.first.toInt() - scale) }!!.first
            call(glyph, "setValue", glyphKey)
            listOf(color, invert, glyph).forEach { call(it, "setVisible", themedOn) }
        }

        pack.onChange { value ->
            val selected = (value as? String)?.takeIf { it != PACK_NONE }
            if (selected == null) {
                Settings.Global.putString(resolver, LauncherIconPack.SETTING_PACK, null)
            } else {
                Settings.Global.putString(resolver, LauncherIconPack.SETTING_PACK, selected)
                Settings.Global.putInt(resolver, SETTING_THEMED, 0)
            }
            scheduleRefresh(context, clearThemeIcons = true)
        }
        recolor.onChange { value ->
            Settings.Global.putInt(resolver, LauncherIconPack.SETTING_AUTO_RECOLOR, if (value == true) 1 else 0)
            scheduleRefresh(context, clearThemeIcons = false)
        }
        background.onChange { value ->
            Settings.Global.putInt(resolver, LauncherIconPack.SETTING_REPLACE_BACKGROUND, if (value == true) 1 else 0)
            scheduleRefresh(context, clearThemeIcons = false)
        }
        packScale.onChange { value ->
            val scale = (value as? Int) ?: return@onChange
            Settings.Global.putInt(resolver, LauncherIconPack.SETTING_SCALE, scale.coerceIn(LauncherIconPack.SCALE_MIN, LauncherIconPack.SCALE_MAX))
            scheduleRefresh(context, clearThemeIcons = false)
        }
        packTint.onChange { value ->
            val key = value as? String ?: return@onChange
            Settings.Global.putInt(resolver, LauncherIconPack.SETTING_TINT, if (key == "0") 0 else Color.parseColor(key))
            scheduleRefresh(context, clearThemeIcons = false)
        }
        packMono.onChange { value ->
            Settings.Global.putInt(resolver, LauncherIconPack.SETTING_MONOCHROME, if (value == true) 1 else 0)
            scheduleRefresh(context, clearThemeIcons = false)
        }
        packInvert.onChange { value ->
            Settings.Global.putInt(resolver, LauncherIconPack.SETTING_INVERT, if (value == true) 1 else 0)
            scheduleRefresh(context, clearThemeIcons = false)
        }
        themed.onChange { value ->
            val enabled = value == true
            Settings.Global.putInt(resolver, SETTING_THEMED, if (enabled) 1 else 0)
            if (enabled) Settings.Global.putString(resolver, LauncherIconPack.SETTING_PACK, null)
            scheduleRefresh(context, clearThemeIcons = enabled)
        }
        color.onChange { value ->
            val key = value as? String ?: return@onChange
            Settings.Global.putInt(resolver, MonetThemedIconFactory.SETTING_THEMED_COLOR, if (key == "0") 0 else Color.parseColor(key))
            scheduleRefresh(context, clearThemeIcons = false)
        }
        invert.onChange { value ->
            Settings.Global.putInt(resolver, MonetThemedIconFactory.SETTING_THEMED_INVERT, if (value == true) 1 else 0)
            scheduleRefresh(context, clearThemeIcons = false)
        }
        glyph.onChange { value ->
            val scale = (value as? String)?.toIntOrNull() ?: return@onChange
            Settings.Global.putInt(resolver, SETTING_GLYPH_SCALE, scale.coerceIn(65, 118))
            scheduleRefresh(context, clearThemeIcons = false)
        }

        // Listeners fire before the new value is stored; refresh once it has landed.
        refreshAction = ::refresh
        refresh()
    }

    // endregion

    private fun findIconPacks(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val packages = linkedSetOf<String>()
        ICON_PACK_ACTIONS.forEach { action ->
            runCatching { pm.queryIntentActivities(Intent(action), 0) }.getOrNull()
                ?.forEach { packages += it.activityInfo.packageName }
        }
        return packages.mapNotNull { name ->
            runCatching { name to pm.getApplicationInfo(name, 0).loadLabel(pm).toString() }.getOrNull()
        }.sortedBy { it.second.lowercase() }
    }

    /**
     * Icons are cached by the launcher, so changes need a restart. Several quick edits are batched
     * into one restart, which reopens this page.
     */
    private fun scheduleRefresh(context: Context, clearThemeIcons: Boolean) {
        handler.post { refreshAction?.invoke() }
        pendingClearIcons = pendingClearIcons || clearThemeIcons
        pendingRefresh?.let(handler::removeCallbacks)
        val app = context.applicationContext
        pendingRefresh = Runnable {
            val clear = pendingClearIcons
            pendingClearIcons = false
            pendingRefresh = null
            restartLauncher(app, clear)
        }.also { handler.postDelayed(it, REFRESH_DELAY_MS) }
    }

    /**
     * Runs inside the launcher, which holds WRITE_SETTINGS / WRITE_SECURE_SETTINGS: sync vivo's own
     * themed-icon flags, mark the stored icons dirty so they are rebuilt, then come back to
     * this page in a fresh process (vivo blocks waking another app's process from here).
     */
    private fun restartLauncher(context: Context, clearThemeIcons: Boolean) {
        val resolver = context.contentResolver
        val themed = if (Settings.Global.getInt(resolver, SETTING_THEMED, 0) == 1) 1 else 0
        runCatching {
            Settings.System.putInt(resolver, "themed_icon_enabled", themed)
            Settings.Secure.putInt(resolver, "themed_icon_enabled", themed)
            Settings.System.putInt(resolver, "key_icon_color_follow_system", themed)
            Settings.System.putString(resolver, "key_system_color_change", (System.currentTimeMillis() / 1000).toString())
        }.onFailure { XposedBridge.log("OriginRootToolbox: icon settings flag sync failed: $it") }
        if (clearThemeIcons) runCatching { File("/data/bbkcore/theme/icons").deleteRecursively() }
        // Workspace icons are stored as bitmaps in launcher.db; this flag makes the next start
        // re-render all of them instead of reusing the stored ones.
        context.getSharedPreferences(LAUNCHER_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("icon_in_db_dirty", true)
            .commit()

        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
        val reopen = PendingIntent.getActivity(
            context,
            0,
            Intent().setClassName(LAUNCHER, ACTIVITY_CLASS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            options
        )
        // Exact alarms need SCHEDULE_EXACT_ALARM, which the launcher lacks; a short window does not.
        runCatching {
            context.getSystemService(AlarmManager::class.java)
                ?.setWindow(AlarmManager.RTC, System.currentTimeMillis() + 400, 300, reopen)
        }.onFailure { XposedBridge.log("OriginRootToolbox: icon settings reopen alarm failed: $it") }
        Process.killProcess(Process.myPid())
    }

    private inline fun internal(block: () -> Unit) {
        internalCall.set(true)
        try {
            block()
        } finally {
            internalCall.remove()
        }
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? {
        val method = findMethod(target.javaClass, name, args) ?: throw NoSuchMethodException("${target.javaClass.name}.$name")
        return method.invoke(target, *args)
    }

    private fun findMethod(type: Class<*>, name: String, args: Array<out Any?>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterTypes.size == args.size &&
                    method.parameterTypes.indices.all { accepts(method.parameterTypes[it], args[it]) }
            }?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }

    private fun accepts(type: Class<*>, value: Any?): Boolean = when {
        value == null -> !type.isPrimitive
        type == Int::class.javaPrimitiveType -> value is Int
        type == Boolean::class.javaPrimitiveType -> value is Boolean
        type == Float::class.javaPrimitiveType -> value is Float
        type == Long::class.javaPrimitiveType -> value is Long
        else -> type.isInstance(value)
    }

    private class Strings(val ru: Boolean) {
        val category = if (ru) "Расширенные настройки значков" else "Advanced icon settings"
        val pack = if (ru) "Пакет значков" else "Icon pack"
        val packNone = if (ru) "Системные значки" else "System icons"
        val recolor = if (ru) "Перекрашивать недостающие значки" else "Recolor missing icons"
        val recolorSummary = if (ru) "Значки, которых нет в пакете, подстраиваются под его стиль" else "Icons missing from the pack follow its style"
        val background = if (ru) "Заменять фон значков" else "Replace icon background"
        val backgroundSummary = if (ru) "Использовать маску и подложку из пакета" else "Use the pack's mask and backplate"
        val themed = if (ru) "Значки AOSP" else "AOSP themed icons"
        val themedSummary = if (ru) "Монохромные значки в цветах Material You, как на Pixel" else "Monochrome Material You icons, like on Pixel"
        val color = if (ru) "Цвет значков" else "Icon color"
        val colorSystem = if (ru) "Системный (Material You)" else "System (Material You)"
        val invert = if (ru) "Инвертировать цвета" else "Invert colors"
        val invertSummary = if (ru) "Поменять местами цвет фона и символа" else "Swap background and glyph colors"
        val glyph = if (ru) "Размер символа" else "Glyph size"
        val packScale = if (ru) "Масштаб значков пакета" else "Icon pack scale"
        val packTint = if (ru) "Цветовой фильтр" else "Color filter"
        val packTintNone = if (ru) "Без фильтра" else "None"
        val packMono = if (ru) "Монохром" else "Monochrome"
        val packMonoSummary = if (ru) "Чёрно-белые значки пакета" else "Black and white pack icons"
        val packInvertSummary = if (ru) "Негатив значков пакета" else "Negative pack icons"
    }

    companion object {
        private const val LAUNCHER = "com.bbk.launcher2"
        private const val ACTIVITY_CLASS = "com.bbk.launcher2.settings.iconstyle.IconSettingsActivity"
        private const val FRAGMENT_CLASS = "com.bbk.launcher2.settings.iconstyle.IconSettingsFragment"
        private const val PREFERENCE_CLASS = "com.bbk.launcher2.settings.iconstyle.IconSettingsPreference"
        private const val SEEKBAR_CLASS = "com.originui.widget.vgearseekbar.VGearSeekbarCompat"
        private const val LAUNCHER_PREFS = "com.bbk.launcher2.prefs"

        private const val SETTING_ICON_SIZE = "originroottoolbox_launcher_icon_size"
        private const val SETTING_THEMED = "originicons_themed_launcher_icons"
        private const val SETTING_GLYPH_SCALE = "originicons_monet_icon_scale"

        private const val SIZE_MIN = 70
        private const val SIZE_MAX = 150
        private const val SIZE_STEP = 5
        private const val SIZE_TICKS = (SIZE_MAX - SIZE_MIN) / SIZE_STEP + 1
        private const val REFRESH_DELAY_MS = 900L

        private const val KEY_CATEGORY = "origin_icons_category"
        private const val KEY_PACK = "origin_icons_pack"
        private const val KEY_RECOLOR = "origin_icons_pack_recolor"
        private const val KEY_BACKGROUND = "origin_icons_pack_background"
        private const val KEY_THEMED = "origin_icons_themed"
        private const val KEY_COLOR = "origin_icons_themed_color"
        private const val KEY_INVERT = "origin_icons_themed_invert"
        private const val KEY_GLYPH = "origin_icons_themed_glyph"
        private const val KEY_PACK_SCALE = "origin_icons_pack_scale"
        private const val KEY_PACK_TINT = "origin_icons_pack_tint"
        private const val KEY_PACK_MONO = "origin_icons_pack_mono"
        private const val KEY_PACK_INVERT = "origin_icons_pack_invert"
        private const val PACK_NONE = "system"

        private val ICON_PACK_ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.gau.go.launcherex.theme",
            "com.anddoes.launcher.THEME"
        )

        private val PALETTE = listOf(
            Triple("#3D7BF7", "Синий", "Blue"),
            Triple("#14A6A6", "Бирюзовый", "Teal"),
            Triple("#3C9A4E", "Зелёный", "Green"),
            Triple("#D9A400", "Жёлтый", "Yellow"),
            Triple("#F07A2A", "Оранжевый", "Orange"),
            Triple("#E5484D", "Красный", "Red"),
            Triple("#E0559B", "Розовый", "Pink"),
            Triple("#8B5CF6", "Фиолетовый", "Purple"),
            Triple("#808890", "Серый", "Gray")
        )
    }
}
