package com.jarvis.assistant.voice.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.assistant.voice.audio.BluetoothAudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val bluetoothAudioManager: BluetoothAudioManager
) : TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false

    private val _ttsState = MutableStateFlow<TtsState>(TtsState.Initializing)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    private var currentSpeechRate = 1.0f
    private var currentSpeechPitch = 1.0f

    init {
        initializeTts()
    }

    fun initializeTts() {
        if (textToSpeech == null) {
            _ttsState.value = TtsState.Initializing
            textToSpeech = TextToSpeech(context, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale("ru", "RU"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                textToSpeech?.setLanguage(Locale.getDefault())
            }

            textToSpeech?.setSpeechRate(currentSpeechRate)
            textToSpeech?.setPitch(currentSpeechPitch)

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
                    _ttsState.value = TtsState.Error("Ошибка воспроизведения речи")
                    _ttsState.value = TtsState.Idle
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    bluetoothAudioManager.abandonAudioFocus()
                    _ttsState.value = TtsState.Error("Ошибка TTS с кодом: $errorCode")
                    _ttsState.value = TtsState.Idle
                }
            })

            isInitialized = true
            _ttsState.value = TtsState.Ready
        } else {
            isInitialized = false
            _ttsState.value = TtsState.Error("Не удалось инициализировать синтезатор речи")
        }
    }

    fun speak(text: String, rate: Float = currentSpeechRate, pitch: Float = currentSpeechPitch) {
        if (!isInitialized || textToSpeech == null) {
            initializeTts()
            return
        }

        stop()

        currentSpeechRate = rate
        currentSpeechPitch = pitch
        textToSpeech?.setSpeechRate(rate)
        textToSpeech?.setPitch(pitch)

        bluetoothAudioManager.requestAudioFocus()

        val utteranceId = UUID.randomUUID().toString()
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        }

        val result = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            _ttsState.value = TtsState.Error("Ошибка при запуске синтеза речи")
        }
    }

    fun stop() {
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
