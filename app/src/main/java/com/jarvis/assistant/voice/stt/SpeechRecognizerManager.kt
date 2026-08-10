package com.jarvis.assistant.voice.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.jarvis.assistant.voice.audio.BluetoothAudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SpeechRecognitionEvent {
    data object Idle : SpeechRecognitionEvent
    data object ReadyForSpeech : SpeechRecognitionEvent
    data object BeginningOfSpeech : SpeechRecognitionEvent
    data class RmsChanged(val rmsDb: Float) : SpeechRecognitionEvent
    data class PartialResult(val partialText: String) : SpeechRecognitionEvent
    data class FinalResult(val recognizedText: String) : SpeechRecognitionEvent
    data class RecognitionError(val errorMessage: String, val errorCode: Int) : SpeechRecognitionEvent
}

@Singleton
class SpeechRecognizerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothAudioManager: BluetoothAudioManager
) : RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private val _speechState = MutableStateFlow<SpeechRecognitionEvent>(SpeechRecognitionEvent.Idle)
    val speechState: StateFlow<SpeechRecognitionEvent> = _speechState.asStateFlow()

    private var isCurrentlyListening = false

    fun isRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startListening(languageTag: String = "ru-RU") {
        if (isCurrentlyListening) {
            stopListening()
        }

        bluetoothAudioManager.startBluetoothSco()

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(this@SpeechRecognizerManager)
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        try {
            isCurrentlyListening = true
            speechRecognizer?.startListening(intent)
            _speechState.value = SpeechRecognitionEvent.ReadyForSpeech
        } catch (e: Exception) {
            isCurrentlyListening = false
            _speechState.value = SpeechRecognitionEvent.RecognitionError("Не удалось запустить микрофон", -1)
        }
    }

    fun stopListening() {
        if (!isCurrentlyListening) return
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) { }
        isCurrentlyListening = false
        bluetoothAudioManager.stopBluetoothSco()
    }

    fun cancel() {
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) { }
        isCurrentlyListening = false
        bluetoothAudioManager.stopBluetoothSco()
        _speechState.value = SpeechRecognitionEvent.Idle
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) { }
        speechRecognizer = null
        isCurrentlyListening = false
        bluetoothAudioManager.stopBluetoothSco()
        _speechState.value = SpeechRecognitionEvent.Idle
    }

    // RecognitionListener Implementation
    override fun onReadyForSpeech(params: Bundle?) {
        _speechState.value = SpeechRecognitionEvent.ReadyForSpeech
    }

    override fun onBeginningOfSpeech() {
        _speechState.value = SpeechRecognitionEvent.BeginningOfSpeech
    }

    override fun onRmsChanged(rmsdB: Float) {
        _speechState.value = SpeechRecognitionEvent.RmsChanged(rmsdB)
    }

    override fun onBufferReceived(buffer: ByteArray?) { }

    override fun onEndOfSpeech() {
        isCurrentlyListening = false
    }

    override fun onError(error: Int) {
        isCurrentlyListening = false
        bluetoothAudioManager.stopBluetoothSco()

        val friendlyMessage = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Ошибка записи аудио."
            SpeechRecognizer.ERROR_CLIENT -> "Внутренняя ошибка распознавания речи."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Не удалось получить доступ к микрофону."
            SpeechRecognizer.ERROR_NETWORK -> "Нет подключения к интернету."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Таймаут подключения при распознавании."
            SpeechRecognizer.ERROR_NO_MATCH -> "Я не расслышал запрос. Попробуйте еще раз."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Служба распознавания занята."
            SpeechRecognizer.ERROR_SERVER -> "Ошибка сервера распознавания речи."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Вы ничего не сказали."
            else -> "Ошибка распознавания речи ($error)."
        }

        _speechState.value = SpeechRecognitionEvent.RecognitionError(friendlyMessage, error)
    }

    override fun onResults(results: Bundle?) {
        isCurrentlyListening = false
        bluetoothAudioManager.stopBluetoothSco()

        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val bestText = matches?.firstOrNull()?.trim()

        if (!bestText.isNullOrEmpty()) {
            _speechState.value = SpeechRecognitionEvent.FinalResult(bestText)
        } else {
            _speechState.value = SpeechRecognitionEvent.RecognitionError("Я не расслышал запрос.", SpeechRecognizer.ERROR_NO_MATCH)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val partialText = matches?.firstOrNull()?.trim()
        if (!partialText.isNullOrEmpty()) {
            _speechState.value = SpeechRecognitionEvent.PartialResult(partialText)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) { }
}
