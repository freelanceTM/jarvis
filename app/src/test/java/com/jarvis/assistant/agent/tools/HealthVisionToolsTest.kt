package com.jarvis.assistant.agent.tools

import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.agent.tools.health.ActivityTool
import com.jarvis.assistant.agent.tools.health.HeartRateTool
import com.jarvis.assistant.agent.tools.health.SleepTool
import com.jarvis.assistant.agent.tools.health.StepsTool
import com.jarvis.assistant.agent.tools.health.WearOsTool
import com.jarvis.assistant.agent.tools.intelligence.VisionTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Честность веток Vision и Health дерева TOOLS:
 * в v0.2 нет ни vision-модели, ни Wear OS / Health Connect —
 * инструменты возвращают UNSUPPORTED с причиной, а не выдуманные данные.
 */
class HealthVisionToolsTest {

    private val emptyArgs = JsonObject(emptyMap())

    @Test
    fun `vision tool honestly reports unavailable model`() = runBlocking {
        val result = VisionTool().execute(emptyArgs)

        assertEquals(ToolExecutionStatus.UNSUPPORTED, result.status)
        assertEquals("VISION_MODEL_UNAVAILABLE", result.error)
        assertTrue(result.summary.contains("модель компьютерного зрения не включена"))
    }

    @Test
    fun `health tools honestly report unavailable data`() = runBlocking {
        val tools = listOf(
            WearOsTool() to "health.wear_os",
            HeartRateTool() to "health.heart_rate",
            StepsTool() to "health.steps",
            SleepTool() to "health.sleep",
            ActivityTool() to "health.activity"
        )

        tools.forEach { (tool, expectedId) ->
            assertEquals(expectedId, tool.toolId)
            val result = tool.execute(emptyArgs)
            assertEquals("$expectedId должен быть UNSUPPORTED", ToolExecutionStatus.UNSUPPORTED, result.status)
            assertEquals("HEALTH_UNAVAILABLE", result.error)
            assertTrue("$expectedId должен объяснять причину", result.summary.isNotBlank())
        }
    }

    @Test
    fun `health tools are registered with safe risk level`() {
        listOf(WearOsTool(), HeartRateTool(), StepsTool(), SleepTool(), ActivityTool())
            .forEach { tool ->
                assertTrue(tool.riskLevel.name in setOf("SAFE", "LOW"))
            }
    }
}
