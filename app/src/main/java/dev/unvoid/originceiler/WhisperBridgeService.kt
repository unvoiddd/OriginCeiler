package dev.unvoid.originceiler

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

/** Direct bridge: Android RecognitionService is deliberately bypassed here. */
class WhisperBridgeService : Service() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BlueLM-Whisper-ASR").apply { isDaemon = true }
    }
    private val active = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val decodeGeneration = AtomicLong(0)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var client: Messenger? = null

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            WhisperBridgeProtocol.START -> startRecognition(message.replyTo)
            WhisperBridgeProtocol.TRANSCRIBE -> transcribePcm(message.replyTo, message.data)
            WhisperBridgeProtocol.STOP -> stopRequested.set(true)
            WhisperBridgeProtocol.CANCEL -> cancelRecognition()
            WhisperBridgeProtocol.TRACE -> Log.i(
                LOG_TAG,
                "BlueLM hook: ${message.data.getString(WhisperBridgeProtocol.TEXT).orEmpty()}"
            )
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onDestroy() {
        cancelRecognition()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startRecognition(replyTo: Messenger?) {
        client = replyTo ?: return
        if (!active.compareAndSet(false, true)) {
            Log.i(LOG_TAG, "Duplicate bridge START ignored")
            return
        }
        stopRequested.set(false)
        worker.execute { recognizeDirectly() }
    }

    /** Decode audio captured under BlueLM's UID; this service never opens the mic. */
    private fun transcribePcm(replyTo: Messenger?, data: Bundle) {
        client = replyTo ?: return
        val pcm = data.getByteArray(WhisperBridgeProtocol.PCM) ?: run {
            send(WhisperBridgeProtocol.ERROR, SpeechRecognizer.ERROR_AUDIO)
            return
        }
        if (!active.compareAndSet(false, true)) {
            Log.w(LOG_TAG, "PCM transcription rejected because bridge is busy")
            send(WhisperBridgeProtocol.ERROR, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        stopRequested.set(false)
        val generation = decodeGeneration.incrementAndGet()
        Handler(Looper.getMainLooper()).postDelayed({
            if (active.get() && decodeGeneration.get() == generation) {
                Log.e(LOG_TAG, "Whisper native decode timed out; recycling decoder process")
                send(WhisperBridgeProtocol.ERROR, SpeechRecognizer.ERROR_SERVER)
                active.set(false)
                Handler(Looper.getMainLooper()).postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 250L)
            }
        }, DECODE_TIMEOUT_MS)
        worker.execute {
            try {
                val model = WhisperModelManager.requireModel(this)
                val samples = FloatArray(pcm.size / 2)
                var source = 0
                for (index in samples.indices) {
                    val value = (pcm[source].toInt() and 0xff) or (pcm[source + 1].toInt() shl 8)
                    samples[index] = value.toShort() / 32768.0f
                    source += 2
                }
                Log.i(LOG_TAG, "Decoding BlueLM PCM: samples=${samples.size}")
                val text = WhisperNative.transcribe(
                    model.absolutePath,
                    samples,
                    (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
                ).trim()
                Log.i(LOG_TAG, "BlueLM PCM Whisper text: $text")
                if (text.isBlank()) {
                    send(WhisperBridgeProtocol.ERROR, SpeechRecognizer.ERROR_NO_MATCH)
                } else {
                    send(WhisperBridgeProtocol.RESULT, data = Bundle().apply {
                        putString(WhisperBridgeProtocol.TEXT, text)
                    })
                }
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "BlueLM PCM transcription failed", error)
                send(WhisperBridgeProtocol.ERROR, SpeechRecognizer.ERROR_SERVER)
            } finally {
                if (decodeGeneration.get() == generation) {
                    active.set(false)
                    stopRequested.set(false)
                }
            }
        }
    }

    private fun recognizeDirectly() {
        var audioRecord: AudioRecord? = null
        try {
            val model = WhisperModelManager.requireModel(this)
            Log.i(LOG_TAG, "Direct bridge model ready: ${model.length()} bytes")
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
            send(WhisperBridgeProtocol.READY)
            audioRecord.startRecording()
            Log.i(LOG_TAG, "Direct bridge recording started")

            val chunks = ArrayList<ShortArray>()
            val buffer = ShortArray(minimum / 2)
            var sampleCount = 0
            var speechStarted = false
            var silentSamples = 0
            var peakRms = 0.0
            var nextLevelLog = SAMPLE_RATE
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
                peakRms = maxOf(peakRms, rms)
                if (rms >= SPEECH_RMS) {
                    if (!speechStarted) {
                        speechStarted = true
                        send(WhisperBridgeProtocol.BEGIN)
                    }
                    silentSamples = 0
                } else if (speechStarted) {
                    silentSamples += count
                    if (silentSamples >= END_SILENCE_SAMPLES) break
                } else if (sampleCount >= NO_SPEECH_SAMPLES) {
                    send(WhisperBridgeProtocol.ERROR, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    return
                }
                if (sampleCount >= nextLevelLog) {
                    Log.i(LOG_TAG, "Direct bridge audio: seconds=${sampleCount / SAMPLE_RATE}, rms=$rms, peak=$peakRms, speech=$speechStarted")
                    nextLevelLog += SAMPLE_RATE
                }
            }
            runCatching { audioRecord.stop() }
            Log.i(
                LOG_TAG,
                "Direct bridge capture finished: samples=$sampleCount, peak=$peakRms, speech=$speechStarted, requested=${stopRequested.get()}"
            )
            send(WhisperBridgeProtocol.END)
            if (!active.get()) return

            val samples = FloatArray(sampleCount)
            var offset = 0
            for (chunk in chunks) for (sample in chunk) samples[offset++] = sample / 32768.0f
            if (!speechStarted || samples.none { abs(it) >= 0.02f }) {
                send(WhisperBridgeProtocol.ERROR, SpeechRecognizer.ERROR_NO_MATCH)
                return
            }
            val text = WhisperNative.transcribe(
                model.absolutePath,
                samples,
                (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
            ).trim()
            Log.i(LOG_TAG, "Direct Whisper text: $text")
            if (text.isBlank()) {
                send(WhisperBridgeProtocol.ERROR, SpeechRecognizer.ERROR_NO_MATCH)
                return
            }
            send(WhisperBridgeProtocol.RESULT, data = Bundle().apply {
                putString(WhisperBridgeProtocol.TEXT, text)
            })
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "Direct Whisper bridge failed", error)
            send(WhisperBridgeProtocol.ERROR, SpeechRecognizer.ERROR_SERVER)
        } finally {
            runCatching { audioRecord?.release() }
            recorder = null
            active.set(false)
            stopRequested.set(false)
        }
    }

    private fun send(what: Int, arg: Int = 0, data: Bundle? = null) {
        runCatching {
            client?.send(Message.obtain(null, what, arg, 0).apply {
                if (data != null) this.data = data
            })
        }.onFailure { Log.e(LOG_TAG, "Cannot return bridge event $what", it) }
    }

    private fun cancelRecognition() {
        stopRequested.set(true)
        active.set(false)
        runCatching { recorder?.stop() }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        // VOICE_RECOGNITION on Vivo keeps a low, processed noise floor which can
        // prevent a silence-only endpoint from firing. Keep VAD for the quick
        // path, but always finalize a normal assistant utterance after 8 seconds.
        const val SPEECH_RMS = 0.008
        const val END_SILENCE_SAMPLES = SAMPLE_RATE * 6 / 5
        const val NO_SPEECH_SAMPLES = SAMPLE_RATE * 5
        const val MAX_SAMPLES = SAMPLE_RATE * 8
        const val DECODE_TIMEOUT_MS = 12_000L
        const val LOG_TAG = "OriginWhisper"
    }
}

internal object WhisperBridgeProtocol {
    const val START = 1
    const val STOP = 2
    const val CANCEL = 3
    const val TRACE = 4
    const val TRANSCRIBE = 5
    const val READY = 10
    const val BEGIN = 11
    const val END = 12
    const val PARTIAL = 13
    const val RESULT = 14
    const val ERROR = 15
    const val TEXT = "text"
    const val PCM = "pcm16le"
}
