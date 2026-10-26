package dev.unvoid.originceiler

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

object LockscreenClockRuntime {
    private const val VIVO_LAYOUTS_ASSET = "native-clock/vivo/layouts.apk"
    private const val VIVO_LAYOUTS_SHA_ASSET = "native-clock/vivo/layouts.sha256"
    private const val HYPEROS_RUNTIME_ASSET = "native-clock/runtime/hyperos/runtime.apk"
    private const val HYPEROS_RUNTIME_SHA_ASSET = "native-clock/runtime/hyperos/runtime.sha256"
    private const val HYPEROS_GLOBAL_FONT_ASSET = "native-clock/runtime/hyperos/fonts/MiSansVF.ttf"
    private const val HYPEROS_GLOBAL_FONT_SHA = "0ddef90648998900175cfdca9a6f087a2544c182f130b0ad4f7e94a03a115e79"
    private const val FLYME_UI_ASSET = "ui/editor-ui.apk"
    private const val FLYME_UI_SHA_ASSET = "ui/editor-ui.sha256"
    private const val MODULE_PACKAGE = "dev.unvoid.originceiler"

    @Volatile
    private var moduleApkPath: String? = null

    @JvmStatic
    fun initModulePath(path: String) {
        if (path.isNotBlank()) {
            moduleApkPath = path
        }
    }

    @JvmStatic
    fun resolveModuleApkPath(context: Context): String {
        moduleApkPath?.let { if (File(it).exists()) return it }
        val fromPm = runCatching {
            context.packageManager.getApplicationInfo(MODULE_PACKAGE, 0).sourceDir
        }.getOrNull()
        if (!fromPm.isNullOrBlank() && File(fromPm).exists()) {
            moduleApkPath = fromPm
            return fromPm
        }
        return ""
    }

    @JvmStatic
    fun openAsset(context: Context, assetPath: String): java.io.InputStream {
        val apkPath = resolveModuleApkPath(context)
        if (apkPath.isNotBlank()) {
            val zf = runCatching { java.util.zip.ZipFile(apkPath) }.getOrNull()
            if (zf != null) {
                val clean = assetPath.trimStart('/')
                val entry = zf.getEntry("assets/$clean") ?: zf.getEntry(clean)
                if (entry != null) {
                    return zf.getInputStream(entry)
                }
            }
        }
        val moduleCtx = getModuleContext(context)
        return moduleCtx.assets.open(assetPath)
    }

    @JvmStatic
    fun getModuleContext(host: Context): Context {
        if (host.packageName == MODULE_PACKAGE) return host
        return runCatching {
            host.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: host
    }

    @JvmStatic
    @Synchronized
    fun vivoLayouts(host: Context): File {
        val digest = readSha(host, VIVO_LAYOUTS_SHA_ASSET, "be647ffce9b4ffb723800c1f1298915fc99b4ba7ca1e312a4b3edbc56afdd619")
        return unpackFile(
            host = host,
            asset = VIVO_LAYOUTS_ASSET,
            digest = digest,
            directory = "original-clock-vivo",
            suffix = ".apk"
        )
    }

    @JvmStatic
    @Synchronized
    fun unpack(host: Context, name: String): File {
        if (name != "hyperos") throw IllegalArgumentException("Unknown clock runtime: $name")
        val digest = readSha(host, HYPEROS_RUNTIME_SHA_ASSET, "9ff06ad561bc522005715616c9dd7eeb16f480c3da29850ff3c3b5753684bc9b")
        return unpackFile(
            host = host,
            asset = HYPEROS_RUNTIME_ASSET,
            digest = digest,
            directory = "original-clock-hyperos",
            suffix = ".apk"
        )
    }

    @JvmStatic
    @Synchronized
    fun globalFont(host: Context): File {
        return unpackFile(
            host = host,
            asset = HYPEROS_GLOBAL_FONT_ASSET,
            digest = HYPEROS_GLOBAL_FONT_SHA,
            directory = "original-clock-hyperos",
            suffix = ".ttf"
        )
    }

    @JvmStatic
    @Synchronized
    fun flymeUi(host: Context): File {
        val digest = readSha(host, FLYME_UI_SHA_ASSET, "ffc85195a80813668ebd39151bef9398bfc1bc728c7f8d7b9f2b70ba78bf7f65")
        return unpackFile(
            host = host,
            asset = FLYME_UI_ASSET,
            digest = digest,
            directory = "official-ui",
            suffix = ".apk"
        )
    }

    private fun readSha(host: Context, assetPath: String, fallback: String): String {
        return runCatching {
            openAsset(host, assetPath).use { inStream ->
                val out = ByteArrayOutputStream()
                val b = ByteArray(128)
                var n: Int
                while (inStream.read(b).also { n = it } != -1) {
                    out.write(b, 0, n)
                    if (out.size() > 128) throw IOException("Invalid digest size")
                }
                out.toString("US-ASCII").trim()
            }
        }.getOrDefault(fallback)
    }

    private fun unpackFile(
        host: Context,
        asset: String,
        digest: String,
        directory: String,
        suffix: String
    ): File {
        if (!digest.matches(Regex("[a-f0-9]{64}"))) {
            throw IOException("Invalid clock runtime digest: $digest")
        }
        val folder = File(host.codeCacheDir, directory)
        if (!folder.isDirectory && !folder.mkdirs()) {
            throw IOException("Clock runtime cache folder unavailable")
        }
        val target = File(folder, digest + suffix)
        if (target.isFile && target.length() > 0) {
            return target
        }

        val temporary = File.createTempFile("runtime-", ".tmp", folder)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            openAsset(host, asset).use { inStream ->
                FileOutputStream(temporary).use { outStream ->
                    runCatching { temporary.setReadOnly() }
                    val chunk = ByteArray(65536)
                    var n: Int
                    while (inStream.read(chunk).also { n = it } != -1) {
                        outStream.write(chunk, 0, n)
                        hash.update(chunk, 0, n)
                    }
                    outStream.fd.sync()
                }
            }
            val actual = StringBuilder()
            for (b in hash.digest()) {
                actual.append(String.format(java.util.Locale.ROOT, "%02x", b.toInt() and 0xFF))
            }
            if (actual.toString() != digest) {
                if (temporary.length() == 0L) {
                    throw IOException("Clock runtime checksum mismatch: expected $digest, got $actual")
                }
            }
            temporary.setReadOnly()
            if (!temporary.renameTo(target)) {
                if (!target.exists()) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
            }
            target.setReadOnly()
            return target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
