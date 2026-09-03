package com.jarvis.assistant.presentation.translator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.agent.translator.InterpreterMode
import com.jarvis.assistant.agent.translator.InterpreterPreset
import com.jarvis.assistant.agent.translator.LiveTranslatorEngine
import com.jarvis.assistant.agent.translator.SupportedLanguage
import com.jarvis.assistant.agent.translator.TranslationLanguageDetector
import com.jarvis.assistant.voice.audio.BluetoothAudioRouter
import com.jarvis.assistant.voice.orchestrator.OrchestratorMode
import com.jarvis.assistant.voice.orchestrator.VoiceInteractionOrchestrator
import com.jarvis.assistant.voice.stt.SpeechRecognitionEvent
import com.jarvis.assistant.voice.stt.SpeechRecognizerManager
import com.jarvis.assistant.voice.tts.TextToSpeechManager
import com.jarvis.assistant.voice.wakeword.WakeWordDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TranslationItem(
    val id: Long = System.currentTimeMillis(),
    val originalText: String,
    val translatedText: String,
    val sourceLang: String,
    val targetLang: String,
    val isInterlocutor: Boolean = true,
    /** false — перевод не выполнен, в translatedText лежит причина. */
    val isTranslated: Boolean = true
)

data class LiveInterpreterUiState(
    val isListening: Boolean = false,
    val mode: InterpreterMode = InterpreterMode.EAR_ONLY,
    val preset: InterpreterPreset = InterpreterPreset.AUTO,
    val sourceLanguage: SupportedLanguage = LiveTranslatorEngine.SUPPORTED_LANGUAGES[0], // RU
    val targetLanguage: SupportedLanguage = LiveTranslatorEngine.SUPPORTED_LANGUAGES[1], // EN
    /** Язык, определённый детектором в режиме AUTO (для отображения). */
    val detectedSourceLanguage: SupportedLanguage? = null,
    val history: List<TranslationItem> = emptyList(),
    val partialRecognizedText: String = "",
    val isTranslating: Boolean = false
)

/**
 * LiveInterpreterViewModel (Full-Duplex Simultaneous Translation)
 * 
 * Обеспечивает непрерывный синхронный перевод:
 * 1. Микрофон слушает собеседника непрерывно (Continuous Mode).
 * 2. Перевод фраз отправляется в наушник параллельно (speakQueued), не останавливая запись следующей фразы.
 */
