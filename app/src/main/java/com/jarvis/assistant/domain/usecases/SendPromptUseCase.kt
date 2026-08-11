package com.jarvis.assistant.domain.usecases

import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.agent.registry.ToolResult
import com.jarvis.assistant.core.result.Resource
import com.jarvis.assistant.domain.models.Message
import com.jarvis.assistant.domain.models.MessageRole
import com.jarvis.assistant.domain.repository.AIRepository
import com.jarvis.assistant.domain.repository.MessageRepository
import com.jarvis.assistant.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject

class SendPromptUseCase @Inject constructor(
    private val aiRepository: AIRepository,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
    private val toolRegistry: ToolRegistry
) {
    suspend operator fun invoke(userPrompt: String): Resource<String> {
        val trimmedPrompt = userPrompt.trim()
        if (trimmedPrompt.isEmpty()) {
            return Resource.Error(IllegalArgumentException("Пустой запрос"))
        }

        // 1. Сохраняем вопрос пользователя в Room
        val userMessage = Message(
            role = MessageRole.USER,
            text = trimmedPrompt,
            timestamp = System.currentTimeMillis()
        )
        messageRepository.insertMessage(userMessage)

        // 2. Добавляем системные описания инструментов в промпт
        val baseSystemPrompt = settingsRepository.systemPromptFlow.first()
        val toolsPrompt = toolRegistry.buildToolsSystemPrompt()
        val combinedSystemPrompt = "$baseSystemPrompt\n\n$toolsPrompt"

        val history = messageRepository.getRecentMessages(limit = 4)

        // 3. Запрос в AI
        val aiResult = aiRepository.generateResponse(
            prompt = trimmedPrompt,
            systemPrompt = combinedSystemPrompt,
            history = history
        )

        // 4. Обработка ответа: Проверка на Action Tool Call
        if (aiResult is Resource.Success) {
            val rawOutput = aiResult.data.trim()
            val finalAnswer = processAgentActionIfNeeded(rawOutput)

            // Сохраняем ответ ассистента в Room
            val assistantMessage = Message(
                role = MessageRole.ASSISTANT,
                text = finalAnswer,
                timestamp = System.currentTimeMillis()
            )
            messageRepository.insertMessage(assistantMessage)

            return Resource.Success(finalAnswer)
        }

        return aiResult
    }

    private suspend fun processAgentActionIfNeeded(rawOutput: String): String {
        val marker = "ACTION_CALL:"
        if (!rawOutput.contains(marker)) {
            return rawOutput
        }

        try {
            val jsonPart = rawOutput.substringAfter(marker).trim()
            val json = JSONObject(jsonPart)
            val toolName = json.getString("tool")
            val paramsObj = json.optJSONObject("params")

            val paramsMap = mutableMapOf<String, String>()
            paramsObj?.keys()?.forEach { key ->
                paramsMap[key] = paramsObj.getString(key)
            }

            val actionResult = toolRegistry.executeTool(toolName, paramsMap)
            return when (actionResult) {
                is ToolResult.Success -> {
                    "${actionResult.summary}, сэр."
                }
                is ToolResult.Failure -> {
                    "Не удалось выполнить действие: ${actionResult.summary}"
                }
            }
        } catch (_: Exception) {
            return rawOutput.replace(marker, "").trim()
        }
    }
}
