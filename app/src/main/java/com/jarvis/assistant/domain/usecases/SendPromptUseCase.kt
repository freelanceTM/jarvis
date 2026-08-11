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
            val finalAnswer = processAgentAction(rawOutput, trimmedPrompt)

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

    private suspend fun processAgentAction(rawOutput: String, userPrompt: String): String {
        val marker = "ACTION_CALL:"
        val lowerPrompt = userPrompt.lowercase()

        // 1. Если модель выдала явный маркер ACTION_CALL
        if (rawOutput.contains(marker)) {
            try {
                val jsonPart = rawOutput.substringAfter(marker).trim()
                val json = JSONObject(jsonPart)
                val rawToolName = json.optString("tool", "")
                val paramsObj = json.optJSONObject("params")

                val paramsMap = mutableMapOf<String, String>()
                paramsObj?.keys()?.forEach { key ->
                    paramsMap[key] = paramsObj.getString(key)
                }

                // Интеллектуальное авто-исправление имени инструмента
                val resolvedTool = resolveToolName(rawToolName, paramsMap, lowerPrompt)
                val actionResult = toolRegistry.executeTool(resolvedTool, paramsMap)

                return when (actionResult) {
                    is ToolResult.Success -> "${actionResult.summary}, сэр."
                    is ToolResult.Failure -> actionResult.summary
                }
            } catch (_: Exception) { }
        }

        // 2. Эвристический роутер прямого намерения (если модель ответила простым текстом на команду управления)
        if (lowerPrompt.startsWith("открой") || lowerPrompt.startsWith("запусти") || lowerPrompt.startsWith("включи")) {
            if (lowerPrompt.contains("телеграм") || lowerPrompt.contains("telegram")) {
                toolRegistry.executeTool("open_app", mapOf("app_name" to "telegram"))
                return "Открываю Telegram, сэр."
            }
            if (lowerPrompt.contains("ютуб") || lowerPrompt.contains("youtube")) {
                toolRegistry.executeTool("open_app", mapOf("app_name" to "youtube"))
                return "Открываю YouTube, сэр."
            }
            if (lowerPrompt.contains("камер") || lowerPrompt.contains("camera")) {
                toolRegistry.executeTool("open_app", mapOf("app_name" to "camera"))
                return "Включаю камеру, сэр."
            }
            if (lowerPrompt.contains("ватсап") || lowerPrompt.contains("whatsapp")) {
                toolRegistry.executeTool("open_app", mapOf("app_name" to "whatsapp"))
                return "Открываю WhatsApp, сэр."
            }
            if (lowerPrompt.contains("блютуз") || lowerPrompt.contains("bluetooth")) {
                toolRegistry.executeTool("open_settings", mapOf("target" to "bluetooth"))
                return "Открываю настройки Bluetooth, сэр."
            }
            if (lowerPrompt.contains("вайфай") || lowerPrompt.contains("wi-fi") || lowerPrompt.contains("wifi")) {
                toolRegistry.executeTool("open_settings", mapOf("target" to "wifi"))
                return "Открываю настройки Wi-Fi, сэр."
            }
        }

        if (lowerPrompt.contains("громче") || lowerPrompt.contains("прибавь звук")) {
            toolRegistry.executeTool("set_volume", mapOf("action" to "up"))
            return "Громкость увеличена, сэр."
        }

        if (lowerPrompt.contains("тише") || lowerPrompt.contains("убавь звук")) {
            toolRegistry.executeTool("set_volume", mapOf("action" to "down"))
            return "Громкость уменьшена, сэр."
        }

        if (lowerPrompt.contains("без звука") || lowerPrompt.contains("выключи звук")) {
            toolRegistry.executeTool("set_volume", mapOf("action" to "mute"))
            return "Звук выключен, сэр."
        }

        return rawOutput.replace(marker, "").trim()
    }

    private fun resolveToolName(rawToolName: String, params: Map<String, String>, prompt: String): String {
        if (rawToolName.isNotBlank() && rawToolName != "tool_name" && toolRegistry.getTool(rawToolName) != null) {
            return rawToolName
        }

        // Авто-определение по параметрам
        if (params.containsKey("app_name")) return "open_app"
        if (params.containsKey("action") || params.containsKey("level")) return "set_volume"
        if (params.containsKey("target")) return "open_settings"
        if (params.containsKey("value") || params.containsKey("type")) return "set_timer_alarm"

        // Авто-определение по тексту запроса
        if (prompt.contains("открой") || prompt.contains("запусти")) return "open_app"
        if (prompt.contains("громк") || prompt.contains("звук")) return "set_volume"
        if (prompt.contains("блютуз") || prompt.contains("wifi") || prompt.contains("настройк")) return "open_settings"
        if (prompt.contains("таймер") || prompt.contains("будильник")) return "set_timer_alarm"

        return rawToolName
    }
}
