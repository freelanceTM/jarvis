package com.jarvis.assistant.agent.automation.engine

import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import com.jarvis.assistant.agent.automation.model.TimeRangeCondition
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Почему правило не будет выполнено.
 */
enum class RuleSkipReason {
    DISABLED,
    TIME_CONDITION_NOT_MET,
    COOLDOWN_ACTIVE,
    NO_ACTIONS
}

sealed interface RuleDecision {
    data class Execute(val rule: AutomationEntity) : RuleDecision
    data class Skip(val rule: AutomationEntity, val reason: RuleSkipReason) : RuleDecision
}

/**
 * Чистая логика отбора правил: без Android, Room и корутин — поэтому легко
 * покрывается unit-тестами.
 *
 * Ключевое изменение v0.2: движок возвращает **все** подходящие правила,
 * отсортированные по приоритету. Раньше выполнение прерывалось после первого
 * сработавшего правила (`break`), из-за чего остальные правила на то же
 * событие молча игнорировались.
 */
@Singleton
class RuleEvaluator @Inject constructor(
    private val json: Json
) {

    /**
     * @return решения по каждому правилу в порядке выполнения
     *         (сначала более высокий priority, затем более старые правила).
     */
    fun evaluate(
        rules: List<AutomationEntity>,
        nowMillis: Long,
        currentHour: Int,
        currentMinute: Int
    ): List<RuleDecision> {
        return rules
            .sortedWith(compareByDescending<AutomationEntity> { it.priority }.thenBy { it.id })
            .map { rule -> decide(rule, nowMillis, currentHour, currentMinute) }
    }

    /** Только правила, которые действительно надо выполнить. */
    fun executable(
        rules: List<AutomationEntity>,
        nowMillis: Long,
        currentHour: Int,
        currentMinute: Int
    ): List<AutomationEntity> =
        evaluate(rules, nowMillis, currentHour, currentMinute)
            .filterIsInstance<RuleDecision.Execute>()
            .map { it.rule }

    private fun decide(
        rule: AutomationEntity,
        nowMillis: Long,
        currentHour: Int,
        currentMinute: Int
    ): RuleDecision {
        if (!rule.isEnabled) return RuleDecision.Skip(rule, RuleSkipReason.DISABLED)

        if (isCooldownActive(rule, nowMillis)) {
            return RuleDecision.Skip(rule, RuleSkipReason.COOLDOWN_ACTIVE)
        }

        if (!isTimeConditionSatisfied(rule.conditionsJson, currentHour, currentMinute)) {
            return RuleDecision.Skip(rule, RuleSkipReason.TIME_CONDITION_NOT_MET)
        }

        if (rule.actionsJson.isBlank() || rule.actionsJson == "[]") {
            return RuleDecision.Skip(rule, RuleSkipReason.NO_ACTIONS)
        }

        return RuleDecision.Execute(rule)
    }

    fun isCooldownActive(rule: AutomationEntity, nowMillis: Long): Boolean {
        if (rule.cooldownMs <= 0L) return false
        if (rule.lastTriggeredAt <= 0L) return false
        return nowMillis - rule.lastTriggeredAt < rule.cooldownMs
    }

    /**
     * Пустое условие означает «всегда». Повреждённое или выходящее за
     * календарные границы условие блокирует выполнение (fail closed):
     * автоматизация не должна неожиданно звонить/отправлять данные только
     * потому, что conditionsJson не удалось десериализовать.
     */
    fun isTimeConditionSatisfied(conditionsJson: String, currentHour: Int, currentMinute: Int): Boolean {
        if (currentHour !in 0..23 || currentMinute !in 0..59) return false
        if (conditionsJson.isBlank()) return true

        val condition = try {
            json.decodeFromString(TimeRangeCondition.serializer(), conditionsJson)
        } catch (_: IllegalArgumentException) {
            return false
        } catch (_: kotlinx.serialization.SerializationException) {
            return false
        }

        if (condition.startHour !in 0..23 || condition.endHour !in 0..23 ||
            condition.startMinute !in 0..59 || condition.endMinute !in 0..59
        ) {
            return false
        }

        val current = currentHour * 60 + currentMinute
        val start = condition.startHour * 60 + condition.startMinute
        val end = condition.endHour * 60 + condition.endMinute

        return if (start <= end) {
            current in start..end
        } else {
            // Интервал через полночь, например 22:00–06:00
            current >= start || current <= end
        }
    }
}
