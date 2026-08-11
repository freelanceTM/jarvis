package com.jarvis.assistant.agent.registry

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolDefinition
import com.jarvis.assistant.agent.model.ToolExecutionResult
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRegistry @Inject constructor(
    tools: Set<@JvmSuppressWildcards JarvisTool>
) {
    // Индексация по полному toolId и короткому имени для обратной совместимости
    private val toolsById: Map<String, JarvisTool> = tools.associateBy { it.toolId }
    private val toolsByName: Map<String, JarvisTool> = tools.associateBy { it.name.substringAfterLast(".") }

    fun getTool(identifier: String): JarvisTool? {
        return toolsById[identifier] ?: toolsByName[identifier]
    }

    fun getAllTools(): List<JarvisTool> = toolsById.values.toList()

    fun getToolsByCategory(category: ToolCategory): List<JarvisTool> {
        return toolsById.values.filter { it.category == category }
    }

    fun getToolDefinitions(): List<ToolDefinition> = toolsById.values.map { it.toDefinition() }

    suspend fun execute(toolIdentifier: String, arguments: JsonObject): ToolExecutionResult {
        val tool = getTool(toolIdentifier)
            ?: return ToolExecutionResult.failure(
                summary = "Инструмент '$toolIdentifier' не найден в Tool Registry 2.0",
                error = "TOOL_NOT_FOUND"
            )

        val startTime = System.currentTimeMillis()
        return try {
            val result = tool.execute(arguments)
            val duration = System.currentTimeMillis() - startTime
            result.copy(executionTimeMs = duration)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            ToolExecutionResult.failure(
                summary = e.localizedMessage ?: "Ошибка выполнения инструмента $toolIdentifier",
                error = e.javaClass.simpleName,
                executionTimeMs = duration
            )
        }
    }

    /**
     * Формирует системную инструкцию со спецификациями схемы Tool Registry 2.0
     */
    fun buildSystemPrompt(): String {
        val sb = StringBuilder()
        sb.append("Ты автономный агент JARVIS, управляющий операционной системой Android.\n")
        sb.append("Доступные зарегистрированные инструменты (Tool Registry 2.0):\n")

        toolsById.values.forEach { tool ->
            val offlineBadge = if (tool.isOffline) "[ОФЛАЙН]" else "[ОНЛАЙН]"
            sb.append("- \"${tool.toolId}\" $offlineBadge: ${tool.description}. Схема: ${tool.parametersSchema}\n")
        }

        sb.append("\nПРАВИЛА СТРУКТУРИРОВАННОГО ВЫЗОВА (JSON):\n")
        sb.append("Если запрос пользователя требует действий, верни JSON строго в формате:\n")
        sb.append("```json\n")
        sb.append("{\n")
        sb.append("  \"tool_calls\": [\n")
        sb.append("    {\n")
        sb.append("      \"tool\": \"идентификатор_инструмента\",\n")
        sb.append("      \"arguments\": { ... }\n")
        sb.append("    }\n")
        sb.append("  ]\n")
        sb.append("}\n")
        sb.append("```\n")
        sb.append("Если действие не требуется — отвечай кратко в 1-2 предложения.\n")

        return sb.toString()
    }
}
