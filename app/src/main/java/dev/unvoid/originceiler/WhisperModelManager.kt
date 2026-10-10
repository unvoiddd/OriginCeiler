package dev.unvoid.originceiler

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal object WhisperModelManager {
    const val MODEL_NAME = "ggml-tiny-q5_1.bin"
    const val MIN_MODEL_BYTES = 30_000_000L
    private const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin"

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "originroottoolbox-whisper"), MODEL_NAME)

    @Synchronized
    fun requireModel(context: Context): File {
        val target = modelFile(context)
        if (target.length() >= MIN_MODEL_BYTES) return target
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "$MODEL_NAME.part")
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "OriginRootToolbox/Whisper")
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(partial.length() >= MIN_MODEL_BYTES) { "Incomplete Whisper model" }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Cannot activate Whisper model" }
            return target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }
}
