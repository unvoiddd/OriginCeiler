package dev.unvoid.originceiler

import android.app.Activity
import android.app.Application
import android.content.res.Resources
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Applies the bundled Russian catalogue to every UI surface hosted by BlueLM. */
class BlueLmRussianHook(private val packageName: String) {
    @Volatile
    private var enabled: Boolean? = null

    fun install(classLoader: ClassLoader) {
        if (packageName == "com.vivo.ai.copilot") {
            runCatching { BlueLmWhisperHook(classLoader).install() }
                .onFailure { XposedBridge.log("OriginRootToolbox: system Whisper hook failed: $it") }
        }
        runCatching { hookResources() }
            .onFailure { XposedBridge.log("OriginRootToolbox: BlueLM resource hook failed: $it") }
        runCatching { hookViews() }
            .onFailure { XposedBridge.log("OriginRootToolbox: BlueLM view hook failed: $it") }
        runCatching { hookCompose(classLoader) }
            .onFailure { XposedBridge.log("OriginRootToolbox: BlueLM Compose hook failed: $it") }
    }

    private fun hookResources() {
        listOf("getText", "getString", "getQuantityText", "getQuantityString").forEach { methodName ->
            XposedBridge.hookAllMethods(
                Resources::class.java,
                methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        val resources = param.thisObject as? Resources ?: return
                        val id = param.args.firstOrNull() as? Int ?: return
                        val name = runCatching { resources.getResourceEntryName(id) }.getOrNull() ?: return
                        val translated = BlueLmRussianTranslations.resource(packageName, name) ?: return
                        param.result = if (methodName == "getString" || methodName == "getQuantityString") {
                            format(translated, param.args)
                        } else {
                            translated
                        }
                    }
                }
            )
        }

        listOf("getTextArray", "getStringArray").forEach { methodName ->
            XposedBridge.hookAllMethods(
                Resources::class.java,
                methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isEnabled()) return
                        when (val result = param.result) {
                            is Array<*> -> param.result = result.map {
                                translateText(it?.toString()) ?: it?.toString().orEmpty()
                            }.toTypedArray()
                        }
                    }
                }
            )
        }
    }

    private fun hookViews() {
        XposedBridge.hookAllMethods(
            TextView::class.java,
            "setText",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val source = param.args.firstOrNull() as? CharSequence ?: return
                    val text = source.toString()
                    translateText(text)?.let {
                        param.args[0] = it
                        return
                    }
                    // Do not touch the editable value while the user is typing. Hints are
                    // handled separately below, while sent messages are ordinary TextViews.
                    val view = param.thisObject as? TextView ?: return
                    if (view is EditText) return
                    translateDynamic(text) { translated ->
                        val reference = WeakReference(view)
                        reference.get()?.post {
                            reference.get()?.takeIf { it.text?.toString() == text }?.text = translated
                        }
                    }
                }
            }
        )
        XposedBridge.hookAllMethods(
            TextView::class.java,
            "setHint",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val source = param.args.firstOrNull() as? CharSequence ?: return
                    val text = source.toString()
                    translateText(text)?.let {
                        param.args[0] = it
                        return
                    }
                    val view = param.thisObject as? TextView ?: return
                    translateDynamic(text) { translated ->
                        val reference = WeakReference(view)
                        reference.get()?.post {
                            reference.get()?.takeIf { it.hint?.toString() == text }?.hint = translated
                        }
                    }
                }
            }
        )
        XposedBridge.hookAllMethods(
            View::class.java,
            "setContentDescription",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val source = param.args.firstOrNull() as? CharSequence ?: return
                    val text = source.toString()
                    translateText(text)?.let {
                        param.args[0] = it
                        return
                    }
                    val view = param.thisObject as? View ?: return
                    translateDynamic(text) { translated ->
                        val reference = WeakReference(view)
                        reference.get()?.post {
                            reference.get()?.takeIf { it.contentDescription?.toString() == text }
                                ?.contentDescription = translated
                        }
                    }
                }
            }
        )
        XposedBridge.hookAllMethods(
            Activity::class.java,
            "setTitle",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val source = param.args.firstOrNull() as? CharSequence ?: return
                    translateText(source.toString())?.let { param.args[0] = it }
                }
            }
        )
    }

    private fun hookCompose(classLoader: ClassLoader) {
        val annotatedString = runCatching {
            Class.forName("androidx.compose.ui.text.AnnotatedString", false, classLoader)
        }.getOrNull() ?: return
        XposedBridge.hookAllConstructors(
            annotatedString,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled()) return
                    val index = param.args.indexOfFirst { it is String }
                    if (index < 0) return
                    val source = param.args[index] as String
                    translateText(source)?.let { param.args[index] = it }
                }
            }
        )
    }

    private fun format(template: String, args: Array<Any?>): String {
        val formatArgs = when {
            args.size >= 3 && args[2] is Array<*> -> (args[2] as Array<*>).map { it }.toTypedArray()
            args.size >= 2 && args[1] is Array<*> -> (args[1] as Array<*>).map { it }.toTypedArray()
            else -> emptyArray()
        }
        if (formatArgs.isEmpty()) return template
        return runCatching { String.format(RUSSIAN, template, *formatArgs) }.getOrDefault(template)
    }

    private fun translateText(source: String?): String? {
        if (source.isNullOrBlank()) return null
        return BlueLmRussianTranslations.text(source) ?: dynamicCache[source]
    }

    private fun translateDynamic(source: String, callback: (String) -> Unit) {
        if (!containsChinese(source) || source.length > MAX_DYNAMIC_TEXT) return
        dynamicCache[source]?.let {
            callback(it)
            return
        }

        val callbacks = pendingTranslations.computeIfAbsent(source) { CopyOnWriteArrayList() }
        callbacks += callback
        if (callbacks.size != 1) return

        translationExecutor.execute {
            val translated = runCatching { requestRussianTranslation(source) }
                .onFailure { error ->
                    if (networkErrorLogged.compareAndSet(false, true)) {
                        XposedBridge.log("OriginRootToolbox: BlueLM live translation unavailable: $error")
                    }
                }
                .getOrNull()
            val listeners = pendingTranslations.remove(source).orEmpty()
            if (!translated.isNullOrBlank() && translated != source) {
                dynamicCache[source] = translated
                listeners.forEach { runCatching { it(translated) } }
            }
        }
    }

    private fun requestRussianTranslation(source: String): String {
        return splitForTranslation(source).joinToString("") { part ->
            if (!containsChinese(part)) part else requestChunk(part)
        }
    }

    private fun requestChunk(source: String): String {
        val connection = (URL(TRANSLATE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", "OriginRootToolbox/BlueLM-Russian")
        }
        val payload = "client=gtx&sl=auto&tl=ru&dt=t&q=" +
            URLEncoder.encode(source, Charsets.UTF_8.name())
        connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            error("HTTP ${connection.responseCode}")
        }
        val response = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
            it.readText()
        }
        connection.disconnect()
        val segments = JSONArray(response).optJSONArray(0) ?: return source
        return buildString {
            for (index in 0 until segments.length()) {
                append(segments.optJSONArray(index)?.optString(0).orEmpty())
            }
        }.ifBlank { source }
    }

    private fun splitForTranslation(source: String): List<String> {
        if (source.length <= TRANSLATION_CHUNK) return listOf(source)
        val result = mutableListOf<String>()
        var start = 0
        while (start < source.length) {
            var end = minOf(start + TRANSLATION_CHUNK, source.length)
            if (end < source.length) {
                val boundary = source.lastIndexOfAny(charArrayOf('\n', '。', '！', '？', '.', '!', '?'), end - 1)
                if (boundary > start + TRANSLATION_CHUNK / 2) end = boundary + 1
            }
            result += source.substring(start, end)
            start = end
        }
        return result
    }

    private fun containsChinese(text: String): Boolean = text.any {
        it in '\u3400'..'\u4DBF' || it in '\u4E00'..'\u9FFF' || it in '\uF900'..'\uFAFF'
    }

    private fun isEnabled(): Boolean {
        enabled?.let { return it }
        val application = currentApplication() ?: return false
        return (Settings.Global.getInt(application.contentResolver, SETTING_ENABLED, 0) == 1).also {
            enabled = it
        }
    }

    private fun currentApplication(): Application? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()

    companion object {
        const val SETTING_ENABLED = "originroottoolbox_bluelm_russian"

        val PACKAGES = setOf(
            "com.vivo.ai.copilot",
            "com.vivo.launchercopilot",
            "com.vivo.ai.base.copilot",
            "com.vivo.ai.copilot.shortcut.component",
            "com.vivo.ai.gptagent"
        )

        private val RUSSIAN = Locale("ru", "RU")
        private const val TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single"
        private const val TRANSLATION_CHUNK = 1_400
        private const val MAX_DYNAMIC_TEXT = 24_000
        private val dynamicCache = ConcurrentHashMap<String, String>()
        private val pendingTranslations =
            ConcurrentHashMap<String, CopyOnWriteArrayList<(String) -> Unit>>()
        private val translationExecutor = Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "BlueLM-Russian").apply { isDaemon = true }
        }
        private val networkErrorLogged = AtomicBoolean(false)
    }
}
