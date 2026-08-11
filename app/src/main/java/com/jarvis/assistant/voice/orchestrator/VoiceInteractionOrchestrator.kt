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
    VERIFYING_KEYWORD,     // Проверка ключевого слова
    LISTENING_USER_QUERY,  // Активная запись вопроса
    AI_THINKING,           // Быстрая генерация ответа
    TTS_SPEAKING,          // Полное озвучивание без прерываний
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
    private var silenceDebounceJob: Job? = null
    private var isServiceActive = false

    private var speechRate = 1.05f
    private var speechPitch = 1.0f

    // Ключевые слова для активации (без ложных срабатываний на посторонние слова)
    private val wakeKeywords = listOf("джарвис", "jarvis", "джар", "джей", "диджей", "jarv")

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
                    is WakeWordEvent.VoiceActivityDetected -> {
                        // Только когда ассистент в режиме ожидания
                        if (_currentMode.value == OrchestratorMode.STANDBY_WAKE_WORD) {
                            _currentMode.value = OrchestratorMode.VERIFYING_KEYWORD
                            wakeWordDetector.stopListening()
                            speechRecognizerManager.startListening()
                        }
                    }
                    is WakeWordEvent.VoiceLevelChanged -> Unit
                }
            }
        }
    }

    private fun observeSpeechRecognition() {
        scope.launch {
            speechRecognizerManager.speechState.collectLatest { event ->
                when (event) {
                    is SpeechRecognitionEvent.PartialResult -> {
                        handlePartialSpeech(event.partialText)
                    }
                    is SpeechRecognitionEvent.FinalResult -> {
                        handleFinalSpeech(event.recognizedText)
                    }
                    is SpeechRecognitionEvent.RecognitionError -> {
                        silenceDebounceJob?.cancel()
                        if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY) {
                            _assistantState.value = VoiceAssistantState.Error(event.errorMessage)
                            delay(1200)
                            startWakeWordListening()
                        } else if (_currentMode.value == OrchestratorMode.VERIFYING_KEYWORD) {
                            startWakeWordListening()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun handlePartialSpeech(partialText: String) {
        val lower = partialText.lowercase().trim()

        if (_currentMode.value == OrchestratorMode.VERIFYING_KEYWORD) {
            // Проверяем, содержит ли сказанное слово "Джарвис"
            val hasWakeWord = wakeKeywords.any { lower.contains(it) }
            if (hasWakeWord) {
                // Слово "Джарвис" подтверждено -> издаем звук и переходим в активное слушание
                playWakeChime()
                _currentMode.value = OrchestratorMode.LISTENING_USER_QUERY
                _assistantState.value = VoiceAssistantState.Listening
            }
        } else if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY) {
            _assistantState.value = VoiceAssistantState.Recognizing(partialText)
            _lastQuery.value = cleanWakeWordPrefix(partialText)

            // Быстрая отправка через 1.2 сек после окончания фразы
            silenceDebounceJob?.cancel()
            silenceDebounceJob = scope.launch {
                delay(1200)
                if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY && partialText.isNotBlank()) {
                    speechRecognizerManager.stopListening()
                    executeAiQuery(cleanWakeWordPrefix(partialText))
                }
            }
        }
    }

    private fun handleFinalSpeech(finalText: String) {
        silenceDebounceJob?.cancel()
        val lower = finalText.lowercase().trim()

        // 1. Проверка на команду прерывания
        if (lower == "стоп" || lower == "хватит" || lower == "отмена" || lower == "джарвис стоп") {
            handleBargeInInterrupt()
            return
        }

        // 2. Если мы были в режиме верификации ключевого слова
        if (_currentMode.value == OrchestratorMode.VERIFYING_KEYWORD) {
            val hasWakeWord = wakeKeywords.any { lower.contains(it) }
            if (hasWakeWord) {
                val cleanedQuery = cleanWakeWordPrefix(finalText)
                if (cleanedQuery.isNotBlank()) {
                    // Пользователь сразу сказал: "Джарвис, какая сегодня погода?"
                    executeAiQuery(cleanedQuery)
                } else {
                    // Пользователь сказал только "Джарвис" -> слушаем следующий вопрос
                    playWakeChime()
                    _currentMode.value = OrchestratorMode.LISTENING_USER_QUERY
                    _assistantState.value = VoiceAssistantState.Listening
                    speechRecognizerManager.startListening()
                }
            } else {
                // Сказано случайное постороннее слово -> игнорируем и возвращаемся в сон без шума!
                startWakeWordListening()
            }
            return
        }

        // 3. Если мы были в активном слушании запроса
        if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY) {
            val cleanedQuery = cleanWakeWordPrefix(finalText)
            if (cleanedQuery.isNotBlank()) {
                executeAiQuery(cleanedQuery)
            } else {
                startWakeWordListening()
            }
        }
    }

    private fun cleanWakeWordPrefix(text: String): String {
        var clean = text
        for (kw in wakeKeywords) {
            clean = clean.replace(Regex("(?i)^.*$kw[,\\s]*"), "").trim()
        }
        return clean.ifEmpty { text }
    }

    private fun executeAiQuery(userText: String) {
        _currentMode.value = OrchestratorMode.AI_THINKING
        _assistantState.value = VoiceAssistantState.Thinking
        _lastQuery.value = userText

        aiJob = scope.launch {
            val result = sendPromptUseCase(userText)
            when (result) {
                is Resource.Success -> {
                    val answer = result.data.trim()
                    _lastAnswer.value = answer
                    _currentMode.value = OrchestratorMode.TTS_SPEAKING
                    _assistantState.value = VoiceAssistantState.Speaking(answer)

                    // Озвучиваем ответ ПОЛНОСТЬЮ без самопрерывания
                    textToSpeechManager.speak(answer, speechRate, speechPitch)
                }
                is Resource.Error -> {
                    val errorMsg = result.message ?: "Ошибка связи с AI"
                    _lastAnswer.value = "Ошибка: $errorMsg"
                    _assistantState.value = VoiceAssistantState.Error(errorMsg)
                    textToSpeechManager.speak(errorMsg, speechRate, speechPitch)
                    delay(2500)
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
                        // Озвучивание успешно завершено целиком
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

    private fun handleBargeInInterrupt() {
        silenceDebounceJob?.cancel()
        aiJob?.cancel()
        aiJob = null
        textToSpeechManager.stop()
        playCancelChime()

        _assistantState.value = VoiceAssistantState.Idle
        _currentMode.value = OrchestratorMode.STANDBY_WAKE_WORD
        startWakeWordListening()
    }

    private fun startWakeWordListening() {
        if (!isServiceActive) return
        _currentMode.value = OrchestratorMode.STANDBY_WAKE_WORD
        wakeWordDetector.startListening()
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
        silenceDebounceJob?.cancel()
        aiJob?.cancel()
        aiJob = null
        wakeWordDetector.stopListening()
        speechRecognizerManager.stopListening()
        textToSpeechManager.stop()
    }
}
