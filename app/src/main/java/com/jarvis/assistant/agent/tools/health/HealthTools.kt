package com.jarvis.assistant.agent.tools.health

import com.jarvis.assistant.agent.capability.DangerLevel
import com.jarvis.assistant.agent.capability.ToolCapabilityContract
import com.jarvis.assistant.agent.core.CapabilityAwareTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Health Tools — честные заглушки-контракты (ветка Health дерева TOOLS).
 *
 * В v0.2 нет ни Wear OS-подключения, ни Health Connect-интеграции, ни
 * локальных сенсоров (пульсометра и т.п.). Каждый инструмент честно
 * возвращает UNSUPPORTED с причиной вместо выдуманных цифр. Когда появится
 * реальный источник данных (Wear OS / Health Connect API), заглушки будут
 * заменены реализациями без изменения контракта — как EmbeddingProvider.
 */

/** Общая реализация: честный UNSUPPORTED с причиной. */
abstract class HealthToolBase(
    final override val toolId: String,
    final override val description: String,
    private val reason: String
) : CapabilityAwareTool {

    final override val category: ToolCategory = ToolCategory.INTELLIGENCE
    final override val riskLevel: ToolRisk = ToolRisk.SAFE
    final override val isOffline: Boolean = true

    final override val capabilityContract = ToolCapabilityContract(
        capabilities = emptySet(),
        dangerLevel = DangerLevel.LOW
    )

    final override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    final override suspend fun execute(arguments: JsonObject): ToolExecutionResult =
        ToolExecutionResult.unsupported(
            summary = "$reason",
            reason = "HEALTH_UNAVAILABLE"
        )
}

@Singleton
class WearOsTool @Inject constructor() : HealthToolBase(
    toolId = "health.wear_os",
    description = "Показывает состояние подключения носимого устройства Wear OS (будет в следующих версиях)",
    reason = "Подключение Wear OS не реализовано в текущей версии, сэр."
)

@Singleton
class HeartRateTool @Inject constructor() : HealthToolBase(
    toolId = "health.heart_rate",
    description = "Показывает текущий пульс с носимого устройства (будет в следующих версиях)",
    reason = "Данные пульса недоступны: нет подключённого носимого устройства или сенсора, сэр."
)

@Singleton
class StepsTool @Inject constructor() : HealthToolBase(
    toolId = "health.steps",
    description = "Показывает количество шагов за день (будет в следующих версиях)",
    reason = "Счётчик шагов недоступен: нет интеграции с Health Connect, сэр."
)

@Singleton
class SleepTool @Inject constructor() : HealthToolBase(
    toolId = "health.sleep",
    description = "Показывает данные сна за прошлую ночь (будет в следующих версиях)",
    reason = "Данные сна недоступны: нет подключённого носимого устройства, сэр."
)

@Singleton
class ActivityTool @Inject constructor() : HealthToolBase(
    toolId = "health.activity",
    description = "Показывает уровень физической активности за день (будет в следующих версиях)",
    reason = "Данные активности недоступны: нет интеграции с Health Connect, сэр."
)
