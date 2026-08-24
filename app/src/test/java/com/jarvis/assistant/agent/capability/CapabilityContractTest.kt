package com.jarvis.assistant.agent.capability

import com.jarvis.assistant.agent.core.CapabilityAwareTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.safety.PreflightVerdict
import com.jarvis.assistant.agent.safety.ToolPermissionManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Проверяет, что агент может спросить «могу ли я выполнить это действие
 * на данном устройстве» ДО вызова инструмента.
 */
class CapabilityContractTest {

    private class TestTool(
        override val toolId: String,
        override val riskLevel: ToolRisk,
        override val capabilityContract: ToolCapabilityContract
    ) : CapabilityAwareTool {
        override val description: String = "Тестовый инструмент $toolId"
        override val category: ToolCategory = ToolCategory.DEVICE
        override val parametersSchema: JsonObject = buildJsonObject { }
        override suspend fun execute(arguments: JsonObject) = ToolExecutionResult.success("ok")
    }

    private val emptyCall = ToolCall("test", buildJsonObject { })

    @Test
    fun `low danger tool runs without confirmation`() {
        val tool = TestTool(
            "device.volume",
            ToolRisk.LOW,
            ToolCapabilityContract(setOf(DeviceCapability.CONTROL_VOLUME), dangerLevel = DangerLevel.LOW)
        )
        val manager = ToolPermissionManager(FakeCapabilityRegistry.create())

        assertTrue(manager.isExecutionAllowed(tool))
        assertEquals(PreflightVerdict.Allowed, manager.preflight(tool, emptyCall))
    }

    @Test
    fun `high danger tool always requires confirmation`() {
        val tool = TestTool(
            "communication.sms",
            ToolRisk.CONFIRMATION_REQUIRED,
            ToolCapabilityContract(
                capabilities = setOf(DeviceCapability.SEND_SMS_DIRECTLY),
                requiredPermissions = listOf("android.permission.SEND_SMS"),
                dangerLevel = DangerLevel.HIGH,
                confirmationRequired = true
            )
        )
        val registry = FakeCapabilityRegistry.create().grant("android.permission.SEND_SMS")
        val manager = ToolPermissionManager(registry)

        assertFalse(manager.isExecutionAllowed(tool))
        assertTrue(manager.preflight(tool, emptyCall) is PreflightVerdict.ConfirmationRequired)
    }

    @Test
    fun `missing permission for high danger tool blocks before execution`() {
        val tool = TestTool(
            "communication.sms",
            ToolRisk.CONFIRMATION_REQUIRED,
            ToolCapabilityContract(
                capabilities = setOf(DeviceCapability.SEND_SMS_DIRECTLY),
                requiredPermissions = listOf("android.permission.SEND_SMS"),
                dangerLevel = DangerLevel.HIGH
            )
        )
        // Разрешение НЕ выдано
        val manager = ToolPermissionManager(FakeCapabilityRegistry.create())

        val verdict = manager.preflight(tool, emptyCall)

        assertTrue(verdict is PreflightVerdict.PermissionsMissing)
        assertEquals(
            listOf("android.permission.SEND_SMS"),
            (verdict as PreflightVerdict.PermissionsMissing).permissions
        )
    }

    @Test
    fun `call tool always requires confirmation before dialing`() {
        val tool = TestTool(
            "communication.call",
            ToolRisk.CONFIRMATION_REQUIRED,
            ToolCapabilityContract(
                capabilities = setOf(DeviceCapability.PLACE_CALL_DIRECTLY),
                requiredPermissions = listOf("android.permission.CALL_PHONE"),
                dangerLevel = DangerLevel.MEDIUM,
                confirmationRequired = true
            )
        )
        val registry = FakeCapabilityRegistry.create().grant("android.permission.CALL_PHONE")
        val manager = ToolPermissionManager(registry)

        // Даже с выданным разрешением звонок не выполняется без подтверждения.
        assertFalse(manager.isExecutionAllowed(tool))
        assertTrue(manager.preflight(tool, emptyCall) is PreflightVerdict.ConfirmationRequired)
    }

    @Test
    fun `call tool without permission is not allowed and never returns success`() {
        val tool = TestTool(
            "communication.call",
            ToolRisk.CONFIRMATION_REQUIRED,
            ToolCapabilityContract(
                capabilities = setOf(DeviceCapability.PLACE_CALL_DIRECTLY),
                requiredPermissions = listOf("android.permission.CALL_PHONE"),
                dangerLevel = DangerLevel.MEDIUM,
                confirmationRequired = true
            )
        )
        // Разрешение НЕ выдано
        val manager = ToolPermissionManager(FakeCapabilityRegistry.create())

        assertFalse(manager.isExecutionAllowed(tool))
        // Гейт подтверждения срабатывает первым — звонок не выполняется молча.
        assertTrue(manager.preflight(tool, emptyCall) is PreflightVerdict.ConfirmationRequired)
    }

    @Test
    fun `tool whose every capability is unsupported is rejected`() {
        val tool = TestTool(
            "device.screenshot",
            ToolRisk.LOW,
            ToolCapabilityContract(setOf(DeviceCapability.TAKE_SCREENSHOT_ACCESSIBILITY))
        )
        val registry = FakeCapabilityRegistry.create().set(
            DeviceCapability.TAKE_SCREENSHOT_ACCESSIBILITY,
            CapabilityStatus.Unsupported("Требуется Android 11")
        )

        val verdict = ToolPermissionManager(registry).preflight(tool, emptyCall)

        assertTrue(verdict is PreflightVerdict.Unsupported)
        assertEquals("Требуется Android 11", (verdict as PreflightVerdict.Unsupported).reason)
    }

    @Test
    fun `tool with one usable capability is not rejected`() {
        val tool = TestTool(
            "device.bluetooth",
            ToolRisk.LOW,
            ToolCapabilityContract(
                setOf(
                    DeviceCapability.TOGGLE_BLUETOOTH_DIRECTLY,
                    DeviceCapability.OPEN_BLUETOOTH_SETTINGS
                )
            )
        )
        val registry = FakeCapabilityRegistry.create().set(
            DeviceCapability.TOGGLE_BLUETOOTH_DIRECTLY,
            CapabilityStatus.UserActionRequired("Android 13+")
        )

        assertEquals(PreflightVerdict.Allowed, ToolPermissionManager(registry).preflight(tool, emptyCall))
    }

    @Test
    fun `capability status helpers behave correctly`() {
        assertTrue(CapabilityStatus.Available.isAvailable)
        assertFalse(CapabilityStatus.PermissionRequired(listOf("p")).isAvailable)
        assertFalse(CapabilityStatus.UserActionRequired("r").isAvailable)
        assertFalse(CapabilityStatus.Unsupported("r").isAvailable)
    }

    @Test
    fun `contract defaults confirmation to danger level`() {
        val high = ToolCapabilityContract(setOf(DeviceCapability.SEND_SMS_DIRECTLY), dangerLevel = DangerLevel.HIGH)
        val low = ToolCapabilityContract(setOf(DeviceCapability.CONTROL_VOLUME), dangerLevel = DangerLevel.LOW)

        assertTrue(high.confirmationRequired)
        assertFalse(low.confirmationRequired)
    }
}
