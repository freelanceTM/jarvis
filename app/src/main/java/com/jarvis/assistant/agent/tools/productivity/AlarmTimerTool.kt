package com.jarvis.assistant.agent.tools.productivity

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmTimerTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "productivity.alarm_timer"
    override val description: String = "Устанавливает системный таймер в секундах/минутах или будильник на точное время (без лишних кликов на экране)"
    override val category: ToolCategory = ToolCategory.PRODUCTIVITY
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val mayDiscloseUserContentExternally: Boolean = true
    override val supportsParallel: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("type") {
                put("type", "string")
                put("description", "timer (таймер) или alarm (будильник)")
            }
            putJsonObject("value") {
                put("type", "number")
                put("description", "Для таймера: минуты (например 5, 10, 30). Для будильника: час (например 7, 8, 14)")
            }
            putJsonObject("label") {
                put("type", "string")
                put("description", "Название или метка таймера/будильника")
            }
        }
        put("required", buildJsonArray { add("type"); add("value") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val type = arguments["type"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim() ?: "timer"
        val value = arguments["value"]?.jsonPrimitive?.intOrNull ?: 5
        val label = arguments["label"]?.jsonPrimitive?.contentOrNull?.take(100) ?: "JARVIS"
        val isAlarm = type == "alarm" || type == "будильник"
        val isTimer = type == "timer" || type == "таймер"

        if (!isAlarm && !isTimer) {
            return ToolExecutionResult.failure("Неизвестный тип: $type", "INVALID_TYPE")
        }
        if (isAlarm && value !in 0..23) {
            return ToolExecutionResult.failure("Час будильника должен быть от 0 до 23", "INVALID_ALARM_HOUR")
        }
        if (isTimer && value !in 1..1_440) {
            return ToolExecutionResult.failure("Таймер должен быть от 1 до 1440 минут", "INVALID_TIMER_DURATION")
        }

        return try {
            if (isAlarm) {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, value)
                    putExtra(AlarmClock.EXTRA_MINUTES, 0)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true) // Без открытия интерфейса прямо в кармане!
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolExecutionResult.success("Будильник установлен на $value:00")
            } else {
                val seconds = value * 60
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true) // Без открытия экрана!
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolExecutionResult.success("Таймер установлен на $value минут")
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure("Не удалось запустить таймер: ${e.localizedMessage}", "TIMER_ERROR")
        }
    }
}
