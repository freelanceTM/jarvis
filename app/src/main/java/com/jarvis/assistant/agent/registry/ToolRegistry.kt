package com.jarvis.assistant.agent.registry

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.discovery.ToolDiscoveryEngine
import com.jarvis.assistant.agent.model.ToolDefinition
import com.jarvis.assistant.agent.model.ToolExecutionResult
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRegistry @Inject constructor(
    tools: Set<@JvmSuppressWildcards JarvisTool>,
    private val discoveryEngine: ToolDiscoveryEngine
) {
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

    /**
     * Tool Discovery 2.0: Динамически отбирает 3-4 инструмента под конкретный запрос
     */
    fun discoverRelevantTools(userQuery: String, maxTools: Int = 4): List<JarvisTool> {
        return discoveryEngine.discoverTools(userQuery, getAllTools(), maxTools)
    }

    /**
     * Формирует сжатый системный промпт ТОЛЬКО для найденных через Discovery инструментов
     */
    fun buildTargetedSystemPrompt(userQuery: String): String {
        val discovered = discoverRelevantTools(userQuery)
        return discoveryEngine.buildTargetedToolsPrompt(discovered)
    }

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
}
