package com.jarvis.assistant.agent.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Уровни риска выполнения инструмента в Tool Registry 2.0
 */
enum class ToolRisk {
    SAFE,                  // 🟢 Чтение системной информации (батарея, время, память)
    LOW,                   // 🟢 Безопасные действия на устройстве (громкость, фонарик, запуск приложений)
    CONFIRMATION_REQUIRED, // 🟡 Действия, требующие подтверждения голосом (звонки, SMS, переводы)
    HIGH,                  // 🔴 Опасные действия (удаление файлов, очистка данных)
    CRITICAL               // 🔴 Критические операции (финансы, wipe устройства)
}

/**
 * Статус выполнения инструмента
 */
enum class ToolExecutionStatus {
    SUCCESS,
    FAILURE,
    TIMEOUT,
    CANCELLED,
    REQUIRES_USER_CONFIRMATION,
    REQUIRES_SYSTEM_PANEL
}

/**
 * Структурированный результат выполнения Tool Registry 2.0
 */
data class ToolExecutionResult(
    val status: ToolExecutionStatus,
    val summary: String,
    val data: JsonObject? = null,
    val rollbackData: JsonObject? = null,
    val executionTimeMs: Long = 0L,
    val error: String? = null,
    val actionRequiresUser: Boolean = false,
    val pendingCall: ToolCall? = null
) {
    val isSuccess: Boolean get() = status == ToolExecutionStatus.SUCCESS

    companion object {
        fun success(
            summary: String,
            data: JsonObject? = null,
            rollbackData: JsonObject? = null,
            executionTimeMs: Long = 0L,
            actionRequiresUser: Boolean = false
        ) = ToolExecutionResult(
            status = ToolExecutionStatus.SUCCESS,
            summary = summary,
            data = data,
            rollbackData = rollbackData,
            executionTimeMs = executionTimeMs,
            actionRequiresUser = actionRequiresUser
        )

        fun failure(
            summary: String,
            error: String,
            executionTimeMs: Long = 0L
        ) = ToolExecutionResult(
            status = ToolExecutionStatus.FAILURE,
            summary = summary,
            error = error,
            executionTimeMs = executionTimeMs
        )

        fun timeout(
            toolName: String,
            timeoutMs: Long
        ) = ToolExecutionResult(
            status = ToolExecutionStatus.TIMEOUT,
            summary = "Превышен таймаут выполнения инструмента $toolName ($timeoutMs мс)",
            error = "ExecutionTimeoutException"
        )

        fun requiresConfirmation(
            message: String,
            pendingCall: ToolCall
        ) = ToolExecutionResult(
            status = ToolExecutionStatus.REQUIRES_USER_CONFIRMATION,
            summary = message,
            pendingCall = pendingCall
        )
    }
}

/**
 * Структурированный вызов инструмента от LLM / Fast Router
 */
data class ToolCall(
    val toolId: String,
    val arguments: JsonObject,
    val callId: String = UUID.randomUUID().toString()
) {
    val name: String get() = toolId
}

/**
 * Метаданные спецификации инструмента для предоставления в AI
 */
data class ToolDefinition(
    val toolId: String,
    val name: String,
    val description: String,
    val parametersSchema: JsonObject,
    val riskLevel: ToolRisk,
    val isOffline: Boolean,
    val executionTimeoutMs: Long,
    val supportsParallel: Boolean,
    val requiresForeground: Boolean
)
