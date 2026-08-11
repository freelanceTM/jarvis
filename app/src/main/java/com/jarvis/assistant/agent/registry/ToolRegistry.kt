package com.jarvis.assistant.agent.registry

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolDefinition
import com.jarvis.assistant.agent.model.ToolResult
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRegistry @Inject constructor(
    tools: Set<@JvmSuppressWildcards JarvisTool>
) {
    private val toolsByName: Map<String, JarvisTool> = tools.associateBy { it.name }

    fun getTool(name: String): JarvisTool? = toolsByName[name]

    fun getAllTools(): List<JarvisTool> = toolsByName.values.toList()

    fun getToolDefinitions(): List<ToolDefinition> = toolsByName.values.map { it.toDefinition() }

    suspend fun execute(name: String, arguments: JsonObject): ToolResult {
        val tool = toolsByName[name]
            ?: return ToolResult.Error(
                message = "Инструмент '$name' не зарегистрирован в системе",
                code = "TOOL_NOT_FOUND"
            )

        return try {
            tool.execute(arguments)
        } catch (e: Exception) {
            ToolResult.Error(
                message = e.localizedMessage ?: "Ошибка выполнения инструмента $name",
                code = "TOOL_EXECUTION_ERROR"
            )
        }
    }

    /**
     * Формирует системную инструкцию для LLM со списком доступных инструментов
     */
    fun buildSystemPrompt(): String {
        val sb = StringBuilder()
        sb.append("Ты JARVIS — автономный агент, управляющий системой Android.\n")
        sb.append("Доступные инструменты (Tools):\n")

        toolsByName.values.forEach { tool ->
            sb.append("- \"${tool.name}\": ${tool.description}. Схема: ${tool.parametersSchema}\n")
        }

        sb.append("\nПРАВИЛА ВЫЗОВА ИНСТРУМЕНТОВ:\n")
        sb.append("Если запрос пользователя требует действия, верни JSON-структуру строго в формате:\n")
        sb.append("```json\n")
        sb.append("{\n")
        sb.append("  \"tool_calls\": [\n")
        sb.append("    {\n")
        sb.append("      \"tool\": \"имя_инструмента\",\n")
        sb.append("      \"arguments\": { ... }\n")
        sb.append("    }\n")
        sb.append("  ]\n")
        sb.append("}\n")
        sb.append("```\n")
        sb.append("Если действие не требуется — отвечай лаконичным текстом (1-2 предложения) для озвучки.\n")

        return sb.toString()
    }
}
