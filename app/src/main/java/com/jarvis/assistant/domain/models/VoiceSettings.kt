package com.jarvis.assistant.domain.models

data class VoiceSettings(
    val userName: String = "Сэр",
    val systemPrompt: String = "",
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val voiceLocale: String = "ru-RU",
    /**
     * AR-01: поле оставлено для совместимости с DataStore / сериализацией,
     * но пользовательского выбора модели больше нет — сервер принимает
     * решение самостоятельно (п.29 ТЗ). Значение всегда "server-managed";
     * UI его не отображает и не изменяет.
     */
    @Deprecated("Model selection is server-managed; kept only for DataStore backwards compatibility.")
    val selectedModel: String = "server-managed",
    val isHeadsetOnlyMode: Boolean = false,
    val wakeWordSensitivity: Float = 0.65f
)

sealed interface VoiceAssistantState {
    data object Idle : VoiceAssistantState
    data object Listening : VoiceAssistantState
    data class Recognizing(val partialText: String = "") : VoiceAssistantState
    data object Thinking : VoiceAssistantState
    data class Speaking(val answerText: String) : VoiceAssistantState
    data class Error(val userFriendlyMessage: String, val errorDetails: String? = null) : VoiceAssistantState
}
