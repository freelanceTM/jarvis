package com.jarvis.assistant.agent.core

import com.jarvis.assistant.agent.model.ToolDefinition
import com.jarvis.assistant.agent.model.ToolResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.JsonObject

/**
 * Базовый контракт любого инструмента в экосистеме JARVIS.
 * Каждая способность системы является изолированным независимым классом.
 */
interface JarvisTool {
    val name: String
    val description: String
    val parametersSchema: JsonObject
    val risk: ToolRisk

    fun toDefinition(): ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parametersSchema = parametersSchema,
        risk = risk
    )

    suspend fun execute(arguments: JsonObject): ToolResult
}
