// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.osfans.trime.ime.core.TrimeInputMethodService
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * On-device streaming speech recognition with sherpa-onnx.
 *
 * All model loading, audio capture and decoding happen off the main thread.
 * The recognizer is created from files extracted by [VoiceModelStore]; it is
 * released when the keyboard hides so the IME does not sit on a few hundred
 * megabytes of native memory while the user is typing.
 */
object OfflineVoiceManager {
    enum class State { IDLE, PREPARING, LISTENING, FINISHING }

    interface Listener {
        fun onStateChanged(state: State)

        fun onCaption(text: String)

        fun onTranscript(text: String)

        fun onAmplitude(level: Float)

        fun onError(message: String)
    }

    private const val SAMPLE_RATE = 16_000
    private const val MAX_SESSION_MS = 60_000L
    private const val IDLE_RELEASE_MS = 120_000L
    private const val SPEECH_THRESHOLD = 0.02f
    private const val PAUSE_THRESHOLD = 0.012f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "trime-offline-voice").apply { isDaemon = true }
    }

    @Volatile
    private var state = State.IDLE

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var service: TrimeInputMethodService? = null

    private val stopRequested = AtomicBoolean(false)
    private val commitOnFinish = AtomicBoolean(true)
    private val preloadStarted = AtomicBoolean(false)

    private var lastPartial = ""

    private val idleRelease = Runnable {
        worker.execute {
            runCatching { recognizer?.release() }
            recognizer = null
        }
    }

    fun setListener(value: Listener?) {
        listener = value
    }

    fun isBusy(): Boolean = state != State.IDLE

    /**
     * Copies the bundled model to app storage in the background so the first
     * microphone tap does not wait for ~180 MB of asset extraction.
     */
    fun preload(context: android.content.Context) {
        if (!preloadStarted.compareAndSet(false, true)) return
        worker.execute {
            if (VoiceModelStore.prepare(context) { } == null) preloadStarted.set(false)
        }
    }

    fun toggle(service: TrimeInputMethodService) {
        if (state == State.IDLE) start(service) else stop()
    }

    fun start(service: TrimeInputMethodService) {
        if (state != State.IDLE) return
        mainHandler.removeCallbacks(idleRelease)
        this.service = service
        stopRequested.set(false)
        commitOnFinish.set(true)
        lastPartial = ""
        setState(State.PREPARING)
        postCaption("正在准备离线语音模型…")
        worker.execute { runSession(service) }
    }

    /** Ends the current session and commits the recognized text. */
    fun stop() {
        Timber.i("voice: stop requested (state=%s)", state)
        if (state != State.IDLE) {
            commitOnFinish.set(true)
            stopRequested.set(true)
        }
    }

    /** Ends the current session without committing anything. */
    fun cancel() {
        if (state != State.IDLE) {
            commitOnFinish.set(false)
            stopRequested.set(true)
        }
    }

    /** Frees native memory; called when the keyboard hides. */
    fun release() {
        cancel()
        // Queued behind a running session on the same worker, so a live decode
        // never loses its native recognizer.
        worker.execute {
            runCatching { recognizer?.release() }
            recognizer = null
        }
    }

    private fun runSession(service: TrimeInputMethodService) {
        var stream: OnlineStream? = null
        var recorder: AudioRecord? = null
        var finalText = ""
        val startedAt = SystemClock.uptimeMillis()
        try {
            val dir = VoiceModelStore.prepare(service) { percent ->
                if (percent > 0 && percent % 10 == 0) postCaption("正在准备离线语音模型… $percent%")
            }
            if (dir == null) {
                fail("离线语音模型不可用")
                return
            }
            Timber.i("voice: model files ready in %d ms", SystemClock.uptimeMillis() - startedAt)
            val engine = ensureRecognizer(dir)
            if (engine == null) {
                fail("离线语音模型加载失败")
                return
            }
            Timber.i("voice: recognizer ready in %d ms", SystemClock.uptimeMillis() - startedAt)
            if (stopRequested.get()) {
                finishSession("")
                return
            }
            stream = engine.createStream()
            recorder = openRecorder()
            if (recorder == null) {
                fail("无法打开麦克风")
                return
            }
            recorder.startRecording()
            setState(State.LISTENING)
            postCaption("正在聆听 · 点击麦克风结束")

            val startedAt = SystemClock.uptimeMillis()
            // Meter each 25 ms slice, but keep ASR/punctuation on 100 ms batches.
            val shorts = ShortArray(SAMPLE_RATE / 40)
            val floats = FloatArray(1_600)
            var filled = 0
            var batchEnergy = 0.0
            val pausePrefixes = ArrayList<String>(4)
            var speaking = false
            while (!stopRequested.get() && SystemClock.uptimeMillis() - startedAt < MAX_SESSION_MS) {
                val read = recorder.read(shorts, 0, minOf(shorts.size, floats.size - filled))
                if (read <= 0) continue
                var energy = 0.0
                for (i in 0 until read) {
                    val sample = shorts[i] / 32768.0f
                    floats[filled + i] = sample
                    energy += sample * sample
                }
                postAmplitude(sqrt(energy / read).toFloat())
                filled += read
                batchEnergy += energy
                if (filled < floats.size) continue
                val level = sqrt(batchEnergy / filled).toFloat()
                stream.acceptWaveform(floats, SAMPLE_RATE)
                filled = 0
                batchEnergy = 0.0
                while (engine.isReady(stream)) engine.decode(stream)
                val partial = cleanText(engine.getResult(stream).text)
                if (partial.isNotEmpty() && partial != lastPartial) {
                    lastPartial = partial
                    postTranscript(partial)
                }
                // A pause between phrases becomes a comma in the final text.
                if (level > SPEECH_THRESHOLD) {
                    speaking = true
                } else if (speaking && level < PAUSE_THRESHOLD) {
                    speaking = false
                    val last = pausePrefixes.lastOrNull()
                    if (partial.length >= 3 && partial != last && partial.length - (last?.length ?: 0) >= 3) {
                        pausePrefixes.add(partial)
                    }
                }
            }

            setState(State.FINISHING)
            postCaption("正在识别…")
            if (filled > 0) stream.acceptWaveform(floats.copyOf(filled), SAMPLE_RATE)
            stream.inputFinished()
            while (engine.isReady(stream)) engine.decode(stream)
            finalText = VoiceTextNormalizer.punctuate(
                VoiceTextNormalizer.normalizeNumbers(cleanText(engine.getResult(stream).text)),
                pausePrefixes,
            )
            Timber.i(
                "voice: session finished in %d ms, %d chars",
                SystemClock.uptimeMillis() - startedAt,
                finalText.length,
            )
        } catch (e: Throwable) {
            Timber.e(e, "Offline voice session failed")
            fail("语音识别出错")
            return
        } finally {
            runCatching {
                recorder?.stop()
                recorder?.release()
            }
            runCatching { stream?.release() }
        }
        finishSession(
            finalText.ifEmpty {
                VoiceTextNormalizer.punctuate(
                    VoiceTextNormalizer.normalizeNumbers(lastPartial),
                    emptyList(),
                )
            },
        )
    }

    private fun finishSession(text: String) {
        val shouldCommit = commitOnFinish.get()
        val target = service
        if (shouldCommit && text.isNotEmpty() && target != null) {
            mainHandler.post {
                if (target.currentInputConnection != null) target.commitText(text)
            }
        }
        setState(State.IDLE)
        if (text.isEmpty()) postCaption("没有听清，请再说一次") else postCaption("已输入")
        service = null
        mainHandler.removeCallbacks(idleRelease)
        mainHandler.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    private fun fail(message: String) {
        setState(State.IDLE)
        service = null
        mainHandler.post { listener?.onError(message) }
        mainHandler.removeCallbacks(idleRelease)
        mainHandler.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    private fun ensureRecognizer(dir: File): OnlineRecognizer? {
        recognizer?.let { return it }
        val engine = runCatching {
            val modelConfig = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig().apply {
                    encoder = VoiceModelStore.file(dir, "encoder-epoch-99-avg-1.int8.onnx")
                    decoder = VoiceModelStore.file(dir, "decoder-epoch-99-avg-1.int8.onnx")
                    joiner = VoiceModelStore.file(dir, "joiner-epoch-99-avg-1.int8.onnx")
                }
                tokens = VoiceModelStore.file(dir, "tokens.txt")
                bpeVocab = VoiceModelStore.file(dir, "bpe.model")
                modelType = "zipformer"
                modelingUnit = "cjkchar+bpe"
                numThreads = 2
                debug = false
            }
            val config = OnlineRecognizerConfig().apply {
                this.modelConfig = modelConfig
                this.featConfig = FeatureConfig(SAMPLE_RATE, 80, 0f)
                enableEndpoint = false
            }
            // A null AssetManager makes sherpa read the absolute model paths above.
            OnlineRecognizer(null, config)
        }.onFailure { Timber.e(it, "Failed to initialize sherpa-onnx recognizer") }.getOrNull()
        recognizer = engine
        return engine
    }

    @Suppress("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE)
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val recorder = runCatching {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 2,
                )
            }.getOrNull() ?: continue
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
            recorder.release()
        }
        return null
    }

    /** Removes the spaces a sub-word tokenizer inserts between Chinese characters. */
    private fun cleanText(raw: String): String =
        raw.replace(Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun setState(value: State) {
        if (state == value) return
        state = value
        mainHandler.post { listener?.onStateChanged(value) }
    }

    private fun postCaption(text: String) {
        mainHandler.post { listener?.onCaption(text) }
    }

    private fun postTranscript(text: String) {
        mainHandler.post { listener?.onTranscript(text) }
    }

    private fun postAmplitude(level: Float) {
        mainHandler.post { listener?.onAmplitude(level) }
    }
}
