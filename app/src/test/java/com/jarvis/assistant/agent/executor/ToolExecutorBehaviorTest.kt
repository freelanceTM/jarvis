package com.jarvis.assistant.agent.executor

import com.jarvis.assistant.agent.capability.FakeCapabilityRegistry
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.discovery.ToolDiscoveryEngine
import com.jarvis.assistant.agent.memory.semantic.SemanticTextMatcher
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.agent.safety.ToolPermissionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ToolExecutorBehaviorTest {

    private class ScriptedTool(
        override val toolId: String,
        override val executionTimeoutMs: Long = 4_000,
        private val run: suspend () -> ToolExecutionResult
    ) : JarvisTool {
        override val description = toolId
        override val category = ToolCategory.SYSTEM
        override val parametersSchema: JsonObject = buildJsonObject { }
        override val riskLevel = ToolRisk.SAFE
        val rollbacks = AtomicInteger(0)
        override suspend fun execute(arguments: JsonObject) = run()
        override suspend fun rollback(arguments: JsonObject, rollbackData: JsonObject?): Boolean {
            rollbacks.incrementAndGet()
            return true
        }
    }

    private fun executor(vararg tools: JarvisTool): ToolExecutor {
        val registry = ToolRegistry(
            tools.toSet(),
            ToolDiscoveryEngine(SemanticTextMatcher())
        )
        return ToolExecutor(registry, ToolPermissionManager(FakeCapabilityRegistry.create()))
    }

    private fun call(id: String) = ToolCall(id, buildJsonObject { })

    @Test
    fun `tool timeout is normalized without escaping exception`() = runBlocking {
        val slow = ScriptedTool("slow", executionTimeoutMs = 20) {
            delay(200)
            ToolExecutionResult.success("late")
        }

        val result = executor(slow).execute(call("slow"))

        assertEquals(ToolExecutionStatus.TIMEOUT, result.status)
        assertEquals("ExecutionTimeoutException", result.error)
    }

    @Test
    fun `caller cancellation propagates and rolls back completed workflow steps`() = runBlocking {
        val first = ScriptedTool("first") {
            ToolExecutionResult.success(
                "first done",
                data = buildJsonObject { put("changed", true) },
                rollbackData = buildJsonObject { put("previous", false) }
            )
        }
        val secondStarted = CompletableDeferred<Unit>()
        val second = ScriptedTool("second") {
            secondStarted.complete(Unit)
            awaitCancellation()
        }
        val executor = executor(first, second)

        val job = launch {
            executor.executeAll(listOf(call("first"), call("second")))
        }
        secondStarted.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(1, first.rollbacks.get())
        assertEquals(0, second.rollbacks.get())
    }
}
