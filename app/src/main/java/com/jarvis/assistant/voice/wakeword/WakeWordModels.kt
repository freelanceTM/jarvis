package com.jarvis.assistant.voice.wakeword

import kotlinx.coroutines.flow.SharedFlow

enum class WakeWordEngineType(
    val id: String,
    val displayName: String,
    val description: String
) {
    HYBRID_ACOUSTIC_VAD(
        id = "hybrid",
        displayName = "⚡ Гибридный VAD + Contour (Встроенный)",
        description = "Акустический анализ формант речи и контура слогов (0 МБ, по умолчанию)"
    ),
    PICOVOICE_PORCUPINE(
        id = "porcupine",
        displayName = "🧠 Picovoice Porcupine (Neural KWS)",
        description = "Промышленная нейросеть для ключевого слова «Джарвис» (<50 мс задержка, <0.8% CPU)"
    ),
    VOSK_OFFLINE_KWS(
        id = "vosk",
        displayName = "📴 Vosk Offline Kaldi KWS",
        description = "100% локальное фонетическое распознавание на устройстве с ограниченной грамматикой"
    );

    companion object {
        fun fromId(id: String): WakeWordEngineType {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: HYBRID_ACOUSTIC_VAD
        }
    }
}

sealed interface WakeWordEvent {
    data object VoiceActivityDetected : WakeWordEvent
    data class KeywordDetected(val keyword: String, val confidence: Float = 1.0f) : WakeWordEvent
    data class VoiceLevelChanged(val rms: Float) : WakeWordEvent
}

interface WakeWordDetector {
    val events: SharedFlow<WakeWordEvent>
    fun startListening()
    fun stopListening()
    fun isRunning(): Boolean
    fun setSensitivity(sensitivity: Float)
    fun destroy()
}
