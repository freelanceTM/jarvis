package com.jarvis.assistant.agent.engine

import com.jarvis.assistant.agent.capability.FakeCapabilityRegistry
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.discovery.ToolDiscoveryEngine
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.memory.WorkingMemory
import com.jarvis.assistant.agent.memory.context.AnaphoraContextEngine
import com.jarvis.assistant.agent.memory.context.ReferenceResolver
import com.jarvis.assistant.agent.memory.semantic.SemanticTextMatcher
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.observation.AgentObservationEngine
import com.jarvis.assistant.agent.parser.ToolCallParser
import com.jarvis.assistant.agent.planner.CognitivePlanner
import com.jarvis.assistant.agent.planner.ExecutionPlan
import com.jarvis.assistant.agent.planner.PlanStep
import com.jarvis.assistant.agent.planner.StepObservation
import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.agent.safety.ToolPermissionManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Тесты agent state machine:
 *
 *   PLAN → EXECUTE → OBSERVE → goal achieved? → DONE / REPLAN
 *
 * Проверяются guardrails: MAX_REPLANS = 2 и отсутствие бесконечного цикла.
 */
class AgentCognitiveLoopTest {

    /** Инструмент с программируемым поведением и счётчиком вызовов. */
    private class ScriptedTool(
        override val toolId: String,
        private val results: List<ToolExecutionResult>
    ) : JarvisTool {
        var calls = 0
            private set

        override val description: String = "Scripted $toolId"
        override val category: ToolCategory = ToolCategory.DEVICE
        override val riskLevel: ToolRisk = ToolRisk.SAFE
        override val parametersSchema: JsonObject = buildJsonObject { }

        override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
            val result = results[minOf(calls, results.lastIndex)]
            calls++
            return result
        }
    }

    /** Планировщик, который всегда предлагает один и тот же альтернативный шаг. */
    private class AlwaysReplanPlanner(
        private val alternativeToolId: String,
        parser: ToolCallParser
    ) : CognitivePlanner(parser) {
        var replanCalls = 0
            private set

        override fun replan(
            currentPlan: ExecutionPlan,
            failedStep: PlanStep,
            observation: StepObservation.StepFailed,
            attemptNumber: Int
        ): ExecutionPlan? {
            replanCalls++
            if (attemptNumber > 2) return null
            return ExecutionPlan(
                goal = currentPlan.goal,
                explanation = "alternative #$attemptNumber",
                steps = listOf(
                    PlanStep(
                        toolCall = ToolCall(alternativeToolId, buildJsonObject { }),
                        description = "Альтернатива $attemptNumber",
                        isCritical = true
                    )
                )
            )
        }
    }

    private fun buildLoop(
        tools: Set<JarvisTool>,
        planner: CognitivePlanner
    ): AgentCognitiveLoop {
        val registry = ToolRegistry(tools, ToolDiscoveryEngine(SemanticTextMatcher()))
        val executor = ToolExecutor(registry, ToolPermissionManager(FakeCapabilityRegistry.create()))
        val workingMemory = WorkingMemory(AnaphoraContextEngine(), ReferenceResolver())
        return AgentCognitiveLoop(planner, executor, AgentObservationEngine(workingMemory))
    }

    private fun planOf(vararg toolIds: String, critical: Boolean = true) = ExecutionPlan(
        goal = "test goal",
        steps = toolIds.map {
            PlanStep(toolCall = ToolCall(it, buildJsonObject { }), description = it, isCritical = critical)
        }
    )

    @Test
    fun `successful plan completes without replanning`() = runBlocking {
        val parser = ToolCallParser(Json { ignoreUnknownKeys = true })
        val planner = AlwaysReplanPlanner("device.fallback", parser)
        val tool = ScriptedTool("device.volume", listOf(ToolExecutionResult.success("Громкость установлена")))
        val loop = buildLoop(setOf(tool), planner)

        val summary = loop.runPlan(planOf("device.volume"))

        assertTrue(summary.isAllSuccessful)
        assertEquals(0, planner.replanCalls)
        assertEquals(1, tool.calls)
    }

    @Test
    fun `failing step triggers at most MAX_REPLANS replans`() = runBlocking {
        val parser = ToolCallParser(Json { ignoreUnknownKeys = true })
        val planner = AlwaysReplanPlanner("device.always_fails", parser)
        // Инструмент падает всегда — цикл обязан остановиться по лимиту.
        val tool = ScriptedTool("device.always_fails", listOf(ToolExecutionResult.failure("boom", "E")))
        val loop = buildLoop(setOf(tool), planner)

        val summary = loop.runPlan(planOf("device.always_fails"))

        assertFalse(summary.isAllSuccessful)
        assertEquals(
            "Planner must be asked to replan no more than MAX_REPLANS times",
            AgentCognitiveLoop.MAX_REPLANS,
            planner.replanCalls
        )
        // 1 исходный вызов + по одному на каждый replan
        assertEquals(AgentCognitiveLoop.MAX_REPLANS + 1, tool.calls)
    }

    @Test
    fun `loop terminates and never exceeds step budget`() = runBlocking {
        val parser = ToolCallParser(Json { ignoreUnknownKeys = true })
        val planner = AlwaysReplanPlanner("device.always_fails", parser)
        val tool = ScriptedTool("device.always_fails", listOf(ToolExecutionResult.failure("boom", "E")))
        val loop = buildLoop(setOf(tool), planner)

        val summary = loop.runPlan(planOf("device.always_fails"))

        assertTrue("Loop must terminate", summary.observations.isNotEmpty())
        assertTrue(
            "Step budget must never be exceeded",
            tool.calls <= AgentCognitiveLoop.MAX_TOTAL_STEPS
        )
    }

    @Test
    fun `replan can rescue a failing step`() = runBlocking {
        val parser = ToolCallParser(Json { ignoreUnknownKeys = true })
        val planner = AlwaysReplanPlanner("device.backup", parser)
        val failing = ScriptedTool("device.primary", listOf(ToolExecutionResult.failure("boom", "E")))
        val backup = ScriptedTool("device.backup", listOf(ToolExecutionResult.success("Сработала альтернатива")))
        val loop = buildLoop(setOf(failing, backup), planner)

        val summary = loop.runPlan(planOf("device.primary"))

        assertEquals(1, planner.replanCalls)
        assertEquals(1, backup.calls)
        assertTrue(summary.finalVoiceSummary.contains("альтернатива", ignoreCase = true))
    }

    @Test
    fun `android restriction does not trigger replanning`() = runBlocking {
        val parser = ToolCallParser(Json { ignoreUnknownKeys = true })
        val planner = AlwaysReplanPlanner("device.backup", parser)
        // Bluetooth нельзя включить программно — перепланирование бессмысленно.
        val blocked = ScriptedTool(
            "device.bluetooth",
            listOf(ToolExecutionResult.userActionRequired("Откройте настройки Bluetooth", "REQUIRES_USER"))
        )
        val loop = buildLoop(setOf(blocked), planner)

        val summary = loop.runPlan(planOf("device.bluetooth"))

        assertEquals("Re-plan cannot bypass Android restrictions", 0, planner.replanCalls)
        assertEquals(1, blocked.calls)
        assertFalse(summary.isAllSuccessful)
        assertTrue(summary.finalVoiceSummary.contains("Bluetooth"))
    }

    @Test
    fun `unsupported capability aborts without replanning`() = runBlocking {
        val parser = ToolCallParser(Json { ignoreUnknownKeys = true })
        val planner = AlwaysReplanPlanner("device.backup", parser)
        val unsupported = ScriptedTool(
            "device.screenshot",
            listOf(ToolExecutionResult.unsupported("Android 10 не умеет", "UNSUPPORTED"))
        )
        val loop = buildLoop(setOf(unsupported), planner)

        val summary = loop.runPlan(planOf("device.screenshot"))

        assertEquals(0, planner.replanCalls)
        assertFalse(summary.isAllSuccessful)
    }

    @Test
    fun `non critical failure lets the rest of the plan continue`() = runBlocking {
        val parser = ToolCallParser(Json { ignoreUnknownKeys = true })
        val planner = object : CognitivePlanner(parser) {
            override fun replan(
                currentPlan: ExecutionPlan,
                failedStep: PlanStep,
                observation: StepObservation.StepFailed,
                attemptNumber: Int
            ): ExecutionPlan? = null
        }
        val failing = ScriptedTool("device.flaky", listOf(ToolExecutionResult.failure("boom", "E")))
        val working = ScriptedTool("system.battery", listOf(ToolExecutionResult.success("Заряд 80%")))
        val loop = buildLoop(setOf(failing, working), planner)

        val summary = loop.runPlan(planOf("device.flaky", "system.battery", critical = false))

        assertEquals("Second step must still run", 1, working.calls)
        assertFalse(summary.isAllSuccessful)
    }

    @Test
    fun `confirmation pauses the loop and reports pending call`() = runBlocking {
        val parser = ToolCallParser(Json { ignoreUnknownKeys = true })
        val planner = AlwaysReplanPlanner("device.backup", parser)
        val call = ToolCall("communication.sms", buildJsonObject { })
        val needsConfirmation = ScriptedTool(
            "communication.sms",
            listOf(ToolExecutionResult.requiresConfirmation("Подтвердите отправку", call))
        )
        val loop = buildLoop(setOf(needsConfirmation), planner)

        val summary = loop.runPlan(planOf("communication.sms"))

        assertNotNull(summary.pendingConfirmation)
        assertFalse(summary.isAllSuccessful)
        assertEquals(0, planner.replanCalls)
    }

    @Test
    fun `MAX_REPLANS constant is two`() {
        assertEquals(2, AgentCognitiveLoop.MAX_REPLANS)
    }

    // =========================================================================
    // VERIFY phase: шаг засчитывается только после проверки экрана
    // =========================================================================

    @Test
    fun `step with verify passes when expected text is on screen`() = runBlocking {
        val parser = ToolCallParser(Json { ignoreUnknownKeys = true })
        val planner = AlwaysReplanPlanner("device.backup", parser)
        val openApp = ScriptedTool("device.open_app", listOf(ToolExecutionResult.success("YouTube открыт")))
        // Экран после открытия: поле поиска и результаты присутствуют.
        val screenReader = ScriptedTool(
            "accessibility.screen_reader",
            listOf(ToolExecutionResult.success("YouTube, поиск, UFC, бой вечера"))
        )
        val loop = buildLoop(setOf(openApp, screenReader), planner)

        val plan = ExecutionPlan(
            goal = "Открыть YouTube и найти UFC",
            explanation = "",
            steps = listOf(
                PlanStep(
                    toolCall = ToolCall("device.open_app", buildJsonObject { }),
                    description = "Открыть YouTube",
                    verifyScreenContains = "UFC"
                )
            )
        )

        val summary = loop.runPlan(plan)

        assertTrue("Goal is verified when expected text is on screen", summary.isAllSuccessful)
        assertEquals("VERIFY reads the screen exactly once", 1, screenReader.calls)
        assertEquals("No replan needed when verify passes", 0, planner.replanCalls)
    }

    @Test
    fun `step fails verification when expected text is missing and replans`() = runBlocking {
        val parser = ToolCallParser(Json { ignoreUnknownKeys = true })
        // Планировщик, который НЕ предлагает альтернатив (verify-провал честный).
        val planner = object : CognitivePlanner(parser) {
            override fun replan(
                currentPlan: ExecutionPlan,
                failedStep: PlanStep,
                observation: StepObservation.StepFailed,
                attemptNumber: Int
            ): ExecutionPlan? = null
        }
        val openApp = ScriptedTool("device.open_app", listOf(ToolExecutionResult.success("YouTube открыт")))
        // Экран открылся, но искомого текста НЕТ — цель не достигнута.
        val screenReader = ScriptedTool(
            "accessibility.screen_reader",
            listOf(ToolExecutionResult.success("YouTube, главная страница, без результатов"))
        )
        val loop = buildLoop(setOf(openApp, screenReader), planner)

        val plan = ExecutionPlan(
            goal = "Открыть YouTube и найти UFC",
            explanation = "",
            steps = listOf(
                PlanStep(
                    toolCall = ToolCall("device.open_app", buildJsonObject { }),
                    description = "Открыть YouTube",
                    isCritical = false,
                    verifyScreenContains = "UFC"
                )
            )
        )

        val summary = loop.runPlan(plan)

        assertFalse("Verify failure must not report success", summary.isAllSuccessful)
        assertEquals(1, screenReader.calls)
        assertTrue(
            "Voice summary must honestly report that goal was not confirmed",
            summary.finalVoiceSummary.contains("не найден", ignoreCase = true)
        )
    }
}
