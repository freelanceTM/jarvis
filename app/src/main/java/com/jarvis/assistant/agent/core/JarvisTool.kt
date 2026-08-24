package com.jarvis.assistant.agent.core

import com.jarvis.assistant.agent.capability.JarvisCapability
import com.jarvis.assistant.agent.capability.ToolCapabilityContract
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

    /**
     * True when arguments can leave the JARVIS process/device boundary, even if
     * the tool itself does not require network access (share intents, dialer,
     * accessibility text entry, synced calendar, and similar hand-offs).
     */
    val mayDiscloseUserContentExternally: Boolean
        get() = !isOffline

    /** Local-only context the privacy gate must classify before executing. */
    fun externalPrivacyContext(arguments: JsonObject): List<String> = emptyList()

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
        requiresForeground = requiresForeground,
        capabilityId = (this as? CapabilityAwareTool)?.capability?.id
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

/**
 * Инструмент, который явно объявляет свои требования к устройству и разрешениям.
 *
 * Позволяет агенту ответить на вопрос «могу ли я выполнить это действие на данном
 * устройстве» ДО вызова [JarvisTool.execute] — вместо того, чтобы узнавать об
 * ограничении Android постфактум.
 */
interface CapabilityAwareTool : JarvisTool {
    val capabilityContract: ToolCapabilityContract

    /**
     * Группа Android Capability Layer, к которой относится инструмент
     * (например, [JarvisCapability.Bluetooth] для device.bluetooth).
     * `null`, если инструмент не привязан ни к одному домену слоя.
     */
    val capability: JarvisCapability?
        get() = null

    override val requiresConfirmation: Boolean
        get() = capabilityContract.confirmationRequired ||
            riskLevel == ToolRisk.CONFIRMATION_REQUIRED ||
            riskLevel == ToolRisk.HIGH ||
            riskLevel == ToolRisk.CRITICAL
}
