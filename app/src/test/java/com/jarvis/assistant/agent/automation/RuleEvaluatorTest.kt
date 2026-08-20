package com.jarvis.assistant.agent.automation

import com.jarvis.assistant.agent.automation.engine.RuleDecision
import com.jarvis.assistant.agent.automation.engine.RuleEvaluator
import com.jarvis.assistant.agent.automation.engine.RuleSkipReason
import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import com.jarvis.assistant.agent.automation.model.TimeRangeCondition
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Тесты движка правил автоматизации.
 *
 * Главный зафиксированный баг: раньше после первого сработавшего правила
 * выполнение прерывалось (`break`), и остальные правила на то же событие
 * молча игнорировались.
 */
class RuleEvaluatorTest {

    private lateinit var evaluator: RuleEvaluator
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val actions = """[{"tool":"device.volume","arguments":{"action":"set","percent":50}}]"""

    @Before
    fun setUp() {
        evaluator = RuleEvaluator(json)
    }

    private fun rule(
        id: Long,
        name: String,
        enabled: Boolean = true,
        priority: Int = 0,
        cooldownMs: Long = 0L,
        lastTriggeredAt: Long = 0L,
        conditionsJson: String = "",
        actionsJson: String = actions
    ) = AutomationEntity(
        id = id,
        ruleId = "rule_$id",
        name = name,
        triggerType = "HEADPHONES_CONNECTED",
        conditionsJson = conditionsJson,
        actionsJson = actionsJson,
        isEnabled = enabled,
        priority = priority,
        cooldownMs = cooldownMs,
        lastTriggeredAt = lastTriggeredAt
    )

    @Test
    fun `all matching rules are executed not just the first`() {
        val rules = listOf(rule(1, "First"), rule(2, "Second"), rule(3, "Third"))

        val executable = evaluator.executable(rules, nowMillis = 1_000_000, currentHour = 9, currentMinute = 0)

        assertEquals("Every matching rule must run", 3, executable.size)
    }

    @Test
    fun `rules are ordered by priority descending`() {
        val rules = listOf(
            rule(1, "Low", priority = 1),
            rule(2, "High", priority = 20),
            rule(3, "Medium", priority = 10)
        )

        val executable = evaluator.executable(rules, 1_000_000, 9, 0)

        assertEquals(listOf("High", "Medium", "Low"), executable.map { it.name })
    }

    @Test
    fun `disabled rule is skipped but others still run`() {
        val rules = listOf(rule(1, "Disabled", enabled = false), rule(2, "Enabled"))

        val decisions = evaluator.evaluate(rules, 1_000_000, 9, 0)
        val skipped = decisions.filterIsInstance<RuleDecision.Skip>()

        assertEquals(1, skipped.size)
        assertEquals(RuleSkipReason.DISABLED, skipped.first().reason)
        assertEquals(1, decisions.filterIsInstance<RuleDecision.Execute>().size)
    }

    @Test
    fun `cooldown blocks a recently triggered rule`() {
        val now = 1_000_000L
        val rules = listOf(
            rule(1, "Recent", cooldownMs = 60_000, lastTriggeredAt = now - 10_000),
            rule(2, "Old", cooldownMs = 60_000, lastTriggeredAt = now - 120_000)
        )

        val decisions = evaluator.evaluate(rules, now, 9, 0)

        val skipped = decisions.filterIsInstance<RuleDecision.Skip>()
        assertEquals(1, skipped.size)
        assertEquals(RuleSkipReason.COOLDOWN_ACTIVE, skipped.first().reason)
        assertEquals("Old", decisions.filterIsInstance<RuleDecision.Execute>().single().rule.name)
    }

    @Test
    fun `rule never triggered ignores cooldown`() {
        val rules = listOf(rule(1, "Fresh", cooldownMs = 60_000, lastTriggeredAt = 0L))
        assertEquals(1, evaluator.executable(rules, 1_000_000, 9, 0).size)
    }

    @Test
    fun `time window is respected`() {
        val morning = json.encodeToString(
            TimeRangeCondition.serializer(),
            TimeRangeCondition(startHour = 6, startMinute = 0, endHour = 10, endMinute = 0)
        )
        val rules = listOf(rule(1, "Morning", conditionsJson = morning))

        assertEquals(1, evaluator.executable(rules, 1_000_000, 8, 30).size)
        val afternoon = evaluator.evaluate(rules, 1_000_000, 15, 0)
        assertEquals(
            RuleSkipReason.TIME_CONDITION_NOT_MET,
            afternoon.filterIsInstance<RuleDecision.Skip>().single().reason
        )
    }

    @Test
    fun `time window crossing midnight works`() {
        val night = json.encodeToString(
            TimeRangeCondition.serializer(),
            TimeRangeCondition(startHour = 22, startMinute = 0, endHour = 6, endMinute = 0)
        )
        assertTrue(evaluator.isTimeConditionSatisfied(night, 23, 30))
        assertTrue(evaluator.isTimeConditionSatisfied(night, 3, 0))
        assertFalse(evaluator.isTimeConditionSatisfied(night, 12, 0))
    }

    @Test
    fun `empty condition always matches`() {
        assertTrue(evaluator.isTimeConditionSatisfied("", 13, 45))
    }

    @Test
    fun `malformed and out of range conditions fail closed`() {
        assertFalse(evaluator.isTimeConditionSatisfied("{not json", 13, 45))
        assertFalse(
            evaluator.isTimeConditionSatisfied(
                """{"startHour":99,"startMinute":0,"endHour":12,"endMinute":0}""",
                10,
                0
            )
        )
        assertFalse(evaluator.isTimeConditionSatisfied("", 24, 0))
        assertFalse(evaluator.isTimeConditionSatisfied("", 12, 60))
    }

    @Test
    fun `rule without actions is skipped`() {
        val rules = listOf(rule(1, "Empty", actionsJson = "[]"))
        val decisions = evaluator.evaluate(rules, 1_000_000, 9, 0)
        assertEquals(
            RuleSkipReason.NO_ACTIONS,
            decisions.filterIsInstance<RuleDecision.Skip>().single().reason
        )
    }

    @Test
    fun `mixed rule set evaluates each rule independently`() {
        val now = 2_000_000L
        val rules = listOf(
            rule(1, "Disabled", enabled = false),
            rule(2, "Cooling", cooldownMs = 60_000, lastTriggeredAt = now - 1_000),
            rule(3, "Ready", priority = 5),
            rule(4, "AlsoReady", priority = 1)
        )

        val executable = evaluator.executable(rules, now, 9, 0)

        assertEquals(listOf("Ready", "AlsoReady"), executable.map { it.name })
    }
}
