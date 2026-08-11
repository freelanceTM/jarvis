package com.jarvis.assistant.voice.orchestrator

import android.content.Context
import android.media.ToneGenerator
import android.media.AudioManager
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.core.security.SecurityManager
import com.jarvis.assistant.domain.models.VoiceAssistantState
import com.jarvis.assistant.domain.usecases.GetSettingsUseCase
import com.jarvis.assistant.domain.usecases.SendPromptUseCase
import com.jarvis.assistant.voice.audio.BluetoothAudioRouter
import com.jarvis.assistant.voice.stt.SpeechRecognitionEvent
import com.jarvis.assistant.voice.stt.SpeechRecognizerManager
import com.jarvis.assistant.voice.tts.TextToSpeechManager
import com.jarvis.assistant.voice.tts.TtsState
import com.jarvis.assistant.voice.wakeword.WakeWordDetector
import com.jarvis.assistant.voice.wakeword.WakeWordEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

enum class OrchestratorMode {
    STANDBY_WAKE_WORD,        // Ожидание «Джарвис»
    VERIFYING_KEYWORD,        // Анализ
    LISTENING_USER_QUERY,     // Запись голоса
    CONTINUOUS_CONVERSATION,  // Диалоговое окно (без повтора «Джарвис»)
    AI_THINKING,              // Запрос AI / Fast Router
    TTS_SPEAKING,             // Озвучивание ответа
    AWAITING_CONFIRMATION,    // Ожидание голосового подтверждения (Да/Нет)
    PAUSED_CALL_OR_SLEEP      // Пауза
}