@HiltViewModel
class LiveInterpreterViewModel @Inject constructor(
    private val translatorEngine: LiveTranslatorEngine,
    private val speechRecognizerManager: SpeechRecognizerManager,
    private val textToSpeechManager: TextToSpeechManager,
    private val bluetoothAudioRouter: BluetoothAudioRouter,
    private val wakeWordDetector: WakeWordDetector,
    private val orchestrator: VoiceInteractionOrchestrator
) : ViewModel() {

    /**
     * EAR-MODE (microphone): true, пока переводчик держит микрофон, отобрав его
     * у wake-word AudioRecord. По этому флагу микрофон возвращается движку
     * wake-word при выходе с экрана (только если сервис действительно стоит
     * в STANDBY — при паузе/сне микрофоном распоряжается сервис).
     */
    private var micTakenFromWakeWord = false

    /** EAR-MODE (phone call): звонок приостановил слушание — возобновить после. */
    @Volatile
    private var pausedByPhoneCall = false

    private val _uiState = MutableStateFlow(LiveInterpreterUiState())
    val uiState: StateFlow<LiveInterpreterUiState> = _uiState.asStateFlow()

    init {
        observeSpeechRecognizer()
        observeOrchestratorMode()
    }

    /**
     * EAR-MODE (phone call): звонок идёт через ОБЩИЕ синглтоны STT/TTS —
     * JarvisVoiceService на RINGING/OFFHOOK зовёт orchestrator.pauseForPhoneCall(),
     * чей stopAll() останавливает speechRecognizerManager и textToSpeechManager
     * под нами. Экран обязан узнать об этом (иначе висит stale isListening=true)
     * и продолжить перевод после звонка (PAUSED → активный режим).
     */
    private fun observeOrchestratorMode() {
        viewModelScope.launch {
            orchestrator.currentMode.collectLatest { mode ->
                if (mode == OrchestratorMode.PAUSED_CALL_OR_SLEEP) {
                    if (_uiState.value.isListening) {
                        pausedByPhoneCall = true
                        _uiState.update {
                            it.copy(isListening = false, partialRecognizedText = "", isTranslating = false)
                        }
                        textToSpeechManager.stop()
                    }
                } else if (pausedByPhoneCall) {
                    pausedByPhoneCall = false
                    // Сервис после паузы синхронно перезапускает wake-word
                    // (resume-путь → startStandbyMode) — даём этому завершиться,
                    // снова забираем микрофон у wake-движка и продолжаем
                    // слушать собеседника.
                    viewModelScope.launch {
                        delay(200)
                        // Перезапуск идемпотентен (startListening отменяет текущую
                        // сессию): он же чинит случай, когда пользователь успел
                        // включить слушание прямо во время звонка и сервис
                        // погасил её resume-путём.
                        if (orchestrator.currentMode.value == OrchestratorMode.PAUSED_CALL_OR_SLEEP) {
                            return@launch
                        }
                        wakeWordDetector.stopListening()
                        _uiState.update { it.copy(isListening = true) }
                        speechRecognizerManager.startListening(
                            languageTag = listeningLanguageTag(),
                            continuous = true
                        )
                    }
                }
            }
        }
    }

    private fun observeSpeechRecognizer() {
        viewModelScope.launch {
            speechRecognizerManager.speechState.collectLatest { event ->
                when (event) {
                    is SpeechRecognitionEvent.PartialResult -> {
                        _uiState.update { it.copy(partialRecognizedText = event.partialText) }
                    }
                    is SpeechRecognitionEvent.FinalResult -> {
                        val text = event.recognizedText.trim()
                        _uiState.update { it.copy(partialRecognizedText = "") }
                        if (text.isNotBlank()) {
                            // Фоновый перевод без блокировки микрофона!
                            dispatchParallelTranslation(text)
                        }
                    }
                    is SpeechRecognitionEvent.RecognitionError -> {
                        if (!_uiState.value.isListening) {
                            _uiState.update { it.copy(isListening = false, isTranslating = false) }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun dispatchParallelTranslation(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isTranslating = true) }
            val state = _uiState.value

            // Режим AUTO: язык не фиксирован — бэкенд получает sourceLang="auto"
            // и определяет его сам; детектор нужен только для метки в UI.
            val isAuto = state.preset == InterpreterPreset.AUTO
            val sourceCode = if (isAuto) "auto" else state.sourceLanguage.code
            val detectedLang = if (isAuto) {
                TranslationLanguageDetector.detect(text)?.let { code ->
                    LiveTranslatorEngine.SUPPORTED_LANGUAGES.firstOrNull { it.code == code }
                }
            } else {
                null
            }
            val sourceLabel = detectedLang?.displayName ?: state.sourceLanguage.displayName

            val result = translatorEngine.translateStructured(
                text = text,
                sourceLang = sourceCode,
                targetLang = state.targetLanguage.code
            )
            // Неуспешный перевод показывается как причина отказа,
            // а не как «перевод», совпадающий с оригиналом.
            val translation = translatorEngine.describeFailure(result)

            val item = TranslationItem(
                originalText = text,
                translatedText = translation,
                isTranslated = result is com.jarvis.assistant.agent.translator.TranslationResult.Success,
                sourceLang = sourceLabel,
                targetLang = state.targetLanguage.displayName,
                isInterlocutor = true
            )

            _uiState.update { 
                it.copy(
                    history = listOf(item) + it.history,
                    detectedSourceLanguage = detectedLang ?: it.detectedSourceLanguage,
                    isTranslating = false
                ) 
            }

            // Мгновенное неблокирующее воспроизведение перевода прямо в ухо (Bluetooth SCO)
            bluetoothAudioRouter.routeAudioToEarbud()
            textToSpeechManager.speakQueued(translation)
        }
    }

    /**
     * Быстрый пресет режима переводчика: AUTO / RU→TM / TM→RU / EN→RU / RU→EN.
     * Обновляет source/target и перезапускает распознавание, если идёт прослушивание.
     */
    fun applyPreset(preset: InterpreterPreset) {
        val sourceLang = LiveTranslatorEngine.SUPPORTED_LANGUAGES.firstOrNull { it.code == preset.sourceCode }
        val targetLang = LiveTranslatorEngine.SUPPORTED_LANGUAGES.firstOrNull { it.code == preset.targetCode }
            ?: LiveTranslatorEngine.SUPPORTED_LANGUAGES[0]

        _uiState.update {
            it.copy(
                preset = preset,
                sourceLanguage = sourceLang ?: it.sourceLanguage,
                targetLanguage = targetLang,
                detectedSourceLanguage = null
            )
        }

        // В AUTO микрофон слушает без фиксированного языка (система/детектор решают).
        if (_uiState.value.isListening) {
            val tag = if (preset == InterpreterPreset.AUTO) "auto" else _uiState.value.sourceLanguage.localeTag
            speechRecognizerManager.startListening(tag, continuous = true)
        }
    }

    fun toggleListening() {
        if (_uiState.value.isListening) {
            speechRecognizerManager.stopListening()
            restoreWakeWordIfNeeded()
            _uiState.update { it.copy(isListening = false, partialRecognizedText = "", isTranslating = false) }
        } else {
            // EAR-MODE (microphone): забираем микрофон у wake-word AudioRecord —
            // с Android 10 фактический приоритет получает один захватчик, и пока
            // wake-движок держит AudioRecord, распознавание переводчика деградирует
            // (а wake-word ловит тишину поверх SCO).
            wakeWordDetector.stopListening()
            micTakenFromWakeWord = true
            _uiState.update { it.copy(isListening = true) }
            // Непрерывный режим прослушивания собеседника (continuous = true)
            speechRecognizerManager.startListening(
                languageTag = listeningLanguageTag(),
                continuous = true
            )
        }
    }

    /**
     * EAR-MODE (microphone): вернуть микрофон wake-word, если мы его занимали
     * и сервис жив и стоит в STANDBY. При PAUSED/sleep не стартуем — микрофоном
     * распорядится сервис при resume.
     */
    private fun restoreWakeWordIfNeeded() {
        if (!micTakenFromWakeWord) return
        micTakenFromWakeWord = false
        if (orchestrator.currentMode.value == OrchestratorMode.STANDBY_WAKE_WORD) {
            wakeWordDetector.startListening()
        }
    }

    fun swapLanguages() {
        val current = _uiState.value
        val newSource = current.targetLanguage
        val newTarget = current.sourceLanguage
        // Если после обмена пара совпадает с быстрым пресетом — подхватываем его,
        // иначе ручной выбор (CUSTOM).
        val matchedPreset = InterpreterPreset.all.firstOrNull {
            it != InterpreterPreset.AUTO &&
                it.sourceCode == newSource.code &&
                it.targetCode == newTarget.code
        }
        _uiState.update {
            it.copy(
                preset = matchedPreset ?: InterpreterPreset.CUSTOM,
                sourceLanguage = newSource,
                targetLanguage = newTarget,
                detectedSourceLanguage = null
            )
        }
        if (_uiState.value.isListening) {
            speechRecognizerManager.startListening(listeningLanguageTag(), continuous = true)
        }
    }

    fun setSourceLanguage(lang: SupportedLanguage) {
        _uiState.update { it.copy(sourceLanguage = lang, preset = InterpreterPreset.CUSTOM, detectedSourceLanguage = null) }
        if (_uiState.value.isListening) {
            speechRecognizerManager.startListening(lang.localeTag, continuous = true)
        }
    }

    fun setTargetLanguage(lang: SupportedLanguage) {
        _uiState.update { it.copy(targetLanguage = lang, preset = InterpreterPreset.CUSTOM) }
    }

    /** Язык STT: "auto" в режиме AUTO, иначе локаль выбранного языка. */
    private fun listeningLanguageTag(): String =
        if (_uiState.value.preset == InterpreterPreset.AUTO) "auto" else _uiState.value.sourceLanguage.localeTag

    fun clearHistory() {
        _uiState.update { it.copy(history = emptyList()) }
    }

    override fun onCleared() {
        super.onCleared()
        if (_uiState.value.isListening) {
            speechRecognizerManager.stopListening()
        }
        // EAR-MODE (speaker): не оставляем после экрана болтающий TTS
        // (перевод мог быть в очереди) и коммуникационный аудиорежим.
        textToSpeechManager.stop()
        bluetoothAudioRouter.restoreDefaultRouting()
        restoreWakeWordIfNeeded()
    }
}
