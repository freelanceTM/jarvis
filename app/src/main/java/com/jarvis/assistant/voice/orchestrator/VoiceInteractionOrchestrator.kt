package com.jarvis.assistant.voice.orchestrator

import com.jarvis.assistant.R
import android.content.Context
import android.media.ToneGenerator
import android.media.AudioManager
import android.util.Log
import com.jarvis.assistant.agent.decision.PrivacyClassification
import com.jarvis.assistant.agent.decision.PrivacyClassifier
import com.jarvis.assistant.agent.decision.PrivacyContent
import com.jarvis.assistant.agent.decision.PrivacyReason
import com.jarvis.assistant.agent.decision.PrivacyLevel
import com.jarvis.assistant.agent.decision.RequestSource
import com.jarvis.assistant.agent.executor.ConfirmationOwner
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.translator.LiveTranslatorEngine
import com.jarvis.assistant.core.confirmation.ConfirmationIntent
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.PromptExecutionResult
import com.jarvis.assistant.domain.models.VoiceAssistantState
import com.jarvis.assistant.domain.repository.MessageRepository
import com.jarvis.assistant.domain.usecases.GetSettingsUseCase
import com.jarvis.assistant.domain.usecases.SendPromptUseCase
import com.jarvis.assistant.voice.audio.BluetoothAudioRouter
import com.jarvis.assistant.voice.stt.SpeechRecognitionEvent
import com.jarvis.assistant.voice.stt.SpeechRecognizerManager
import com.jarvis.assistant.voice.tts.TextToSpeechManager
import com.jarvis.assistant.voice.tts.TtsState
import com.jarvis.assistant.voice.wakeword.WakeWordDetector
import com.jarvis.assistant.voice.wakeword.WakeWordEvent
import com.jarvis.assistant.voice.wakeword.WakeWordExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

