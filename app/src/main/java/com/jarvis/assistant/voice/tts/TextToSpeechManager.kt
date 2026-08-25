package com.jarvis.assistant.voice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TtsState {
    data object Idle : TtsState
    data object Initializing : TtsState
    data object Ready : TtsState
    data object Speaking : TtsState
    data object Done : TtsState
    data object Error : TtsState
}

/**
 * Text-to-Speech Manager v2.0
 * 
 * Управляет синтезом речи с поддержкой:
 * - Настройки скорости и высоты голоса
 * - Очереди utterances
 * - Состояния воспроизведения через StateFlow
 * - Корректного shutdown при уничтожении
 */
@Singleton
class TextToSpeechManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TtsManager"
        private const val UTTERANCE_ID_PREFIX = "jarvis_tts_"
        private const val INIT_TIMEOUT_MS = 4000L
    }

    private val disposed = java.util.concurrent.atomic.AtomicBoolean(false)

    // CR-13/CR-24: именованный SupervisorJob + CoroutineExceptionHandler,
    // чтобы можно было безопасно отменить scope на shutdown().
    private val ttsJob = SupervisorJob()
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "uncaught exception in tts scope", t)
    }
    private val scope = CoroutineScope(
        Dispatchers.Main.immediate + ttsJob + exceptionHandler
    )

    private var tts: TextToSpeech? = null
    private var utteranceCounter = 0L
    private var initializationGeneration = 0L

    /**
     * CR-09: буфер для фразы, которая поступила до готовности движка. Играется
     * ровно один раз сразу после успешной инициализации.
     *
     * Структура: (text, speechRate, pitch, queueMode).
     */
    @Volatile
    private var pendingUtterance: PendingUtterance? = null

    /** CR-09: защита от гонок между concurrent speak()/init(). */
    private val speakMutex = Mutex()

    private val _ttsState = MutableStateFlow<TtsState>(TtsState.Idle)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private data class PendingUtterance(
        val text: String,
        val speechRate: Float,
        val pitch: Float,
        val queueMode: Int
    )

    init {
        initializeTts()
    }

    private fun initializeTts() {
        if (_ttsState.value == TtsState.Initializing) return
        _ttsState.value = TtsState.Initializing
        val generation = ++initializationGeneration
        var candidate: TextToSpeech? = null

        // CR-09: таймаут инициализации — не молчим бесконечно.
        scope.launch {
            delay(INIT_TIMEOUT_MS)
            if (generation == initializationGeneration && _ttsState.value == TtsState.Initializing) {
                Log.e(TAG, "TTS init timed out after ${INIT_TIMEOUT_MS}ms")
                _isInitialized.value = false
                _ttsState.value = TtsState.Error
                // Сбрасываем pending, чтобы он не «завис» навечно.
                pendingUtterance = null
                runCatching { candidate?.shutdown() }
                tts = null
            }
        }

        // Важно: присваиваем tts = candidate ДО того, как init-callback может
        // сработать (на некоторых версиях Android callback вызывается прямо из
        // конструктора TextToSpeech синхронно). Иначе configureTts()/drainPendingUtterance()
        // увидят tts == null и фраза будет потеряна / NPE.
        candidate = TextToSpeech(context) { status ->
            if (generation != initializationGeneration) {
                runCatching { candidate?.shutdown() }
                return@TextToSpeech
            }
            if (status == TextToSpeech.SUCCESS) {
                configureTts()
                _isInitialized.value = true
                _ttsState.value = TtsState.Ready
                Log.d(TAG, "TTS initialized successfully")
                // CR-09: если во время init пришла фраза — озвучиваем ровно один раз.
                scope.launch {
                    speakMutex.withLock { drainPendingUtterance() }
                }
            } else {
                _isInitialized.value = false
                _ttsState.value = TtsState.Error
                Log.e(TAG, "TTS initialization failed with status: $status")
                pendingUtterance = null
            }
        }
        tts = candidate
    }

    /** CR-09: разыгрывает ровно один pending-utterance после успешного init. */
    private fun drainPendingUtterance() {
        val pending = pendingUtterance ?: return
        pendingUtterance = null
        Log.d(TAG, "Draining pending utterance after init (chars=${pending.text.length})")
        speakInternal(
            text = pending.text,
            speechRate = pending.speechRate,
            pitch = pending.pitch,
            queueMode = pending.queueMode
        )
    }

    private fun configureTts() {
        tts?.apply {
            // Настройка русского языка
            val russianLocale = Locale("ru", "RU")
            val result = setLanguage(russianLocale)
            
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback на английский
                setLanguage(Locale.US)
                Log.w(TAG, "Russian TTS not available, using English")
            }
            
            // Listener для отслеживания состояния
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _ttsState.value = TtsState.Speaking
                    Log.d(TAG, "TTS started: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    _ttsState.value = TtsState.Done
                    Log.d(TAG, "TTS done: $utteranceId")
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _ttsState.value = TtsState.Error
                    Log.e(TAG, "TTS error: $utteranceId")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _ttsState.value = TtsState.Error
                    Log.e(TAG, "TTS error: $utteranceId, code: $errorCode")
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    _ttsState.value = TtsState.Ready
                    Log.d(TAG, "TTS stopped: $utteranceId, interrupted: $interrupted")
                }
            })
        }
    }

    /**
     * Озвучивает текст с заданными параметрами.
     *
     * CR-09: если движок ещё не готов, фраза сохраняется в pendingUtterance,
     * запускается (ре)инициализация, и после успешного init воспроизводится
     * РОВНО ОДИН РАЗ. Вместо silent-drop возвращаем TtsState.Error по таймауту.
     * Гонки между concurrent speak() исключены speakMutex.
     *
     * @param text Текст для озвучивания
     * @param speechRate Скорость речи (0.5 - 2.0, по умолчанию 1.0)
     * @param pitch Высота голоса (0.5 - 2.0, по умолчанию 1.0)
     * @param queueMode Режим очереди (FLUSH - прервать текущее, ADD - добавить в очередь)
     */
    fun speak(
        text: String,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH
    ) {
        if (disposed.get()) return
        if (text.isBlank()) {
            Log.w(TAG, "Empty text, skipping TTS")
            return
        }

        scope.launch {
            speakMutex.withLock {
                val engine = tts
                if (engine != null && _isInitialized.value && _ttsState.value !is TtsState.Initializing) {
                    speakInternal(text, speechRate, pitch, queueMode)
                    return@withLock
                }
                // Движок не готов — сохраняем текст (последний вызов «выигрывает»),
                // (ре)стартуем init, после чего drainPendingUtterance() произнесёт его.
                Log.w(TAG, "TTS not ready — buffering utterance and (re)initializing")
                pendingUtterance = PendingUtterance(
                    text = text,
                    speechRate = speechRate.coerceIn(0.5f, 2.0f),
                    pitch = pitch.coerceIn(0.5f, 2.0f),
                    queueMode = queueMode
                )
                if (_ttsState.value == TtsState.Error || _ttsState.value == TtsState.Idle || tts == null) {
                    initializeTts()
                }
            }
        }
    }

    /** Внутренний вызов speak, вызывается ТОЛЬКО когда движок гарантированно готов. */
    private fun speakInternal(
        text: String,
        speechRate: Float,
        pitch: Float,
        queueMode: Int
    ) {
        val engine = tts
        if (engine == null) {
            Log.e(TAG, "speakInternal: engine is null unexpectedly")
            _ttsState.value = TtsState.Error
            return
        }
        engine.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))

        val utteranceId = "${UTTERANCE_ID_PREFIX}${++utteranceCounter}"
        val result = engine.speak(text, queueMode, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak() returned error")
            _ttsState.value = TtsState.Error
        }
    }

    /**
     * Добавляет текст в очередь озвучивания (не прерывает текущее)
     */
    fun speakQueued(text: String, speechRate: Float = 1.0f, pitch: Float = 1.0f) {
        speak(text, speechRate, pitch, TextToSpeech.QUEUE_ADD)
    }

    /**
     * Останавливает текущее воспроизведение.
     * CR-09: также сбрасывает pendingUtterance, чтобы не всплыл отменённый текст.
     */
    fun stop() {
        if (disposed.get()) return
        scope.launch {
            speakMutex.withLock {
                pendingUtterance = null
                tts?.stop()
                if (_isInitialized.value) {
                    _ttsState.value = TtsState.Ready
                }
            }
        }
    }

    /**
     * Проверяет, идёт ли сейчас воспроизведение
     */
    fun isSpeaking(): Boolean {
        return if (disposed.get()) false else tts?.isSpeaking == true
    }

    /**
     * Полное освобождение ресурсов TTS.
     * ОБЯЗАТЕЛЬНО вызывать при уничтожении сервиса/активности!
     * Идемпотентен.
     */
    fun shutdown() {
        if (!disposed.compareAndSet(false, true)) return
        Log.d(TAG, "Shutting down TTS")
        initializationGeneration++
        pendingUtterance = null
        ttsJob.cancel()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        } finally {
            tts = null
            _isInitialized.value = false
            _ttsState.value = TtsState.Idle
        }
    }

    /**
     * Перезапуск TTS (например, после ошибки)
     */
    fun restart() {
        shutdown()
        initializeTts()
    }

    /**
     * Устанавливает язык TTS
     */
    fun setLanguage(locale: Locale): Boolean {
        val result = tts?.setLanguage(locale)
        return result != TextToSpeech.LANG_MISSING_DATA && 
               result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /**
     * Возвращает список доступных голосов
     */
    fun getAvailableVoices(): List<android.speech.tts.Voice> {
        return tts?.voices?.toList() ?: emptyList()
    }
}
