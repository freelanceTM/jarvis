package com.jarvis.assistant.agent.tools.intelligence

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
 * Vision Tool — честная заглушка-контракт (ветка Intelligence → Vision).
 *
 * Описание экрана/фото требует модели компьютерного зрения (multimodal LLM
 * или локальной CV-модели). В v0.2 такая модель НЕ включена — инструмент
 * возвращает UNSUPPORTED с объяснением, а не выдуманное описание.
 * Когда появится vision-провайдер (например, multimodal-эндпоинт), этот
 * тул получит реальную реализацию без изменения контракта.
 */
@Singleton
class VisionTool @Inject constructor() : CapabilityAwareTool {

    override val toolId: String = "intelligence.vision"
    override val description: String = "Описывает изображение или экран (модель компьютерного зрения — будет в следующих версиях)"
    override val category: ToolCategory = ToolCategory.INTELLIGENCE
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = false

    override val capabilityContract = ToolCapabilityContract(
        capabilities = emptySet(),
        dangerLevel = DangerLevel.LOW
    )

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("image_source") {
                put("type", "string")
                put("description", "Источник изображения: 'screen' — текущий экран, либо путь к файлу")
            }
        }
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult =
        ToolExecutionResult.unsupported(
            summary = "Описание изображений недоступно: модель компьютерного зрения не включена в текущую версию. " +
                "Я могу прочитать содержимое экрана текстом (accessibility.screen_reader), сэр.",
            reason = "VISION_MODEL_UNAVAILABLE"
        )
}
