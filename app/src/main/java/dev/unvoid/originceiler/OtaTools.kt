package dev.unvoid.originceiler

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A system update package link caught inside the updater by [OtaUpdaterHook]. */
data class OtaCapture(
    val url: String,
    val version: String?,
    val size: Long?,
    val md5: String?,
    val source: String,
    val time: Long,
    /** Raw package type from the server: "incre" / "patch" or "full". */
    val type: String? = null
) {
    val incremental: Boolean
        get() = type?.lowercase()?.let { it.startsWith("incre") || it == "patch" || it == "delta" } == true

    val full: Boolean
        get() = type?.lowercase()?.let { it == "full" || it == "whole" } == true

    val fileName: String
        get() = Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() } ?: "ota-update.zip"

    /** The firmware number in [version], e.g. "16.1.12.0" from "PD2419_A_16.1.12.0.W10". */
    val versionNumber: String?
        get() = version?.let(OtaVersion::numberIn)
}

/** One system property the fake update rewrites. */
data class OtaPropChange(val key: String, val from: String, val to: String)

object OtaVersion {
    private val number = Regex("""\d+(?:\.\d+){2,}""")

    /** Properties holding the vivo firmware version, the first one found names the current version. */
    private val versionKeys = listOf(
        "ro.vivo.product.version",
        "ro.build.version.bbk",
        "ro.build.software.version",
        "ro.vivo.build.version",
        "ro.build.display.id"
    )

    fun numberIn(text: String): String? = number.find(text)?.value

    fun displayVersion(props: Map<String, String>): String =
        versionKeys.firstNotNullOfOrNull { props[it]?.takeIf { value -> numberIn(value) != null } }
            ?: props["ro.build.display.id"].orEmpty()

    /** Every property whose value carries [from] as a whole version number, rewritten to [to]. */
    fun changes(props: Map<String, String>, from: String, to: String): List<OtaPropChange> {
        if (from.isBlank() || to.isBlank() || from == to) return emptyList()
        val token = Regex("""(?<![\d.])${Regex.escape(from)}(?!\.?\d)""")
        return props.mapNotNull { (key, value) ->
            if (token.containsMatchIn(value)) OtaPropChange(key, value, token.replace(value, to)) else null
        }.sortedBy { it.key }
    }

    fun isValidNumber(text: String) = Regex("""\d+(?:\.\d+){2,}""").matches(text)
}

/** Captured links, newest first, in the module's own preferences. */
object OtaStore {
    const val PREFERENCES = "ota"
    const val KEY_HISTORY = "history"
    private const val LIMIT = 20

    fun load(context: Context): List<OtaCapture> = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFERENCES, 0).getString(KEY_HISTORY, "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            OtaCapture(
                url = item.getString("url"),
                version = item.optString("version").ifBlank { null },
                size = item.optLong("size", -1).takeIf { it > 0 },
                md5 = item.optString("md5").ifBlank { null },
                source = item.optString("source"),
                time = item.optLong("time"),
                type = item.optString("type").ifBlank { null }
            )
        }
    }.getOrDefault(emptyList())

    /** Returns true when the link is new, a known link only gains the fields it was missing. */
    fun add(context: Context, capture: OtaCapture): Boolean {
        val current = load(context)
        val existing = current.firstOrNull { it.url == capture.url }
        val merged = existing?.copy(
            version = existing.version ?: capture.version,
            size = existing.size ?: capture.size,
            md5 = existing.md5 ?: capture.md5,
            type = existing.type ?: capture.type
        ) ?: capture
        save(context, (listOf(merged) + current.filter { it.url != capture.url }).take(LIMIT))
        return existing == null
    }

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, captures: List<OtaCapture>) {
        val array = JSONArray()
        captures.forEach {
            array.put(JSONObject().apply {
                put("url", it.url)
                put("version", it.version.orEmpty())
                put("size", it.size ?: -1)
                put("md5", it.md5.orEmpty())
                put("source", it.source)
                put("time", it.time)
                put("type", it.type.orEmpty())
            })
        }
        // commit: the receiver's process can be frozen right after onReceive, before apply writes.
        context.getSharedPreferences(PREFERENCES, 0).edit().putString(KEY_HISTORY, array.toString()).commit()
    }
}

object OtaActions {
    private const val CHANNEL = "ota_updates"

    fun download(context: Context, capture: OtaCapture): Boolean = runCatching {
        val request = DownloadManager.Request(Uri.parse(capture.url))
            .setTitle(capture.version ?: capture.fileName)
            .setDescription(capture.fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "OTA/${capture.fileName}")
        context.getSystemService(DownloadManager::class.java).enqueue(request)
        true
    }.getOrDefault(false)

