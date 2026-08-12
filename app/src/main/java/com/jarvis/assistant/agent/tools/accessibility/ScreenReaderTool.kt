package com.jarvis.assistant.agent.tools.accessibility

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenReaderTool @Inject constructor() : JarvisTool {

    override val toolId: String = "accessibility.screen_reader"
    override val description: String = "Считывает и анализирует весь видимый текстовый контент на текущем экране смартфона"
    override val category: ToolCategory = ToolCategory.SYSTEM
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        if (!JarvisAccessibilityService.isServiceRunning()) {
            return ToolExecutionResult.failure(
                summary = "Служба специальных возможностей не включена. Включите JARVIS в настройках Спец. возможностей Android.",
                error = "ACCESSIBILITY_SERVICE_DISABLED"
            )
        }

        val screenText = JarvisAccessibilityService.getScreenContent()
        val trimmed = if (screenText.length > 400) screenText.take(400) + "..." else screenText

        return ToolExecutionResult.success(
            summary = "На экране: $trimmed",
            data = buildJsonObject {
                put("full_text", screenText)
                put("summary", trimmed)
            }
        )
    }
}
