package com.jarvis.assistant.presentation.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.R
import com.jarvis.assistant.agent.decision.PrivacyClassification
import com.jarvis.assistant.agent.decision.PrivacyClassifier
import com.jarvis.assistant.agent.decision.PrivacyContent
import com.jarvis.assistant.agent.decision.PrivacyReason
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
import kotlinx.coroutines.Job
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
    val privacyClassification: PrivacyClassification =
        PrivacyClassification.unknown(PrivacyReason.NOT_CLASSIFIED),
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

    companion object {
        /**
         * CR-17: дебаунс для PrivacyClassifier при быстром вводе с клавиатуры.
         * Меньше 150 мс — дёргает классификатор на каждый слог; больше 400 мс —
         * индикатор в UI ощущается отставшим. 250 мс — стандартный UX-компромисс.
         */
        private const val CLASSIFY_DEBOUNCE_MS = 250L
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var speechRate = 1.05f
    private var speechPitch = 0.90f
    private var systemPrompt = ""

    /**
     * CR-17: буфер текста из onInputTextChanged. Вместо запуска PrivacyClassifier
     * на каждый keystroke, тексты сливаются через [debounce] (250 мс) и последняя
     * актуальная версия классифицируется ровно один раз. При быстром вводе /
     * lifecycle destruction старая классификация не применяется к новому тексту.
     */
    private val _inputClassificationRequests = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var inputClassificationJob: Job? = null

    init {
        loadHistory()
        observeSettings()
        observeSpeechRecognizer()
        startInputClassification()
    }

    /**
     * CR-17: один подписчик на поток ввода. Используем collectLatest: новая эмиссия
     * автоматически отменяет предыдущую classify/задержку, исключая race stale->new.
     */
    private fun startInputClassification() {
        inputClassificationJob?.cancel()
        inputClassificationJob = viewModelScope.launch {
            _inputClassificationRequests
                .debounce(CLASSIFY_DEBOUNCE_MS)
                .conflate()
                .collectLatest { text ->
                    val classification = PrivacyClassifier.classifySafely(PrivacyContent(text))
                    // atomic CAS: обновляем поле ТОЛЬКО если inputText всё ещё совпадает
                    // (защита от stale результата на случай, если экран уничтожен между
                    // классификацией и применением).
                    _uiState.update { current ->
                        if (current.inputText == text) current.copy(privacyClassification = classification)
                        else current
                    }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        inputClassificationJob?.cancel()
        inputClassificationJob = null
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
                systemPrompt = settings.systemPrompt
            }
        }
    }

    private fun observeSpeechRecognizer() {
        viewModelScope.launch {
            speechRecognizerManager.speechState.collectLatest { event ->
                when (event) {
                    is SpeechRecognitionEvent.PartialResult -> {
                        // CR-17: голосовой промежуточный текст тоже идёт через debounce
                        // классификацию (тот же pipeline, что и клавиатурный ввод).
                        if (_uiState.value.inputText != event.partialText) {
                            _uiState.update { it.copy(inputText = event.partialText) }
                            _inputClassificationRequests.tryEmit(event.partialText)
                        }
                    }
                    is SpeechRecognitionEvent.FinalResult -> {
                        _uiState.update { it.copy(inputText = event.recognizedText, isVoiceDictating = false) }
                        // sendTextMessage сам классифицирует финальный query — дополнительно
                        // не дёргаем debounce, чтобы не показать промежуточную метку на пустом поле.
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
        // CR-17: inputText обновляем немедленно (UI binding), но классификацию
        // запускаем через debounce поток. Пустой ввод и идентичные фрагменты
        // short-circuit, чтобы не плодить work.
        val prev = _uiState.value
        if (newText == prev.inputText) return
        _uiState.update {
            it.copy(
                inputText = newText,
                // При пустом вводе нет смысла показывать старую метку — сбрасываем
                // сразу (это не race: речь про пустую строку).
                privacyClassification = if (newText.isEmpty()) {
                    PrivacyClassification.unknown(PrivacyReason.NOT_CLASSIFIED)
                } else {
                    it.privacyClassification
                }
            )
        }
        // tryEmit с DROP_OLDEST: в буфере всегда последнее актуальное значение.
        _inputClassificationRequests.tryEmit(newText)
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

        val classification = PrivacyClassifier.classifySafely(PrivacyContent(query))
        _uiState.update {
            it.copy(inputText = "", isSending = true, privacyClassification = classification)
        }

        viewModelScope.launch {
            val history = messageRepository.getRecentMessages(limit = 10)
            val contextualClassification = PrivacyClassifier.classifySafely(
                PrivacyContent(
                    text = query,
                    relatedContent = listOf(systemPrompt) + history.map(Message::text)
                )
            )
            _uiState.update { it.copy(privacyClassification = contextualClassification) }
            val result = sendPromptUseCase(
                query,
                privacyLevel = contextualClassification.level
            )
            _uiState.update { it.copy(isSending = false) }

            when (result) {
                is Resource.Success -> {
                    when (val exec = result.data) {
                        is PromptExecutionResult.ConfirmationRequired -> {
                            // CR-04: токен берём по callId, а не из головы очереди.
                            // При параллельных запросах из чата и голоса peek() мог
                            // вернуть токен ДРУГОЙ записи и подтвердить не тот вызов.
                            val token = toolExecutor.confirmationTokenFor(exec.toolCall.callId)
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
