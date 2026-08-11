package com.jarvis.assistant.voice.wakeword

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

sealed interface WakeWordEvent {
    data object VoiceActivityDetected : WakeWordEvent // Голос обнаружен для верификации
    data class VoiceLevelChanged(val rms: Float) : WakeWordEvent
}

interface WakeWordDetector {
    val events: SharedFlow<WakeWordEvent>
    fun startListening()
    fun stopListening()
    fun isRunning(): Boolean
    fun setSensitivity(sensitivity: Float)
}

@Singleton
class NativeWakeWordDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : WakeWordDetector {

    private val _events = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<WakeWordEvent> = _events.asSharedFlow()

    private var audioRecord: AudioRecord? = null
    private var listeningJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private var sensitivity: Float = 0.65f
    private var isRecording = false

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)

    override fun setSensitivity(sensitivity: Float) {
        this.sensitivity = sensitivity.coerceIn(0.1f, 1.0f)
    }

    override fun isRunning(): Boolean = isRecording

    @SuppressLint("MissingPermission")
    override fun startListening() {
        if (isRecording) return
        stopListening()

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            listeningJob = scope.launch {
                val buffer = ShortArray(bufferSize / 2)
                var consecutiveVoiceFrames = 0
                // Повышенный порог шума для предотвращения ложных срабатываний
                val energyThreshold = (1800 * (1.15f - sensitivity)).toInt()

                while (isActive && isRecording) {
                    val readSamples = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSamples <= 0) continue

                    var sum = 0.0
                    for (i in 0 until readSamples) {
                        sum += buffer[i] * buffer[i]
                    }
                    val rms = sqrt(sum / readSamples).toFloat()
                    _events.tryEmit(WakeWordEvent.VoiceLevelChanged(rms))

                    // Фильтр устойчивой речи: нужно несколько непрерывных кадров уверенного голоса
                    if (rms > energyThreshold) {
                        consecutiveVoiceFrames++
                        if (consecutiveVoiceFrames >= 4) {
                            _events.emit(WakeWordEvent.VoiceActivityDetected)
                            consecutiveVoiceFrames = 0
                        }
                    } else {
                        consecutiveVoiceFrames = (consecutiveVoiceFrames - 1).coerceAtLeast(0)
                    }
                }
            }
        } catch (_: Exception) {
            stopListening()
        }
    }

    override fun stopListening() {
        isRecording = false
        listeningJob?.cancel()
        listeningJob = null
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (_: Exception) { }
        audioRecord = null
    }
}
