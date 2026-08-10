package com.jarvis.assistant.voice.orchestrator

import android.content.Context
import android.media.ToneGenerator
import android.media.AudioManager
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
import javax.inject.Inject
import javax.inject.Singleton

enum class OrchestratorMode {
    STANDBY_WAKE_WORD,     // Ожидание "Джарвис"
    LISTENING_USER_QUERY,  // Активная запись и STT
    AI_THINKING,           // Запрос в AI
    TTS_SPEAKING,          // Озвучивание с поддержкой прерывания "Стоп"
    PAUSED_CALL_OR_SLEEP   // Пауза (звонок / энергосбережение)
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
    private val securityManager: SecurityManager
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
    private var inactivityTimerJob: Job? = null

    private var speechRate = 1.0f
    private var speechPitch = 1.0f
    private var isServiceActive = false

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (_: Exception) { }

        observeSettings()
        observeWakeWord()
        observeSpeechRecognition()
        observeTts()
    }

    fun startServicePipeline() {
        isServiceActive = true
        bluetoothAudioRouter.routeAudioToEarbud()
        resetInactivityTimer()
        startWakeWordListening()
    }

    fun stopServicePipeline() {
        isServiceActive = false
        stopAll()
        _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
        _assistantState.value = VoiceAssistantState.Idle
    }

    private fun observeSettings() {
        scope.launch {
            getSettingsUseCase().collectLatest { settings ->
                speechRate = settings.speechRate
                speechPitch = settings.speechPitch
            }
        }
    }

    private fun observeWakeWord() {
        scope.launch {
            wakeWordDetector.events.collectLatest { event ->
                when (event) {
                    is WakeWordEvent.WakeWordDetected -> {
                        if (_currentMode.value == OrchestratorMode.STANDBY_WAKE_WORD) {
                            handleWakeWordTriggered()
                        }
                    }
                    is WakeWordEvent.InterruptDetected -> {
                        if (_currentMode.value == OrchestratorMode.TTS_SPEAKING ||
                            _currentMode.value == OrchestratorMode.AI_THINKING) {
                            handleBargeInInterrupt()
                        }
                    }
                    is WakeWordEvent.VoiceLevelChanged -> Unit
                }
            }
        }
    }

    private fun handleWakeWordTriggered() {
        resetInactivityTimer()
        playWakeChime()
        _currentMode.value = OrchestratorMode.LISTENING_USER_QUERY
        _assistantState.value = VoiceAssistantState.Listening

        wakeWordDetector.stopListening()
        speechRecognizerManager.startListening()
    }

    private fun handleBargeInInterrupt() {
        aiJob?.cancel()
        aiJob = null
        textToSpeechManager.stop()
        playCancelChime()

        _assistantState.value = VoiceAssistantState.Idle
        _currentMode.value = OrchestratorMode.STANDBY_WAKE_WORD
        startWakeWordListening()
    }

    private fun observeSpeechRecognition() {
        scope.launch {
            speechRecognizerManager.speechState.collectLatest { event ->
                when (event) {
                    is SpeechRecognitionEvent.PartialResult -> {
                        _assistantState.value = VoiceAssistantState.Recognizing(event.partialText)
                        _lastQuery.value = event.partialText
                    }
                    is SpeechRecognitionEvent.FinalResult -> {
                        _lastQuery.value = event.recognizedText
                        val text = event.recognizedText.lowercase()

                        if (text.contains("стоп") || text.contains("отмена") || text.contains("хватит")) {
                            handleBargeInInterrupt()
                            return@collectLatest
                        }

                        executeAiQuery(event.recognizedText)
                    }
                    is SpeechRecognitionEvent.RecognitionError -> {
                        if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY) {
                            _assistantState.value = VoiceAssistantState.Error(event.errorMessage)
                            textToSpeechManager.speak(event.errorMessage, speechRate, speechPitch)
                            delay(2500)
                            startWakeWordListening()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun executeAiQuery(userText: String) {
        _currentMode.value = OrchestratorMode.AI_THINKING
        _assistantState.value = VoiceAssistantState.Thinking

        wakeWordDetector.startListening(isInterruptModeOnly = true)

        aiJob = scope.launch {
            val result = sendPromptUseCase(userText)
            when (result) {
                is Resource.Success -> {
                    val answer = result.data
                    _lastAnswer.value = answer
                    _currentMode.value = OrchestratorMode.TTS_SPEAKING
                    _assistantState.value = VoiceAssistantState.Speaking(answer)

                    // Озвучиваем сгенерированный ответ
                    textToSpeechManager.speak(answer, speechRate, speechPitch)
                }
                is Resource.Error -> {
                    val errorMsg = result.message ?: "Не удалось связаться с сервером AI."
                    _lastAnswer.value = "Ошибка: $errorMsg"
                    _assistantState.value = VoiceAssistantState.Error(errorMsg)
                    
                    // Голосовое оповещение об ошибке через наушник
                    textToSpeechManager.speak(errorMsg, speechRate, speechPitch)
                    delay(3000)
                    startWakeWordListening()
                }
                is Resource.Loading -> Unit
            }
        }
    }

    private fun observeTts() {
        scope.launch {
            textToSpeechManager.ttsState.collectLatest { ttsState ->
                when (ttsState) {
                    is TtsState.Finished -> {
                        if (_currentMode.value == OrchestratorMode.TTS_SPEAKING) {
                            _assistantState.value = VoiceAssistantState.Idle
                            startWakeWordListening()
                        }
                    }
                    is TtsState.Error -> {
                        _assistantState.value = VoiceAssistantState.Error(ttsState.message)
                        startWakeWordListening()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun startWakeWordListening() {
        if (!isServiceActive) return
        _currentMode.value = OrchestratorMode.STANDBY_WAKE_WORD
        wakeWordDetector.startListening(isInterruptModeOnly = false)
    }

    private fun playWakeChime() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (_: Exception) { }
    }

    private fun playCancelChime() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 100)
        } catch (_: Exception) { }
    }

    private fun resetInactivityTimer() {
        inactivityTimerJob?.cancel()
        inactivityTimerJob = scope.launch {
            delay(10 * 60 * 1000L)
            if (_currentMode.value == OrchestratorMode.STANDBY_WAKE_WORD) {
                wakeWordDetector.stopListening()
                _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
            }
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
        aiJob?.cancel()
        aiJob = null
        wakeWordDetector.stopListening()
        speechRecognizerManager.stopListening()
        textToSpeechManager.stop()
    }
}
