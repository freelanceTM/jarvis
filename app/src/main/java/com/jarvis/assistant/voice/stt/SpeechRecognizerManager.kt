package com.jarvis.assistant.voice.stt

import com.jarvis.assistant.R
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SpeechRecognitionEvent {
    data object Idle : SpeechRecognitionEvent
    data object ReadyForSpeech : SpeechRecognitionEvent
    data class PartialResult(val partialText: String) : SpeechRecognitionEvent
    data class FinalResult(val recognizedText: String) : SpeechRecognitionEvent
    data class RecognitionError(val errorMessage: String, val errorCode: Int) : SpeechRecognitionEvent
}

/**
 * SpeechRecognizerManager v4.0 (CR-08: generation/session для защиты от stale callbacks).
 *
 * Вместо пересоздания SpeechRecognizer на каждый startListening мы держим один
 * экземпляр (recreating создаёт lifecycle-race, когда callback старой сессии
 * прилетает ПОСЛЕ старта новой).
 *
 * Для защиты от stale callbacks введён [sessionId] — поколение (generation):
 * каждый вызов startListening инкрементирует поколение; любой callback проверяет,
 * что он принадлежит текущему поколению — если нет, молча игнорируется.
 *
 * restart (непрерывный режим переводчика) происходит ровно из одного места —
 * [scheduleContinuousRestart], и тоже переиспользует тот же recognizer.
 */