@Singleton
class VoiceInteractionOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wakeWordDetector: WakeWordDetector,
    private val speechRecognizerManager: SpeechRecognizerManager,
    private val textToSpeechManager: TextToSpeechManager,
    private val bluetoothAudioRouter: BluetoothAudioRouter,
    private val sendPromptUseCase: SendPromptUseCase,
    private val getSettingsUseCase: GetSettingsUseCase,
    private val securityManager: SecurityManager,
    private val toolExecutor: ToolExecutor
) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _currentMode = MutableStateFlow(OrchestratorMode.STANDBY_WAKE_WORD)
    val currentMode: StateFlow<OrchestratorMode> = _currentMode.asStateFlow()

    private val _assistantState = MutableStateFlow<VoiceAssistantState>(VoiceAssistantState.Idle)
    val assistantState: StateFlow<VoiceAssistantState> = _assistantState.asStateFlow()

    private val _lastQuery = MutableStateFlow("")
    val lastQuery: StateFlow<String> = _lastQuery.asStateFlow()

    private val _lastAnswer = MutableStateFlow("")
    val lastAnswer: StateFlow<String> = _lastAnswer.asStateFlow()

    private var toneGenerator: ToneGenerator? = null
    private var aiJob: Job? = null
    private var silenceJob: Job? = null
    private var followUpWindowJob: Job? = null
    private var confirmationTimeoutJob: Job? = null
    private var isServiceActive = false

    private var pendingToolCall: ToolCall? = null
    private var pendingConfirmationPrompt: String = ""

    private var speechRate = 1.05f
    private var speechPitch = 0.90f

    private val wakeKeywords = listOf("джарвис", "jarvis", "жарвис", "дарвис", "джей", "диджей", "джар")

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (_: Exception) { }

        observeSettings()
        observeHeadsetPlugging()
        observeWakeDetector()
        observeSpeechRecognizer()
        observeTtsEngine()
    }

    fun startServicePipeline() {
        isServiceActive = true
        bluetoothAudioRouter.checkHeadsetConnection()

        if (!bluetoothAudioRouter.isHeadsetConnected()) {
            _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
            _assistantState.value = VoiceAssistantState.Error("Подключите наушники для работы")
            return
        }

        bluetoothAudioRouter.routeAudioToEarbud()
        startStandbyMode()
    }

    fun stopServicePipeline() {
        isServiceActive = false
        stopAll()
        _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
        _assistantState.value = VoiceAssistantState.Idle
    }

    private fun observeHeadsetPlugging() {
        scope.launch {
            bluetoothAudioRouter.isHeadsetPlugged.collectLatest { isPlugged ->
                if (isServiceActive) {
                    if (isPlugged) {
                        bluetoothAudioRouter.routeAudioToEarbud()
                        playWakeChime()
                        startStandbyMode()
                    } else {
                        stopAll()
                        _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
                        _assistantState.value = VoiceAssistantState.Error("Наушники отключены. Ожидание...")
                    }
                }
            }
        }
    }

    private fun observeSettings() {
        scope.launch {
            getSettingsUseCase().collectLatest { settings ->
                speechRate = settings.speechRate
                speechPitch = settings.speechPitch
            }
        }
    }

    private fun observeWakeDetector() {
        scope.launch {
            wakeWordDetector.events.collectLatest { event ->
                if (event is WakeWordEvent.VoiceActivityDetected && _currentMode.value == OrchestratorMode.STANDBY_WAKE_WORD) {
                    if (bluetoothAudioRouter.isHeadsetConnected()) {
                        switchToSpeechRecognition()
                    }
                }
            }
        }
    }

    private fun startStandbyMode() {
        if (!isServiceActive) return

        if (!bluetoothAudioRouter.isHeadsetConnected()) {
            _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
            _assistantState.value = VoiceAssistantState.Error("Подключите наушники")
            return
        }

        followUpWindowJob?.cancel()
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null
        pendingToolCall = null
        toolExecutor.clearPendingConfirmation()

        _currentMode.value = OrchestratorMode.STANDBY_WAKE_WORD
        _assistantState.value = VoiceAssistantState.Idle
        speechRecognizerManager.stopListening()
        wakeWordDetector.startListening()
    }

    private fun switchToSpeechRecognition() {
        wakeWordDetector.stopListening()
        _currentMode.value = OrchestratorMode.LISTENING_USER_QUERY
        _assistantState.value = VoiceAssistantState.Listening
        speechRecognizerManager.startListening()
    }

    private fun observeSpeechRecognizer() {
        scope.launch {
            speechRecognizerManager.speechState.collectLatest { event ->
                when (event) {
                    is SpeechRecognitionEvent.PartialResult -> {
                        if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY ||
                            _currentMode.value == OrchestratorMode.CONTINUOUS_CONVERSATION) {
                            _assistantState.value = VoiceAssistantState.Recognizing(event.partialText)
                            _lastQuery.value = cleanWakeWord(event.partialText)

                            // Быстрая авто-отправка через 850мс тишины
                            silenceJob?.cancel()
                            silenceJob = scope.launch {
                                delay(850)
                                val current = _currentMode.value
                                if ((current == OrchestratorMode.LISTENING_USER_QUERY || current == OrchestratorMode.CONTINUOUS_CONVERSATION) && event.partialText.isNotBlank()) {
                                    speechRecognizerManager.stopListening()
                                    processUserQuery(cleanWakeWord(event.partialText))
                                }
                            }
                        }
                    }
                    is SpeechRecognitionEvent.FinalResult -> {
                        silenceJob?.cancel()
                        val text = event.recognizedText.trim()

                        if (text.lowercase() in listOf("стоп", "хватит", "отмена", "джарвис стоп")) {
                            handleCancel()
                            return@collectLatest
                        }

                        // Если находимся в режиме ожидания голосового подтверждения (Да/Нет)
                        if (_currentMode.value == OrchestratorMode.AWAITING_CONFIRMATION) {
                            handleConfirmationResponse(text)
                            return@collectLatest
                        }

                        val clean = cleanWakeWord(text)
                        if (clean.isNotBlank()) {
                            processUserQuery(clean)
                        } else {
                            startStandbyMode()
                        }
                    }
                    is SpeechRecognitionEvent.RecognitionError -> {
                        silenceJob?.cancel()
                        if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY ||
                            _currentMode.value == OrchestratorMode.CONTINUOUS_CONVERSATION) {
                            delay(400)
                            startStandbyMode()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun cleanWakeWord(raw: String): String {
        var result = raw
        for (kw in wakeKeywords) {
            result = result.replace(Regex("(?i)^.*?$kw[,\\s]*"), "").trim()
        }
        return result.ifEmpty { raw }
    }

    private fun processUserQuery(query: String) {
        if (query.isBlank()) {
            startStandbyMode()
            return
        }

        playWakeChime()
        followUpWindowJob?.cancel()
        confirmationTimeoutJob?.cancel()
        _currentMode.value = OrchestratorMode.AI_THINKING
        _assistantState.value = VoiceAssistantState.Thinking
        _lastQuery.value = query

        aiJob?.cancel()
        aiJob = scope.launch {
            val result = sendPromptUseCase(query)
            when (result) {
                is Resource.Success -> {
                    val answer = result.data.trim()

                    // Проверка: требуется ли голосовое подтверждение опасного действия
                    if (answer.contains("CONFIRM:")) {
                        val parts = answer.split(":")
                        val toolId = parts.getOrNull(1)?.trim().orEmpty()
                        pendingConfirmationPrompt = if (parts.size >= 3) {
                            parts.subList(2, parts.size).joinToString(":")
                        } else {
                            parts.getOrNull(1) ?: "Подтвердите действие, сэр."
                        }

                        pendingToolCall = toolExecutor.pendingConfirmationCall ?: run {
                            if (toolId.isNotBlank()) ToolCall(toolId, JsonObject(emptyMap())) else null
                        }

                        _lastAnswer.value = pendingConfirmationPrompt
                        _currentMode.value = OrchestratorMode.AWAITING_CONFIRMATION
                        _assistantState.value = VoiceAssistantState.Speaking(pendingConfirmationPrompt)

                        // Озвучиваем вопрос подтверждения пользователю
                        textToSpeechManager.speak(pendingConfirmationPrompt, speechRate, speechPitch)

                        // 10-секундный таймаут ожидания голосового ответа
                        confirmationTimeoutJob?.cancel()
                        confirmationTimeoutJob = scope.launch {
                            delay(10_000)
                            if (_currentMode.value == OrchestratorMode.AWAITING_CONFIRMATION) {
                                val timeoutMsg = "Время ожидания истекло. Операция отменена, сэр."
                                _lastAnswer.value = timeoutMsg
                                _assistantState.value = VoiceAssistantState.Speaking(timeoutMsg)
                                textToSpeechManager.speak(timeoutMsg, speechRate, speechPitch)
                                pendingToolCall = null
                                toolExecutor.clearPendingConfirmation()
                                delay(2000)
                                startStandbyMode()
                            }
                        }
                    } else {
                        // Обычный ответ — озвучиваем
                        _lastAnswer.value = answer
                        _currentMode.value = OrchestratorMode.TTS_SPEAKING
                        _assistantState.value = VoiceAssistantState.Speaking(answer)
                        textToSpeechManager.speak(answer, speechRate, speechPitch)
                    }
                }
                is Resource.Error -> {
                    val errorMsg = result.message ?: "Ошибка связи с AI"
                    _lastAnswer.value = "Ошибка: $errorMsg"
                    _assistantState.value = VoiceAssistantState.Error(errorMsg)
                    textToSpeechManager.speak(errorMsg, speechRate, speechPitch)
                    delay(2000)
                    startStandbyMode()
                }
                is Resource.Loading -> Unit
            }
        }
    }

    private fun handleConfirmationResponse(response: String) {
        confirmationTimeoutJob?.cancel()
        val text = response.lowercase().trim()

        val isYes = text.contains("да") ||
                text.contains("подтверждаю") ||
                text.contains("давай") ||
                text.contains("окей") ||
                text.contains("ок") ||
                text.contains("выполняй") ||
                text.contains("разрешаю") ||
                text.contains("звони") ||
                text.contains("набирай") ||
                text.contains("отправляй") ||
                text.contains("согласен") ||
                text.contains("делай") ||
                text.contains("конечно") ||
                text.contains("ага") ||
                text.contains("добро")

        val isNo = text.contains("нет") ||
                text.contains("отмена") ||
                text.contains("стоп") ||
                text.contains("отменить") ||
                text.contains("не надо") ||
                text.contains("не нужно") ||
                text.contains("отбой") ||
                text.contains("не стоит") ||
                text.contains("передумал") ||
                text.contains("хватит")

        when {
            isYes && pendingToolCall != null -> {
                val callToExecute = pendingToolCall!!
                pendingToolCall = null
                toolExecutor.clearPendingConfirmation()

                _currentMode.value = OrchestratorMode.AI_THINKING
                _assistantState.value = VoiceAssistantState.Thinking

                scope.launch {
                    val result = toolExecutor.executeWithBypass(callToExecute)
                    val voiceResponse = if (result.isSuccess) {
                        "${result.summary}, сэр."
                    } else {
                        "Не удалось выполнить: ${result.error ?: result.summary}"
                    }
                    _lastAnswer.value = voiceResponse
                    _currentMode.value = OrchestratorMode.TTS_SPEAKING
                    _assistantState.value = VoiceAssistantState.Speaking(voiceResponse)
                    textToSpeechManager.speak(voiceResponse, speechRate, speechPitch)
                }
            }
            isNo -> {
                pendingToolCall = null
                toolExecutor.clearPendingConfirmation()
                val cancelMsg = "Операция отменена, сэр."
                _lastAnswer.value = cancelMsg
                _currentMode.value = OrchestratorMode.TTS_SPEAKING
                _assistantState.value = VoiceAssistantState.Speaking(cancelMsg)
                textToSpeechManager.speak(cancelMsg, speechRate, speechPitch)
            }
            else -> {
                // Переспросить
                val retryMsg = "Не понял. Скажите да или нет."
                _assistantState.value = VoiceAssistantState.Speaking(retryMsg)
                textToSpeechManager.speak(retryMsg, speechRate, speechPitch)

                // Перезапуск 10-секундного таймаута
                confirmationTimeoutJob?.cancel()
                confirmationTimeoutJob = scope.launch {
                    delay(10_000)
                    if (_currentMode.value == OrchestratorMode.AWAITING_CONFIRMATION) {
                        val timeoutMsg = "Время ожидания истекло. Операция отменена, сэр."
                        _lastAnswer.value = timeoutMsg
                        _assistantState.value = VoiceAssistantState.Speaking(timeoutMsg)
                        textToSpeechManager.speak(timeoutMsg, speechRate, speechPitch)
                        pendingToolCall = null
                        toolExecutor.clearPendingConfirmation()
                        delay(2000)
                        startStandbyMode()
                    }
                }
            }
        }
    }

    private fun observeTtsEngine() {
        scope.launch {
            textToSpeechManager.ttsState.collectLatest { ttsState ->
                when (ttsState) {
                    is TtsState.Finished -> {
                        when (_currentMode.value) {
                            OrchestratorMode.TTS_SPEAKING -> {
                                openContinuousConversationWindow()
                            }
                            OrchestratorMode.AWAITING_CONFIRMATION -> {
                                // Начинаем слушать ответ пользователя (Да/Нет)
                                wakeWordDetector.stopListening()
                                speechRecognizerManager.startListening()
                            }
                            else -> Unit
                        }
                    }
                    is TtsState.Error -> {
                        startStandbyMode()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun openContinuousConversationWindow() {
        if (!isServiceActive) return
        _currentMode.value = OrchestratorMode.CONTINUOUS_CONVERSATION
        _assistantState.value = VoiceAssistantState.Idle
        wakeWordDetector.stopListening()
        speechRecognizerManager.startListening()

        followUpWindowJob?.cancel()
        followUpWindowJob = scope.launch {
            delay(4000) // 4 секунды на продолжение диалога
            if (_currentMode.value == OrchestratorMode.CONTINUOUS_CONVERSATION) {
                startStandbyMode()
            }
        }
    }

    private fun handleCancel() {
        silenceJob?.cancel()
        followUpWindowJob?.cancel()
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null
        pendingToolCall = null
        toolExecutor.clearPendingConfirmation()
        aiJob?.cancel()
        aiJob = null
        textToSpeechManager.stop()
        playCancelChime()
        startStandbyMode()
    }

    private fun playWakeChime() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        } catch (_: Exception) { }
    }

    private fun playCancelChime() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 70)
        } catch (_: Exception) { }
    }

    fun pauseForPhoneCall() {
        stopAll()
        _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
    }

    fun resumeAfterPhoneCall() {
        if (isServiceActive) {
            startServicePipeline()
        }
    }

    fun stopAll() {
        silenceJob?.cancel()
        followUpWindowJob?.cancel()
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null
        pendingToolCall = null
        toolExecutor.clearPendingConfirmation()
        aiJob?.cancel()
        aiJob = null
        wakeWordDetector.stopListening()
        speechRecognizerManager.stopListening()
        textToSpeechManager.stop()
    }
}
