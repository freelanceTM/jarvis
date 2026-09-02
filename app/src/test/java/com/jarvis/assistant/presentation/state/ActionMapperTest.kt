package com.jarvis.assistant.presentation.state

import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests protect the two promises the specification makes about actions:
 * the user is told what is happening in their own words (§19), and the app
 * never invents a status or a target (§3, §33).
 */
class ActionMapperTest {

    private fun call(toolId: String, vararg args: Pair<String, String>) = ToolCall(
        toolId = toolId,
        arguments = buildJsonObject { args.forEach { (k, v) -> put(k, v) } }
    )

    private fun result(
        status: ToolExecutionStatus,
        summary: String = "",
        error: String? = null
    ) = ToolExecutionResult(status = status, summary = summary, error = error)

    @Test
    fun `known tools map to their phrasing family`() {
        assertEquals(ActionType.CALL, ActionMapper.typeOf("communication.call"))
        assertEquals(ActionType.MESSAGE, ActionMapper.typeOf("communication.sms"))
        assertEquals(ActionType.ALARM, ActionMapper.typeOf("productivity.alarm_timer"))
        assertEquals(ActionType.NAVIGATION, ActionMapper.typeOf("location.navigation"))
        assertEquals(ActionType.APP, ActionMapper.typeOf("device.open_app"))
        assertEquals(ActionType.MEDIA, ActionMapper.typeOf("media.control"))
        assertEquals(ActionType.DEVICE_SETTING, ActionMapper.typeOf("device.flashlight"))
    }

    @Test
    fun `an unknown tool degrades to Generic instead of leaking its id`() {
        val type = ActionMapper.typeOf("some.internal.tool_v2")
        assertEquals(ActionType.Generic, type)
    }

    @Test
    fun `the target is taken from real arguments in priority order`() {
        val c = call("communication.call", "name" to "Wrong", "contact_name" to "Alex")
        assertEquals("Alex", ActionMapper.targetOf(c))
    }

    @Test
    fun `a missing target stays null rather than becoming a placeholder`() {
        // The forbidden behaviour is inventing "Alex" when nothing was said.
        val c = call("communication.call")
        assertNull(ActionMapper.targetOf(c))
    }

    @Test
    fun `blank and null-like argument values are ignored`() {
        val blank = call("communication.call", "contact_name" to "   ")
        assertNull(ActionMapper.targetOf(blank))

        val nulled = ToolCall(
            toolId = "communication.call",
            arguments = buildJsonObject { put("contact_name", JsonNull) }
        )
        assertNull(ActionMapper.targetOf(nulled))
    }

    @Test
    fun `executing snapshot carries the type, target and running status`() {
        val snapshot = ActionMapper.executing(call("communication.call", "contact_name" to "Alex"))
        assertEquals(ActionType.CALL, snapshot.type)
        assertEquals(ActionStatus.EXECUTING, snapshot.status)
        assertEquals("Alex", snapshot.target)
        assertFalse(snapshot.confirmationRequired)
    }

    @Test
    fun `pending confirmation snapshot is flagged and not yet executing`() {
        val snapshot = ActionMapper.pendingConfirmation(
            call("communication.sms", "recipient" to "Mum")
        )
        assertEquals(ActionStatus.PENDING_CONFIRMATION, snapshot.status)
        assertTrue(snapshot.confirmationRequired)
        assertEquals("Mum", snapshot.target)
    }

    @Test
    fun `success keeps the executor summary as the visible result`() {
        val snapshot = ActionMapper.completed(
            call("productivity.alarm_timer", "time" to "7:00"),
            result(ToolExecutionStatus.SUCCESS, summary = "Alarm set for 7:00")
        )
        assertEquals(ActionStatus.SUCCEEDED, snapshot.status)
        assertEquals("Alarm set for 7:00", snapshot.result)
        assertNull(snapshot.error)
    }

    @Test
    fun `every failure status becomes a human system state, never a code`() {
        val expectations = mapOf(
            ToolExecutionStatus.PERMISSION_REQUIRED to SystemStateType.PERMISSION_REQUIRED,
            ToolExecutionStatus.USER_ACTION_REQUIRED to SystemStateType.USER_ACTION_REQUIRED,
            ToolExecutionStatus.REQUIRES_SYSTEM_PANEL to SystemStateType.USER_ACTION_REQUIRED,
            ToolExecutionStatus.UNSUPPORTED to SystemStateType.CAPABILITY_UNAVAILABLE,
            ToolExecutionStatus.TIMEOUT to SystemStateType.SERVICE_UNREACHABLE,
            ToolExecutionStatus.FAILURE to SystemStateType.ACTION_FAILED
        )
        expectations.forEach { (status, expected) ->
            val snapshot = ActionMapper.completed(
                call("communication.call"),
                // A raw technical string that must never reach the UI.
                result(status, summary = "ToolException: HTTP 503", error = "DEVICE_DISCONNECTED")
            )
            assertEquals("wrong mapping for $status", expected, snapshot.error)
            assertEquals(ActionStatus.FAILED, snapshot.status)
            assertNull("technical summary leaked for $status", snapshot.result)
        }
    }

    @Test
    fun `cancelled is a distinct outcome and not an error`() {
        val snapshot = ActionMapper.completed(
            call("communication.call"),
            result(ToolExecutionStatus.CANCELLED, summary = "cancelled")
        )
        assertEquals(ActionStatus.CANCELLED, snapshot.status)
        assertNull(snapshot.error)
    }

    @Test
    fun `a result asking for confirmation returns to the confirmation state`() {
        val snapshot = ActionMapper.completed(
            call("communication.sms", "recipient" to "Alex"),
            result(ToolExecutionStatus.REQUIRES_USER_CONFIRMATION, summary = "Send it?")
        )
        assertEquals(ActionStatus.PENDING_CONFIRMATION, snapshot.status)
        assertTrue(snapshot.confirmationRequired)
    }
}
