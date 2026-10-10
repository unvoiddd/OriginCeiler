package dev.unvoid.originceiler

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Captures assistant audio inside the BlueLM process.
 *
 * This is intentionally not done by WhisperBridgeService: Android silences a
 * microphone opened by the module UID while BlueLM is the visible assistant.
 */
internal class BlueLmAudioCapture(
    private val listener: Listener
) {
    interface Listener {
        fun onReady()
        fun onBeginningOfSpeech()
        fun onEndOfSpeech()
        fun onAudio(pcm16le: ByteArray)
        fun onNoSpeech()
        fun onError(error: Throwable)
    }

    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        stopRequested.set(false)
        Thread(::capture, "BlueLM-Whisper-Capture").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        stopRequested.set(true)
    }

    fun cancel() {
        running.set(false)
        stopRequested.set(true)
        runCatching { recorder?.stop() }
    }

    private fun capture() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var audioRecord: AudioRecord? = null
        try {
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLE_RATE / 2)
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minimum * 2)
                .build()
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                "BlueLM AudioRecord is not initialized"
            }
            recorder = audioRecord
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "BlueLM AudioRecord did not start"
            }
            // The overlay may only enter its listening state after this callback.
            // Never announce readiness before AudioFlinger has actually started.
            listener.onReady()
            Log.i(LOG_TAG, "BlueLM-process recording started")

            val chunks = ArrayList<ShortArray>()
            val buffer = ShortArray(minimum / 2)
            var sampleCount = 0
            var speechStarted = false
            var silentSamples = 0
            var peakRms = 0.0
            var nextLevelLog = SAMPLE_RATE
            while (running.get() && !stopRequested.get() && sampleCount < MAX_SAMPLES) {
                val count = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) continue
                val chunk = buffer.copyOf(count)
                chunks += chunk
                sampleCount += count
                var energy = 0.0
                for (i in 0 until count) {
                    val value = chunk[i] / 32768.0
                    energy += value * value
                }
                val rms = sqrt(energy / count)
                peakRms = maxOf(peakRms, rms)
                if (rms >= SPEECH_RMS) {
                    if (!speechStarted) {
                        speechStarted = true
                        listener.onBeginningOfSpeech()
                    }
                    silentSamples = 0
                } else if (speechStarted) {
                    silentSamples += count
                    if (silentSamples >= END_SILENCE_SAMPLES) break
                } else if (sampleCount >= NO_SPEECH_SAMPLES) {
                    listener.onNoSpeech()
                    return
                }
                if (sampleCount >= nextLevelLog) {
                    Log.i(
                        LOG_TAG,
                        "BlueLM-process audio: seconds=${sampleCount / SAMPLE_RATE}, " +
                            "rms=$rms, peak=$peakRms, speech=$speechStarted"
                    )
                    nextLevelLog += SAMPLE_RATE
                }
            }
            runCatching { audioRecord.stop() }
            if (!running.get()) return
            Log.i(
                LOG_TAG,
                "BlueLM-process capture finished: samples=$sampleCount, peak=$peakRms, " +
                    "speech=$speechStarted, requested=${stopRequested.get()}"
            )
            listener.onEndOfSpeech()
            if (!speechStarted || sampleCount == 0) {
                listener.onNoSpeech()
                return
            }

            val pcm = ByteArray(sampleCount * 2)
            var byteOffset = 0
            for (chunk in chunks) {
                for (sample in chunk) {
                    val value = sample.toInt()
                    pcm[byteOffset++] = (value and 0xff).toByte()
                    pcm[byteOffset++] = ((value ushr 8) and 0xff).toByte()
                }
            }
            listener.onAudio(pcm)
        } catch (error: Throwable) {
            if (running.get()) listener.onError(error)
        } finally {
            runCatching { audioRecord?.release() }
            recorder = null
            running.set(false)
            stopRequested.set(false)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val SPEECH_RMS = 0.008
        const val END_SILENCE_SAMPLES = SAMPLE_RATE * 6 / 5
        const val NO_SPEECH_SAMPLES = SAMPLE_RATE * 5
        const val MAX_SAMPLES = SAMPLE_RATE * 8
        const val LOG_TAG = "OriginWhisper"
    }
}
