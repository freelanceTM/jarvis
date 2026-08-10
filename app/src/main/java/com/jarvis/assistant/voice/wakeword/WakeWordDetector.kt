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
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

sealed interface WakeWordEvent {
    data object WakeWordDetected : WakeWordEvent       // "Джарвис"
    data object InterruptDetected : WakeWordEvent      // "Джарвис, стоп" / "Стоп"
    data class VoiceLevelChanged(val rms: Float) : WakeWordEvent
}

interface WakeWordDetector {
    val events: SharedFlow<WakeWordEvent>
    fun startListening(isInterruptModeOnly: Boolean = false)
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

    private var sensitivity: Float = 0.7f // 0.1 .. 1.0
    private var isInterruptOnlyMode: Boolean = false
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
    override fun startListening(isInterruptModeOnly: Boolean) {
        if (isRecording && this.isInterruptOnlyMode == isInterruptModeOnly) return

        stopListening()
        this.isInterruptOnlyMode = isInterruptModeOnly

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
                var voiceEnergyCount = 0
                val energyThreshold = (1200 * (1.1f - sensitivity)).toInt()

                while (isActive && isRecording) {
                    val readSamples = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSamples <= 0) continue

                    // 1. Calculate RMS Energy (Voice Activity Detection - VAD)
                    var sum = 0.0
                    for (i in 0 until readSamples) {
                        sum += buffer[i] * buffer[i]
                    }
                    val rms = sqrt(sum / readSamples).toFloat()
                    _events.tryEmit(WakeWordEvent.VoiceLevelChanged(rms))

                    // 2. High-speed Acoustic VAD trigger
                    if (rms > energyThreshold) {
                        voiceEnergyCount++
                        if (voiceEnergyCount >= 3) {
                            // Voice burst detected
                            if (isInterruptOnlyMode) {
                                _events.emit(WakeWordEvent.InterruptDetected)
                            } else {
                                _events.emit(WakeWordEvent.WakeWordDetected)
                            }
                            voiceEnergyCount = 0
                        }
                    } else {
                        voiceEnergyCount = (voiceEnergyCount - 1).coerceAtLeast(0)
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
