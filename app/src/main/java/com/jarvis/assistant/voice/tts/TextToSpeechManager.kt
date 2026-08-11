package com.jarvis.assistant.voice.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.jarvis.assistant.voice.audio.BluetoothAudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TtsState {
    data object Idle : TtsState
    data object Initializing : TtsState
    data object Ready : TtsState
    data class Speaking(val utteranceId: String, val text: String) : TtsState
    data class Finished(val utteranceId: String) : TtsState
    data class Error(val message: String) : TtsState
}

@Singleton
class TextToSpeechManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothAudioManager: BluetoothAudioManager,
    private val neuralVoicePlayer: NeuralVoicePlayer
) : TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _ttsState = MutableStateFlow<TtsState>(TtsState.Initializing)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    private var currentSpeechRate = 1.05f
    private var currentSpeechPitch = 0.90f

    init {
        initializeTts()
    }

    fun initializeTts() {
        if (textToSpeech == null) {
            _ttsState.value = TtsState.Initializing
            textToSpeech = TextToSpeech(context, this, "com.google.android.tts")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            setupVoiceAndTone()

            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _ttsState.value = TtsState.Speaking(utteranceId.orEmpty(), "")
                }

                override fun onDone(utteranceId: String?) {
                    bluetoothAudioManager.abandonAudioFocus()
                    _ttsState.value = TtsState.Finished(utteranceId.orEmpty())
                    _ttsState.value = TtsState.Idle
                }

                override fun onError(utteranceId: String?) {
                    bluetoothAudioManager.abandonAudioFocus()
                    _ttsState.value = TtsState.Idle
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    bluetoothAudioManager.abandonAudioFocus()
                    _ttsState.value = TtsState.Idle
                }
            })

            isInitialized = true
            _ttsState.value = TtsState.Ready
        } else {
            textToSpeech = TextToSpeech(context, { secondaryStatus ->
                if (secondaryStatus == TextToSpeech.SUCCESS) {
                    setupVoiceAndTone()
                    isInitialized = true
                    _ttsState.value = TtsState.Ready
                } else {
                    isInitialized = false
                    _ttsState.value = TtsState.Error("Не удалось инициализировать синтезатор речи")
                }
            })
        }
    }

    private fun setupVoiceAndTone() {
        val localeRu = Locale("ru", "RU")
        val result = textToSpeech?.setLanguage(localeRu)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            textToSpeech?.setLanguage(Locale.getDefault())
        }

        val selectedVoice = findBestJarvisVoice()
        if (selectedVoice != null) {
            textToSpeech?.voice = selectedVoice
        }

        textToSpeech?.setSpeechRate(currentSpeechRate)
        textToSpeech?.setPitch(currentSpeechPitch)
    }

    private fun findBestJarvisVoice(): Voice? {
        val voices = textToSpeech?.voices ?: return null
        val ruVoices = voices.filter { it.locale.language == "ru" }

        return ruVoices.firstOrNull { it.name.contains("dfc", ignoreCase = true) }
            ?: ruVoices.firstOrNull { it.name.contains("male", ignoreCase = true) }
            ?: ruVoices.firstOrNull { it.name.contains("ru-ru-x", ignoreCase = true) }
            ?: ruVoices.firstOrNull()
    }

    fun speak(text: String, rate: Float = currentSpeechRate, pitch: Float = currentSpeechPitch) {
        val cleanText = text.replace(Regex("[*#_`~]"), "").trim()
        if (cleanText.isEmpty()) return

        stop()
        bluetoothAudioManager.requestAudioFocus()
        val utteranceId = UUID.randomUUID().toString()

        _ttsState.value = TtsState.Speaking(utteranceId, cleanText)

        // 1. Попытка высококачественного воспроизведения через живой нейро-голос
        scope.launch {
            val playedNeural = neuralVoicePlayer.playNeuralVoice(cleanText) {
                bluetoothAudioManager.abandonAudioFocus()
                _ttsState.value = TtsState.Finished(utteranceId)
                _ttsState.value = TtsState.Idle
            }

            // 2. Если нейро-голос недоступен, бесшовный fallback на локальный баритон
            if (!playedNeural) {
                speakLocalTts(cleanText, utteranceId, rate, pitch)
            }
        }
    }

    private fun speakLocalTts(text: String, utteranceId: String, rate: Float, pitch: Float) {
        if (!isInitialized || textToSpeech == null) {
            initializeTts()
            return
        }

        currentSpeechRate = rate
        currentSpeechPitch = pitch
        textToSpeech?.setSpeechRate(rate)
        textToSpeech?.setPitch(pitch)

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        }

        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun stop() {
        neuralVoicePlayer.stop()
        if (isInitialized) {
            textToSpeech?.stop()
            bluetoothAudioManager.abandonAudioFocus()
            _ttsState.value = TtsState.Idle
        }
    }

    fun shutdown() {
        try {
            stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) { }
        textToSpeech = null
        isInitialized = false
        _ttsState.value = TtsState.Idle
    }
}
