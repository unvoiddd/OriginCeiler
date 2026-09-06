package dev.unvoid.originceiler

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import java.io.File

class GboardBackgroundProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val currentContext = context ?: error("Provider context is unavailable")
        val packages = currentContext.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if ("com.google.android.inputmethod.latin" !in packages && "com.android.systemui" !in packages && currentContext.packageName !in packages) {
            throw SecurityException("Caller is not allowed")
        }
        val prefix = if (uri.lastPathSegment == "notifications") "notifications-background" else "gboard-background"
        val target = File(currentContext.cacheDir, "$prefix.png")
        val temporary = File(currentContext.cacheDir, "$prefix.tmp.png")
        val uid = android.os.Process.myUid()
        val targetPath = shellPath(target.absolutePath)
        val temporaryPath = shellPath(temporary.absolutePath)
        val command = "/system/bin/screencap -p $temporaryPath && chown $uid:$uid $temporaryPath && chmod 600 $temporaryPath && mv -f $temporaryPath $targetPath"
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0 || !target.isFile) error("Unable to capture keyboard background")
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "image/png"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun shellPath(path: String): String = "'${path.replace("'", "'\\''")}'"
}
