package com.jarvis.assistant.agent.tools

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.agent.tools.device.DoNotDisturbTool
import com.jarvis.assistant.agent.tools.productivity.AlarmTimerTool
import com.jarvis.assistant.agent.tools.productivity.ClipboardTool
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Контракты честности (execute → verify → SUCCESS) для инструментов,
 * у которых Android может МОЛЧА не применить изменение:
 *  - DND: setInterruptionFilter возвращает UNKNOWN / фильтр не применяется;
 *  - буфер обмена: с API 29 фоновая запись игнорируется системой;
 *  - будильник: ACTION_SET_ALARM — fire-and-forget, часы могут не сохранить.
 */
class ToolVerificationBehaviorTest {

    // ------------------------------------------------------------ DND

    @Test
    fun `dnd tool verifies interruption filter before claiming success`() = runBlocking {
        val context = mockk<Context>()
        val nm = mockk<NotificationManager>()
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns nm
        every { nm.isNotificationPolicyAccessGranted } returns true
        // Система применила фильтр: возврат вызова = целевой, read-back подтвердил.
        every { nm.setInterruptionFilter(any()) } answers { firstArg<Int>() }
        every { nm.currentInterruptionFilter } returnsMany listOf(0, 2)

        val result = DoNotDisturbTool(context).execute(buildJsonObject { put("enabled", true) })

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertTrue(result.summary.contains("включён"))
    }

    @Test
    fun `dnd tool returns failure when system does not apply the filter`() = runBlocking {
        val context = mockk<Context>()
        val nm = mockk<NotificationManager>()
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns nm
        every { nm.isNotificationPolicyAccessGranted } returns true
        // Система отклонила: applied = INTERRUPTION_FILTER_UNKNOWN (0), read-back = ALL (1).
        every { nm.setInterruptionFilter(any()) } returns NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        every { nm.currentInterruptionFilter } returns NotificationManager.INTERRUPTION_FILTER_ALL

        val result = DoNotDisturbTool(context).execute(buildJsonObject { put("enabled", true) })

        assertEquals(ToolExecutionStatus.FAILURE, result.status)
        assertEquals("DND_VERIFY_FAILED", result.error)
        // В data — фактические значения для диагностики, не выдуманный успех.
        assertTrue(result.data?.get("applied_filter")?.jsonPrimitive?.int == 0)
    }

    @Test
    fun `dnd tool without policy access returns user action required instead of success`() = runBlocking {
        val context = mockk<Context>()
        val nm = mockk<NotificationManager>()
        val pm = mockk<PackageManager>()
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns nm
        every { nm.isNotificationPolicyAccessGranted } returns false
        every { context.packageManager } returns pm
        every { pm.resolveActivity(any(), any<Int>()) } returns mockk<ResolveInfo>()
        every { context.startActivity(any()) } just runs

        val result = DoNotDisturbTool(context).execute(buildJsonObject { put("enabled", false) })

        // Состояние устройства НЕ изменилось — это не SUCCESS с actionRequiresUser.
        assertEquals(ToolExecutionStatus.USER_ACTION_REQUIRED, result.status)
        assertEquals("NOTIFICATION_POLICY_ACCESS_REQUIRED", result.error)
        assertTrue(result.data?.get("opened_settings")?.jsonPrimitive?.boolean == true)
        verify { context.startActivity(any()) }
    }

    // ------------------------------------------------------------ clipboard

