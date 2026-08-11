package com.jarvis.assistant.agent.core

import com.jarvis.assistant.agent.model.ToolDefinition
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.JsonObject

enum class ToolCategory(val displayName: String) {
    SYSTEM("Система и статус"),
    DEVICE("Управление устройством"),
    COMMUNICATION("Связь и уведомления"),
    PRODUCTIVITY("Задачи и автоматизация"),
    INTELLIGENCE("Интеллект, память и поиск")
}

/**
 * Стандарт контракта Tool Registry 2.0 в JARVIS v0.3
 */
interface JarvisTool {
    val toolId: String
    val name: String get() = toolId
    val description: String
    val category: ToolCategory
    val parametersSchema: JsonObject
    val riskLevel: ToolRisk

    val requiresConfirmation: Boolean
        get() = riskLevel == ToolRisk.CONFIRMATION_REQUIRED || riskLevel == ToolRisk.HIGH || riskLevel == ToolRisk.CRITICAL

    val isOffline: Boolean
        get() = true

    val executionTimeoutMs: Long
        get() = 4000L

    val supportsParallel: Boolean
        get() = true

    val requiresForeground: Boolean
        get() = false

    fun toDefinition(): ToolDefinition = ToolDefinition(
        toolId = toolId,
        name = name,
        description = description,
        parametersSchema = parametersSchema,
        riskLevel = riskLevel,
        isOffline = isOffline,
        executionTimeoutMs = executionTimeoutMs,
        supportsParallel = supportsParallel,
        requiresForeground = requiresForeground
    )

    /**
     * Основное выполнение действия в системе Android
     */
    suspend fun execute(arguments: JsonObject): ToolExecutionResult

    /**
     * Транзакционный откат действия в случае ошибки в цепочке сценария (Rollback)
     */
    suspend fun rollback(arguments: JsonObject, rollbackData: JsonObject?): Boolean {
        return false // По умолчанию для необратимых действий (например, опрос времени/батареи)
    }
}
