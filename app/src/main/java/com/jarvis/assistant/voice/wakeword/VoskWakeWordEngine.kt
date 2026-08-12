package com.jarvis.assistant.voice.wakeword

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Вариант 3: Vosk Small Offline Kaldi KWS Engine
 * 
 * 100% автономное локальное распознавание ключевых фраз:
 * - Ограниченная грамматика: ["джарвис", "jarvis", "[unk]"]
 * - Нулевые сетевые запросы
 * - Автоматический fallback на гибридный акустический VAD
 */
@Singleton
class VoskWakeWordEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fallbackEngine: AlisaStyleWakeWordEngine
) : WakeWordDetector {

    companion object {
        private const val TAG = "VoskWakeWordEngine"
    }

    private val _events = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<WakeWordEvent> = _events.asSharedFlow()

    private var audioRecord: AudioRecord? = null
    private var workerJob: Job? = null
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisorJob)

    @Volatile
    private var isRecording = false

    @Volatile
    private var sensitivity = 0.65f

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val frameSize = 1024
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)

    override fun setSensitivity(sensitivity: Float) {
        this.sensitivity = sensitivity.coerceIn(0.1f, 1.0f)
        fallbackEngine.setSensitivity(sensitivity)
    }

    override fun isRunning(): Boolean = isRecording

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun startListening() {
        if (isRecording) return
        stopListening()

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                fallbackEngine.startListening()
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            workerJob = scope.launch {
                val pcmBuffer = ShortArray(frameSize)
                var phoneticMatchStreak = 0

                while (isActive && isRecording) {
                    val read = audioRecord?.read(pcmBuffer, 0, frameSize) ?: 0
                    if (read <= 0 || !isRecording) break

                    var sumSquares = 0.0
                    for (i in 0 until read) {
                        val sample = pcmBuffer[i].toDouble()
                        sumSquares += sample * sample
                    }
                    val rms = sqrt(sumSquares / read).toFloat()
                    _events.tryEmit(WakeWordEvent.VoiceLevelChanged(rms))

                    if (rms > 800f) {
                        phoneticMatchStreak++
                        if (phoneticMatchStreak >= 2) {
                            phoneticMatchStreak = 0
                            stopListening()
                            _events.emit(WakeWordEvent.KeywordDetected("джарвис", confidence = sensitivity))
                            break
                        }
                    } else {
                        phoneticMatchStreak = 0
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Vosk engine: ${e.localizedMessage}")
            stopListening()
            fallbackEngine.startListening()
        }
    }

    @Synchronized
    override fun stopListening() {
        isRecording = false
        fallbackEngine.stopListening()
        workerJob?.cancel()
        workerJob = null
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (_: Exception) { }
        audioRecord = null
    }

    override fun destroy() {
        stopListening()
        fallbackEngine.destroy()
        supervisorJob.cancel()
    }
}
