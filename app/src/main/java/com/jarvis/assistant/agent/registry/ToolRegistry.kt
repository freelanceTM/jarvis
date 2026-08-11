package com.jarvis.assistant.agent.registry

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRegistry @Inject constructor(
    private val toolsSet: Set<@JvmSuppressWildcards JarvisTool>
) {
    private val toolsMap: Map<String, JarvisTool> = toolsSet.associateBy { it.name }

    fun getAllTools(): List<JarvisTool> = toolsMap.values.toList()

    fun getTool(name: String): JarvisTool? = toolsMap[name]

    suspend fun executeTool(toolName: String, arguments: Map<String, String>): ToolResult {
        val tool = getTool(toolName)
            ?: return ToolResult.Failure(
                summary = "Инструмент '$toolName' не найден",
                errorMessage = "ToolNotFound: $toolName"
            )

        return try {
            tool.execute(arguments)
        } catch (e: Exception) {
            ToolResult.Failure(
                summary = "Ошибка при выполнении $toolName: ${e.localizedMessage}",
                errorMessage = e.localizedMessage ?: "Unknown error"
            )
        }
    }

    /**
     * Генерирует компактное описание инструментов для системного промпта LLM
     */
    fun buildToolsSystemPrompt(): String {
        val sb = StringBuilder()
        sb.append("Доступные системные инструменты Android:\n")
        toolsMap.values.forEach { tool ->
            sb.append("- ${tool.name}: ${tool.description}. Параметры: ")
            sb.append(tool.parameters.joinToString(", ") { "${it.name} (${it.type}): ${it.description}" })
            sb.append("\n")
        }
        sb.append("\nЕсли запрос пользователя требует действия с телефоном, ответь строго в формате JSON-вызова действия:\n")
        sb.append("ACTION_CALL: {\"tool\": \"tool_name\", \"params\": {\"param1\": \"val1\"}}\n")
        sb.append("Если действие не требуется — отвечай обычным текстом кратко.\n")
        return sb.toString()
    }
}
