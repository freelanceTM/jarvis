package com.jarvis.assistant.agent.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Уровни риска выполнения инструмента (Safety Layer)
 */
enum class ToolRisk {
    SAFE,                  // Чтение данных (время, батарея, инфо)
    LOW,                   // Безопасные действия (громкость, фонарик, запуск приложений)
    CONFIRMATION_REQUIRED, // Действия, требующие подтверждения голосом (звонки, SMS)
    HIGH                   // Опасные действия (удаление данных, оплата)
}

/**
 * Структурированный вызов инструмента от LLM
 */
data class ToolCall(
    val name: String,
    val arguments: JsonObject
)

/**
 * Результат выполнения инструмента
 */
sealed interface ToolResult {
    val message: String

    data class Success(
        override val message: String,
        val data: JsonObject? = null,
        val actionRequiresUser: Boolean = false
    ) : ToolResult

    data class RequiresConfirmation(
        override val message: String,
        val pendingCall: ToolCall
    ) : ToolResult

    data class Error(
        override val message: String,
        val code: String? = null
    ) : ToolResult
}

/**
 * Метаданные инструмента для предоставления в LLM
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchema: JsonObject,
    val risk: ToolRisk
)
