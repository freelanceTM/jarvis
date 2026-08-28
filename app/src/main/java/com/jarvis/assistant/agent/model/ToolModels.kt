package com.jarvis.assistant.agent.model

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
    REQUIRES_SYSTEM_PANEL,

    /**
     * Отсутствует runtime-разрешение Android. Действие НЕ выполнено.
     * В [ToolExecutionResult.missingPermissions] перечислено, что нужно запросить.
     */
    PERMISSION_REQUIRED,

    /**
     * Android не позволяет выполнить действие программно: требуется, чтобы
     * пользователь сам подтвердил его в системном UI. Действие НЕ выполнено,
     * но пользователь направлен в нужный системный экран.
     */
    USER_ACTION_REQUIRED,

    /**
     * Возможность отсутствует на данном устройстве или API-level.
     * Честный отказ вместо имитации успеха.
     */
    UNSUPPORTED
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
    val pendingCall: ToolCall? = null,
    /** Разрешения, которых не хватило для выполнения действия. */
    val missingPermissions: List<String> = emptyList()
) {
    val isSuccess: Boolean get() = status == ToolExecutionStatus.SUCCESS

    /**
     * Действие фактически НЕ изменило состояние устройства, но и не является
     * технической ошибкой: нужно разрешение, согласие пользователя, либо
     * возможность отсутствует на этом Android.
     */
    val isBlockedByAndroid: Boolean
        get() = status == ToolExecutionStatus.PERMISSION_REQUIRED ||
            status == ToolExecutionStatus.USER_ACTION_REQUIRED ||
            status == ToolExecutionStatus.UNSUPPORTED

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

        /**
         * Действие не выполнено: не хватает runtime-разрешения.
         * Никогда не должно подменяться на success().
         */
        fun permissionRequired(
            summary: String,
            permissions: List<String>,
            data: JsonObject? = null
        ) = ToolExecutionResult(
            status = ToolExecutionStatus.PERMISSION_REQUIRED,
            summary = summary,
            error = "PERMISSION_REQUIRED",
            data = data,
            actionRequiresUser = true,
            missingPermissions = permissions
        )

        /**
         * Действие не выполнено программно: Android требует, чтобы пользователь
         * завершил его сам (обычно в системном UI, куда мы его направили).
         */
        fun userActionRequired(
            summary: String,
            reason: String,
            data: JsonObject? = null
        ) = ToolExecutionResult(
            status = ToolExecutionStatus.USER_ACTION_REQUIRED,
            summary = summary,
            error = reason,
            data = data,
            actionRequiresUser = true
        )

        /** Возможность недоступна на данном устройстве / API-level. */
        fun unsupported(
            summary: String,
            reason: String,
            data: JsonObject? = null
        ) = ToolExecutionResult(
            status = ToolExecutionStatus.UNSUPPORTED,
            summary = summary,
            error = reason,
            data = data
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
    val requiresForeground: Boolean,
    /** Группа Android Capability Layer (например, "device.bluetooth"), если применимо. */
    val capabilityId: String? = null
)
