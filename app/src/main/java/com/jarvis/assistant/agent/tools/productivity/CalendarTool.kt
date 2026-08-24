package com.jarvis.assistant.agent.tools.productivity

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "productivity.calendar"
    override val description: String = "Добавляет событие или напоминание в системный календарь Google/Samsung"
    override val category: ToolCategory = ToolCategory.PRODUCTIVITY
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val mayDiscloseUserContentExternally: Boolean = true
    override val requiresForeground: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") {
                put("type", "string")
                put("description", "Название события (например: Встреча с инвестором, Тренировка)")
            }
            putJsonObject("description") {
                put("type", "string")
                put("description", "Описание или заметки к встрече")
            }
        }
        put("required", buildJsonArray { add("title") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val title = arguments["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val desc = arguments["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        if (title.isEmpty()) {
            return ToolExecutionResult.failure("Укажите название события", "MISSING_TITLE")
        }

        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.Events.DESCRIPTION, desc)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolExecutionResult.success("Открываю добавление события '$title' в календарь", actionRequiresUser = true)
        } catch (e: Exception) {
            ToolExecutionResult.failure("Не удалось открыть календарь: ${e.localizedMessage}", "CALENDAR_ERROR")
        }
    }
}
