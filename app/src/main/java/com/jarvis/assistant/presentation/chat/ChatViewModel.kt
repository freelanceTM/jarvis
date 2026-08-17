package com.jarvis.assistant.presentation.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.R
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.core.confirmation.ConfirmationIntent
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import com.jarvis.assistant.domain.models.PromptExecutionResult
import com.jarvis.assistant.domain.repository.MessageRepository
import com.jarvis.assistant.domain.usecases.ClearChatHistoryUseCase
import com.jarvis.assistant.domain.usecases.GetChatHistoryUseCase
import com.jarvis.assistant.domain.usecases.GetSettingsUseCase
import com.jarvis.assistant.domain.usecases.SendPromptUseCase
import com.jarvis.assistant.voice.stt.SpeechRecognitionEvent
import com.jarvis.assistant.voice.stt.SpeechRecognizerManager
import com.jarvis.assistant.voice.tts.TextToSpeechManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Отложенное действие, ожидающее подтверждения пользователя в чате. */
data class PendingConfirmationUi(
    val toolCall: ToolCall,
    val promptMessage: String,

    /**
     * Одноразовый токен подтверждения (пункт аудита #5): получается из очереди
     * ToolExecutor и передаётся в executeWithBypass для проверки.
     */
    val confirmationToken: String
)

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val isVoiceDictating: Boolean = false,
    val pendingConfirmation: PendingConfirmationUi? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getChatHistoryUseCase: GetChatHistoryUseCase,
    private val clearChatHistoryUseCase: ClearChatHistoryUseCase,
    private val sendPromptUseCase: SendPromptUseCase,
    private val getSettingsUseCase: GetSettingsUseCase,
    private val textToSpeechManager: TextToSpeechManager,
    private val speechRecognizerManager: SpeechRecognizerManager,
    private val toolExecutor: ToolExecutor,
    private val messageRepository: MessageRepository
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

        // Если ждём подтверждения действия — текст «да/нет» обрабатывается как ответ.
        val pending = _uiState.value.pendingConfirmation
        if (pending != null && ConfirmationIntent.isDefinitive(query)) {
            _uiState.update { it.copy(inputText = "") }
            if (ConfirmationIntent.isYes(query)) {
                confirmPendingAction()
            } else {
                cancelPendingAction()
            }
            return
        }

        _uiState.update { it.copy(inputText = "", isSending = true) }

        viewModelScope.launch {
            val result = sendPromptUseCase(query)
            _uiState.update { it.copy(isSending = false) }

            when (result) {
                is Resource.Success -> {
                    when (val exec = result.data) {
                        is PromptExecutionResult.ConfirmationRequired -> {
                            // Показываем карточку подтверждения вместо простого озвучивания.
                            // Токен берём из очереди ToolExecutor (пункт аудита #5).
                            val token = toolExecutor.peekPendingConfirmation()?.confirmationToken
                            _uiState.update {
                                it.copy(
                                    pendingConfirmation = if (token != null) {
                                        PendingConfirmationUi(
                                            toolCall = exec.toolCall,
                                            promptMessage = exec.promptMessage,
                                            confirmationToken = token
                                        )
                                    } else {
                                        null
                                    }
                                )
                            }
                            textToSpeechManager.speak(exec.promptMessage, speechRate, speechPitch)
                        }
                        is PromptExecutionResult.DirectAnswer -> {
                            textToSpeechManager.speak(exec.text, speechRate, speechPitch)
                        }
                    }
                }
                is Resource.Error -> {
                    val errorMsg = result.message ?: context.getString(R.string.oshibka_vypolneniya_zaprosa)
                    textToSpeechManager.speak(context.getString(R.string.oshibka, errorMsg), speechRate, speechPitch)
                }
                is Resource.Loading -> Unit
            }
        }
    }

    /**
     * Пользователь подтвердил отложенное действие (кнопка «Подтвердить» или «Да»).
     * Выполняется БЕЗ повторного гейта подтверждения — как после голосового «Да».
     *
     * Пункт аудита #4: после выполнения показываем СЛЕДУЮЩИЙ ожидающий
     * подтверждения вызов из очереди ToolExecutor, если он есть.
     */
    fun confirmPendingAction() {
        val pending = _uiState.value.pendingConfirmation ?: return
        _uiState.update { it.copy(pendingConfirmation = null, isSending = true) }

        viewModelScope.launch {
            val result = toolExecutor.executeWithBypass(
                call = pending.toolCall,
                confirmationToken = pending.confirmationToken,
                source = "chat_ui"
            )
            val voiceResponse = when {
                result.isSuccess -> context.getString(R.string.s_sir, result.summary)
                result.isBlockedByAndroid -> result.summary
                else -> context.getString(R.string.ne_udalos_vypolnit, result.error ?: result.summary)
            }
            messageRepository.insertMessage(
                Message(
                    role = MessageRole.ASSISTANT,
                    text = voiceResponse,
                    timestamp = System.currentTimeMillis()
                )
            )
            _uiState.update { it.copy(isSending = false) }
            textToSpeechManager.speak(voiceResponse, speechRate, speechPitch)

            // Следующий запрос подтверждения из очереди (если есть).
            toolExecutor.peekPendingConfirmation()?.let { next ->
                _uiState.update {
                    it.copy(
                        pendingConfirmation = PendingConfirmationUi(
                            toolCall = next.toolCall,
                            promptMessage = next.promptMessage,
                            confirmationToken = next.confirmationToken
                        )
                    )
                }
            }
        }
    }

    /** Пользователь отклонил отложенное действие (кнопка «Отмена» или «Нет»). */
    fun cancelPendingAction() {
        val pending = _uiState.value.pendingConfirmation ?: return
        _uiState.update { it.copy(pendingConfirmation = null) }
        // Пункт аудита #4: отменяем ТОЛЬКО текущий вызов — остальные в очереди не трогаем.
        toolExecutor.removePendingConfirmation(pending.toolCall)

        viewModelScope.launch {
            val cancelMsg = context.getString(R.string.operaciya_otmenena_sir)
            messageRepository.insertMessage(
                Message(
                    role = MessageRole.ASSISTANT,
                    text = cancelMsg,
                    timestamp = System.currentTimeMillis()
                )
            )
            textToSpeechManager.speak(cancelMsg, speechRate, speechPitch)

            // Показываем следующий ожидающий подтверждения, если есть.
            toolExecutor.peekPendingConfirmation()?.let { next ->
                _uiState.update {
                    it.copy(
                        pendingConfirmation = PendingConfirmationUi(
                            toolCall = next.toolCall,
                            promptMessage = next.promptMessage,
                            confirmationToken = next.confirmationToken
                        )
                    )
                }
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
