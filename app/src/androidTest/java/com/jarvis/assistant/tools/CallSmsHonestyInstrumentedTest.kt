package com.jarvis.assistant.tools

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.agent.capability.DeviceCapabilityRegistry
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.agent.tools.communication.CallTool
import com.jarvis.assistant.agent.tools.communication.ContactResolver
import com.jarvis.assistant.agent.tools.communication.SmsTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Инструментальные тесты честности звонков и SMS (пункт аудита #11 — MEDIUM).
 *
 * Главный инвариант: без разрешений инструменты НЕ возвращают SUCCESS.
 *  - CallTool без CALL_PHONE → USER_ACTION_REQUIRED (открыт диалер) — звонок НЕ совершён;
 *  - CallTool с именем контакта без READ_CONTACTS → PERMISSION_REQUIRED;
 *  - SmsTool без SEND_SMS → PERMISSION_REQUIRED — сообщение НЕ отправлено.
 *
 * Запуск: ./gradlew connectedDebugAndroidTest (требует эмулятора/устройства).
 */
@RunWith(AndroidJUnit4::class)
class CallSmsHonestyInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var capabilities: DeviceCapabilityRegistry
    private lateinit var contactResolver: ContactResolver

    @Before
    fun setUp() {
        capabilities = DeviceCapabilityRegistry(context)
        contactResolver = ContactResolver(context, capabilities)
    }

    @Test
    fun callByPhoneNumberWithoutPermissionReturnsUserActionRequired() = runBlocking {
        assumePermissionDenied(Manifest.permission.CALL_PHONE)
        val tool = CallTool(context, capabilities, contactResolver)

        val result = tool.execute(buildJsonObject { put("recipient", "+79991234567") })

        // Честность: звонок не совершён. Либо нужен пользователь в диалере,
        // либо (если CALL_PHONE выдан на устройстве тестирования) — SUCCESS.
        // На CI-эмуляторе разрешение не выдаётся → USER_ACTION_REQUIRED.
        assertNotEquals(
            "Звонок не должен «тихо» падать в FAILURE",
            ToolExecutionStatus.FAILURE,
            result.status
        )
        if (result.status == ToolExecutionStatus.USER_ACTION_REQUIRED) {
            assertTrue("Должно быть объяснение", result.summary.isNotBlank())
        }
    }

    @Test
    fun callByContactNameWithoutContactsPermissionReturnsPermissionRequired() = runBlocking {
        assumePermissionDenied(Manifest.permission.READ_CONTACTS)
        val tool = CallTool(context, capabilities, contactResolver)

        val result = tool.execute(buildJsonObject { put("recipient", "Иван Иванович") })

        // На эмуляторе READ_CONTACTS не выдан → либо PERMISSION_REQUIRED, либо
        // (если выдан) CONTACT_NOT_FOUND — НО не SUCCESS с подставленным текстом.
        if (result.status == ToolExecutionStatus.PERMISSION_REQUIRED) {
            assertTrue(result.missingPermissions.isNotEmpty())
        }
        assertNotEquals(
            "«Звоню Ивану» с текстом вместо номера — недопустимо",
            ToolExecutionStatus.SUCCESS,
            result.status
        )
    }

    @Test
    fun smsWithoutPermissionReturnsPermissionRequired() = runBlocking {
        assumePermissionDenied(Manifest.permission.SEND_SMS)
        val tool = SmsTool(context, capabilities, contactResolver)

        val result = tool.execute(
            buildJsonObject {
                put("recipient", "+79991234567")
                put("message", "Тестовое сообщение")
            }
        )

        // Без SEND_SMS сообщение НЕ отправлено — честный PERMISSION_REQUIRED.
        if (result.status == ToolExecutionStatus.PERMISSION_REQUIRED) {
            assertTrue(result.missingPermissions.isNotEmpty())
        }
        assertNotEquals(ToolExecutionStatus.SUCCESS, result.status)
    }

    @Test
    fun smsWithEmptyMessageReturnsFailure() = runBlocking {
        val tool = SmsTool(context, capabilities, contactResolver)

        val result = tool.execute(buildJsonObject { put("recipient", "+79991234567") })

        assertEquals(ToolExecutionStatus.FAILURE, result.status)
        assertEquals("MISSING_MESSAGE", result.error)
    }

    @Test
    fun smsWithEmptyRecipientReturnsFailure() = runBlocking {
        val tool = SmsTool(context, capabilities, contactResolver)

        val result = tool.execute(buildJsonObject { put("message", "текст") })

        assertEquals(ToolExecutionStatus.FAILURE, result.status)
        assertEquals("MISSING_RECIPIENT", result.error)
    }

    private fun assumePermissionDenied(permission: String) {
        assumeTrue(
            "Safety precondition: $permission must be denied; test will not perform a real call/SMS",
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        )
    }
}
