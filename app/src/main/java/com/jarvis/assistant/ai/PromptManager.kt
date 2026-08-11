package com.jarvis.assistant.ai

import com.jarvis.assistant.data.remote.dto.ApiMessageDto
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PromptManager @Inject constructor() {

    private val voiceSystemConstraint = """Ты JARVIS — персональный голосовой AI-ассистент.
Правила ответа:
1. Отвечай кратко и емко: ровно 1-2 предложения, максимум 3.
2. Говори живым разговорным языком для озвучивания вслух.
3. Категорически запрещено использовать списки, маркировку, markdown-звездочки (**), решетки (#) и спецсимволы.
4. Отвечай прямо по сути без лишних вступительных фраз."""

    fun buildChatPrompt(
        systemPrompt: String,
        userPrompt: String,
        recentHistory: List<Message> = emptyList(),
        maxHistoryItems: Int = 4
    ): List<ApiMessageDto> {
        val messages = mutableListOf<ApiMessageDto>()

        val effectiveSystemPrompt = if (systemPrompt.isNotBlank()) {
            "$systemPrompt\n\n$voiceSystemConstraint"
        } else {
            voiceSystemConstraint
        }

        messages.add(
            ApiMessageDto(
                role = MessageRole.SYSTEM.value,
                content = effectiveSystemPrompt.trim()
            )
        )

        // Контекст истории
        val sortedHistory = recentHistory
            .takeLast(maxHistoryItems)
            .filter { it.text.isNotBlank() }

        for (msg in sortedHistory) {
            messages.add(
                ApiMessageDto(
                    role = msg.role.value,
                    content = msg.text.trim()
                )
            )
        }

        if (sortedHistory.lastOrNull()?.text != userPrompt) {
            messages.add(
                ApiMessageDto(
                    role = MessageRole.USER.value,
                    content = userPrompt.trim()
                )
            )
        }

        return messages
    }
}
