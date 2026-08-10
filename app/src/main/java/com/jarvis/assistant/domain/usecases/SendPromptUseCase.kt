package com.jarvis.assistant.domain.usecases

import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import com.jarvis.assistant.domain.repository.AIRepository
import com.jarvis.assistant.domain.repository.MessageRepository
import com.jarvis.assistant.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class SendPromptUseCase @Inject constructor(
    private val aiRepository: AIRepository,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(userPrompt: String): Resource<String> {
        val trimmedPrompt = userPrompt.trim()
        if (trimmedPrompt.isEmpty()) {
            return Resource.Error(IllegalArgumentException("Пустой запрос"))
        }

        // 1. Save User Message into Room database
        val userMessage = Message(
            role = MessageRole.USER,
            text = trimmedPrompt,
            timestamp = System.currentTimeMillis()
        )
        messageRepository.insertMessage(userMessage)

        // 2. Fetch System Prompt and context history
        val systemPrompt = settingsRepository.systemPromptFlow.first()
        val history = messageRepository.getRecentMessages(limit = 6)

        // 3. Request completion from AI
        val aiResult = aiRepository.generateResponse(
            prompt = trimmedPrompt,
            systemPrompt = systemPrompt,
            history = history
        )

        // 4. If successful, save Assistant Message into Room
        if (aiResult is Resource.Success) {
            val assistantMessage = Message(
                role = MessageRole.ASSISTANT,
                text = aiResult.data,
                timestamp = System.currentTimeMillis()
            )
            messageRepository.insertMessage(assistantMessage)
        }

        return aiResult
    }
}
