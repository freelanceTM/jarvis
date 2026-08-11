package com.jarvis.assistant.agent.registry

import kotlinx.serialization.json.JsonObject

sealed interface ToolResult {
    val summary: String
    val isSuccess: Boolean

    data class Success(override val summary: String, val data: Map<String, String> = emptyMap()) : ToolResult {
        override val isSuccess: Boolean = true
    }

    data class Failure(override val summary: String, val errorMessage: String) : ToolResult {
        override val isSuccess: Boolean = false
    }
}

data class ToolParamSpec(
    val name: String,
    val type: String, // "string", "number", "boolean"
    val description: String,
    val isRequired: Boolean = true
)

interface JarvisTool {
    val name: String
    val description: String
    val parameters: List<ToolParamSpec>
    val category: ToolCategory

    suspend fun execute(args: Map<String, String>): ToolResult
}

enum class ToolCategory(val displayName: String) {
    DEVICE("Управление устройством"),
    COMMUNICATION("Связь и звонки"),
    PRODUCTIVITY("Продуктивность и задачи"),
    INTERNET("Интернет и поиск")
}
