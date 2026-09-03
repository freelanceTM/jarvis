package com.jarvis.assistant.agent.tools.productivity

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.tools.verification.ExecutionVerification
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Будильники/таймеры С ВЕРИФИКАЦИЕЙ результата (execute → verify → SUCCESS).
 *
 * [AlarmClock.ACTION_SET_ALARM] с EXTRA_SKIP_UI — fire-and-forget: startActivity
 * проходит, а приложение часов МОЖЕТ молча не сохранить будильник (права,
 * политика приложения). Для будильника, на который пользователь рассчитывает
 * проснуться, «не подтвердилось» ≠ «готово»:
 *
 *  - будильник: после отправки интента читается [AlarmManager.nextAlarmClockInfo]
 *    (read-back); SUCCESS только если следующий будильник системы стоит на
 *    запрошенный час. Иначе — USER_ACTION_REQUIRED «проверьте в часах»;
 *  - таймер: публичного API для верификации системного таймера НЕТ, поэтому
 *    формулировка честно ограничена сделанным действием: «отправлен в
 *    приложение часов» (проверить сохранение можно только в UI часов).
 */
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
            if (isAlarm) setVerifiedAlarm(value, label) else setHonestTimer(value, label)
        } catch (e: android.content.ActivityNotFoundException) {
            ToolExecutionResult.failure("Приложение часов не найдено на этом устройстве", "CLOCK_APP_NOT_FOUND")
        } catch (e: Exception) {
            ToolExecutionResult.failure("Не удалось установить $type: ${e.localizedMessage}", "ALARM_TIMER_ERROR")
        }
    }

    /** Будильник: intent → read-back nextAlarmClockInfo → SUCCESS только при подтверждении. */
    private suspend fun setVerifiedAlarm(hour: Int, label: String): ToolExecutionResult {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, 0)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Разрешение проверяется через PackageManager напрямую (а не
        // Intent.resolveActivity): семантика та же, но вызов верифицируем
        // в JVM-тестах, где тела android-заглушек вырезаны.
        if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            return ToolExecutionResult.failure(
                "Приложение часов не найдено — будильник установить некому",
                "CLOCK_APP_NOT_FOUND"
            )
        }

        val now = System.currentTimeMillis()
        context.startActivity(intent)

        // -------------------------------------------------------------- VERIFY
        // Приложению часов нужно время применить будильник — поллинг read-back.
        val verifiedTrigger: Long? = alarmManager?.let { am ->
            ExecutionVerification.pollFor(
                attempts = ALARM_VERIFY_ATTEMPTS,
                stepMs = ALARM_VERIFY_STEP_MS,
                read = { am.nextAlarmClockInfo?.triggerTime },
                satisfied = { ExecutionVerification.nextAlarmMatchesHour(it, now, hour) }
            )
        }

        val verified = ExecutionVerification.nextAlarmMatchesHour(verifiedTrigger, now, hour)

        val data = buildJsonObject {
            put("hour", hour)
            put("label", label)
            put("verified", verified)
            put("next_trigger_ms", verifiedTrigger)
        }

        return if (verified) {
            ToolExecutionResult.success("Будильник установлен на $hour:00", data = data)
        } else {
            // Честный исход: отправлено, но система не подтвердила. Для будильника,
            // на который рассчитывают проснуться, это USER_ACTION_REQUIRED.
            ToolExecutionResult.userActionRequired(
                summary = "Будильник на $hour:00 отправлен в приложение часов, но система его не подтвердила. Проверьте будильник в часах, сэр.",
                reason = "ALARM_UNVERIFIED",
                data = data
            )
        }
    }

    /**
     * Таймер: публичного API верификации системного таймера нет. Формулировка
     * честно ограничена действием (интент доставлен приложению часов), а не
     * неподтверждаемым результатом.
     */
    private fun setHonestTimer(minutes: Int, label: String): ToolExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            return ToolExecutionResult.failure(
                "Приложение часов не найдено — таймер установить некому",
                "CLOCK_APP_NOT_FOUND"
            )
        }
        context.startActivity(intent)
        return ToolExecutionResult.success(
            summary = "Таймер на $minutes минут отправлен в приложение часов",
            data = buildJsonObject {
                put("minutes", minutes)
                put("label", label)
                put("verified", false)
            }
        )
    }

    private companion object {
        /** Бюджет верификации будильника: 6 × 100 мс — много меньше tool-таймаута 4 с. */
        const val ALARM_VERIFY_ATTEMPTS = 6
        const val ALARM_VERIFY_STEP_MS = 100L
    }
}
