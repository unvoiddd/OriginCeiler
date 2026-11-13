package dev.unvoid.originceiler

import android.app.BroadcastOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * Runs inside the vivo System Update app.
 *
 * Capture: the updater's server answers with JSON that holds the package URL, so every
 * JSONObject parsed from a string containing ".zip" is walked for zip links, and the
 * neighbouring version / size / md5 fields are sent to the module app with the link.
 * URL and Uri parsing are watched too, for links that never pass through JSON.
 *
 * Fake version: the module stores "from" and "to" version numbers in Settings.Global; while
 * they are set, every system property the updater reads has "from" replaced with "to". The
 * module also rewrites the real properties with resetprop, this hook covers the time before
 * that takes effect and devices without resetprop.
 */
object OtaUpdaterHook {
    val PACKAGES = setOf("com.bbk.updater")

    const val SETTING_CAPTURE = "originroottoolbox_ota_capture"
    const val SETTING_SPOOF_FROM = "originroottoolbox_ota_spoof_from"
    const val SETTING_SPOOF_TO = "originroottoolbox_ota_spoof_to"

    const val ACTION_OTA_FOUND = "dev.unvoid.originceiler.OTA_FOUND"
    const val EXTRA_URL = "url"
    const val EXTRA_VERSION = "version"
    const val EXTRA_SIZE = "size"
    const val EXTRA_MD5 = "md5"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_TYPE = "type"

    private const val MODULE_PACKAGE = "dev.unvoid.originceiler"
    private const val SETTINGS_CACHE_MS = 2_000L

