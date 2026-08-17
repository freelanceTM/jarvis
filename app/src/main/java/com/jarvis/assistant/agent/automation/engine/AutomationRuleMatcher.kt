package com.jarvis.assistant.agent.automation.engine

import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import com.jarvis.assistant.agent.automation.model.AutomationTriggerType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rule Matcher — мост «событие → список правил».
 *
 * Архитектура v0.2 (вместо «одно событие → одно правило»):
 *
 *   Event
 *    ↓
 *   RuleMatcher
 *    ↓
 *   List<AutomationRule>
 *    ↓
 *   execute all matching rules
 *
 * Матчер принимает КАНДИДАТОВ (правила события, загруженные из DAO) и
 * возвращает ВСЕ правила, которые реально нужно выполнить сейчас:
 *  - системные события → условия (время/кулдаун/включено) через [RuleEvaluator];
 *  - расписание (TIME_SCHEDULE) → точное время из triggerParam ("07:00").
 *
 * Результат отсортирован по приоритету (больший — раньше), поэтому
 * исполнитель просто проходит список по порядку.
 */
@Singleton
class AutomationRuleMatcher @Inject constructor(
    private val evaluator: RuleEvaluator
) {

    /**
     * Матчинг для системного события: все правила события, прошедшие
     * проверку условий (включено, кулдаун, временной диапазон).
     */
    fun matchForTrigger(
        rules: List<AutomationEntity>,
        nowMillis: Long,
        currentHour: Int,
        currentMinute: Int
    ): List<AutomationEntity> =
        evaluator.executable(rules, nowMillis, currentHour, currentMinute)

    /**
     * Матчинг для расписания (TIME_SCHEDULE).
     *
     *  - triggerParam "HH:MM" — точное время (например "07:00": сработает
     *    в 07:00);
     *  - без triggerParam — fallback на временной диапазон conditionsJson.
     *
     * Кулдаун и isEnabled проверяются тем же [RuleEvaluator], что и для
     * системных событий, — поведение консистентно.
     */
    fun matchForSchedule(
        rules: List<AutomationEntity>,
        nowMillis: Long,
        currentHour: Int,
        currentMinute: Int
    ): List<AutomationEntity> {
        val scheduleRules = rules.filter { it.triggerType == AutomationTriggerType.TIME_SCHEDULE.name }
        val timeMatched = scheduleRules.filter { matchesScheduleTime(it, currentHour, currentMinute) }
        return evaluator.executable(timeMatched, nowMillis, currentHour, currentMinute)
    }

    /** true, если текущее время совпадает с точным временем правила. */
    private fun matchesScheduleTime(rule: AutomationEntity, hour: Int, minute: Int): Boolean {
        val param = rule.triggerParam.trim()
        if (param.isNotEmpty()) {
            val parts = param.split(":")
            val paramHour = parts.getOrNull(0)?.toIntOrNull() ?: return false
            val paramMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return paramHour == hour && paramMinute == minute
        }
        // Без triggerParam — диапазон из conditionsJson (RuleEvaluator проверит
        // его после этого фильтра, здесь пропускаем все).
        return true
    }
}