enum class OrchestratorMode {
    STANDBY_WAKE_WORD,        // Ожидание «Джарвис»
    VERIFYING_KEYWORD,        // Верификация ключевого слова (anti-false-trigger)
    LISTENING_USER_QUERY,     // Запись голоса
    CONTINUOUS_CONVERSATION,  // Диалоговое окно (без повтора «Джарвис»)
    AI_THINKING,              // Запрос AI / Fast Router
    TTS_SPEAKING,             // Озвучивание ответа
    AWAITING_CONFIRMATION,    // Ожидание голосового подтверждения действия (Да/Нет)
    AWAITING_PRIVACY_CONSENT, // C-02: ожидание согласия на отправку приватного запроса в облако
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
    private val toolExecutor: ToolExecutor,
    private val translatorEngine: LiveTranslatorEngine,
    private val messageRepository: MessageRepository
) {
    companion object {
        private const val TAG = "VoiceOrchestrator"
        
        private const val KEYWORD_VERIFICATION_TIMEOUT_MS = 3000L
        private const val SILENCE_AFTER_PARTIAL_MS = 1200L
        private const val FOLLOW_UP_WINDOW_MS = 8000L
        private const val CONFIRMATION_TIMEOUT_MS = 10000L

        /**
         * CR-01: режимы, в которых FinalResult speech-распознавателя ДОЛЖЕН
         * обрабатываться. В STANDBY/AI_THINKING/TTS_SPEAKING/PAUSED финальный
         * результат игнорируется — он обычно является эхом/остаточным callback
         * от ПРЕДЫДУЩЕГО распознавания и приводит к двойной обработке команд,
         * «оживающим» ответам и несанкционированным AI/TTS вызовам.
         */
        private val ALLOWED_FINAL_RESULT_MODES = setOf(
            OrchestratorMode.VERIFYING_KEYWORD,
            OrchestratorMode.LISTENING_USER_QUERY,
            OrchestratorMode.CONTINUOUS_CONVERSATION,
            OrchestratorMode.AWAITING_CONFIRMATION,
            OrchestratorMode.AWAITING_PRIVACY_CONSENT, // C-02: ловим «да/нет» в ответ на cloud-consent
            OrchestratorMode.LIVE_EAR_INTERPRETER
        )
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

    private val _privacyClassification = MutableStateFlow(
        PrivacyClassification.unknown(PrivacyReason.NOT_CLASSIFIED)
    )
    val privacyClassification: StateFlow<PrivacyClassification> =
        _privacyClassification.asStateFlow()

    private var toneGenerator: ToneGenerator? = null
    private var aiJob: Job? = null
    private var silenceJob: Job? = null
    private var followUpWindowJob: Job? = null
    private var confirmationTimeoutJob: Job? = null

    /** CR-22: актуальный job перевода в режиме LIVE_EAR_INTERPRETER. Новый перевод
     * отменяет предыдущий — «последний побеждает». Без этого параллельные
     * переводы могут завершиться в неправильном порядке и перекрыться в TTS. */
    private var translationJob: Job? = null

    private var isServiceActive = false

    private val isProcessingQuery = AtomicBoolean(false)

    /**
     * CR-07: поколение (эпоха) голосового сеанса. Стартует в 0, инкрементируется
     * при каждом входе в STANDBY. processUserQuery запоминает эпоху на старте и
     * отбрасывает любые поздние результаты, если эпоха успела смениться
     * (т.е. пользователь нажал «стоп» / перешёл в standby).
     */
    private val sessionEpoch = AtomicInteger(0)

    private var pendingToolCall: ToolCall? = null
    private var pendingConfirmationPrompt: String = ""

    /** Одноразовый токен подтверждения (пункт аудита #5). */
    private var pendingConfirmationToken: String? = null

    /**
     * C-02: ожидание голосового ответа на privacy-consent вопрос
     * («отправить в облако?»). Отдельное поле, а не часть pendingToolCall —
     * в этом состоянии голосовой оркестратор НЕ вызывает ToolExecutor,
     * а должен повторно вызвать sendPromptUseCase с cloudExplicitlyAllowed=true.
     */
    private var pendingCloudConsentQuery: String? = null
    private var pendingCloudConsentLevel: PrivacyLevel = PrivacyLevel.UNKNOWN
    private var pendingCloudConsentCaptureEpoch: Int = 0

    private var speechRate = 1.05f
    private var speechPitch = 0.90f
    private var systemPrompt = ""
    private var isHeadsetOnlyMode = false

    private val wakeKeywords = WakeWordExtractor.DEFAULT_WAKE_WORDS

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
        // H-04: защита от двойного/повторного старта при прямом вызове
        // (например из ManualWakeWordTrigger до того, как сервис поднялся,
        // или при race между resumeAfterPhoneCall и onServiceConnected).
        if (isServiceActive) {
            Log.d(TAG, "startServicePipeline: already active — skip")
            return
        }

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
                systemPrompt = settings.systemPrompt
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

        // CR-07: отмена активного AI-запроса при уходе в STANDBY. Инкрементируем
        // эпоху — старый aiJob, даже если он вернётся после IO, не сможет
        // мутировать UI/TTS состояние (см. проверку captureEpoch ниже).
        sessionEpoch.incrementAndGet()
        aiJob?.cancel()
        aiJob = null
        pendingToolCall = null
        pendingConfirmationToken = null
        pendingConfirmationPrompt = ""
        // C-02: при уходе в standby сбрасываем и privacy-consent.
        pendingCloudConsentQuery = null
        pendingCloudConsentLevel = PrivacyLevel.UNKNOWN
        pendingCloudConsentCaptureEpoch = 0
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
        // CR-22: на всякий случай сбрасываем предыдущий перевод, если кто-то
        // вошёл в переводчик, пока старый ещё жив.
        translationJob?.cancel()
        translationJob = null
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
                            if (WakeWordExtractor.containsWakeWord(partial, wakeKeywords)) {
                                silenceJob?.cancel()
                                playWakeChime()
                                val query = WakeWordExtractor.extractQuery(event.partialText, wakeKeywords)
                                if (query != null) {
                                    _currentMode.value = OrchestratorMode.LISTENING_USER_QUERY
                                    _assistantState.value = VoiceAssistantState.Recognizing(query)
                                    _lastQuery.value = query

                                    silenceJob = scope.launch {
                                        delay(SILENCE_AFTER_PARTIAL_MS)
                                        if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY) {
                                            speechRecognizerManager.stopListening()
                                            processUserQuery(query)
                                        }
                                    }
                                } else {
                                    // CR-02: прозвучало ТОЛЬКО wake-word — переходим в LISTENING
                                    // за командой, не запуская processUserQuery с пустым текстом.
                                    _currentMode.value = OrchestratorMode.LISTENING_USER_QUERY
                                    _assistantState.value = VoiceAssistantState.Listening
                                }
                            }
                        } else if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY ||
                            _currentMode.value == OrchestratorMode.CONTINUOUS_CONVERSATION) {
                            val cleaned = WakeWordExtractor.extractQuery(event.partialText, wakeKeywords) ?: event.partialText
                            _assistantState.value = VoiceAssistantState.Recognizing(event.partialText)
                            _lastQuery.value = cleaned

                            silenceJob?.cancel()
                            silenceJob = scope.launch {
                                delay(SILENCE_AFTER_PARTIAL_MS)
                                val current = _currentMode.value
                                if ((current == OrchestratorMode.LISTENING_USER_QUERY || current == OrchestratorMode.CONTINUOUS_CONVERSATION) && cleaned.isNotBlank()) {
                                    speechRecognizerManager.stopListening()
                                    processUserQuery(cleaned)
                                }
                            }
                        }
                    }
                    is SpeechRecognitionEvent.FinalResult -> {
                        // CR-01: игнорируем FinalResult, если мы не в слушающем режиме
                        // (STANDBY/AI_THINKING/TTS_SPEAKING/PAUSED). Приходит от старого
                        // распознавания после перехода на другой режим и провоцирует
                        // двойную обработку.
                        if (_currentMode.value !in ALLOWED_FINAL_RESULT_MODES) {
                            Log.d(TAG, "FinalResult ignored in mode=${_currentMode.value} (CR-01 guard)")
                            return@collectLatest
                        }

                        silenceJob?.cancel()
                        followUpWindowJob?.cancel()
                        val text = event.recognizedText.trim()

                        if (text.lowercase() in listOf("стоп", "хватит", "отмена", "джарвис стоп", "выйти")) {
                            handleCancel()
                            return@collectLatest
                        }

                        // 🎧 НЕПРЕРЫВНЫЙ СИНХРОННЫЙ ПЕРЕВОДЧИК В УХО (Full-Duplex Continuous Stream)
                        if (_currentMode.value == OrchestratorMode.LIVE_EAR_INTERPRETER) {
                            _privacyClassification.value =
                                PrivacyClassifier.classifySafely(PrivacyContent(text))
                            // CR-22: сериализация «последний побеждает».
                            // Отменяем предыдущий in-flight перевод (и его TTS),
                            // чтобы не озвучивать устаревшую фразу собеседника
                            // поверх актуальной. Без этого два близких FinalResult
                            // (например, после длинной паузы) порождали параллельные
                            // translateStructured, которые могли завершиться в
                            // любом порядке.
                            translationJob?.cancel()
                            val captureEpoch = sessionEpoch.get()
                            translationJob = scope.launch(Dispatchers.IO) {
                                val result = translatorEngine.translateStructured(
                                    text = text,
                                    sourceLang = "auto",
                                    targetLang = "ru"
                                )
                                // Устарело — пользователь ушёл в другой режим
                                // или сервис остановлен — не озвучиваем.
                                if (_currentMode.value != OrchestratorMode.LIVE_EAR_INTERPRETER ||
                                    sessionEpoch.get() != captureEpoch
                                ) return@launch
                                val spoken = translatorEngine.describeFailure(result)
                                _lastAnswer.value = spoken
                                bluetoothAudioRouter.routeAudioToEarbud()
                                // Прерываем предыдущий TTS тоже (FLUSH, а не ADD).
                                textToSpeechManager.speak(spoken, speechRate, speechPitch)
                            }
                            return@collectLatest
                        }

                        if (_currentMode.value == OrchestratorMode.AWAITING_PRIVACY_CONSENT) {
                            handlePrivacyConsentResponse(text)
                            return@collectLatest
                        }

                        if (_currentMode.value == OrchestratorMode.AWAITING_CONFIRMATION) {
                            handleConfirmationResponse(text)
                            return@collectLatest
                        }

                        if (_currentMode.value == OrchestratorMode.VERIFYING_KEYWORD) {
                            if (WakeWordExtractor.containsWakeWord(text, wakeKeywords)) {
                                playWakeChime()
                                // CR-02: extractQuery вернёт null, если после wake-word ничего нет
                                // → переключаемся на LISTENING_USER_QUERY вместо processUserQuery("Джарвис").
                                val query = WakeWordExtractor.extractQuery(text, wakeKeywords)
                                if (query != null) {
                                    processUserQuery(query)
                                } else {
                                    switchToSpeechRecognition()
                                }
                            } else {
                                startStandbyMode()
                            }
                            return@collectLatest
                        }

                        // CR-02: вместо чистки с ifEmpty { raw } используем extractQuery,
                        // который возвращает null при пустом результате — не дёргаем
                        // processUserQuery с сырым wake-word.
                        val query = WakeWordExtractor.extractQuery(text, wakeKeywords)
                        if (query != null) {
                            processUserQuery(query)
                        } else {
                            // Прозвучало только имя ассистента — переходим в LISTENING
                            // за командой (без AI-вызова/TTS).
                            switchToSpeechRecognition()
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

    private fun processUserQuery(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) {
            _privacyClassification.value =
                PrivacyClassification.unknown(PrivacyReason.EMPTY_INPUT)
            startStandbyMode()
            return
        }
        // H-02: text-only быстрая метка для UI/логов (без systemPrompt/истории).
        // Полная контекстная классификация (prompt+systemPrompt+history) будет
        // сделана ровно один раз в SendPromptUseCase — мы не дублируем её здесь.
        val quickClassification = PrivacyClassifier.classifySafely(PrivacyContent(clean))
        _privacyClassification.value = quickClassification

        // Команда активации режима синхронного переводчика
        if (clean.lowercase().contains("переводчик") || clean.lowercase().contains("синхронный перевод") || clean.lowercase().contains("переводи собеседника")) {
            startLiveEarInterpreter()
            return
        }

        if (!isProcessingQuery.compareAndSet(false, true)) {
            Log.d(
                TAG,
                "Query already processing; duplicate skipped | chars=${clean.length} | " +
                    "privacy=${quickClassification.level}"
            )
            return
        }

        silenceJob?.cancel()
        followUpWindowJob?.cancel()
        confirmationTimeoutJob?.cancel()

        _currentMode.value = OrchestratorMode.AI_THINKING
        _assistantState.value = VoiceAssistantState.Thinking
        _lastQuery.value = clean

        aiJob?.cancel()
        val captureEpoch = sessionEpoch.get()
        aiJob = scope.launch {
            try {
                val history = messageRepository.getRecentMessages(limit = 10)

                // CR-07: если за время получения истории пользователь успел перейти в standby,
                // результат отбрасываем.
                if (sessionEpoch.get() != captureEpoch) {
                    Log.d(TAG, "Discarding late AI result (epoch mismatch: $captureEpoch vs ${sessionEpoch.get()})")
                    return@launch
                }

                // H-02: передаём quick-уровень как hint — authoritative classification
                // сделает SendPromptUseCase (с контекстом systemPrompt+history) и вернёт
                // NeedsConsent при необходимости; мы не дублируем classifySafely здесь.
                val result = sendPromptUseCase(
                    clean,
                    source = RequestSource.VOICE,
                    privacyLevel = quickClassification.level
                )

                // CR-07: вторая точка проверки эпохи — после сетевого/AI-вызова.
                if (sessionEpoch.get() != captureEpoch) {
                    Log.d(TAG, "Discarding late AI result post-sendPrompt (epoch mismatch)")
                    return@launch
                }

                when (result) {
                    is Resource.Success -> {
                        when (val execution = result.data) {
                            is PromptExecutionResult.ConfirmationRequired -> {
                                pendingToolCall = execution.toolCall
                                pendingConfirmationPrompt = execution.promptMessage
                                // CR-04: забираем вызов у CHAT_UI под управление голоса
                                // (чтобы «да» из TTS-потока не подтвердило чат-запрос),
                                // а токен берём ПО callId, а не с головы очереди.
                                toolExecutor.claimPendingConfirmation(
                                    execution.toolCall.callId,
                                    ConfirmationOwner.VOICE
                                )
                                pendingConfirmationToken =
                                    toolExecutor.confirmationTokenFor(execution.toolCall.callId)

                                _lastAnswer.value = pendingConfirmationPrompt
                                _currentMode.value = OrchestratorMode.AWAITING_CONFIRMATION
                                _assistantState.value = VoiceAssistantState.Speaking(pendingConfirmationPrompt)

                                textToSpeechManager.speak(pendingConfirmationPrompt, speechRate, speechPitch)

                                confirmationTimeoutJob?.cancel()
                                confirmationTimeoutJob = scope.launch {
                                    delay(CONFIRMATION_TIMEOUT_MS)
                                    // CR-07: таймер подтверждения тоже уважает эпоху.
                                    if (sessionEpoch.get() != captureEpoch) return@launch
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
                    // C-02: use case просит согласия на облачную отправку —
                    // переключаемся в режим ожидания голосового «да/нет».
                    is Resource.NeedsConsent -> {
                        pendingCloudConsentQuery = clean
                        pendingCloudConsentLevel = result.privacyLevel
                        pendingCloudConsentCaptureEpoch = captureEpoch

                        val voicePrompt = when (result.privacyLevel) {
                            PrivacyLevel.SENSITIVE -> context.getString(R.string.cloud_consent_voice_sensitive)
                            else -> context.getString(R.string.cloud_consent_voice_private)
                        }
                        _lastAnswer.value = voicePrompt
                        _currentMode.value = OrchestratorMode.AWAITING_PRIVACY_CONSENT
                        _assistantState.value = VoiceAssistantState.Speaking(voicePrompt)

                        textToSpeechManager.speak(voicePrompt, speechRate, speechPitch)

                        // Таймаут — как у tool-confirmation: N секунд и авто-отказ.
                        confirmationTimeoutJob?.cancel()
                        confirmationTimeoutJob = scope.launch {
                            delay(CONFIRMATION_TIMEOUT_MS)
                            if (sessionEpoch.get() != captureEpoch) return@launch
                            if (_currentMode.value == OrchestratorMode.AWAITING_PRIVACY_CONSENT) {
                                val timeoutMsg = context.getString(R.string.cloud_consent_timeout)
                                _lastAnswer.value = timeoutMsg
                                _assistantState.value = VoiceAssistantState.Speaking(timeoutMsg)
                                textToSpeechManager.speak(timeoutMsg, speechRate, speechPitch)
                                pendingCloudConsentQuery = null
                                pendingCloudConsentLevel = PrivacyLevel.UNKNOWN
                                pendingCloudConsentCaptureEpoch = 0
                                delay(2000)
                                startStandbyMode()
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
                // CR-07: флаг сбрасываем только для «нашей» эпохи, чтобы не
                // сломать флаг нового сеанса, запущенного поверх отмены.
                if (sessionEpoch.get() == captureEpoch) {
                    isProcessingQuery.set(false)
                }
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
            // N-02: захватываем pendingToolCall в локальную val ДО ветвления,
            // чтобы не использовать !! и не словить NPE между проверкой
            // `!= null` и smart-cast в многопоточном контексте.
            isYes -> {
                val callToExecute = pendingToolCall
                if (callToExecute == null) {
                    // Гонка: pending сбросили другим cancellation-path —
                    // возвращаемся в standby без краша.
                    startStandbyMode()
                    return
                }
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
                    // Голос забирает его себе (owner=VOICE) и токен берёт по callId.
                    toolExecutor.peekPendingConfirmation()?.let { head ->
                        toolExecutor.claimPendingConfirmation(head.toolCall.callId, ConfirmationOwner.VOICE)
                        val next = toolExecutor.findPendingConfirmation(head.toolCall.callId) ?: head
                        pendingToolCall = next.toolCall
                        pendingConfirmationPrompt = next.promptMessage
                        pendingConfirmationToken =
                            toolExecutor.confirmationTokenFor(next.toolCall.callId)
                        _currentMode.value = OrchestratorMode.AWAITING_CONFIRMATION
                        _assistantState.value = VoiceAssistantState.Speaking(next.promptMessage)
                        textToSpeechManager.speak(next.promptMessage, speechRate, speechPitch)
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

    /**
     * C-02: обработка голосового ответа «да/нет» на вопрос об отправке приватного
     * запроса в облако. Аналогично [handleConfirmationResponse], но вместо вызова
     * инструмента делает повторный вызов [SendPromptUseCase] с флагом
     * cloudExplicitlyAllowed=true.
     */
    private fun handlePrivacyConsentResponse(response: String) {
        confirmationTimeoutJob?.cancel()

        val isYes = ConfirmationIntent.isYes(response)
        val isNo = ConfirmationIntent.isNo(response)
        val consentQuery = pendingCloudConsentQuery
        val consentCaptureEpoch = pendingCloudConsentCaptureEpoch

        when {
            isYes -> {
                if (consentQuery == null) {
                    // Гонка — consent уже сброшен (таймаут / отмена / новая сессия).
                    startStandbyMode()
                    return
                }
                // Запоминаем уровень с пришедшего consent, чтобы не
                // пересчитывать классификатор на повторе (H-02), и сбрасываем
                // pending СРАЗУ перед запуском aiJob — чтобы гонка с новым
                // запросом не подхватила чужое состояние.
                val consentLevel = pendingCloudConsentLevel
                pendingCloudConsentQuery = null
                pendingCloudConsentLevel = PrivacyLevel.UNKNOWN
                pendingCloudConsentCaptureEpoch = 0

                _currentMode.value = OrchestratorMode.AI_THINKING
                _assistantState.value = VoiceAssistantState.Thinking

                aiJob?.cancel()

                aiJob = scope.launch {
                    // Важно: после «да» контекст/эпоха должны совпадать с той,
                    // в которой мы задавали вопрос. Если пользователь уже ушёл
                    // в standby — не выполняем ничего.
                    if (sessionEpoch.get() != consentCaptureEpoch) return@launch

                    // H-02: передаём сохранённый уровень как hint; authoritative
                    // classification с учётом systemPrompt+history будет сделана
                    // один раз в SendPromptUseCase.
                    val result = sendPromptUseCase(
                        userPrompt = consentQuery,
                        source = RequestSource.VOICE,
                        privacyLevel = consentLevel,
                        cloudExplicitlyAllowed = true
                    )
                    if (sessionEpoch.get() != consentCaptureEpoch) return@launch

                    when (result) {
                        is Resource.Success -> {
                            when (val execution = result.data) {
                                is PromptExecutionResult.ConfirmationRequired -> {
                                    // Может потребоваться подтверждение действия (звонок/SMS)
                                    // — делегируем в существующий confirmation-flow.
                                    pendingToolCall = execution.toolCall
                                    pendingConfirmationPrompt = execution.promptMessage
                                    toolExecutor.claimPendingConfirmation(
                                        execution.toolCall.callId,
                                        ConfirmationOwner.VOICE
                                    )
                                    pendingConfirmationToken =
                                        toolExecutor.confirmationTokenFor(execution.toolCall.callId)
                                    _lastAnswer.value = pendingConfirmationPrompt
                                    _currentMode.value = OrchestratorMode.AWAITING_CONFIRMATION
                                    _assistantState.value = VoiceAssistantState.Speaking(pendingConfirmationPrompt)
                                    textToSpeechManager.speak(pendingConfirmationPrompt, speechRate, speechPitch)
                                }
                                is PromptExecutionResult.DirectAnswer -> {
                                    _lastAnswer.value = execution.text
                                    _currentMode.value = OrchestratorMode.TTS_SPEAKING
                                    _assistantState.value = VoiceAssistantState.Speaking(execution.text)
                                    textToSpeechManager.speak(execution.text, speechRate, speechPitch)
                                }
                            }
                        }
                        is Resource.NeedsConsent -> {
                            // Не должно случиться (cloudExplicitlyAllowed=true),
                            // но fail-safe — возвращаемся в standby.
                            startStandbyMode()
                        }
                        is Resource.Error -> {
                            val err = result.message ?: context.getString(R.string.oshibka_svyazi_s_ai)
                            _lastAnswer.value = "Ошибка: $err"
                            _assistantState.value = VoiceAssistantState.Error(err)
                            textToSpeechManager.speak("Ошибка: $err", speechRate, speechPitch)
                            delay(2500)
                            startStandbyMode()
                        }
                        is Resource.Loading -> Unit
                    }
                }
            }
            isNo -> {
                pendingCloudConsentQuery = null
                pendingCloudConsentLevel = PrivacyLevel.UNKNOWN
                pendingCloudConsentCaptureEpoch = 0
                val declineMsg = context.getString(R.string.cloud_consent_declined)
                _lastAnswer.value = declineMsg
                _currentMode.value = OrchestratorMode.TTS_SPEAKING
                _assistantState.value = VoiceAssistantState.Speaking(declineMsg)
                textToSpeechManager.speak(declineMsg, speechRate, speechPitch)
            }
            else -> {
                val retryMsg = context.getString(R.string.ne_ponyal_skazhite_da_ili_net)
                _assistantState.value = VoiceAssistantState.Speaking(retryMsg)
                textToSpeechManager.speak(retryMsg, speechRate, speechPitch)

                confirmationTimeoutJob?.cancel()
                confirmationTimeoutJob = scope.launch {
                    delay(CONFIRMATION_TIMEOUT_MS)
                    if (sessionEpoch.get() != consentCaptureEpoch) return@launch
                    if (_currentMode.value == OrchestratorMode.AWAITING_PRIVACY_CONSENT) {
                        val timeoutMsg = context.getString(R.string.cloud_consent_timeout)
                        _lastAnswer.value = timeoutMsg
                        _assistantState.value = VoiceAssistantState.Speaking(timeoutMsg)
                        textToSpeechManager.speak(timeoutMsg, speechRate, speechPitch)
                        pendingCloudConsentQuery = null
                        pendingCloudConsentLevel = PrivacyLevel.UNKNOWN
                        pendingCloudConsentCaptureEpoch = 0
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
                            // C-02: после фразы «отправить в облако?» начинаем слушать ответ.
                            OrchestratorMode.AWAITING_PRIVACY_CONSENT -> {
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
        // CR-22: отменяем in-flight перевод при любом останове.
        translationJob?.cancel()
        translationJob = null

        sessionEpoch.incrementAndGet()
        aiJob?.cancel()
        aiJob = null
        pendingToolCall = null
        pendingConfirmationPrompt = ""
        pendingConfirmationToken = null
        toolExecutor.clearPendingConfirmation()
        isProcessingQuery.set(false)
        wakeWordDetector.stopListening()
        speechRecognizerManager.stopListening()
        textToSpeechManager.stop()
    }

    fun destroy() {
        stopAll()
        isServiceActive = false
        orchestratorJob.cancel()
        // CR-12: каскадный dispose. Порядок: сначала компоненты, которые могут
        // порождать новые вызовы (wake/STT/TTS), потом аудио-маршрутизация,
        // потом генератор тонов (самый низкоуровневый ресурс).
        runCatching { wakeWordDetector.destroy() }
            .onFailure { Log.e(TAG, "destroy: wakeWordDetector.destroy() failed", it) }
        runCatching { speechRecognizerManager.destroy() }
            .onFailure { Log.e(TAG, "destroy: speechRecognizerManager.destroy() failed", it) }
        runCatching { textToSpeechManager.shutdown() }
            .onFailure { Log.e(TAG, "destroy: textToSpeechManager.shutdown() failed", it) }
        runCatching { bluetoothAudioRouter.dispose() }
            .onFailure { Log.e(TAG, "destroy: bluetoothAudioRouter.dispose() failed", it) }
        try {
            toneGenerator?.release()
        } catch (e: Exception) {
            Log.e(TAG, "releaseToneGenerator: не удалось освободить ToneGenerator", e)
        }
        toneGenerator = null
    }
}
