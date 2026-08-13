package com.jarvis.assistant.presentation.translator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.agent.translator.InterpreterMode
import com.jarvis.assistant.agent.translator.LiveTranslatorEngine
import com.jarvis.assistant.agent.translator.SupportedLanguage
import com.jarvis.assistant.voice.audio.BluetoothAudioRouter
import com.jarvis.assistant.voice.stt.SpeechRecognitionEvent
import com.jarvis.assistant.voice.stt.SpeechRecognizerManager
import com.jarvis.assistant.voice.tts.TextToSpeechManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TranslationItem(
    val id: Long = System.currentTimeMillis(),
    val originalText: String,
    val translatedText: String,
    val sourceLang: String,
    val targetLang: String,
    val isInterlocutor: Boolean = true
)

data class LiveInterpreterUiState(
    val isListening: Boolean = false,
    val mode: InterpreterMode = InterpreterMode.EAR_ONLY,
    val sourceLanguage: SupportedLanguage = LiveTranslatorEngine.SUPPORTED_LANGUAGES[0], // RU
    val targetLanguage: SupportedLanguage = LiveTranslatorEngine.SUPPORTED_LANGUAGES[1], // EN
    val history: List<TranslationItem> = emptyList(),
    val partialRecognizedText: String = "",
    val isTranslating: Boolean = false
)

@HiltViewModel
class LiveInterpreterViewModel @Inject constructor(
    private val translatorEngine: LiveTranslatorEngine,
    private val speechRecognizerManager: SpeechRecognizerManager,
    private val textToSpeechManager: TextToSpeechManager,
    private val bluetoothAudioRouter: BluetoothAudioRouter
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveInterpreterUiState())
    val uiState: StateFlow<LiveInterpreterUiState> = _uiState.asStateFlow()

    init {
        observeSpeechRecognizer()
    }

    private fun observeSpeechRecognizer() {
        viewModelScope.launch {
            speechRecognizerManager.speechState.collectLatest { event ->
                when (event) {
                    is SpeechRecognitionEvent.PartialResult -> {
                        _uiState.update { it.copy(partialRecognizedText = event.partialText) }
                    }
                    is SpeechRecognitionEvent.FinalResult -> {
                        val text = event.recognizedText.trim()
                        _uiState.update { it.copy(partialRecognizedText = "", isTranslating = true) }
                        if (text.isNotBlank()) {
                            processLiveTranslation(text)
                        } else {
                            _uiState.update { it.copy(isTranslating = false) }
                        }
                    }
                    is SpeechRecognitionEvent.RecognitionError -> {
                        _uiState.update { it.copy(isListening = false, isTranslating = false) }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun processLiveTranslation(text: String) {
        viewModelScope.launch {
            val state = _uiState.value
            val translation = translatorEngine.translate(
                text = text,
                sourceLang = state.sourceLanguage.code,
                targetLang = state.targetLanguage.code
            )

            val item = TranslationItem(
                originalText = text,
                translatedText = translation,
                sourceLang = state.sourceLanguage.displayName,
                targetLang = state.targetLanguage.displayName,
                isInterlocutor = true
            )

            _uiState.update { 
                it.copy(
                    history = listOf(item) + it.history,
                    isTranslating = false
                ) 
            }

            // Мгновенное озвучивание перевода прямо в ухо через Bluetooth SCO
            bluetoothAudioRouter.routeAudioToEarbud()
            textToSpeechManager.speak(translation)

            // Если непрерывный режим — продолжаем слушать следующую фразу
            if (_uiState.value.isListening) {
                speechRecognizerManager.startListening(state.sourceLanguage.localeTag)
            }
        }
    }

    fun toggleListening() {
        if (_uiState.value.isListening) {
            speechRecognizerManager.stopListening()
            _uiState.update { it.copy(isListening = false, partialRecognizedText = "") }
        } else {
            _uiState.update { it.copy(isListening = true) }
            speechRecognizerManager.startListening(_uiState.value.sourceLanguage.localeTag)
        }
    }

    fun swapLanguages() {
        _uiState.update { 
            it.copy(
                sourceLanguage = it.targetLanguage,
                targetLanguage = it.sourceLanguage
            ) 
        }
        if (_uiState.value.isListening) {
            speechRecognizerManager.startListening(_uiState.value.sourceLanguage.localeTag)
        }
    }

    fun setSourceLanguage(lang: SupportedLanguage) {
        _uiState.update { it.copy(sourceLanguage = lang) }
    }

    fun setTargetLanguage(lang: SupportedLanguage) {
        _uiState.update { it.copy(targetLanguage = lang) }
    }

    fun clearHistory() {
        _uiState.update { it.copy(history = emptyList()) }
    }
}