    fun copy(context: Context, capture: OtaCapture) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(capture.version ?: "OTA", capture.url))
    }

    fun notifyFound(context: Context, capture: OtaCapture) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("System updates"), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val id = capture.url.hashCode()
        fun action(action: String, request: Int) = PendingIntent.getBroadcast(
            context,
            id + request,
            Intent(context, OtaActionReceiver::class.java).setAction(action).putExtra(OtaUpdaterHook.EXTRA_URL, capture.url),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(tr("System update found"))
            .setContentText(capture.version ?: capture.fileName)
            .setSubText(capture.size?.let(::formatSize))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, tr("Download"), action(OtaActionReceiver.ACTION_DOWNLOAD, 1)).build())
            .addAction(Notification.Action.Builder(null, tr("Copy link"), action(OtaActionReceiver.ACTION_COPY, 2)).build())
            .build()
        runCatching { manager.notify(id, notification) }
    }

    /** Decimal units, the way the updater shows package sizes. */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1e9)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1e6)
        else -> "${bytes / 1000} KB"
    }
}

/** Receives links from [OtaUpdaterHook]; exported, so it only trusts the updater itself. */
class OtaCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OtaUpdaterHook.ACTION_OTA_FOUND) return
        // The hook always shares its identity on Android 14+, anything else is not the updater.
        if (Build.VERSION.SDK_INT >= 34 && sentFromPackage !in OtaUpdaterHook.PACKAGES) return
        val url = intent.getStringExtra(OtaUpdaterHook.EXTRA_URL)?.takeIf { it.startsWith("http") } ?: return
        val capture = OtaCapture(
            url = url,
            version = intent.getStringExtra(OtaUpdaterHook.EXTRA_VERSION),
            size = intent.getStringExtra(OtaUpdaterHook.EXTRA_SIZE)?.toLongOrNull(),
            md5 = intent.getStringExtra(OtaUpdaterHook.EXTRA_MD5),
            source = intent.getStringExtra(OtaUpdaterHook.EXTRA_SOURCE).orEmpty(),
            time = System.currentTimeMillis(),
            type = intent.getStringExtra(OtaUpdaterHook.EXTRA_TYPE)
        )
        AppLanguage.init(context)
        val isNew = OtaStore.add(context, capture)
        if (isNew || capture.version != null) {
            OtaStore.load(context).firstOrNull { it.url == url }?.let { OtaActions.notifyFound(context, it) }
        }
    }
}

