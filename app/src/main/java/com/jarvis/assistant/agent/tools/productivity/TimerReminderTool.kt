package com.jarvis.assistant.agent.tools.productivity

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.jarvis.assistant.agent.registry.JarvisTool
import com.jarvis.assistant.agent.registry.ToolCategory
import com.jarvis.assistant.agent.registry.ToolParamSpec
import com.jarvis.assistant.agent.registry.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimerReminderTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val name: String = "set_timer_alarm"
    override val description: String = "Устанавливает таймер на количество минут или будильник на определенный час"
    override val category: ToolCategory = ToolCategory.PRODUCTIVITY

    override val parameters: List<ToolParamSpec> = listOf(
        ToolParamSpec(
            name = "type",
            type = "string",
            description = "timer (таймер в секундах/минутах) или alarm (будильник на время)"
        ),
        ToolParamSpec(
            name = "value",
            type = "number",
            description = "Для таймера: минуты (например 5, 10). Для будильника: час (например 7, 8)"
        ),
        ToolParamSpec(
            name = "message",
            type = "string",
            description = "Название или заметка таймера/будильника",
            isRequired = false
        )
    )

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val type = args["type"]?.lowercase()?.trim() ?: "timer"
        val value = args["value"]?.toIntOrNull() ?: 5
        val message = args["message"] ?: "JARVIS Reminder"

        return try {
            if (type.contains("alarm") || type.contains("будильник")) {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, value)
                    putExtra(AlarmClock.EXTRA_MINUTES, 0)
                    putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolResult.Success("Будильник установлен на $value:00")
            } else {
                val seconds = value * 60
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolResult.Success("Таймер установлен на $value минут")
            }
        } catch (e: Exception) {
            ToolResult.Failure("Не удалось запустить таймер/будильник", e.localizedMessage ?: "")
        }
    }
}
