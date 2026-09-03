package com.jarvis.assistant.presentation.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.R
import com.jarvis.assistant.agent.decision.PrivacyClassification
import com.jarvis.assistant.agent.decision.PrivacyClassifier
import com.jarvis.assistant.agent.decision.PrivacyContent
import com.jarvis.assistant.agent.decision.PrivacyLevel
import com.jarvis.assistant.agent.tools.accessibility.ScreenContentPrivacy
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
import kotlinx.coroutines.channels.BufferOverflow
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

/**
 * C-02: ожидание согласия пользователя на отправку приватного запроса в облако.
 *
 * Отличается от [PendingConfirmationUi] тем, что здесь нет ToolCall/токена
 * подтверждения действия — мы просто запоминаем оригинальный текст запроса,
 * чтобы при нажатии «Отправить в облако» повторить вызов SendPromptUseCase с
 * флагом cloudExplicitlyAllowed=true. При «Только локально» — повторяем с
 * cloudExplicitlyAllowed=false? Нет: «Только локально» означает «не давать
 * согласие», т.е. запрос должен быть обработан локально (on-device Gemma);
 * в текущей реализации если локальный путь не может обработать — вернётся
 * внятная ошибка, а не обход согласия.
 */
data class PendingCloudConsentUi(
    val privacyLevel: PrivacyLevel,
    val promptMessage: String,
    val userPrompt: String
)

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val isVoiceDictating: Boolean = false,
    val privacyClassification: PrivacyClassification =
        PrivacyClassification.unknown(PrivacyReason.NOT_CLASSIFIED),
    val pendingConfirmation: PendingConfirmationUi? = null,
    /** C-02: запрос на отправку приватных данных в облако, ожидающий ответа пользователя. */
    val pendingCloudConsent: PendingCloudConsentUi? = null
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

        // C-02: приоритет №1 — если висит cloud-consent вопрос, «да/нет» — ответ на него.
        val pendingConsent = _uiState.value.pendingCloudConsent
        if (pendingConsent != null && ConfirmationIntent.isDefinitive(query)) {
            _uiState.update { it.copy(inputText = "") }
            if (ConfirmationIntent.isYes(query)) {
                confirmCloudConsent(pendingConsent)
            } else {
                denyCloudConsent(pendingConsent)
            }
            return
        }

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

        // H-02: НЕ пересчитываем классификацию на send. Она 1) уже посчитана
        // debounce-пайплайном для индикатора (keystroke-level, без истории),
        // 2) будет ПОЛНОСТЬЮ пересчитана в SendPromptUseCase с контекстом
        // (systemPrompt + история) — это authoritative classification.
        // Здесь передаём подсказку (hint) от UI на основании текущей метки;
        // больше ничего — use case является единственным источником истины.
        val sendHint = _uiState.value.privacyClassification.level
        _uiState.update {
            it.copy(
                inputText = "",
                isSending = true,
                // C-02: новый сброс старого consent, если начат новый запрос.
                pendingCloudConsent = null
            )
        }

        viewModelScope.launch {
            val result = sendPromptUseCase(
                query,
                privacyLevel = sendHint
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
                // C-02: use case сигнализирует, что нужен согласие на облако.
                is Resource.NeedsConsent -> {
                    _uiState.update {
                        it.copy(
                            pendingCloudConsent = PendingCloudConsentUi(
                                privacyLevel = result.privacyLevel,
                                promptMessage = result.prompt,
                                userPrompt = result.retryOnConsentArgs.userPrompt
                            )
                        )
                    }
                    // Читаем вопрос вслух, чтобы голосовой пользователь тоже услышал его
                    // (при диктовке из микрофона чат виден на экране).
                    textToSpeechManager.speak(result.prompt, speechRate, speechPitch)
                }
            }
        }
    }

    /**
     * C-02: пользователь согласился отправить приватный запрос в облако.
     *
     * Повторяем вызов [SendPromptUseCase] с cloudExplicitlyAllowed=true —
     * use case на этот раз пройдёт privacy gate и отправит запрос в cloud AI.
     */
    fun confirmCloudConsent(consent: PendingCloudConsentUi? = _uiState.value.pendingCloudConsent) {
        // `return` запрещён в default-выражении параметра, поэтому дефолт —
        // nullable, а выход — первый строкой тела (семантика не изменилась).
        if (consent == null) return
        _uiState.update { it.copy(pendingCloudConsent = null, isSending = true) }

        // H-02: НЕ пересчитываем классификацию — use case сам сделает это с полным
        // контекстом на момент consent-retry. Уровень из consent.privacyLevel — это
        // effective, который был посчитан use case в момент первого вызова и уже
        // показан в UI-карточке; его достаточно для hint.
        viewModelScope.launch {
            val result = sendPromptUseCase(
                userPrompt = consent.userPrompt,
                source = com.jarvis.assistant.agent.decision.RequestSource.CHAT,
                privacyLevel = consent.privacyLevel,
                cloudExplicitlyAllowed = true
            )
            _uiState.update { it.copy(isSending = false) }
            when (result) {
                is Resource.Success -> {
                    when (val exec = result.data) {
                        is PromptExecutionResult.ConfirmationRequired -> {
                            val token = toolExecutor.confirmationTokenFor(exec.toolCall.callId)
                            _uiState.update {
                                it.copy(
                                    pendingConfirmation = if (token != null) PendingConfirmationUi(
                                        toolCall = exec.toolCall,
                                        promptMessage = exec.promptMessage,
                                        confirmationToken = token
                                    ) else null
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
                // Теоретически не может прийти NeedsConsent второй раз — флаг уже true.
                is Resource.NeedsConsent -> Unit
            }
        }
    }

    /**
     * C-02: пользователь отказался отправлять запрос в облако.
     *
     * В первой версии просто показываем отказ и НЕ делаем запрос в облако.
     * Полноценный fallback на Local AI (on-device Gemma) с этим флагом — это
     * отдельная работа Этап 2, когда local executor будет стабилен; сейчас
     * локальный путь сам сработает в decision engine, если модель доступна
     * и способна обработать запрос.
     */
    fun denyCloudConsent(consent: PendingCloudConsentUi? = _uiState.value.pendingCloudConsent) {
        if (consent == null) return
        _uiState.update { it.copy(pendingCloudConsent = null) }
        val declineMsg = context.getString(R.string.cloud_consent_declined)
        viewModelScope.launch {
            messageRepository.insertMessage(
                Message(
                    role = MessageRole.ASSISTANT,
                    text = declineMsg,
                    timestamp = System.currentTimeMillis()
                )
            )
            textToSpeechManager.speak(declineMsg, speechRate, speechPitch)
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
            // Accessibility Lockdown: подтверждённое чтение экрана озвучивается,
            // но в историю пишется placeholder — иначе bypass-результат утёк бы
            // в следующий облачный запрос через history (ScreenContentPrivacy).
            val persistedText =
                if (result.isSuccess && ScreenContentPrivacy.isScreenReaderCall(pending.toolCall.toolId)) {
                    ScreenContentPrivacy.PLACEHOLDER
                } else {
                    voiceResponse
                }
            messageRepository.insertMessage(
                Message(
                    role = MessageRole.ASSISTANT,
                    text = persistedText,
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
