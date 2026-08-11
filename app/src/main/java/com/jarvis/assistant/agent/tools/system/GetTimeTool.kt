package com.jarvis.assistant.agent.tools.system

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetTimeTool @Inject constructor() : JarvisTool {

    override val toolId: String = "system.time"
    override val description: String = "Возвращает точное текущее время, дату и день недели"
    override val category: ToolCategory = ToolCategory.SYSTEM
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = true
    override val supportsParallel: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val locale = Locale("ru", "RU")
        val now = Date()
        val timeFormat = SimpleDateFormat("HH:mm", locale)
        val dateFormat = SimpleDateFormat("d MMMM yyyy г., EEEE", locale)

        val timeStr = timeFormat.format(now)
        val dateStr = dateFormat.format(now)

        val summary = "Сейчас $timeStr. Сегодня $dateStr"
        val dataObj = buildJsonObject {
            put("time", timeStr)
            put("date", dateStr)
            put("timestamp", now.time)
        }

        return ToolExecutionResult.success(summary = summary, data = dataObj)
    }
}
