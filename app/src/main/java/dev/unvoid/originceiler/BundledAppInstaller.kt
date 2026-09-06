package dev.unvoid.originceiler

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object BundledAppInstaller {
    fun isInstalled(context: Context, packageName: String): Boolean {
        return runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
    }

    fun requestInstall(context: Context, assetName: String, fileName: String) {
        val directory = File(context.cacheDir, "bundled_apks").apply { mkdirs() }
        val apk = File(directory, fileName)
        context.assets.open(assetName).use { input ->
            apk.outputStream().use { output -> input.copyTo(output) }
        }
        val uri: Uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    fun installWithRoot(context: Context, assetName: String, fileName: String): Boolean {
        val directory = File(context.cacheDir, "bundled_apks").apply { mkdirs() }
        val apk = File(directory, fileName)
        context.assets.open(assetName).use { input ->
            apk.outputStream().use { output -> input.copyTo(output) }
        }
        val source = apk.absolutePath.replace("'", "'\\''")
        val target = "/data/local/tmp/${fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")}" 
        val command = "cp '$source' '$target' && chmod 644 '$target' && pm install -r '$target'; RESULT=\$?; rm -f '$target'; exit \$RESULT"
        return runCatching {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor() == 0 && output.contains("Success", true)
        }.getOrDefault(false)
    }
}
