package com.jarvis.assistant.voice.wakeword

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
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
    fun destroy()
}

/**
 * Acoustic Speech Activity & Formant Filter (Front-End для Wake Word).
 *
 * CR-14: жизненный цикл engine:
 *  - Собственный CoroutineScope с SupervisorJob + CoroutineExceptionHandler
 *    (никаких падений scope из-за одной ошибки в аудио-лупе).
 *  - destroy() идемпотентен через AtomicBoolean; отменяет scope, workerJob,
 *    cooldown jobs и останавливает AudioRecord.
 *  - workerJob после cooldown-delay проверяет, что engine всё ещё жив
 *    и не уничтожен — в противном случае не стартует новое прослушивание.
 *  - stopListening() синхронизирован и безопасно вызываться из любого потока
 *    и из любого состояния (частично инициализированный / уже остановленный).
 */
@Singleton
class AlisaStyleWakeWordEngine @Inject constructor() : WakeWordDetector {

    private val TAG = "AlisaWakeWord"

    private val _events = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<WakeWordEvent> = _events.asSharedFlow()

    private val disposed = AtomicBoolean(false)

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var workerJob: Job? = null

    @Volatile
    private var cooldownJob: Job? = null

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) throw throwable
        Log.e(TAG, "uncaught exception in wake word engine", throwable)
        // Важно: ошибка в аудио-лупе НЕ должна оставлять AudioRecord висеть.
        try { stopListeningInternal() } catch (_: Throwable) {}
    }
    private val engineJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + engineJob + exceptionHandler)

    @Volatile
    private var isRecording = false

    @Volatile
    private var currentSensitivity = 0.65f

    @Volatile
    private var effectiveRmsThreshold = 850f

    @Volatile
    private var lastTriggerTimestamp = 0L
    private val cooldownMs = 2000L

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val frameSizeSamples = 512
    private val minBufferSize = AudioRecord.getMinBufferSize(
        sampleRate, channelConfig, audioFormat
    ).coerceAtLeast(4096)

    init {
        updateThreshold()
    }

    override fun setSensitivity(sensitivity: Float) {
        currentSensitivity = sensitivity.coerceIn(0.1f, 1.0f)
        updateThreshold()
    }

    private fun updateThreshold() {
        effectiveRmsThreshold = 1600f - (currentSensitivity * 1050f)
    }

    override fun isRunning(): Boolean = isRecording

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun startListening() {
        if (disposed.get()) return
        if (isRecording) return
        // Сбросить предыдущий worker (если остался в некорректном состоянии).
        stopListeningInternal()

        val now = System.currentTimeMillis()
        if (now - lastTriggerTimestamp < cooldownMs) {
            // CR-14: cooldown-launch запоминается в отдельную job, чтобы
            // destroy/stop мог его отменить.
            cooldownJob?.cancel()
            cooldownJob = scope.launch {
                val waitMs = cooldownMs - (now - lastTriggerTimestamp)
                delay(waitMs)
                // Повторный старт только если нас не утилизировали за время
                // ожидания и не стартовали другой listening.
                if (!disposed.get() && !isRecording) {
                    // синхронный повторный старт (уже в @Synchronized через
                    // вызов startListening нельзя напрямую — рекурсивный
                    // monitor; поэтому стартуем внутренне).
                    startListeningInternal()
                }
            }
            return
        }

        startListeningInternal()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startListeningInternal() {
        if (disposed.get() || isRecording) return
        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return
            }
            audioRecord = record
            record.startRecording()
            isRecording = true

            workerJob = scope.launch {
                val pcmBuffer = ShortArray(frameSizeSamples)
                var speechFrames = 0
                try {
                    while (isActive && isRecording && !disposed.get()) {
                        val currentRec = audioRecord ?: break
                        val read = currentRec.read(pcmBuffer, 0, frameSizeSamples)
                        if (read <= 0 || !isRecording) break

                        var sumSquares = 0.0
                        var zeroCrossings = 0
                        for (i in 0 until read) {
                            val sample = pcmBuffer[i].toDouble()
                            sumSquares += sample * sample
                            if (i > 0) {
                                val prev = pcmBuffer[i - 1]
                                val curr = pcmBuffer[i]
                                if ((prev > 0 && curr <= 0) || (prev < 0 && curr >= 0)) {
                                    zeroCrossings++
                                }
                            }
                        }
                        val rms = sqrt(sumSquares / read).toFloat()
                        val zcr = zeroCrossings.toFloat() / read.toFloat()

                        _events.tryEmit(WakeWordEvent.VoiceLevelChanged(rms))

                        val isSpeechFormant = zcr in 0.02f..0.48f
                        val isAboveThreshold = rms > effectiveRmsThreshold

                        if (isAboveThreshold && isSpeechFormant) {
                            speechFrames++
                            if (speechFrames >= 3) {
                                speechFrames = 0
                                lastTriggerTimestamp = System.currentTimeMillis()
                                // Синхронно отпускаем микрофон и сигнализируем.
                                stopListeningInternal()
                                _events.emit(WakeWordEvent.VoiceActivityDetected)
                                break
                            }
                        } else {
                            speechFrames = (speechFrames - 1).coerceAtLeast(0)
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.e(TAG, "audio loop failed", t)
                } finally {
                    stopListeningInternal()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startListening: не удалось запустить AudioRecord", t)
            stopListeningInternal()
        }
    }

    @Synchronized
    override fun stopListening() {
        cooldownJob?.cancel()
        cooldownJob = null
        workerJob?.cancel()
        workerJob = null
        stopListeningInternal()
    }

    /**
     * Внутренняя, повторно-входимая очистка AudioRecord. Не отменяет jobs
     * (это делает stopListening()), поэтому может безопасно вызываться из
     * callback'ов/exception-handler'ов.
     */
    @Synchronized
    private fun stopListeningInternal() {
        isRecording = false
        val record = audioRecord
        audioRecord = null
        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "stopListening: не удалось остановить AudioRecord", e)
            } finally {
                try { record.release() } catch (_: Throwable) {}
            }
        }
    }

    @Synchronized
    override fun destroy() {
        if (!disposed.compareAndSet(false, true)) return
        Log.d(TAG, "destroy: releasing wake word engine")
        cooldownJob?.cancel()
        cooldownJob = null
        workerJob?.cancel()
        workerJob = null
        engineJob.cancel()
        stopListeningInternal()
    }
}