/** Notification buttons. Only links already in [OtaStore] are acted on. */
class OtaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(OtaUpdaterHook.EXTRA_URL) ?: return
        val capture = OtaStore.load(context).firstOrNull { it.url == url } ?: return
        AppLanguage.init(context)
        when (intent.action) {
            ACTION_DOWNLOAD -> {
                val started = OtaActions.download(context, capture)
                Toast.makeText(context, tr(if (started) "Download started" else "Unable to start download"), Toast.LENGTH_SHORT).show()
            }
            ACTION_COPY -> {
                OtaActions.copy(context, capture)
                Toast.makeText(context, tr("Link copied"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val ACTION_DOWNLOAD = "dev.unvoid.originceiler.OTA_DOWNLOAD"
        const val ACTION_COPY = "dev.unvoid.originceiler.OTA_COPY"
    }
}

/**
 * Root side of the System Update page. The fake update rewrites every property that holds the
 * current firmware number with resetprop, right away and again on each boot from a
 * post-fs-data script. The script only touches a property while it still has the value it had
 * when the fake was applied, so after a real update it does nothing.
 */
class OtaRootTools(private val context: Context) {
    fun readProps(): Map<String, String> {
        val output = execute("getprop").message
        val line = Regex("""^\[(.+?)]: \[(.*)]$""")
        return output.lineSequence().mapNotNull { line.find(it.trim())?.destructured }
            .associate { (key, value) -> key to value }
    }

    fun spoofActive(): Boolean = execute("[ -f $BACKUP ]").success

    fun applySpoof(changes: List<OtaPropChange>, from: String, to: String): RootResult {
        val settings = "settings put global ${OtaUpdaterHook.SETTING_SPOOF_FROM} '$from'; " +
            "settings put global ${OtaUpdaterHook.SETTING_SPOOF_TO} '$to'"
        val script = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# OriginCeiler fake system update: $from -> $to")
            appendLine(RESETPROP_LOOKUP)
            appendLine("[ -n \"\$RP\" ] || exit 0")
            changes.forEach {
                appendLine("[ \"\$(getprop ${quote(it.key)})\" = ${quote(it.from)} ] && \"\$RP\" -n ${quote(it.key)} ${quote(it.to)}")
            }
            appendLine("exit 0")
        }
        val backup = changes.joinToString("\n") { "${it.key}=${it.from}" }
        val scriptFile = File(context.cacheDir, "ota_spoof.sh").apply { writeText(script) }
        val backupFile = File(context.cacheDir, "ota_backup.prop").apply { writeText(backup) }
        val result = execute(
            "$RESETPROP_LOOKUP; " +
                "mkdir -p $DIR /data/adb/post-fs-data.d; " +
                "cp '${scriptFile.absolutePath}' $BOOT_SCRIPT && chmod 755 $BOOT_SCRIPT; " +
                "cp '${backupFile.absolutePath}' $BACKUP; " +
                "$settings; " +
                "if [ -n \"\$RP\" ]; then sh $BOOT_SCRIPT; echo applied; else echo updater-only; fi; " +
                "am force-stop ${UPDATER}"
        )
        scriptFile.delete()
        backupFile.delete()
        return result
    }

    fun restoreSpoof(): RootResult {
        val backup = execute("cat $BACKUP 2>/dev/null").message
        val restore = backup.lineSequence().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else "\"\$RP\" -n ${quote(line.substring(0, split))} ${quote(line.substring(split + 1))}"
        }.joinToString("; ")
        return execute(
            "$RESETPROP_LOOKUP; " +
                (if (restore.isNotEmpty()) "[ -n \"\$RP\" ] && { $restore; }; " else "") +
                "rm -f $BOOT_SCRIPT $BACKUP; " +
                "settings delete global ${OtaUpdaterHook.SETTING_SPOOF_FROM}; " +
                "settings delete global ${OtaUpdaterHook.SETTING_SPOOF_TO}; " +
                "am force-stop $UPDATER"
        )
    }

    fun updaterInstalled(): Boolean =
        runCatching { context.packageManager.getApplicationInfo(UPDATER, PackageManager.MATCH_DISABLED_COMPONENTS) }.isSuccess

    fun updaterDisabled(): Boolean = runCatching {
        context.packageManager.getApplicationEnabledSetting(UPDATER) in setOf(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        )
    }.getOrDefault(false)

    fun setUpdaterDisabled(disabled: Boolean): RootResult = execute(
        if (disabled) "am force-stop $UPDATER; pm disable-user --user 0 $UPDATER" else "pm enable $UPDATER"
    )

    fun setCapture(enabled: Boolean): RootResult = execute(
        "settings put global ${OtaUpdaterHook.SETTING_CAPTURE} ${if (enabled) 1 else 0}; " +
            (if (enabled) "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS 2>/dev/null; " else "") +
            "am force-stop $UPDATER"
    )

    fun clearUpdaterData(): RootResult = execute("pm clear $UPDATER")

    fun openUpdater(): RootResult = execute("monkey -p $UPDATER -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")

    fun reboot(target: String): RootResult = execute(
        when (target) {
            "soft" -> "setprop ctl.restart zygote"
            "recovery" -> "svc power reboot recovery || reboot recovery"
            "bootloader" -> "svc power reboot bootloader || reboot bootloader"
            else -> "svc power reboot || reboot"
        }
    )

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun execute(command: String): RootResult = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        RootResult(code == 0, output.ifBlank { if (code == 0) "Done" else "Error code $code" })
    }.getOrElse { RootResult(false, it.message ?: "Unable to start su") }

    companion object {
        const val UPDATER = "com.bbk.updater"
        private const val DIR = "/data/adb/originceiler"
        private const val BACKUP = "$DIR/ota_spoof_backup.prop"
        private const val BOOT_SCRIPT = "/data/adb/post-fs-data.d/originceiler_fake_ota.sh"

        // Magisk puts resetprop in PATH, APatch and KernelSU keep it in their own bin folders.
        private const val RESETPROP_LOOKUP =
            "RP=\$(command -v resetprop 2>/dev/null); " +
                "for C in /data/adb/ap/bin/resetprop /data/adb/ksu/bin/resetprop /data/adb/magisk/resetprop; do " +
                "[ -z \"\$RP\" ] && [ -x \"\$C\" ] && RP=\$C; done"
    }
}
