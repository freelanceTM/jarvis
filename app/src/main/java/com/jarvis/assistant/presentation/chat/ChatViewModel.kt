package com.jarvis.assistant.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.usecases.ClearChatHistoryUseCase
import com.jarvis.assistant.domain.usecases.GetChatHistoryUseCase
import com.jarvis.assistant.domain.usecases.GetSettingsUseCase
import com.jarvis.assistant.voice.tts.TextToSpeechManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val isSpeaking: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val getChatHistoryUseCase: GetChatHistoryUseCase,
    private val clearChatHistoryUseCase: ClearChatHistoryUseCase,
    private val getSettingsUseCase: GetSettingsUseCase,
    private val textToSpeechManager: TextToSpeechManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var speechRate = 1.0f
    private var speechPitch = 1.0f

    init {
        loadHistory()
        observeSettings()
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

    fun speakMessage(text: String) {
        textToSpeechManager.speak(text, speechRate, speechPitch)
    }

    fun stopSpeaking() {
        textToSpeechManager.stop()
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            clearChatHistoryUseCase()
        }
    }
}
