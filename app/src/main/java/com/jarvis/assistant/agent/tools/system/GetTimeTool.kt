package com.jarvis.assistant.agent.tools.system

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetTimeTool @Inject constructor() : JarvisTool {

    override val name: String = "get_time"
    override val description: String = "Возвращает точное текущее время, дату и день недели"
    override val risk: ToolRisk = ToolRisk.SAFE

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val locale = Locale("ru", "RU")
        val now = Date()
        val timeFormat = SimpleDateFormat("HH:mm", locale)
        val dateFormat = SimpleDateFormat("d MMMM yyyy г., EEEE", locale)

        val timeStr = timeFormat.format(now)
        val dateStr = dateFormat.format(now)

        return ToolResult.Success("Сейчас $timeStr. Сегодня $dateStr")
    }
}
