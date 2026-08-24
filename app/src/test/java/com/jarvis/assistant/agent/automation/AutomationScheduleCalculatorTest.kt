package com.jarvis.assistant.agent.automation

import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import com.jarvis.assistant.agent.automation.model.AutomationTriggerType
import com.jarvis.assistant.agent.automation.scheduler.AutomationScheduleCalculator
import com.jarvis.assistant.agent.automation.scheduler.ScheduleSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class AutomationScheduleCalculatorTest {
    private val zone = ZoneId.of("Europe/Amsterdam")

    @Test
    fun `only canonical 24 hour schedule is accepted`() {
        assertEquals(ScheduleSlot(7, 5), AutomationScheduleCalculator.parse("07:05"))
        for (invalid in listOf("7:05", "07:5", "24:00", "23:60", "07:", "07:00:x", "")) {
            assertNull(invalid, AutomationScheduleCalculator.parse(invalid))
        }
    }

    @Test
    fun `next occurrence is today or tomorrow without duplicate wall clock delivery`() {
        val slot = ScheduleSlot(7, 0)
        val before = Instant.parse("2026-08-22T04:00:00Z") // 06:00 local
        val after = Instant.parse("2026-08-22T06:00:00Z") // 08:00 local

        assertEquals("2026-08-22T05:00:00Z", AutomationScheduleCalculator.nextOccurrence(before, slot, zone).toString())
        assertEquals("2026-08-23T05:00:00Z", AutomationScheduleCalculator.nextOccurrence(after, slot, zone).toString())
    }

    @Test
    fun `DST gap moves nonexistent local time forward`() {
        val result = AutomationScheduleCalculator.nextOccurrence(
            Instant.parse("2026-03-28T23:00:00Z"),
            ScheduleSlot(2, 30),
            zone
        ).atZone(zone)

        assertEquals(3, result.hour)
        assertEquals(30, result.minute)
        assertEquals(29, result.dayOfMonth)
    }

    @Test
    fun `DST overlap does not schedule the repeated wall time twice`() {
        // First 02:30 occurrence was 00:30Z. At 00:45Z, the calculator must
        // choose the next day rather than the second overlapping 02:30.
        val result = AutomationScheduleCalculator.nextOccurrence(
            Instant.parse("2026-10-25T00:45:00Z"),
            ScheduleSlot(2, 30),
            zone
        ).atZone(zone)

        assertEquals(26, result.dayOfMonth)
        assertEquals(2, result.hour)
        assertEquals(30, result.minute)
    }

    @Test
    fun `missed schedule fires within grace once and respects persistent success`() {
        val now = Instant.parse("2026-08-22T05:10:00Z") // 07:10 local
        val pending = rule("07:00", lastTriggeredAt = 0)
        val completed = rule(
            "07:05",
            lastTriggeredAt = Instant.parse("2026-08-22T05:06:00Z").toEpochMilli()
        )
        val tooOld = rule("06:00", lastTriggeredAt = 0)

        val missed = AutomationScheduleCalculator.missedOccurrences(
            listOf(pending, completed, tooOld, pending.copy(id = 99)),
            now,
            zone,
            Duration.ofMinutes(15)
        )

        assertEquals(1, missed.size)
        assertEquals(ScheduleSlot(7, 0), missed.single().slot)
        assertTrue(missed.single().scheduledAtMillis > 0)
    }

    private fun rule(time: String, lastTriggeredAt: Long) = AutomationEntity(
        id = 1,
        ruleId = "test-$time",
        name = "test",
        triggerType = AutomationTriggerType.TIME_SCHEDULE.name,
        triggerParam = time,
        actionsJson = "[{\"tool\":\"system.time\"}]",
        isEnabled = true,
        cooldownMs = 0,
        lastTriggeredAt = lastTriggeredAt
    )
}
