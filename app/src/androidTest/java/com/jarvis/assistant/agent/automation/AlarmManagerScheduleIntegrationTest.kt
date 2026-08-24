package com.jarvis.assistant.agent.automation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.agent.automation.entity.AutomationEntity
import com.jarvis.assistant.agent.automation.model.AutomationTriggerType
import com.jarvis.assistant.agent.automation.scheduler.AutomationScheduleEntryPoint
import com.jarvis.assistant.agent.automation.scheduler.ScheduleSlot
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Real AlarmManager -> BroadcastReceiver -> engine -> ToolExecutor -> Room proof. */
@RunWith(AndroidJUnit4::class)
class AlarmManagerScheduleIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val entryPoint get() = EntryPointAccessors.fromApplication(
        context.applicationContext,
        AutomationScheduleEntryPoint::class.java
    )

    @Test
    fun persistentClaimRejectsDuplicateButAllowsEarlierClockCorrectedOccurrence() {
        val scheduler = entryPoint.automationScheduleManager()
        val first = System.currentTimeMillis() + UUID.randomUUID().mostSignificantBits.and(0xffff)
        val earlier = first - 86_400_123

        assertTrue(scheduler.claimDelivery(first))
        assertTrue(!scheduler.claimDelivery(first))
        assertTrue("a clock correction must not trip a monotonic high-water mark", scheduler.claimDelivery(earlier))
    }

    @Test
    fun realAlarmExecutesPersistedAutomationExactlyOnce() = runBlocking {
        val triggerAt = System.currentTimeMillis() + 2_000
        val slot = triggerAt.toSlot()
        val rule = rule(slot)
        val dao = entryPoint.automationDao()
        val scheduler = entryPoint.automationScheduleManager()
        dao.insertAutomation(rule)
        try {
            scheduler.scheduleOneShotForTest(triggerAt, slot)
            assertTrue("real AlarmManager trigger was not observed", awaitTrigger(rule.ruleId, 1, 25_000))
            delay(1_500)
            assertEquals(1, dao.getAutomationByRuleId(rule.ruleId)?.triggerCount)
        } finally {
            dao.deleteAutomationByRuleId(rule.ruleId)
            scheduler.reconcile()
        }
    }

    @Test
    fun disabledRuleIsRecheckedAtDeliveryTime() = runBlocking {
        val triggerAt = System.currentTimeMillis() + 2_000
        val slot = triggerAt.toSlot()
        val rule = rule(slot)
        val dao = entryPoint.automationDao()
        val scheduler = entryPoint.automationScheduleManager()
        dao.insertAutomation(rule)
        dao.setAutomationEnabled(rule.ruleId, false)
        try {
            scheduler.scheduleOneShotForTest(triggerAt, slot)
            delay(7_000)
            assertEquals(0, dao.getAutomationByRuleId(rule.ruleId)?.triggerCount)
        } finally {
            dao.deleteAutomationByRuleId(rule.ruleId)
            scheduler.reconcile()
        }
    }

    @Test
    fun cancellationPreventsDelivery() = runBlocking {
        val triggerAt = System.currentTimeMillis() + 2_000
        val slot = triggerAt.toSlot()
        val rule = rule(slot)
        val dao = entryPoint.automationDao()
        val scheduler = entryPoint.automationScheduleManager()
        dao.insertAutomation(rule)
        try {
            scheduler.scheduleOneShotForTest(triggerAt, slot)
            scheduler.cancel()
            delay(7_000)
            assertEquals(0, dao.getAutomationByRuleId(rule.ruleId)?.triggerCount)
        } finally {
            dao.deleteAutomationByRuleId(rule.ruleId)
            scheduler.reconcile()
        }
    }

    private suspend fun awaitTrigger(ruleId: String, expected: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if ((entryPoint.automationDao().getAutomationByRuleId(ruleId)?.triggerCount ?: 0) >= expected) {
                return true
            }
            delay(250)
        }
        return false
    }

    private fun rule(slot: ScheduleSlot) = AutomationEntity(
        ruleId = "instrumented-${UUID.randomUUID()}",
        name = "AlarmManager integration",
        triggerType = AutomationTriggerType.TIME_SCHEDULE.name,
        triggerParam = "%02d:%02d".format(slot.hour, slot.minute),
        actionsJson = "[{\"tool\":\"system.time\",\"arguments\":{}}]",
        voiceAnnouncement = "",
        isEnabled = true,
        cooldownMs = 60_000
    )

    private fun Long.toSlot(): ScheduleSlot {
        val local = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
        return ScheduleSlot(local.hour, local.minute)
    }
}
