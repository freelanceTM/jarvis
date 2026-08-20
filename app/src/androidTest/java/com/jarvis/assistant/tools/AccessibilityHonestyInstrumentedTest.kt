package com.jarvis.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.agent.tools.accessibility.ScreenReaderTool
import com.jarvis.assistant.agent.tools.accessibility.UiClickTool
import com.jarvis.assistant.agent.tools.accessibility.UiTypeTextTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Инструментальные тесты честности accessibility-действий (пункт аудита #11).
 *
 * На тестовом устройстве/эмуляторе служба специальных возможностей JARVIS
 * НЕ включена → все три тула обязаны вернуть USER_ACTION_REQUIRED, а не
 * SUCCESS и не FAILURE «молча».
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityHonestyInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val emptyArgs: JsonObject = buildJsonObject { }

    @Test
    fun screenReaderWithoutEnabledServiceReturnsUserActionRequired() = runBlocking {
        val tool = ScreenReaderTool(context)

        val result = tool.execute(emptyArgs)

        // Служба не включена → честный USER_ACTION_REQUIRED (с открытием настроек),
        // а НЕ SUCCESS с выдуманным содержимым экрана.
        assertNotEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertEquals(ToolExecutionStatus.USER_ACTION_REQUIRED, result.status)
        assertEquals("ACCESSIBILITY_SERVICE_DISABLED", result.error)
    }

    @Test
    fun uiClickWithoutEnabledServiceReturnsUserActionRequired() = runBlocking {
        val tool = UiClickTool(context)

        val result = tool.execute(buildJsonObject { put("target_text", "Отправить") })

        assertNotEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertEquals(ToolExecutionStatus.USER_ACTION_REQUIRED, result.status)
        assertEquals("ACCESSIBILITY_SERVICE_DISABLED", result.error)
    }

    @Test
    fun typeTextWithoutEnabledServiceReturnsUserActionRequired() = runBlocking {
        val tool = UiTypeTextTool(context)

        val result = tool.execute(buildJsonObject { put("text", "UFC") })

        assertNotEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertEquals(ToolExecutionStatus.USER_ACTION_REQUIRED, result.status)
        assertEquals("ACCESSIBILITY_SERVICE_DISABLED", result.error)
    }

    @Test
    fun uiClickWithEmptyTargetReturnsFailure() = runBlocking {
        val tool = UiClickTool(context)

        val result = tool.execute(emptyArgs)

        assertEquals(ToolExecutionStatus.FAILURE, result.status)
        assertEquals("MISSING_TARGET_TEXT", result.error)
    }

    @Test
    fun typeTextWithEmptyTextReturnsFailure() = runBlocking {
        val tool = UiTypeTextTool(context)

        val result = tool.execute(emptyArgs)

        assertEquals(ToolExecutionStatus.FAILURE, result.status)
        assertEquals("MISSING_TEXT", result.error)
    }
}
