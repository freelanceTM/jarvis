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
    STANDBY_WAKE_WORD,     // Ожидание
    VERIFYING_KEYWORD,     // Анализ
    LISTENING_USER_QUERY,  // Запись голоса
    AI_THINKING,           // Запрос AI
    TTS_SPEAKING,          // Озвучивание ответа
    PAUSED_CALL_OR_SLEEP   // Пауза
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
    private var silenceJob: Job? = null
    private var isServiceActive = false

    private var speechRate = 1.05f
    private var speechPitch = 0.90f

    private val wakeKeywords = listOf("джарвис", "jarvis", "жарвис", "дарвис", "джей", "диджей", "джар")

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (_: Exception) { }

        observeSettings()
        observeWakeDetector()
        observeSpeechRecognizer()
        observeTtsEngine()
    }

    fun startServicePipeline() {
        isServiceActive = true
        bluetoothAudioRouter.routeAudioToEarbud()
        startStandbyMode()
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

    private fun observeWakeDetector() {
        scope.launch {
            wakeWordDetector.events.collectLatest { event ->
                if (event is WakeWordEvent.VoiceActivityDetected && _currentMode.value == OrchestratorMode.STANDBY_WAKE_WORD) {
                    switchToSpeechRecognition()
                }
            }
        }
    }

    private fun startStandbyMode() {
        if (!isServiceActive) return
        _currentMode.value = OrchestratorMode.STANDBY_WAKE_WORD
        _assistantState.value = VoiceAssistantState.Idle
        speechRecognizerManager.stopListening()
        wakeWordDetector.startListening()
    }

    private fun switchToSpeechRecognition() {
        // Строгая последовательность: Сначала освобождаем микрофон из WakeWord, затем открываем SpeechRecognizer
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
                        if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY) {
                            _assistantState.value = VoiceAssistantState.Recognizing(event.partialText)
                            _lastQuery.value = cleanWakeWord(event.partialText)

                            // Авто-отправка через 1.3 сек тишины
                            silenceJob?.cancel()
                            silenceJob = scope.launch {
                                delay(1300)
                                if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY && event.partialText.isNotBlank()) {
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

                        val clean = cleanWakeWord(text)
                        if (clean.isNotBlank()) {
                            processUserQuery(clean)
                        } else {
                            startStandbyMode()
                        }
                    }
                    is SpeechRecognitionEvent.RecognitionError -> {
                        silenceJob?.cancel()
                        if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY) {
                            delay(600)
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
        _currentMode.value = OrchestratorMode.AI_THINKING
        _assistantState.value = VoiceAssistantState.Thinking
        _lastQuery.value = query

        aiJob?.cancel()
        aiJob = scope.launch {
            val result = sendPromptUseCase(query)
            when (result) {
                is Resource.Success -> {
                    val answer = result.data.trim()
                    _lastAnswer.value = answer
                    _currentMode.value = OrchestratorMode.TTS_SPEAKING
                    _assistantState.value = VoiceAssistantState.Speaking(answer)

                    // Озвучиваем ответ полностью
                    textToSpeechManager.speak(answer, speechRate, speechPitch)
                }
                is Resource.Error -> {
                    val errorMsg = result.message ?: "Ошибка связи с AI"
                    _lastAnswer.value = "Ошибка: $errorMsg"
                    _assistantState.value = VoiceAssistantState.Error(errorMsg)
                    textToSpeechManager.speak(errorMsg, speechRate, speechPitch)
                    delay(2500)
                    startStandbyMode()
                }
                is Resource.Loading -> Unit
            }
        }
    }

    private fun observeTtsEngine() {
        scope.launch {
            textToSpeechManager.ttsState.collectLatest { ttsState ->
                when (ttsState) {
                    is TtsState.Finished -> {
                        if (_currentMode.value == OrchestratorMode.TTS_SPEAKING) {
                            startStandbyMode()
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

    private fun handleCancel() {
        silenceJob?.cancel()
        aiJob?.cancel()
        aiJob = null
        textToSpeechManager.stop()
        playCancelChime()
        startStandbyMode()
    }

    private fun playWakeChime() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
        } catch (_: Exception) { }
    }

    private fun playCancelChime() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 90)
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
        aiJob?.cancel()
        aiJob = null
        wakeWordDetector.stopListening()
        speechRecognizerManager.stopListening()
        textToSpeechManager.stop()
    }
}
