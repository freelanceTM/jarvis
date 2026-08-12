package com.jarvis.assistant.voice.orchestrator

import android.content.Context
import android.media.ToneGenerator
import android.media.AudioManager
import android.util.Log
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.core.security.SecurityManager
import com.jarvis.assistant.domain.models.PromptExecutionResult
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

enum class OrchestratorMode {
    STANDBY_WAKE_WORD,        // Ожидание «Джарвис»
    VERIFYING_KEYWORD,        // Верификация ключевого слова (anti-false-trigger)
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
    companion object {
        private const val TAG = "VoiceOrchestrator"
        
        // Увеличенные таймауты для комфортного диалога
        private const val KEYWORD_VERIFICATION_TIMEOUT_MS = 3000L
        private const val SILENCE_AFTER_PARTIAL_MS = 1200L
        private const val FOLLOW_UP_WINDOW_MS = 8000L // Было 4000, теперь 8 секунд
        private const val CONFIRMATION_TIMEOUT_MS = 10000L
    }

    private var orchestratorJob = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.Main.immediate + orchestratorJob)

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

    private val isProcessingQuery = AtomicBoolean(false)

    private var pendingToolCall: ToolCall? = null
    private var pendingConfirmationPrompt: String = ""

    private var speechRate = 1.05f
    private var speechPitch = 0.90f
    private var isHeadsetOnlyMode = false

    private val wakeKeywords = listOf("джарвис", "jarvis", "жарвис", "дарвис", "джей", "диджей", "джар")

    init {
        initToneGenerator()
        observePipelines()
    }

    private fun initToneGenerator() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (_: Exception) { }
    }

    private fun observePipelines() {
        observeSettings()
        observeHeadsetPlugging()
        observeWakeDetector()
        observeSpeechRecognizer()
        observeTtsEngine()
    }

    fun startServicePipeline() {
        if (!orchestratorJob.isActive) {
            orchestratorJob = SupervisorJob()
            scope = CoroutineScope(Dispatchers.Main.immediate + orchestratorJob)
            observePipelines()
        }

        isServiceActive = true
        bluetoothAudioRouter.checkHeadsetConnection()

        if (isHeadsetOnlyMode && !bluetoothAudioRouter.isHeadsetConnected()) {
            _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
            _assistantState.value = VoiceAssistantState.Error("Подключите наушники для работы")
            return
        }

        if (bluetoothAudioRouter.isHeadsetConnected()) {
            bluetoothAudioRouter.routeAudioToEarbud()
        } else {
            bluetoothAudioRouter.routeAudioToSpeaker()
        }
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
                        bluetoothAudioRouter.routeAudioToSpeaker()
                        if (isHeadsetOnlyMode) {
                            stopAll()
                            _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
                            _assistantState.value = VoiceAssistantState.Error("Наушники отключены. Ожидание...")
                        } else {
                            startStandbyMode()
                        }
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
                isHeadsetOnlyMode = settings.isHeadsetOnlyMode
            }
        }
    }

    private fun observeWakeDetector() {
        scope.launch {
            wakeWordDetector.events.collectLatest { event ->
                if (event is WakeWordEvent.VoiceActivityDetected && _currentMode.value == OrchestratorMode.STANDBY_WAKE_WORD) {
                    if (!isHeadsetOnlyMode || bluetoothAudioRouter.isHeadsetConnected()) {
                        startKeywordVerification()
                    }
                }
            }
        }
    }

    private fun startStandbyMode() {
        if (!isServiceActive) return

        if (isHeadsetOnlyMode && !bluetoothAudioRouter.isHeadsetConnected()) {
            _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
            _assistantState.value = VoiceAssistantState.Error("Подключите наушники")
            return
        }

        followUpWindowJob?.cancel()
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null
        silenceJob?.cancel()
        silenceJob = null
        pendingToolCall = null
        toolExecutor.clearPendingConfirmation()
        isProcessingQuery.set(false)

        _currentMode.value = OrchestratorMode.STANDBY_WAKE_WORD
        _assistantState.value = VoiceAssistantState.Idle
        speechRecognizerManager.stopListening()
        wakeWordDetector.startListening()
    }

    private fun startKeywordVerification() {
        wakeWordDetector.stopListening()
        _currentMode.value = OrchestratorMode.VERIFYING_KEYWORD
        _assistantState.value = VoiceAssistantState.Listening
        speechRecognizerManager.startListening()

        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(KEYWORD_VERIFICATION_TIMEOUT_MS)
            if (_currentMode.value == OrchestratorMode.VERIFYING_KEYWORD) {
                startStandbyMode()
            }
        }
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
                        val partial = event.partialText.lowercase().trim()

                        if (_currentMode.value == OrchestratorMode.VERIFYING_KEYWORD) {
                            if (containsWakeWord(partial)) {
                                silenceJob?.cancel()
                                playWakeChime()
                                val clean = cleanWakeWord(event.partialText)
                                if (clean.isNotBlank()) {
                                    _currentMode.value = OrchestratorMode.LISTENING_USER_QUERY
                                    _assistantState.value = VoiceAssistantState.Recognizing(clean)
                                    _lastQuery.value = clean

                                    silenceJob = scope.launch {
                                        delay(SILENCE_AFTER_PARTIAL_MS)
                                        if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY) {
                                            speechRecognizerManager.stopListening()
                                            processUserQuery(clean)
                                        }
                                    }
                                } else {
                                    _currentMode.value = OrchestratorMode.LISTENING_USER_QUERY
                                    _assistantState.value = VoiceAssistantState.Listening
                                }
                            }
                        } else if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY ||
                            _currentMode.value == OrchestratorMode.CONTINUOUS_CONVERSATION) {

                            val clean = cleanWakeWord(event.partialText)
                            if (clean.isNotBlank()) {
                                _assistantState.value = VoiceAssistantState.Recognizing(clean)
                                _lastQuery.value = clean

                                silenceJob?.cancel()
                                silenceJob = scope.launch {
                                    delay(SILENCE_AFTER_PARTIAL_MS)
                                    if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY ||
                                        _currentMode.value == OrchestratorMode.CONTINUOUS_CONVERSATION) {
                                        speechRecognizerManager.stopListening()
                                        processUserQuery(clean)
                                    }
                                }
                            }
                        } else if (_currentMode.value == OrchestratorMode.AWAITING_CONFIRMATION) {
                            handleConfirmationInput(partial)
                        }
                    }

                    is SpeechRecognitionEvent.FinalResult -> {
                        val finalText = event.recognizedText.trim()
                        silenceJob?.cancel()

                        when (_currentMode.value) {
                            OrchestratorMode.VERIFYING_KEYWORD -> {
                                if (containsWakeWord(finalText)) {
                                    playWakeChime()
                                    val clean = cleanWakeWord(finalText)
                                    if (clean.isNotBlank()) {
                                        processUserQuery(clean)
                                    } else {
                                        switchToSpeechRecognition()
                                    }
                                } else {
                                    startStandbyMode()
                                }
                            }
                            OrchestratorMode.LISTENING_USER_QUERY,
                            OrchestratorMode.CONTINUOUS_CONVERSATION -> {
                                val clean = cleanWakeWord(finalText)
                                if (clean.isNotBlank()) {
                                    processUserQuery(clean)
                                } else {
                                    startStandbyMode()
                                }
                            }
                            OrchestratorMode.AWAITING_CONFIRMATION -> {
                                handleConfirmationInput(finalText)
                            }
                            else -> Unit
                        }
                    }

                    is SpeechRecognitionEvent.RecognitionError -> {
                        Log.w(TAG, "Recognition error: ${event.errorCode}")
                        if (_currentMode.value != OrchestratorMode.TTS_SPEAKING &&
                            _currentMode.value != OrchestratorMode.AI_THINKING) {
                            startStandbyMode()
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun observeTtsEngine() {
        scope.launch {
            textToSpeechManager.ttsState.collectLatest { state ->
                when (state) {
                    TtsState.Speaking -> {
                        _currentMode.value = OrchestratorMode.TTS_SPEAKING
                        _assistantState.value = VoiceAssistantState.Speaking
                    }
                    TtsState.Done, TtsState.Error -> {
                        if (_currentMode.value == OrchestratorMode.TTS_SPEAKING) {
                            startFollowUpWindow()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Окно для продолжения диалога без повторения wake word (8 секунд)
     */
    private fun startFollowUpWindow() {
        followUpWindowJob?.cancel()
        _currentMode.value = OrchestratorMode.CONTINUOUS_CONVERSATION
        _assistantState.value = VoiceAssistantState.Listening
        speechRecognizerManager.startListening()

        followUpWindowJob = scope.launch {
            delay(FOLLOW_UP_WINDOW_MS)
            if (_currentMode.value == OrchestratorMode.CONTINUOUS_CONVERSATION) {
                startStandbyMode()
            }
        }
    }

    private fun processUserQuery(query: String) {
        if (isProcessingQuery.getAndSet(true)) return

        followUpWindowJob?.cancel()
        _currentMode.value = OrchestratorMode.AI_THINKING
        _assistantState.value = VoiceAssistantState.Processing
        _lastQuery.value = query

        aiJob?.cancel()
        aiJob = scope.launch {
            try {
                val result = sendPromptUseCase(query)
                handlePromptResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing query", e)
                speakAndReturn("Произошла ошибка при обработке запроса, сэр.")
            } finally {
                isProcessingQuery.set(false)
            }
        }
    }

    private suspend fun handlePromptResult(result: Resource<PromptExecutionResult>) {
        when (result) {
            is Resource.Success -> {
                when (val execResult = result.data) {
                    is PromptExecutionResult.DirectAnswer -> {
                        _lastAnswer.value = execResult.text
                        textToSpeechManager.speak(execResult.text, speechRate, speechPitch)
                    }
                    is PromptExecutionResult.ConfirmationRequired -> {
                        pendingToolCall = execResult.toolCall
                        pendingConfirmationPrompt = execResult.promptMessage
                        enterConfirmationMode(execResult.promptMessage)
                    }
                }
            }
            is Resource.Error -> {
                val errorMsg = result.message ?: "Ошибка выполнения"
                _lastAnswer.value = errorMsg
                speakAndReturn(errorMsg)
            }
            is Resource.Loading -> Unit
        }
    }

    private fun enterConfirmationMode(prompt: String) {
        _currentMode.value = OrchestratorMode.AWAITING_CONFIRMATION
        _assistantState.value = VoiceAssistantState.AwaitingConfirmation(prompt)
        textToSpeechManager.speak(prompt, speechRate, speechPitch)

        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = scope.launch {
            delay(CONFIRMATION_TIMEOUT_MS)
            if (_currentMode.value == OrchestratorMode.AWAITING_CONFIRMATION) {
                speakAndReturn("Подтверждение не получено. Отменяю действие, сэр.")
                pendingToolCall = null
                toolExecutor.clearPendingConfirmation()
            }
        }
    }

    private fun handleConfirmationInput(input: String) {
        val lower = input.lowercase().trim()
        val isConfirmed = lower.contains("да") || lower.contains("подтвержда") ||
                lower.contains("yes") || lower.contains("ок") || lower.contains("конечно") ||
                lower.contains("давай") || lower.contains("выполняй") || lower.contains("делай")

        val isDenied = lower.contains("нет") || lower.contains("отмен") ||
                lower.contains("no") || lower.contains("стоп") || lower.contains("не надо") ||
                lower.contains("отказ")

        confirmationTimeoutJob?.cancel()

        if (isConfirmed && pendingToolCall != null) {
            scope.launch {
                _currentMode.value = OrchestratorMode.AI_THINKING
                _assistantState.value = VoiceAssistantState.Processing
                val execResult = toolExecutor.executeWithBypass(pendingToolCall!!)
                pendingToolCall = null

                val response = if (execResult.isSuccess) {
                    "${execResult.summary}, сэр."
                } else {
                    "Не удалось выполнить: ${execResult.summary}"
                }
                _lastAnswer.value = response
                textToSpeechManager.speak(response, speechRate, speechPitch)
            }
        } else if (isDenied) {
            pendingToolCall = null
            toolExecutor.clearPendingConfirmation()
            speakAndReturn("Действие отменено, сэр.")
        }
    }

    private fun speakAndReturn(text: String) {
        _lastAnswer.value = text
        textToSpeechManager.speak(text, speechRate, speechPitch)
    }

    private fun containsWakeWord(text: String): Boolean {
        val lower = text.lowercase()
        return wakeKeywords.any { lower.contains(it) }
    }

    private fun cleanWakeWord(text: String): String {
        var cleaned = text.lowercase().trim()
        for (kw in wakeKeywords) {
            cleaned = cleaned.replace(Regex("^$kw[,\\s]*"), "")
        }
        return cleaned.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun playWakeChime() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        } catch (_: Exception) { }
    }

    private fun stopAll() {
        aiJob?.cancel()
        silenceJob?.cancel()
        followUpWindowJob?.cancel()
        confirmationTimeoutJob?.cancel()
        wakeWordDetector.stopListening()
        speechRecognizerManager.stopListening()
        textToSpeechManager.stop()
    }

    fun pauseForPhoneCall() {
        stopAll()
        _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
        _assistantState.value = VoiceAssistantState.Idle
    }

    fun resumeAfterPhoneCall() {
        if (isServiceActive) {
            startStandbyMode()
        }
    }

    /**
     * Полное освобождение ресурсов. Вызывается из Service.onDestroy()
     */
    fun destroy() {
        stopAll()
        orchestratorJob.cancel()
        wakeWordDetector.destroy()
        textToSpeechManager.shutdown()
        toneGenerator?.release()
        toneGenerator = null
    }
}
