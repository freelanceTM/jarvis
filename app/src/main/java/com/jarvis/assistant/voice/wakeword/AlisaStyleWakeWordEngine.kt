package com.jarvis.assistant.voice.wakeword

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

sealed interface WakeWordEvent {
    data object VoiceActivityDetected : WakeWordEvent
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
class AlisaStyleWakeWordEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : WakeWordDetector {

    private val _events = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<WakeWordEvent> = _events.asSharedFlow()

    private var audioRecord: AudioRecord? = null
    private var workerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    @Volatile
    private var isRecording = false

    private var lastTriggerTimestamp = 0L
    private val cooldownMs = 2000L // 2 секунды антиспам cooldown после срабатывания

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val frameSizeSamples = 512 // 32 мс
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)

    override fun setSensitivity(sensitivity: Float) { }

    override fun isRunning(): Boolean = isRecording

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun startListening() {
        if (isRecording) return
        stopListening()

        val now = System.currentTimeMillis()
        if (now - lastTriggerTimestamp < cooldownMs) {
            scope.launch {
                delay(cooldownMs - (now - lastTriggerTimestamp))
                if (!isRecording) {
                    startListening()
                }
            }
            return
        }

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
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            workerJob = scope.launch {
                val pcmBuffer = ShortArray(frameSizeSamples)
                var voiceStreak = 0

                while (isActive && isRecording) {
                    val read = audioRecord?.read(pcmBuffer, 0, frameSizeSamples) ?: 0
                    if (read <= 0 || !isRecording) break

                    var sum = 0.0
                    for (i in 0 until read) {
                        sum += pcmBuffer[i] * pcmBuffer[i]
                    }
                    val rms = sqrt(sum / read).toFloat()

                    // Порог уверенного человеческого голоса вблизи микрофона
                    if (rms > 850f) {
                        voiceStreak++
                        if (voiceStreak >= 2) {
                            voiceStreak = 0
                            lastTriggerTimestamp = System.currentTimeMillis()
                            // Синхронно освобождаем AudioRecord перед активацией SpeechRecognizer
                            stopListening()
                            _events.emit(WakeWordEvent.VoiceActivityDetected)
                            break
                        }
                    } else {
                        voiceStreak = 0
                    }
                }
            }
        } catch (_: Exception) {
            stopListening()
        }
    }

    @Synchronized
    override fun stopListening() {
        isRecording = false
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
}
