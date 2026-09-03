package com.jarvis.assistant.agent.tools

import android.content.Context
import android.media.AudioManager
import com.jarvis.assistant.agent.capability.DeviceCapabilityRegistry
import com.jarvis.assistant.agent.core.CapabilityAwareTool
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.agent.safety.PreflightVerdict
import com.jarvis.assistant.agent.safety.ToolPermissionManager
import com.jarvis.assistant.agent.tools.device.BluetoothTool
import com.jarvis.assistant.agent.tools.device.OpenAppTool
import com.jarvis.assistant.agent.tools.device.SetVolumeTool
import com.jarvis.assistant.agent.tools.device.WifiTool
import com.jarvis.assistant.agent.tools.productivity.AlarmTimerTool
import com.jarvis.assistant.agent.apps.AppResolver
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конформность Tier 1 единому контракту Tool Registry 2.0:
 *
 * ```
 * Tool: id · description · permissions · execute() · verify() · timeout · error mapping
 * ```
 *
 * Пайплайн Discovery → Selection → Execution → Verification → Result
 * обеспечивается ToolExecutor'ом (см. ToolExecutorBehaviorTest); здесь
 * проверяются контрактные члены самих инструментов.
 */
class ToolContractConformanceTest {

    /** Tier 1: контракту подчиняются все — от app-запуска до Wi-Fi. */
    private fun tier1Tools(context: Context, capabilities: DeviceCapabilityRegistry): List<JarvisTool> = listOf(
        OpenAppTool(context, mockk<AppResolver>()),
        SetVolumeTool(context),
        AlarmTimerTool(context),
        BluetoothTool(context, capabilities),
        WifiTool(context, capabilities)
    )

    @Test
    fun `every tier1 tool declares identity timeout and permissions contract`() {
        val context = mockk<Context>(relaxed = true)
        val capabilities = mockk<DeviceCapabilityRegistry>()
        every { capabilities.bluetoothReadPermissions() } returns listOf("android.permission.BLUETOOTH_CONNECT")

        for (tool in tier1Tools(context, capabilities)) {
            // id / description
            assertTrue(tool.toolId.isNotBlank())
            assertTrue(tool.description.isNotBlank())
            // timeout: положительный бюджет на execute + verify вместе
            assertTrue("$tool executionTimeoutMs must be positive", tool.executionTimeoutMs > 0)
            // permissions: контрактный член доступен у всех
            assertNotNull(tool.requiredPermissions)
            // verify()/mapError() — члены контракта (наследуют дефолты)
        }
    }

    @Test
    fun `capability aware tools derive permissions from capability contract`() {
        val context = mockk<Context>(relaxed = true)
        val capabilities = mockk<DeviceCapabilityRegistry>()
        val btPermissions = listOf("android.permission.BLUETOOTH_CONNECT")
        every { capabilities.bluetoothReadPermissions() } returns btPermissions

        val bluetooth: CapabilityAwareTool = BluetoothTool(context, capabilities)

        // Единый источник истины: contract.requiredPermissions, без дублей.
        assertEquals(bluetooth.capabilityContract.requiredPermissions, bluetooth.requiredPermissions)
        assertEquals(btPermissions, bluetooth.requiredPermissions)
    }

    @Test
    fun `verify fails closed when draft lacks pre state instead of inventing success`() = runBlocking {
        val context = mockk<Context>()
        val audio = mockk<AudioManager>()
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audio
        every { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 7

        val tool = SetVolumeTool(context)
        val malformedDraft = ToolExecutionResult.success("draft без previous_volume")

        val result = tool.verify(buildJsonObject { put("action", "up") }, malformedDraft)

        // Fail-closed: нет исходного значения — нет подтверждения, нет SUCCESS.
        assertEquals(ToolExecutionStatus.FAILURE, result.status)
        assertEquals("VOLUME_VERIFY_FAILED", result.error)
    }

    @Test
    fun `timer verification is documented pass through because no public api exists`() = runBlocking {
        val tool = AlarmTimerTool(mockk<Context>(relaxed = true))
        val draft = ToolExecutionResult.success(
            "Таймер на 10 минут отправлен в приложение часов",
            data = buildJsonObject { put("minutes", 10); put("verified", false) }
        )

        val verified = tool.verify(buildJsonObject { put("type", "timer") }, draft)

        assertEquals(draft.summary, verified.summary)
        assertEquals(ToolExecutionStatus.SUCCESS, verified.status)
    }

    @Test
    fun `plain tool with ungranted declared permissions is blocked by preflight`() {
        val checker = mockk<com.jarvis.assistant.agent.capability.CapabilityChecker>()
        every { checker.missingPermissions(listOf("android.permission.SMS")) } returns listOf("android.permission.SMS")
        val manager = ToolPermissionManager(checker)

        val tool = PlainDeclaredTool(required = listOf("android.permission.SMS"))
        val verdict = manager.preflight(tool, com.jarvis.assistant.agent.model.ToolCall("plain.tool"))

        assertTrue(verdict is PreflightVerdict.PermissionsMissing)
        assertEquals(listOf("android.permission.SMS"), (verdict as PreflightVerdict.PermissionsMissing).permissions)
    }

    @Test
    fun `plain tool with granted declared permissions passes preflight`() {
        val checker = mockk<com.jarvis.assistant.agent.capability.CapabilityChecker>()
        every { checker.missingPermissions(listOf("android.permission.SMS")) } returns emptyList()
        val manager = ToolPermissionManager(checker)

        val tool = PlainDeclaredTool(required = listOf("android.permission.SMS"))
        val verdict = manager.preflight(tool, com.jarvis.assistant.agent.model.ToolCall("plain.tool"))

        assertEquals(PreflightVerdict.Allowed, verdict)
    }

    /** Минимальный plain-инструмент с объявленными разрешениями (для preflight). */
    private class PlainDeclaredTool(
        override val requiredPermissions: List<String>
    ) : JarvisTool {
        override val toolId = "plain.tool"
        override val description = "plain tool with declared permissions"
        override val category = com.jarvis.assistant.agent.core.ToolCategory.SYSTEM
        override val parametersSchema = kotlinx.serialization.json.JsonObject(emptyMap())
        override val riskLevel = com.jarvis.assistant.agent.model.ToolRisk.SAFE
        override suspend fun execute(arguments: kotlinx.serialization.json.JsonObject) =
            ToolExecutionResult.success("ok")
    }
}
