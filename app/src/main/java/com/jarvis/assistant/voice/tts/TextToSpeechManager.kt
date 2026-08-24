package com.jarvis.assistant.voice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    }

    private var tts: TextToSpeech? = null
    private var utteranceCounter = 0L
    private var initializationGeneration = 0L
    
    private val _ttsState = MutableStateFlow<TtsState>(TtsState.Idle)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()
    
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    init {
        initializeTts()
    }

    private fun initializeTts() {
        if (_ttsState.value == TtsState.Initializing) return
        _ttsState.value = TtsState.Initializing
        val generation = ++initializationGeneration
        var candidate: TextToSpeech? = null
        candidate = TextToSpeech(context) { status ->
            if (generation != initializationGeneration) {
                // shutdown/restart happened before the platform callback arrived.
                runCatching { candidate?.shutdown() }
                return@TextToSpeech
            }
            if (status == TextToSpeech.SUCCESS) {
                configureTts()
                _isInitialized.value = true
                _ttsState.value = TtsState.Ready
                Log.d(TAG, "TTS initialized successfully")
            } else {
                _isInitialized.value = false
                _ttsState.value = TtsState.Error
                Log.e(TAG, "TTS initialization failed with status: $status")
            }
        }
        tts = candidate
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
     * Озвучивает текст с заданными параметрами
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
        if (text.isBlank()) {
            Log.w(TAG, "Empty text, skipping TTS")
            return
        }
        
        val engine = tts
        if (engine == null || !_isInitialized.value) {
            Log.w(TAG, "TTS not initialized, reinitializing...")
            initializeTts()
            return
        }

        // Применяем настройки
        engine.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))

        val utteranceId = "${UTTERANCE_ID_PREFIX}${++utteranceCounter}"
        
        // Озвучиваем
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
     * Останавливает текущее воспроизведение
     */
    fun stop() {
        tts?.stop()
        _ttsState.value = TtsState.Ready
    }

    /**
     * Проверяет, идёт ли сейчас воспроизведение
     */
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    /**
     * Полное освобождение ресурсов TTS.
     * ОБЯЗАТЕЛЬНО вызывать при уничтожении сервиса/активности!
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down TTS")
        initializationGeneration++
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
