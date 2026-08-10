package com.jarvis.assistant.ai

import com.jarvis.assistant.data.remote.dto.ApiMessageDto
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PromptManager @Inject constructor() {

    fun buildChatPrompt(
        systemPrompt: String,
        userPrompt: String,
        recentHistory: List<Message> = emptyList(),
        maxHistoryItems: Int = 6
    ): List<ApiMessageDto> {
        val messages = mutableListOf<ApiMessageDto>()

        // 1. Injected System Prompt
        if (systemPrompt.isNotBlank()) {
            messages.add(
                ApiMessageDto(
                    role = MessageRole.SYSTEM.value,
                    content = systemPrompt.trim()
                )
            )
        }

        // 2. Formatted Context Window (Oldest to newest)
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

        // 3. Current User Command (if not already in history)
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