    @Test
    fun `clipboard copy is verified by read-back before claiming success`() = runBlocking {
        val context = mockk<Context>()
        val cm = mockk<ClipboardManager>()
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns cm
        every { cm.setPrimaryClip(any()) } just runs
        val clip = mockk<ClipData>()
        val item = mockk<ClipData.Item>()
        every { cm.primaryClip } returns clip
        every { clip.getItemAt(0) } returns item
        every { item.text } returns "привет"

        val result = ClipboardTool(context).execute(
            buildJsonObject { put("action", "copy"); put("text", "привет") }
        )

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertTrue(result.data?.get("verified")?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `clipboard copy fails honestly when system ignores background write`() = runBlocking {
        val context = mockk<Context>()
        val cm = mockk<ClipboardManager>()
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns cm
        every { cm.setPrimaryClip(any()) } just runs
        // API 29+: фоновая запись игнорируется — read-back не видит наш текст.
        every { cm.primaryClip } returns null

        val result = ClipboardTool(context).execute(
            buildJsonObject { put("action", "copy"); put("text", "привет") }
        )

        assertEquals(ToolExecutionStatus.FAILURE, result.status)
        assertEquals("CLIPBOARD_VERIFY_FAILED", result.error)
    }

    // ------------------------------------------------------------ alarm / timer

    @Test
    fun `alarm claims success only after system confirms next alarm at requested hour`() = runBlocking {
        val context = mockk<Context>()
        val am = mockk<AlarmManager>()
        val pm = mockk<PackageManager>()
        every { context.getSystemService(Context.ALARM_SERVICE) } returns am
        every { context.packageManager } returns pm
        every { pm.resolveActivity(any(), any<Int>()) } returns mockk<ResolveInfo>()
        every { context.startActivity(any()) } just runs

        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var candidate = now.toLocalDate().atTime(7, 0).atZone(zone)
        if (!candidate.toInstant().isAfter(now.toInstant())) candidate = candidate.plusDays(1)
        val info = mockk<AlarmManager.AlarmClockInfo>()
        every { info.triggerTime } returns candidate.toInstant().toEpochMilli()
        // Первое чтение — ещё старое состояние, второе — будильник появился.
        every { am.nextAlarmClockInfo } returnsMany listOf(null, info)

        val tool = AlarmTimerTool(context)
        val arguments = buildJsonObject { put("type", "alarm"); put("value", 7) }

        // Фаза Execution: интент доставлен, draft (будильник ещё НЕ подтверждён).
        val draft = tool.execute(arguments)
        assertEquals(ToolExecutionStatus.SUCCESS, draft.status)
        verify { context.startActivity(any()) }

        // Фаза Verification: система подтвердила будильник на запрошенный час.
        val result = tool.verify(arguments, draft)
        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertTrue(result.data?.get("verified")?.jsonPrimitive?.boolean == true)
        assertTrue(result.summary.contains("7:00"))
    }

    @Test
    fun `alarm returns user action required when system never confirms it`() = runBlocking {
        val context = mockk<Context>()
        val am = mockk<AlarmManager>()
        val pm = mockk<PackageManager>()
        every { context.getSystemService(Context.ALARM_SERVICE) } returns am
        every { context.packageManager } returns pm
        every { pm.resolveActivity(any(), any<Int>()) } returns mockk<ResolveInfo>()
        every { context.startActivity(any()) } just runs
        // Часы не применили будильник: следующего будильника нет и не появилось.
        every { am.nextAlarmClockInfo } returns null

        val tool = AlarmTimerTool(context)
        val arguments = buildJsonObject { put("type", "alarm"); put("value", 7) }
        val draft = tool.execute(arguments)
        assertEquals(ToolExecutionStatus.SUCCESS, draft.status)

        val result = tool.verify(arguments, draft)

        // Будильник, на который рассчитывают проснуться, не может быть «готово» без подтверждения.
        assertEquals(ToolExecutionStatus.USER_ACTION_REQUIRED, result.status)
        assertEquals("ALARM_UNVERIFIED", result.error)
        assertFalse(result.data?.get("verified")?.jsonPrimitive?.boolean ?: true)
    }

    @Test
    fun `timer wording claims only the dispatch because system timer cannot be verified`() = runBlocking {
        val context = mockk<Context>()
        val pm = mockk<PackageManager>()
        every { context.getSystemService(Context.ALARM_SERVICE) } returns mockk<AlarmManager>()
        every { context.packageManager } returns pm
        every { pm.resolveActivity(any(), any<Int>()) } returns mockk<ResolveInfo>()
        every { context.startActivity(any()) } just runs

        val tool = AlarmTimerTool(context)
        val result = tool.execute(
            buildJsonObject { put("type", "timer"); put("value", 10) }
        )

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertTrue(result.summary.contains("отправлен"))
        assertFalse(result.data?.get("verified")?.jsonPrimitive?.boolean ?: true)

        // verify() для таймера — pass-through: верифицирующего API нет,
        // формулировка execute() уже честная.
        val verified = tool.verify(
            buildJsonObject { put("type", "timer") },
            result
        )
        assertEquals(result.summary, verified.summary)
        assertEquals(ToolExecutionStatus.SUCCESS, verified.status)
    }

    @Test
    fun `alarm and timer fail honestly when clock app is absent`() = runBlocking {
        val context = mockk<Context>()
        every { context.getSystemService(Context.ALARM_SERVICE) } returns mockk<AlarmManager>()
        val pm = mockk<PackageManager>()
        every { context.packageManager } returns pm
        every { pm.resolveActivity(any(), any<Int>()) } returns null

        assertEquals(
            "CLOCK_APP_NOT_FOUND",
            AlarmTimerTool(context).execute(buildJsonObject { put("type", "alarm"); put("value", 7) }).error
        )
        assertEquals(
            "CLOCK_APP_NOT_FOUND",
            AlarmTimerTool(context).execute(buildJsonObject { put("type", "timer"); put("value", 10) }).error
        )
    }
}