@Singleton
class SpeechRecognizerManager @Inject constructor(
    @ApplicationContext private val context: Context
) : RecognitionListener {

    companion object {
        private const val TAG = "SpeechRecognizerManager"
    }

    private val disposed = AtomicBoolean(false)

    /**
     * CR-08: поколение сессии. Инкрементируется при каждом startListening и при
     * destroy; все callbacks сверяются с текущим значением.
     */
    private val generation = AtomicInteger(0)

    private var recognizer: SpeechRecognizer? = null
    private val _speechState = MutableStateFlow<SpeechRecognitionEvent>(SpeechRecognitionEvent.Idle)
    val speechState: StateFlow<SpeechRecognitionEvent> = _speechState.asStateFlow()

    @Volatile
    private var isListening = false

    @Volatile
    private var isContinuousMode = false
    private var currentLanguageTag = "ru-RU"

    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        if (t is CancellationException) throw t
        Log.e(TAG, "uncaught exception in stt scope", t)
    }
    private val sttJob = SupervisorJob()
    private val scope = CoroutineScope(
        Dispatchers.Main.immediate + sttJob + exceptionHandler
    )
    private var restartJob: Job? = null
    private var startMutex = Any() // грубая синхронизация start/stop.

    /** Убеждаемся, что recognizer существует и слушатель назначен. */
    private fun ensureRecognizer(): SpeechRecognizer? {
        if (disposed.get()) return null
        recognizer?.let { return it }
        return try {
            SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(this)
                recognizer = it
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ensureRecognizer: не удалось создать", t)
            null
        }
    }

    fun startListening(languageTag: String = "ru-RU", continuous: Boolean = false) {
        synchronized(startMutex) {
            if (disposed.get()) return

            // Отменяем запланированный restart, чтобы старт был единственным
            // источником truth.
            restartJob?.cancel()
            restartJob = null

            // Если уже слушаем — останавливаем текущее распознавание через
            // recognizer, НЕ уничтожая его и инкрементируя поколение, чтобы
            // stale callbacks от него были проигнорированы.
            cancelCurrentRecognitionLocked()

            isContinuousMode = continuous
            currentLanguageTag = languageTag

            val rec = ensureRecognizer()
            if (rec == null) {
                isListening = false
                _speechState.value = SpeechRecognitionEvent.RecognitionError(
                    context.getString(R.string.ne_udalos_otkryt_mikrofon), -1
                )
                return
            }

            val session = generation.incrementAndGet()

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                if (languageTag != "auto") {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                }
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            isListening = true
            try {
                rec.startListening(intent)
                Log.d(TAG, "startListening session=$session continuous=$continuous lang=$languageTag")
            } catch (t: Throwable) {
                isListening = false
                Log.e(TAG, "startListening: recognizer rejected start", t)
                _speechState.value = SpeechRecognitionEvent.RecognitionError(
                    context.getString(R.string.ne_udalos_otkryt_mikrofon), -1
                )
            }
        }
    }

    /**
     * Полный останов: завершает Continuous режим, отменяет restart-job,
     * останавливает текущее распознавание (но не уничтожает recognizer,
     * он может быть переиспользован следующим startListening).
     */
    fun stopListening() {
        synchronized(startMutex) {
            isContinuousMode = false
            restartJob?.cancel()
            restartJob = null
            cancelCurrentRecognitionLocked()
        }
    }

    /** Уничтожение менеджера: закрывает recognizer, отменяет scope. Идемпотентно. */
    fun destroy() {
        if (!disposed.compareAndSet(false, true)) return
        Log.d(TAG, "destroy: releasing speech recognizer")
        synchronized(startMutex) {
            restartJob?.cancel()
            restartJob = null
            isContinuousMode = false
            cancelCurrentRecognitionLocked()
            val rec = recognizer
            recognizer = null
            if (rec != null) {
                runCatching { rec.destroy() }
                    .onFailure { Log.w(TAG, "destroy: recognizer.destroy() failed", it) }
            }
        }
        sttJob.cancel()
        _speechState.value = SpeechRecognitionEvent.Idle
    }

    /**
     * Останавливает текущее распознавание, не трогая recognizer. Вызывать
     * под [startMutex].
     */
    private fun cancelCurrentRecognitionLocked() {
        isListening = false
        // Инкремент поколения: все долетевшие callbacks от текущей сессии
        // будут молча проигнорированы.
        generation.incrementAndGet()
        val rec = recognizer ?: return
        try {
            // stopListening может бросить если recognizer не слушает.
            runCatching { rec.stopListening() }
            runCatching { rec.cancel() }
        } catch (t: Throwable) {
            Log.w(TAG, "cancelCurrentRecognition: сбой остановки", t)
        }
    }

    // --------------------------------------------------------- RecognitionListener

    private fun isCurrentSession(session: Int): Boolean {
        // Колбэчи могут прийти из старой сессии после того, как startListening
        // или destroy уже переключили поколение.
        return !disposed.get() && session == generation.get()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        val session = generation.get()
        if (!isCurrentSession(session)) return
        _speechState.value = SpeechRecognitionEvent.ReadyForSpeech
    }

    override fun onBeginningOfSpeech() { /* no-op; guarded via onReady */ }

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() { /* решения принимаем в onResults / onError */ }

    override fun onError(error: Int) {
        val session = generation.get()
        if (!isCurrentSession(session)) {
            Log.d(TAG, "onError discarded (stale session=$session current=${generation.get()} error=$error)")
            return
        }
        Log.w(TAG, "Speech recognition error code: $error")

        isListening = false

        if (isContinuousMode && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
            // Непрерывный режим: пауза собеседника — не конец сессии.
            scheduleContinuousRestart(50)
            return
        }

        val friendlyMessage = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> context.getString(R.string.ya_ne_rasslyshal_zapros)
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(R.string.vy_nichego_ne_skazali)
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> context.getString(R.string.net_podklyucheniya_k_internetu)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(R.string.net_razresheniya_na_mikrofon)
            else -> context.getString(R.string.oshibka_raspoznavaniya_rechi, error)
        }
        _speechState.value = SpeechRecognitionEvent.RecognitionError(friendlyMessage, error)
    }

    override fun onResults(results: Bundle?) {
        val session = generation.get()
        if (!isCurrentSession(session)) {
            Log.d(TAG, "onResults discarded (stale session=$session current=${generation.get()})")
            return
        }

        isListening = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val best = matches?.firstOrNull()?.trim().orEmpty()

        if (best.isNotEmpty()) {
            _speechState.value = SpeechRecognitionEvent.FinalResult(best)
        }

        if (isContinuousMode) {
            scheduleContinuousRestart(30)
        }
    }

    private fun scheduleContinuousRestart(delayMs: Long) {
        if (disposed.get()) return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMs)
            if (!isContinuousMode || disposed.get()) return@launch
            restartJob = null
            // Синхронный перезапуск; не трогаем сам recognizer — только
            // startListening на текущем экземпляре.
            startListening(currentLanguageTag, continuous = true)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val session = generation.get()
        if (!isCurrentSession(session)) return
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val partial = matches?.firstOrNull()?.trim().orEmpty()
        if (partial.isNotEmpty()) {
            _speechState.value = SpeechRecognitionEvent.PartialResult(partial)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