    private val zipUrl = Regex("""https?://[^\s"'<>\\]+?\.zip(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
    private val firmwareNumber = Regex("""\d+(?:\.\d+){2,}""")
    private val reported = Collections.synchronizedSet(HashSet<String>())
    private val scanning = ThreadLocal<Boolean>()

    @Volatile private var spoofReadAt = 0L
    @Volatile private var spoof: Pair<String, String>? = null
    @Volatile private var captureEnabled = true
    @Volatile private var buildPatched = false
    private val readingSettings = ThreadLocal<Boolean>()

    fun install(classLoader: ClassLoader) {
        installCapture(classLoader)
        installVersionSpoof()
        XposedBridge.log("OriginCeiler OTA hook installed")
    }

    private fun installCapture(classLoader: ClassLoader) {
        XposedBridge.hookAllConstructors(JSONObject::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val raw = param.args?.firstOrNull() as? String ?: return
                if (looksLikePackage(raw)) scanJson { param.thisObject as? JSONObject }
            }
        })
        // The updater (4.9.x) parses part of its server answers with Gson instead of org.json.
        runCatching {
            val gson = Class.forName("com.google.gson.Gson", false, classLoader)
            XposedBridge.hookAllMethods(gson, "fromJson", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val raw = when (val source = param.args?.firstOrNull()) {
                        is String -> source
                        null -> return
                        else -> if (source.javaClass.name.startsWith("com.google.gson.Json")) source.toString() else return
                    }
                    if (looksLikePackage(raw)) scanJson { runCatching { JSONObject(raw) }.getOrNull() }
                }
            })
        }.onFailure { XposedBridge.log("OriginCeiler OTA: Gson not found, ${it.message}") }
        XposedBridge.hookAllConstructors(java.net.URL::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.args?.lastOrNull() as? String)?.let { scanText(it, "url") }
            }
        })
        XposedBridge.hookAllMethods(android.net.Uri::class.java, "parse", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.args?.firstOrNull() as? String)?.let { scanText(it, "uri") }
            }
        })
    }

    private fun installVersionSpoof() {
        val systemProperties = Class.forName("android.os.SystemProperties")
        XposedBridge.hookAllMethods(systemProperties, "get", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val value = param.result as? String ?: return
                val (from, to) = currentSpoof() ?: return
                if (value.contains(from)) param.result = value.replace(from, to)
            }
        })
    }

    private fun looksLikePackage(raw: String) =
        raw.contains(".zip", ignoreCase = true) || (raw.contains("http") && raw.contains("md5", ignoreCase = true))

    private inline fun scanJson(json: () -> JSONObject?) {
        if (scanning.get() == true) return
        scanning.set(true)
        try {
            json()?.let { walk(it, null, 0) }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        } finally {
            scanning.set(false)
        }
    }

    private fun walk(node: Any?, parent: JSONObject?, depth: Int) {
        if (depth > 12) return
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val value = node.opt(key)) {
                        is String -> {
                            val text = value.replace("\\/", "/").trim()
                            val name = key.lowercase()
                            when {
                                // The changelog page and its zip (logUrl, logZip) sit next to the
                                // package link and carry the same version.
                                isChangelogKey(name) -> Unit
                                // The package link itself is a redirect without .zip, e.g.
                                // downloadURL = https://sysupgrade.vivo.com.cn/pk/redir.do?...
                                text.startsWith("http") && isPackageUrlKey(name, node) -> report(text, node, parent, "json:$key")
                                else -> zipUrl.findAll(text).forEach { report(it.value, node, parent, "json:$key") }
                            }
                        }
                        else -> walk(value, node, depth + 1)
                    }
                }
            }
            is JSONArray -> for (index in 0 until node.length()) walk(node.opt(index), parent, depth + 1)
        }
    }

    /** logUrl, logZip, logZipSha256, h5Zip: the changelog page, not the package. */
    private fun isChangelogKey(name: String) =
        name.startsWith("log") || name.startsWith("h5") || name.contains("banner") || name.contains("icon")

    private fun isPackageUrlKey(name: String, owner: JSONObject): Boolean = when {
        name.contains("download") && name.contains("url") -> true
        name in setOf("url", "fileurl", "packageurl", "otaurl", "pkgurl") ->
            field(owner) { it.contains("md5") || it.contains("sha") || it.contains("size") || it.contains("length") } != null
        else -> false
    }

    private fun scanText(text: String, source: String) {
        if (!text.contains(".zip", ignoreCase = true)) return
        zipUrl.findAll(text).forEach { report(it.value, null, null, source) }
    }

    private fun report(url: String, owner: JSONObject?, parent: JSONObject?, source: String) {
        val context = currentApplication() ?: return
        if (!captureEnabled(context)) return
        val versionKey: (String) -> Boolean = {
            (it.contains("version") || it.contains("vername") || it == "newver") && !it.contains("code")
        }
        // Prefer a value that looks like a firmware number (16.1.12.0) over a name or a date.
        val version = field(owner, parent) { versionKey(it) }?.takeIf { firmwareNumber.containsMatchIn(it) }
            ?: field(owner, parent, valueFilter = { firmwareNumber.containsMatchIn(it) }) { versionKey(it) }
            ?: field(owner, parent) { versionKey(it) }
        val size = field(owner, parent, valueFilter = { it.all(Char::isDigit) }) {
            (it.contains("size") || it.contains("length")) && !it.contains("space")
        }
        val md5 = field(owner, parent) { it == "sha256" || it == "md5" }
            ?: field(owner, parent) { it.contains("md5") || it.contains("sha") }
        // "incre" / "full" in the package object, packageType=patch in the redirect link.
        val type = field(owner) { it == "type" || it == "packagetype" }
            ?: Regex("""[?&]packageType=([^&]+)""").find(url)?.groupValues?.get(1)
        // Report a link again only when this time it comes with the version it belongs to.
        val dedupeKey = if (version != null) "$url#v" else url
        if (!reported.add(dedupeKey)) return
        val intent = Intent(ACTION_OTA_FOUND)
            .setComponent(ComponentName(MODULE_PACKAGE, "$MODULE_PACKAGE.OtaCaptureReceiver"))
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra(EXTRA_URL, url)
            .putExtra(EXTRA_VERSION, version)
            .putExtra(EXTRA_SIZE, size)
            .putExtra(EXTRA_MD5, md5)
            .putExtra(EXTRA_SOURCE, source)
            .putExtra(EXTRA_TYPE, type)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                // Lets the receiver check that the link really came from the updater.
                val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
                context.sendBroadcast(intent, null, options.toBundle())
            } else {
                context.sendBroadcast(intent)
            }
        }.onFailure(XposedBridge::log)
        XposedBridge.log("OriginCeiler OTA link: $url (version=$version, type=$type, from $source)")
    }

    private fun field(
        vararg objects: JSONObject?,
        valueFilter: (String) -> Boolean = { true },
        match: (String) -> Boolean
    ): String? =
        objects.firstNotNullOfOrNull { json ->
            json ?: return@firstNotNullOfOrNull null
            json.keys().asSequence()
                .filter { !isChangelogKey(it.lowercase()) && match(it.lowercase()) }
                .map { json.opt(it) }
                .filter { it is String || it is Number }
                .map { it.toString().trim() }
                .firstOrNull { it.isNotEmpty() && it.length < 160 && !it.startsWith("http") && valueFilter(it) }
        }

    private fun captureEnabled(context: Context): Boolean {
        refreshSettings(context)
        return captureEnabled
    }

    private fun currentSpoof(): Pair<String, String>? {
        currentApplication()?.let(::refreshSettings)
        return spoof
    }

    private fun refreshSettings(context: Context) {
        val now = SystemClock.uptimeMillis()
        if (spoofReadAt != 0L && now - spoofReadAt < SETTINGS_CACHE_MS) return
        // Settings reads can end up in SystemProperties.get, which is hooked.
        if (readingSettings.get() == true) return
        readingSettings.set(true)
        try {
            spoofReadAt = now
            val resolver = context.contentResolver
            captureEnabled = Settings.Global.getInt(resolver, SETTING_CAPTURE, 1) == 1
            val from = Settings.Global.getString(resolver, SETTING_SPOOF_FROM)
            val to = Settings.Global.getString(resolver, SETTING_SPOOF_TO)
            spoof = if (!from.isNullOrBlank() && !to.isNullOrBlank() && from != to) from to to else null
        } catch (_: Throwable) {
        } finally {
            readingSettings.set(false)
        }
        spoof?.let(::patchBuildFields)
    }

    /** Build fields are filled once at process start, before any property hook could apply. */
    private fun patchBuildFields(spoof: Pair<String, String>) {
        if (buildPatched) return
        buildPatched = true
        val (from, to) = spoof
        listOf(
            Build::class.java to "DISPLAY",
            Build::class.java to "FINGERPRINT",
            Build.VERSION::class.java to "INCREMENTAL"
        ).forEach { (owner, name) ->
            runCatching {
                val field = owner.getDeclaredField(name).apply { isAccessible = true }
                val value = field.get(null) as? String ?: return@runCatching
                if (value.contains(from)) field.set(null, value.replace(from, to))
            }
        }
    }

    private fun currentApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()
}
