package com.jarvis.assistant.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.PromptExecutionResult
import com.jarvis.assistant.domain.usecases.ClearChatHistoryUseCase
import com.jarvis.assistant.domain.usecases.GetChatHistoryUseCase
import com.jarvis.assistant.domain.usecases.GetSettingsUseCase
import com.jarvis.assistant.domain.usecases.SendPromptUseCase
import com.jarvis.assistant.voice.stt.SpeechRecognitionEvent
import com.jarvis.assistant.voice.stt.SpeechRecognizerManager
import com.jarvis.assistant.voice.tts.TextToSpeechManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val isVoiceDictating: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val getChatHistoryUseCase: GetChatHistoryUseCase,
    private val clearChatHistoryUseCase: ClearChatHistoryUseCase,
    private val sendPromptUseCase: SendPromptUseCase,
    private val getSettingsUseCase: GetSettingsUseCase,
    private val textToSpeechManager: TextToSpeechManager,
    private val speechRecognizerManager: SpeechRecognizerManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var speechRate = 1.05f
    private var speechPitch = 0.90f

    init {
        loadHistory()
        observeSettings()
        observeSpeechRecognizer()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            getChatHistoryUseCase().collectLatest { messageList ->
                _uiState.update { it.copy(messages = messageList) }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            getSettingsUseCase().collectLatest { settings ->
                speechRate = settings.speechRate
                speechPitch = settings.speechPitch
            }
        }
    }

    private fun observeSpeechRecognizer() {
        viewModelScope.launch {
            speechRecognizerManager.speechState.collectLatest { event ->
                when (event) {
                    is SpeechRecognitionEvent.PartialResult -> {
                        _uiState.update { it.copy(inputText = event.partialText) }
                    }
                    is SpeechRecognitionEvent.FinalResult -> {
                        _uiState.update { it.copy(inputText = event.recognizedText, isVoiceDictating = false) }
                        sendTextMessage(event.recognizedText)
                    }
                    is SpeechRecognitionEvent.RecognitionError -> {
                        _uiState.update { it.copy(isVoiceDictating = false) }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun onInputTextChanged(newText: String) {
        _uiState.update { it.copy(inputText = newText) }
    }

    fun sendTextMessage(textToSend: String = _uiState.value.inputText) {
        val query = textToSend.trim()
        if (query.isBlank() || _uiState.value.isSending) return

        _uiState.update { it.copy(inputText = "", isSending = true) }

        viewModelScope.launch {
            val result = sendPromptUseCase(query)
            _uiState.update { it.copy(isSending = false) }

            when (result) {
                is Resource.Success -> {
                    val voiceText = when (val exec = result.data) {
                        is PromptExecutionResult.ConfirmationRequired -> exec.promptMessage
                        is PromptExecutionResult.DirectAnswer -> exec.text
                    }
                    textToSpeechManager.speak(voiceText, speechRate, speechPitch)
                }
                is Resource.Error -> {
                    val errorMsg = result.message ?: "Ошибка выполнения запроса"
                    textToSpeechManager.speak("Ошибка: $errorMsg", speechRate, speechPitch)
                }
                is Resource.Loading -> Unit
            }
        }
    }

    fun toggleVoiceDictation() {
        if (_uiState.value.isVoiceDictating) {
            speechRecognizerManager.stopListening()
            _uiState.update { it.copy(isVoiceDictating = false) }
        } else {
            _uiState.update { it.copy(isVoiceDictating = true) }
            speechRecognizerManager.startListening()
        }
    }

    fun speakMessage(text: String) {
        textToSpeechManager.speak(text, speechRate, speechPitch)
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            clearChatHistoryUseCase()
        }
    }
}
