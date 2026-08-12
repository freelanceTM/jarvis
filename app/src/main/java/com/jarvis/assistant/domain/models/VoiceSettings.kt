package com.jarvis.assistant.domain.models

data class VoiceSettings(
    val userName: String = "Сэр",
    val systemPrompt: String = "",
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val voiceLocale: String = "ru-RU",
    val selectedModel: String = "gpt-4o-mini",
    val isHeadsetOnlyMode: Boolean = false
)

enum class AIModel(val modelId: String, val displayName: String) {
    GPT_4O_MINI("gpt-4o-mini", "GPT-4o Mini (Быстрый)"),
    GPT_4O("gpt-4o", "GPT-4o (Максимальный интеллект)"),
    CLAUDE_3_5_SONNET("claude-3-5-sonnet-20240620", "Claude 3.5 Sonnet"),
    CUSTOM_BACKEND("jarvis-custom-gateway", "JARVIS Cloud Gateway v0.3");

    companion object {
        fun fromModelId(id: String): AIModel {
            return entries.firstOrNull { it.modelId.equals(id, ignoreCase = true) } ?: GPT_4O_MINI
        }
    }
}

sealed interface VoiceAssistantState {
    data object Idle : VoiceAssistantState
    data object Listening : VoiceAssistantState
    data class Recognizing(val partialText: String = "") : VoiceAssistantState
    data object Thinking : VoiceAssistantState
    data class Speaking(val answerText: String) : VoiceAssistantState
    data class Error(val userFriendlyMessage: String, val errorDetails: String? = null) : VoiceAssistantState
}
