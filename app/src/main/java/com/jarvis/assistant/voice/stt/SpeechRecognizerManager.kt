package com.jarvis.assistant.voice.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SpeechRecognitionEvent {
    data object Idle : SpeechRecognitionEvent
    data object ReadyForSpeech : SpeechRecognitionEvent
    data class PartialResult(val partialText: String) : SpeechRecognitionEvent
    data class FinalResult(val recognizedText: String) : SpeechRecognitionEvent
    data class RecognitionError(val errorMessage: String, val errorCode: Int) : SpeechRecognitionEvent
}

@Singleton
class SpeechRecognizerManager @Inject constructor(
    @ApplicationContext private val context: Context
) : RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private val _speechState = MutableStateFlow<SpeechRecognitionEvent>(SpeechRecognitionEvent.Idle)
    val speechState: StateFlow<SpeechRecognitionEvent> = _speechState.asStateFlow()

    private var isListening = false

    fun startListening(languageTag: String = "ru-RU") {
        stopListening()

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(this@SpeechRecognizerManager)
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            isListening = true
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            _speechState.value = SpeechRecognitionEvent.RecognitionError("Не удалось открыть микрофон", -1)
        }
    }

    fun stopListening() {
        if (!isListening && speechRecognizer == null) return
        isListening = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) { }
        speechRecognizer = null
    }

    fun destroy() {
        stopListening()
        _speechState.value = SpeechRecognitionEvent.Idle
    }

    override fun onReadyForSpeech(params: Bundle?) {
        _speechState.value = SpeechRecognitionEvent.ReadyForSpeech
    }

    override fun onBeginningOfSpeech() { }

    override fun onRmsChanged(rmsdB: Float) { }

    override fun onBufferReceived(buffer: ByteArray?) { }

    override fun onEndOfSpeech() {
        isListening = false
    }

    override fun onError(error: Int) {
        isListening = false
        val friendlyMessage = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "Я не расслышал запрос."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Вы ничего не сказали."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет подключения к интернету."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон."
            else -> "Ошибка распознавания речи ($error)."
        }
        _speechState.value = SpeechRecognitionEvent.RecognitionError(friendlyMessage, error)
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val best = matches?.firstOrNull()?.trim().orEmpty()
        if (best.isNotEmpty()) {
            _speechState.value = SpeechRecognitionEvent.FinalResult(best)
        } else {
            _speechState.value = SpeechRecognitionEvent.RecognitionError("Пустой запрос", SpeechRecognizer.ERROR_NO_MATCH)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val partial = matches?.firstOrNull()?.trim().orEmpty()
        if (partial.isNotEmpty()) {
            _speechState.value = SpeechRecognitionEvent.PartialResult(partial)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) { }
}
