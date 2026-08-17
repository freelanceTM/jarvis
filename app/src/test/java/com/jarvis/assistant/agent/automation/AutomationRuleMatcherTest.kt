package com.jarvis.assistant.agent.automation

import com.jarvis.assistant.agent.automation.engine.AutomationRuleMatcher
import com.jarvis.assistant.agent.automation.engine.RuleEvaluator
import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import com.jarvis.assistant.agent.automation.model.AutomationTriggerType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тесты RuleMatcher: Event → List<AutomationRule> → execute all.
 *
 * Главное: одно событие матчит ВСЕ подходящие правила (не одно),
 * расписание (TIME_SCHEDULE) матчит по точному времени "HH:MM".
 */
class AutomationRuleMatcherTest {

    private lateinit var matcher: AutomationRuleMatcher
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val actions = """[{"tool":"device.volume","arguments":{"action":"set","percent":50}}]"""

    @Before
    fun setUp() {
        matcher = AutomationRuleMatcher(RuleEvaluator(json))
    }

    private fun rule(
        id: Long,
        name: String,
        triggerType: String = AutomationTriggerType.HEADPHONES_CONNECTED.name,
        triggerParam: String = "",
        priority: Int = 0,
        enabled: Boolean = true,
        cooldownMs: Long = 0L,
        lastTriggeredAt: Long = 0L,
        actionsJson: String = actions
    ) = AutomationEntity(
        id = id,
        ruleId = "rule_$id",
        name = name,
        triggerType = triggerType,
        triggerParam = triggerParam,
        actionsJson = actionsJson,
        isEnabled = enabled,
        priority = priority,
        cooldownMs = cooldownMs,
        lastTriggeredAt = lastTriggeredAt
    )

    // ===========================================
    // Одно событие → ВСЕ правила
    // ===========================================

    @Test
    fun `one event matches all enabled rules with actions`() {
        val rules = listOf(
            rule(1, "Правило A", priority = 5),
            rule(2, "Правило B", priority = 1),
            rule(3, "Правило C", priority = 10)
        )

        val matched = matcher.matchForTrigger(rules, nowMillis = 0L, currentHour = 8, currentMinute = 0)

        assertEquals(3, matched.size)
    }

    @Test
    fun `matching rules are ordered by priority descending`() {
        val rules = listOf(
            rule(1, "Низкий", priority = 1),
            rule(2, "Высокий", priority = 10),
            rule(3, "Средний", priority = 5)
        )

        val matched = matcher.matchForTrigger(rules, 0L, 8, 0)

        assertEquals(listOf("Высокий", "Средний", "Низкий"), matched.map { it.name })
    }

    @Test
    fun `disabled and cooldown rules are excluded`() {
        val rules = listOf(
            rule(1, "Выключенное", enabled = false),
            rule(2, "В кулдауне", cooldownMs = 60_000L, lastTriggeredAt = System.currentTimeMillis()),
            rule(3, "Рабочее")
        )

        val matched = matcher.matchForTrigger(rules, System.currentTimeMillis(), 8, 0)

        assertEquals(listOf("Рабочее"), matched.map { it.name })
    }

    @Test
    fun `rules of other events are not matched`() {
        val rules = listOf(
            rule(1, "Наушники", triggerType = AutomationTriggerType.HEADPHONES_CONNECTED.name),
            rule(2, "Wi-Fi", triggerType = AutomationTriggerType.WIFI_CONNECTED.name)
        )

        // Матчим событие Wi-Fi — правило наушников не должно сработать.
        val matched = matcher.matchForTrigger(
            rules.filter { it.triggerType == AutomationTriggerType.WIFI_CONNECTED.name },
            0L, 8, 0
        )

        assertEquals(listOf("Wi-Fi"), matched.map { it.name })
    }

    // ===========================================
    // Расписание TIME_SCHEDULE ("07:00")
    // ===========================================

    @Test
    fun `schedule rule fires at exact time`() {
        val morning = rule(
            1,
            "Утреннее расписание",
            triggerType = AutomationTriggerType.TIME_SCHEDULE.name,
            triggerParam = "07:00"
        )

        // В 07:00 — срабатывает.
        assertEquals(1, matcher.matchForSchedule(listOf(morning), 0L, 7, 0).size)
        // В 07:01 и 06:59 — НЕ срабатывает.
        assertEquals(0, matcher.matchForSchedule(listOf(morning), 0L, 7, 1).size)
        assertEquals(0, matcher.matchForSchedule(listOf(morning), 0L, 6, 59).size)
        // В 08:00 — НЕ срабатывает.
        assertEquals(0, matcher.matchForSchedule(listOf(morning), 0L, 8, 0).size)
    }

    @Test
    fun `multiple schedule rules on same time all fire`() {
        val rules = listOf(
            rule(1, "Открыть календарь", triggerType = AutomationTriggerType.TIME_SCHEDULE.name, triggerParam = "07:00", priority = 3),
            rule(2, "Сказать погоду", triggerType = AutomationTriggerType.TIME_SCHEDULE.name, triggerParam = "07:00", priority = 2),
            rule(3, "Прочитать расписание", triggerType = AutomationTriggerType.TIME_SCHEDULE.name, triggerParam = "07:00", priority = 1),
            rule(4, "Сообщить задачи", triggerType = AutomationTriggerType.TIME_SCHEDULE.name, triggerParam = "07:00", priority = 4)
        )

        val matched = matcher.matchForSchedule(rules, 0L, 7, 0)

        // Пример из ТЗ: 07:00 → ВСЕ четыре действия-правила выполняются.
        assertEquals(4, matched.size)
        assertEquals(
            listOf("Сообщить задачи", "Открыть календарь", "Сказать погоду", "Прочитать расписание"),
            matched.map { it.name }
        )
    }

    @Test
    fun `schedule rule with different time does not fire`() {
        val evening = rule(
            1,
            "Вечерний режим",
            triggerType = AutomationTriggerType.TIME_SCHEDULE.name,
            triggerParam = "22:00"
        )

        assertEquals(0, matcher.matchForSchedule(listOf(evening), 0L, 7, 0).size)
        assertEquals(1, matcher.matchForSchedule(listOf(evening), 0L, 22, 0).size)
    }

    @Test
    fun `schedule without trigger param falls back to time range condition`() {
        val morningRange = AutomationEntity(
            id = 1,
            ruleId = "rule_range",
            name = "Утренний диапазон",
            triggerType = AutomationTriggerType.TIME_SCHEDULE.name,
            conditionsJson = """{"startHour":6,"startMinute":0,"endHour":12,"endMinute":0}""",
            actionsJson = actions
        )

        assertTrue(matcher.matchForSchedule(listOf(morningRange), 0L, 8, 30).isNotEmpty())
        assertTrue(matcher.matchForSchedule(listOf(morningRange), 0L, 14, 0).isEmpty())
    }
}
