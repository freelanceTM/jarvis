package com.jarvis.assistant.agent.observation

import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import org.junit.Assert.*
import org.junit.Test

/**
 * Проверяет главный инвариант честности: заблокированное Android действие
 * НИКОГДА не превращается в успешное наблюдение с изменением состояния.
 */
class ObservationTest {

    @Test
    fun `success maps to state changed and continue`() {
        val observation = Observation.from(
            "device.volume",
            ToolExecutionResult.success("Громкость увеличена")
        )

        assertTrue(observation.success)
        assertTrue(observation.stateChanged)
        assertEquals(NextActionHint.CONTINUE, observation.nextActionHint)
    }

    @Test
    fun `permission required is not success and asks for permission`() {
        val observation = Observation.from(
            "communication.sms",
            ToolExecutionResult.permissionRequired("Нужно разрешение", listOf("android.permission.SEND_SMS"))
        )

        assertFalse(observation.success)
        assertFalse("Blocked action must not report state change", observation.stateChanged)
        assertEquals(NextActionHint.REQUEST_PERMISSION, observation.nextActionHint)
        assertEquals(listOf("android.permission.SEND_SMS"), observation.missingPermissions)
        assertTrue(observation.requiresUserInvolvement)
    }

    @Test
    fun `user action required does not trigger replan`() {
        val observation = Observation.from(
            "device.bluetooth",
            ToolExecutionResult.userActionRequired("Откройте настройки", "BLUETOOTH_TOGGLE_REQUIRES_USER")
        )

        assertFalse(observation.success)
        assertFalse(observation.stateChanged)
        assertEquals(NextActionHint.AWAIT_USER_ACTION, observation.nextActionHint)
        assertFalse("Re-plan cannot bypass an Android restriction", observation.isReplanWorthwhile)
    }

    @Test
    fun `unsupported aborts instead of replanning`() {
        val observation = Observation.from(
            "device.screenshot",
            ToolExecutionResult.unsupported("Недоступно на Android 10", "SCREENSHOT_UNSUPPORTED_BELOW_API_30")
        )

        assertEquals(NextActionHint.ABORT_UNSUPPORTED, observation.nextActionHint)
        assertFalse(observation.isReplanWorthwhile)
    }

    @Test
    fun `plain failure is replan worthy`() {
        val observation = Observation.from(
            "intelligence.web_search",
            ToolExecutionResult.failure("Сервис недоступен", "HTTP_500")
        )

        assertEquals(NextActionHint.REPLAN, observation.nextActionHint)
        assertTrue(observation.isReplanWorthwhile)
    }

    @Test
    fun `timeout is replan worthy`() {
        val observation = Observation.from(
            "intelligence.web_search",
            ToolExecutionResult.timeout("web_search", 5000)
        )

        assertEquals(ToolExecutionStatus.TIMEOUT, ToolExecutionResult.timeout("x", 1).status)
        assertEquals(NextActionHint.REPLAN, observation.nextActionHint)
    }

    @Test
    fun `success requiring user action does not claim state change`() {
        val result = ToolExecutionResult.success("Открываю экран", actionRequiresUser = true)
        val observation = Observation.from("device.wifi", result)

        assertTrue(observation.success)
        assertFalse("Opening a settings screen is not a state change", observation.stateChanged)
    }

    @Test
    fun `blocked statuses are flagged on tool result`() {
        assertTrue(ToolExecutionResult.permissionRequired("s", emptyList()).isBlockedByAndroid)
        assertTrue(ToolExecutionResult.userActionRequired("s", "r").isBlockedByAndroid)
        assertTrue(ToolExecutionResult.unsupported("s", "r").isBlockedByAndroid)
        assertFalse(ToolExecutionResult.success("s").isBlockedByAndroid)
        assertFalse(ToolExecutionResult.failure("s", "e").isBlockedByAndroid)
    }
}
