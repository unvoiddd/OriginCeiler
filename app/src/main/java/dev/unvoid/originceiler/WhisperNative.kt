package dev.unvoid.originceiler

internal object WhisperNative {
    init {
        System.loadLibrary("originwhisper")
    }

    external fun transcribe(modelPath: String, samples: FloatArray, threads: Int): String
}
