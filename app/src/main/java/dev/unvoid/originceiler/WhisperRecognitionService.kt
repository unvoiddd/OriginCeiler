package dev.unvoid.originceiler

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

class WhisperRecognitionService : RecognitionService() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "System-Whisper-ASR").apply { isDaemon = true }
    }
    private val active = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null

    override fun onStartListening(recognizerIntent: Intent?, callback: Callback) {
        Log.i(LOG_TAG, "RecognitionService startListening")
        if (!active.compareAndSet(false, true)) {
            callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        stopRequested.set(false)
        worker.execute { recognize(callback) }
    }

    override fun onStopListening(callback: Callback) {
        stopRequested.set(true)
    }

    override fun onCancel(callback: Callback) {
        stopRequested.set(true)
        active.set(false)
        runCatching { recorder?.stop() }
    }

    override fun onDestroy() {
        stopRequested.set(true)
        runCatching { recorder?.stop() }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun recognize(callback: Callback) {
        var audioRecord: AudioRecord? = null
        try {
            val model = WhisperModelManager.requireModel(this)
            Log.i(LOG_TAG, "Whisper model ready: ${model.length()} bytes")
            if (!active.get()) return
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
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord unavailable" }
            recorder = audioRecord
            callback.readyForSpeech(Bundle())
            Log.i(LOG_TAG, "AudioRecord ready")
            audioRecord.startRecording()

            val chunks = ArrayList<ShortArray>()
            val buffer = ShortArray(minimum / 2)
            var sampleCount = 0
            var speechStarted = false
            var silentSamples = 0
            while (active.get() && !stopRequested.get() && sampleCount < MAX_SAMPLES) {
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
                callback.rmsChanged((20.0 * kotlin.math.log10(rms.coerceAtLeast(0.00001))).toFloat())
                if (rms >= SPEECH_RMS) {
                    if (!speechStarted) {
                        speechStarted = true
                        callback.beginningOfSpeech()
                    }
                    silentSamples = 0
                } else if (speechStarted) {
                    silentSamples += count
                    if (silentSamples >= END_SILENCE_SAMPLES) break
                } else if (sampleCount >= NO_SPEECH_SAMPLES) {
                    callback.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    return
                }
            }
            runCatching { audioRecord.stop() }
            callback.endOfSpeech()
            if (!active.get()) return

            val samples = FloatArray(sampleCount)
            var offset = 0
            for (chunk in chunks) for (sample in chunk) samples[offset++] = sample / 32768.0f
            if (!speechStarted || samples.none { abs(it) >= 0.02f }) {
                callback.error(SpeechRecognizer.ERROR_NO_MATCH)
                return
            }
            val text = WhisperNative.transcribe(
                model.absolutePath,
                samples,
                (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
            ).trim()
            Log.i(LOG_TAG, "Whisper transcription finished: ${text.length} chars")
            if (text.isBlank()) {
                callback.error(SpeechRecognizer.ERROR_NO_MATCH)
                return
            }
            callback.results(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1.0f))
            })
        } catch (error: SecurityException) {
            Log.e(LOG_TAG, "Microphone permission denied", error)
            callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "Recognition failed", error)
            callback.error(SpeechRecognizer.ERROR_SERVER)
        } finally {
            runCatching { audioRecord?.release() }
            recorder = null
            active.set(false)
            stopRequested.set(false)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val SPEECH_RMS = 0.004
        const val END_SILENCE_SAMPLES = SAMPLE_RATE
        const val NO_SPEECH_SAMPLES = SAMPLE_RATE * 6
        const val MAX_SAMPLES = SAMPLE_RATE * 20
        const val LOG_TAG = "OriginWhisper"
    }
}
