// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.k2fsa.sherpa.onnx.*
import com.osfans.trime.ime.core.TrimeInputMethodService
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance, 100% on-device offline ASR manager using Sherpa-ONNX.
 * Zero network requests, 100% private, physical-layer isolated.
 */
object OfflineVoiceManager {

    private const val SAMPLE_RATE = 16000
    private var recognizer: OnlineRecognizer? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    private fun initRecognizer(context: Context): OnlineRecognizer? {
        if (recognizer != null) return recognizer
        try {
            val modelConfig = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig().apply {
                    encoder = "asr_model/encoder-epoch-99-avg-1.int8.onnx"
                    decoder = "asr_model/decoder-epoch-99-avg-1.int8.onnx"
                    joiner = "asr_model/joiner-epoch-99-avg-1.int8.onnx"
                }
                tokens = "asr_model/tokens.txt"
                bpeVocab = "asr_model/bpe.model"
                modelType = "zipformer"
                modelingUnit = "cjkchar+bpe"
                numThreads = 2
                debug = false
            }
            val config = OnlineRecognizerConfig().apply {
                this.modelConfig = modelConfig
                enableEndpoint = true
            }
            recognizer = OnlineRecognizer(context.assets, config)
            Timber.i("Sherpa-ONNX offline recognizer initialized successfully.")
        } catch (e: Throwable) {
            Timber.e(e, "Failed to initialize Sherpa-ONNX recognizer")
        }
        return recognizer
    }

    fun toggleListening(service: TrimeInputMethodService) {
        if (isRecording.get()) {
            stopListening(service)
        } else {
            startListeningWithPermission(service)
        }
    }

    private fun startListeningWithPermission(service: TrimeInputMethodService) {
        if (XXPermissions.isGranted(service, Manifest.permission.RECORD_AUDIO)) {
            startListening(service)
        } else {
            XXPermissions.with(service)
                .permission(Manifest.permission.RECORD_AUDIO)
                .request(object : OnPermissionCallback {
                    override fun onGranted(permissions: MutableList<String>, allGranted: Boolean) {
                        if (allGranted) {
                            startListening(service)
                        } else {
                            Toast.makeText(service, "请授予录音权限以使用离线语音输入", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onDenied(permissions: MutableList<String>, doNotAskAgain: Boolean) {
                        Toast.makeText(service, "未授予录音权限，无法使用语音输入", Toast.LENGTH_SHORT).show()
                    }
                })
        }
    }

    @SuppressLint("MissingPermission")
    private fun startListening(service: TrimeInputMethodService) {
        if (isRecording.getAndSet(true)) return

        val toast = Toast.makeText(service, "🎙️ 正在聆听 (完全离线·端侧识别)... 再次轻按结束", Toast.LENGTH_SHORT)
        toast.show()

        recordingJob = CoroutineScope(Dispatchers.Default).launch {
            val rec = initRecognizer(service)
            if (rec == null) {
                mainHandler.post {
                    Toast.makeText(service, "离线模型加载失败，请检查模型资源", Toast.LENGTH_SHORT).show()
                }
                isRecording.set(false)
                return@launch
            }

            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLE_RATE * 2)

            var audioRecord: AudioRecord? = null
            var stream: OnlineStream? = null

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Timber.e("AudioRecord initialization failed")
                    isRecording.set(false)
                    return@launch
                }

                stream = rec.createStream()
                audioRecord.startRecording()
                val buffer = ShortArray(1024)
                val floatBuffer = FloatArray(1024)
                var lastText = ""

                while (isRecording.get() && isActive) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        for (i in 0 until read) {
                            floatBuffer[i] = buffer[i] / 32768.0f
                        }
                        stream.acceptWaveform(floatBuffer.copyOfRange(0, read), SAMPLE_RATE)
                        while (rec.isReady(stream)) {
                            rec.decode(stream)
                        }
                        val text = rec.getResult(stream).text.trim()
                        if (text.isNotEmpty() && text != lastText) {
                            lastText = text
                        }
                    }
                }

                // Final decode
                stream.inputFinished()
                while (rec.isReady(stream)) {
                    rec.decode(stream)
                }
                val finalText = rec.getResult(stream).text.trim()
                if (finalText.isNotEmpty()) {
                    mainHandler.post {
                        service.commitText(finalText)
                    }
                }
            } catch (e: Throwable) {
                Timber.e(e, "Error during offline audio recognition")
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
                try {
                    stream?.release()
                } catch (_: Exception) {}
                isRecording.set(false)
            }
        }
    }

    fun stopListening(service: TrimeInputMethodService) {
        if (!isRecording.getAndSet(false)) return
        recordingJob?.cancel()
        recordingJob = null
        Toast.makeText(service, "语音已停止，正在上屏", Toast.LENGTH_SHORT).show()
    }
}
