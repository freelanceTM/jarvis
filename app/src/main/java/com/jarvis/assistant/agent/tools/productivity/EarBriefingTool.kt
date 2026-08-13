package com.jarvis.assistant.agent.tools.productivity

import com.jarvis.assistant.agent.briefing.ProactiveEarBriefingEngine
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
class EarBriefingTool @Inject constructor(
    private val briefingEngine: ProactiveEarBriefingEngine
) : JarvisTool {

    override val toolId: String = "productivity.ear_briefing"
    override val description: String = "Формирует персональный голосовой аудио-брифинг в наушник: время, заряд, статус систем, погода и планы"
    override val category: ToolCategory = ToolCategory.PRODUCTIVITY
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val briefingText = briefingEngine.generateBriefing()
        return ToolExecutionResult.success(
            summary = briefingText,
            data = buildJsonObject {
                put("briefing", briefingText)
            }
        )
    }
}
