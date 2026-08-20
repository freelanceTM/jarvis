package com.jarvis.assistant.agent.registry

import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.discovery.ToolDiscoveryEngine
import com.jarvis.assistant.agent.memory.semantic.SemanticTextMatcher
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryValidationTest {

    private class FakeTool(override val toolId: String) : JarvisTool {
        override val description = toolId
        override val category = ToolCategory.SYSTEM
        override val parametersSchema: JsonObject = buildJsonObject { }
        override val riskLevel = ToolRisk.SAFE
        override suspend fun execute(arguments: JsonObject) = ToolExecutionResult.success(toolId)
    }

    private fun registry(vararg tools: JarvisTool) = ToolRegistry(
        tools.toSet(),
        ToolDiscoveryEngine(SemanticTextMatcher())
    )

    @Test
    fun `unique ids support exact and short alias lookup`() {
        val time = FakeTool("system.time")
        val battery = FakeTool("system.battery")
        val registry = registry(time, battery)

        assertEquals(time, registry.getTool("system.time"))
        assertEquals(time, registry.getTool("time"))
        assertEquals(2, registry.getAllTools().size)
    }

    @Test
    fun `blank duplicate ids and ambiguous aliases fail fast`() {
        assertIllegalArgument { registry(FakeTool("")) }
        assertIllegalArgument { registry(FakeTool("system.time"), FakeTool("system.time")) }
        assertIllegalArgument { registry(FakeTool("system.status"), FakeTool("device.status")) }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
