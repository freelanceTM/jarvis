package com.jarvis.assistant.voice.orchestrator

import com.jarvis.assistant.R
import android.content.Context
import android.media.ToneGenerator
import android.media.AudioManager
import android.util.Log
import com.jarvis.assistant.agent.decision.RequestSource
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.translator.LiveTranslatorEngine
import com.jarvis.assistant.core.confirmation.ConfirmationIntent
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
    LIVE_EAR_INTERPRETER,     // Непрерывный синхронный перевод речи собеседника прямо в ухо
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
    private val toolExecutor: ToolExecutor,
    private val translatorEngine: LiveTranslatorEngine
) {
    companion object {
        private const val TAG = "VoiceOrchestrator"
        
        private const val KEYWORD_VERIFICATION_TIMEOUT_MS = 3000L
        private const val SILENCE_AFTER_PARTIAL_MS = 1200L
        private const val FOLLOW_UP_WINDOW_MS = 8000L
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

    /** Одноразовый токен подтверждения (пункт аудита #5). */
    private var pendingConfirmationToken: String? = null

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
        } catch (e: Exception) {
            Log.e(TAG, "initToneGenerator: не удалось инициализировать ToneGenerator", e)
        }
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
            _assistantState.value = VoiceAssistantState.Error(context.getString(R.string.podklyuchite_naushniki_dlya_raboty))
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
                            _assistantState.value = VoiceAssistantState.Error(context.getString(R.string.naushniki_otklyucheny_ozhidanie))
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

    fun startStandbyMode() {
        if (!isServiceActive) return

        if (isHeadsetOnlyMode && !bluetoothAudioRouter.isHeadsetConnected()) {
            _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
            _assistantState.value = VoiceAssistantState.Error(context.getString(R.string.podklyuchite_naushniki))
            return
        }

        followUpWindowJob?.cancel()
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null
        silenceJob?.cancel()
        silenceJob = null
        pendingToolCall = null
                                        pendingConfirmationToken = null
        toolExecutor.clearPendingConfirmation()
        isProcessingQuery.set(false)

        _currentMode.value = OrchestratorMode.STANDBY_WAKE_WORD
        _assistantState.value = VoiceAssistantState.Idle
        speechRecognizerManager.stopListening()
        wakeWordDetector.startListening()
    }

    fun startLiveEarInterpreter() {
        silenceJob?.cancel()
        followUpWindowJob?.cancel()
        confirmationTimeoutJob?.cancel()
        wakeWordDetector.stopListening()

        _currentMode.value = OrchestratorMode.LIVE_EAR_INTERPRETER
        val msg = context.getString(R.string.rezhim_perevodchika_aktivirovan)
        _assistantState.value = VoiceAssistantState.Speaking(msg)
        bluetoothAudioRouter.routeAudioToEarbud()
        textToSpeechManager.speak(msg, speechRate, speechPitch)
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
                            _assistantState.value = VoiceAssistantState.Recognizing(event.partialText)
                            _lastQuery.value = cleanWakeWord(event.partialText)

                            silenceJob?.cancel()
                            silenceJob = scope.launch {
                                delay(SILENCE_AFTER_PARTIAL_MS)
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
                        followUpWindowJob?.cancel()
                        val text = event.recognizedText.trim()

                        if (text.lowercase() in listOf("стоп", "хватит", "отмена", "джарвис стоп", "выйти")) {
                            handleCancel()
                            return@collectLatest
                        }

                        // 🎧 НЕПРЕРЫВНЫЙ СИНХРОННЫЙ ПЕРЕВОДЧИК В УХО (Full-Duplex Continuous Stream)
                        if (_currentMode.value == OrchestratorMode.LIVE_EAR_INTERPRETER) {
                            scope.launch(Dispatchers.IO) {
                                // Если перевод не выполнен, озвучиваем причину,
                                // а НЕ исходную фразу под видом перевода.
                                val result = translatorEngine.translateStructured(
                                    text = text,
                                    sourceLang = "auto",
                                    targetLang = "ru"
                                )
                                val spoken = translatorEngine.describeFailure(result)
                                _lastAnswer.value = spoken
                                bluetoothAudioRouter.routeAudioToEarbud()
                                textToSpeechManager.speakQueued(spoken, speechRate, speechPitch)
                            }
                            return@collectLatest
                        }

                        if (_currentMode.value == OrchestratorMode.AWAITING_CONFIRMATION) {
                            handleConfirmationResponse(text)
                            return@collectLatest
                        }

                        if (_currentMode.value == OrchestratorMode.VERIFYING_KEYWORD) {
                            if (containsWakeWord(text)) {
                                playWakeChime()
                                val clean = cleanWakeWord(text)
                                if (clean.isNotBlank()) {
                                    processUserQuery(clean)
                                } else {
                                    switchToSpeechRecognition()
                                }
                            } else {
                                startStandbyMode()
                            }
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
                        if (_currentMode.value == OrchestratorMode.LIVE_EAR_INTERPRETER) {
                            // В режиме переводчика продолжаем слушать дальше
                            delay(500)
                            if (_currentMode.value == OrchestratorMode.LIVE_EAR_INTERPRETER) {
                                speechRecognizerManager.startListening()
                            }
                        } else if (_currentMode.value == OrchestratorMode.VERIFYING_KEYWORD ||
                            _currentMode.value == OrchestratorMode.LISTENING_USER_QUERY ||
                            _currentMode.value == OrchestratorMode.CONTINUOUS_CONVERSATION) {
                            delay(300)
                            startStandbyMode()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun containsWakeWord(text: String): Boolean {
        val lower = text.lowercase().trim()
        return wakeKeywords.any { kw -> lower.contains(kw) }
    }

    private fun cleanWakeWord(raw: String): String {
        var result = raw
        for (kw in wakeKeywords) {
            result = result.replace(Regex("(?i)^.*?$kw[,\\s]*"), "").trim()
        }
        return result.ifEmpty { raw }
    }

    private fun processUserQuery(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) {
            startStandbyMode()
            return
        }

        // Команда активации режима синхронного переводчика
        if (clean.lowercase().contains("переводчик") || clean.lowercase().contains("синхронный перевод") || clean.lowercase().contains("переводи собеседника")) {
            startLiveEarInterpreter()
            return
        }

        if (!isProcessingQuery.compareAndSet(false, true)) {
            Log.d(TAG, "Query '$clean' already processing, skipping duplicate dispatch.")
            return
        }

        silenceJob?.cancel()
        followUpWindowJob?.cancel()
        confirmationTimeoutJob?.cancel()

        _currentMode.value = OrchestratorMode.AI_THINKING
        _assistantState.value = VoiceAssistantState.Thinking
        _lastQuery.value = clean

        aiJob?.cancel()
        aiJob = scope.launch {
            try {
                // Этап 1: источник запроса передаётся в ExecutionDecisionEngine.
                val result = sendPromptUseCase(clean, source = RequestSource.VOICE)
                when (result) {
                    is Resource.Success -> {
                        when (val execution = result.data) {
                            is PromptExecutionResult.ConfirmationRequired -> {
                                pendingToolCall = execution.toolCall
                                pendingConfirmationPrompt = execution.promptMessage
                                pendingConfirmationToken =
                                    toolExecutor.peekPendingConfirmation()?.confirmationToken

                                _lastAnswer.value = pendingConfirmationPrompt
                                _currentMode.value = OrchestratorMode.AWAITING_CONFIRMATION
                                _assistantState.value = VoiceAssistantState.Speaking(pendingConfirmationPrompt)

                                textToSpeechManager.speak(pendingConfirmationPrompt, speechRate, speechPitch)

                                confirmationTimeoutJob?.cancel()
                                confirmationTimeoutJob = scope.launch {
                                    delay(CONFIRMATION_TIMEOUT_MS)
                                    if (_currentMode.value == OrchestratorMode.AWAITING_CONFIRMATION) {
                                        val timeoutMsg = context.getString(R.string.vremya_ozhidaniya_isteklo)
                                        _lastAnswer.value = timeoutMsg
                                        _assistantState.value = VoiceAssistantState.Speaking(timeoutMsg)
                                        textToSpeechManager.speak(timeoutMsg, speechRate, speechPitch)
                                        val timedOut = pendingToolCall
                                        pendingToolCall = null
                                        pendingConfirmationToken = null
                                        if (timedOut != null) toolExecutor.removePendingConfirmation(timedOut)
                                        delay(2000)
                                        startStandbyMode()
                                    }
                                }
                            }
                            is PromptExecutionResult.DirectAnswer -> {
                                val answer = execution.text
                                _lastAnswer.value = answer
                                _currentMode.value = OrchestratorMode.TTS_SPEAKING
                                _assistantState.value = VoiceAssistantState.Speaking(answer)
                                textToSpeechManager.speak(answer, speechRate, speechPitch)
                            }
                        }
                    }
                    is Resource.Error -> {
                        val errorMsg = result.message ?: context.getString(R.string.oshibka_svyazi_s_ai)
                        _lastAnswer.value = "Ошибка: $errorMsg"
                        _currentMode.value = OrchestratorMode.STANDBY_WAKE_WORD
                        _assistantState.value = VoiceAssistantState.Error(errorMsg)
                        textToSpeechManager.speak("Ошибка: $errorMsg", speechRate, speechPitch)
                        delay(2500)
                        startStandbyMode()
                    }
                    is Resource.Loading -> {
                        _assistantState.value = VoiceAssistantState.Thinking
                    }
                }
            } finally {
                isProcessingQuery.set(false)
            }
        }
    }

    private fun handleConfirmationResponse(response: String) {
        confirmationTimeoutJob?.cancel()

        // Единый источник распознавания «да/нет» — ConfirmationIntent
        // (общий для голосового флоу и текстового чата).
        val isYes = ConfirmationIntent.isYes(response)
        val isNo = ConfirmationIntent.isNo(response)

        when {
            isYes && pendingToolCall != null -> {
                val callToExecute = pendingToolCall!!
                // Пункт аудита #5: сохраняем токен ДО сброса состояния.
                val tokenToUse = pendingConfirmationToken
                pendingToolCall = null
                pendingConfirmationToken = null
                // Пункт аудита #4: НЕ чистим всю очередь — executeWithBypass
                // извлечёт из неё именно этот вызов, остальные останутся ждать.

                _currentMode.value = OrchestratorMode.AI_THINKING
                _assistantState.value = VoiceAssistantState.Thinking

                scope.launch {
                    val result = toolExecutor.executeWithBypass(
                        call = callToExecute,
                        confirmationToken = tokenToUse,
                        source = "voice_orchestrator"
                    )
                    val voiceResponse = when {
                        result.isSuccess -> "${result.summary}, сэр."
                        // Разрешение/системный UI/неподдерживаемая возможность —
                        // это не «ошибка выполнения», а понятное объяснение.
                        result.isBlockedByAndroid -> result.summary
                        else -> "Не удалось выполнить: ${result.error ?: result.summary}"
                    }
                    _lastAnswer.value = voiceResponse
                    _currentMode.value = OrchestratorMode.TTS_SPEAKING
                    _assistantState.value = VoiceAssistantState.Speaking(voiceResponse)
                    textToSpeechManager.speak(voiceResponse, speechRate, speechPitch)

                    // Пункт аудита #4: следующий запрос подтверждения из очереди.
                    toolExecutor.peekPendingConfirmation()?.let { next ->
                        pendingToolCall = next.toolCall
                        pendingConfirmationPrompt = next.promptMessage
                        pendingConfirmationToken = next.confirmationToken
                        _currentMode.value = OrchestratorMode.AWAITING_CONFIRMATION
                    }
                }
            }
            isNo -> {
                val cancelled = pendingToolCall
                pendingToolCall = null
                                        pendingConfirmationToken = null
                if (cancelled != null) toolExecutor.removePendingConfirmation(cancelled)
                val cancelMsg = context.getString(R.string.operaciya_otmenena_sir)
                _lastAnswer.value = cancelMsg
                _currentMode.value = OrchestratorMode.TTS_SPEAKING
                _assistantState.value = VoiceAssistantState.Speaking(cancelMsg)
                textToSpeechManager.speak(cancelMsg, speechRate, speechPitch)
            }
            else -> {
                val retryMsg = context.getString(R.string.ne_ponyal_skazhite_da_ili_net)
                _assistantState.value = VoiceAssistantState.Speaking(retryMsg)
                textToSpeechManager.speak(retryMsg, speechRate, speechPitch)

                confirmationTimeoutJob?.cancel()
                confirmationTimeoutJob = scope.launch {
                    delay(CONFIRMATION_TIMEOUT_MS)
                    if (_currentMode.value == OrchestratorMode.AWAITING_CONFIRMATION) {
                        val timeoutMsg = context.getString(R.string.vremya_ozhidaniya_isteklo)
                        _lastAnswer.value = timeoutMsg
                        _assistantState.value = VoiceAssistantState.Speaking(timeoutMsg)
                        textToSpeechManager.speak(timeoutMsg, speechRate, speechPitch)
                        val timedOut = pendingToolCall
                        pendingToolCall = null
                                        pendingConfirmationToken = null
                        if (timedOut != null) toolExecutor.removePendingConfirmation(timedOut)
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
                    is TtsState.Done -> {
                        when (_currentMode.value) {
                            OrchestratorMode.TTS_SPEAKING -> {
                                openContinuousConversationWindow()
                            }
                            OrchestratorMode.AWAITING_CONFIRMATION -> {
                                wakeWordDetector.stopListening()
                                speechRecognizerManager.startListening()
                            }
                            OrchestratorMode.LIVE_EAR_INTERPRETER -> {
                                // После нашёптывания перевода в ухо мгновенно продолжаем слушать собеседника!
                                wakeWordDetector.stopListening()
                                speechRecognizerManager.startListening()
                            }
                            else -> Unit
                        }
                    }
                    is TtsState.Error -> {
                        if (_currentMode.value != OrchestratorMode.LIVE_EAR_INTERPRETER) {
                            startStandbyMode()
                        }
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
            delay(FOLLOW_UP_WINDOW_MS)
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
                                        pendingConfirmationToken = null
        toolExecutor.clearPendingConfirmation()
        isProcessingQuery.set(false)
        aiJob?.cancel()
        aiJob = null
        textToSpeechManager.stop()
        playCancelChime()
        startStandbyMode()
    }

    private fun playWakeChime() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        } catch (e: Exception) {
            Log.e(TAG, "playWakeChime: не удалось проиграть звук", e)
        }
    }

    private fun playCancelChime() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 70)
        } catch (e: Exception) {
            Log.e(TAG, "playCancelChime: не удалось проиграть звук", e)
        }
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
                                        pendingConfirmationToken = null
        toolExecutor.clearPendingConfirmation()
        isProcessingQuery.set(false)
        aiJob?.cancel()
        aiJob = null
        wakeWordDetector.stopListening()
        speechRecognizerManager.stopListening()
        textToSpeechManager.stop()
    }

    fun destroy() {
        stopAll()
        isServiceActive = false
        orchestratorJob.cancel()
        try {
            toneGenerator?.release()
        } catch (e: Exception) {
            Log.e(TAG, "releaseToneGenerator: не удалось освободить ToneGenerator", e)
        }
        toneGenerator = null
    }
}
