package com.jarvis.assistant.agent.memory.procedural

import com.jarvis.assistant.agent.capability.FakeCapabilityRegistry
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.memory.dao.ProcedureDao
import com.jarvis.assistant.agent.memory.entity.ProcedureEntity
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.agent.safety.ToolPermissionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WorkflowExecutorTest {

    private lateinit var workflowExecutor: WorkflowExecutor
    private lateinit var fakeDao: FakeProcedureDao

    class FakeProcedureDao : ProcedureDao {
        private val storage = mutableMapOf<String, ProcedureEntity>()

        override fun getAllProceduresStream(): Flow<List<ProcedureEntity>> = flowOf(storage.values.toList())

        override suspend fun getProcedureByTrigger(trigger: String): ProcedureEntity? = storage[trigger]

        override suspend fun insertProcedure(proc: ProcedureEntity): Long {
            storage[proc.triggerPhrase] = proc
            return 1L
        }

        override suspend fun recordExecution(trigger: String, timestamp: Long) {
            val existing = storage[trigger]
            if (existing != null) {
                storage[trigger] = existing.copy(
                    executionCount = existing.executionCount + 1,
                    updatedAt = timestamp
                )
            }
        }

        override suspend fun deleteProcedure(trigger: String) {
            storage.remove(trigger)
        }
    }

    class DummyTool(
        override val toolId: String,
        override val description: String = "Test Tool",
        override val category: ToolCategory = ToolCategory.DEVICE,
        override val riskLevel: ToolRisk = ToolRisk.SAFE,
        override val parametersSchema: JsonObject = buildJsonObject { }
    ) : JarvisTool {
        override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
            return ToolExecutionResult.success("Действие $toolId выполнено")
        }
    }

    @Before
    fun setUp() {
        fakeDao = FakeProcedureDao()
        val tools = setOf<JarvisTool>(
            DummyTool("device.volume"),
            DummyTool("device.flashlight"),
            DummyTool("device.open_app")
        )
        val registry = ToolRegistry(tools, com.jarvis.assistant.agent.discovery.ToolDiscoveryEngine(com.jarvis.assistant.agent.memory.semantic.SemanticTextMatcher()))
        // ToolPermissionManager теперь работает поверх capability-реестра;
        // в чистом unit-тесте Android-контекст недоступен, поэтому пропускаем
        // проверку возможностей через null-реестр не получится — используем
        // реальный менеджер с фиктивным реестром.
        val permissionManager = ToolPermissionManager(FakeCapabilityRegistry.create())
        val executor = ToolExecutor(registry, permissionManager)
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        workflowExecutor = WorkflowExecutor(fakeDao, executor, json)
    }

    @Test
    fun testSleepMacroExecution() = runBlocking {
        val result = workflowExecutor.tryExecuteWorkflow("Джарвис, спокойной ночи")
        assertNotNull(result)
        assertTrue(result?.isSuccess == true)
        assertTrue(result?.summary?.contains("сон") == true)
    }

    @Test
    fun testWorkMacroExecution() = runBlocking {
        val result = workflowExecutor.tryExecuteWorkflow("работа")
        assertNotNull(result)
        assertTrue(result?.isSuccess == true)
        assertTrue(result?.summary?.contains("работа") == true)
    }

    @Test
    fun testRegisterCustomWorkflowAndExecute() = runBlocking {
        val actions = listOf(
            ToolCall("device.flashlight", buildJsonObject { })
        )
        workflowExecutor.registerWorkflow("свет", actions)

        val result = workflowExecutor.tryExecuteWorkflow("свет")
        assertNotNull(result)
        assertTrue(result?.isSuccess == true)
        assertTrue(result?.summary?.contains("свет") == true)
    }

    @Test
    fun testNonMatchingTriggerReturnsNull() = runBlocking {
        val result = workflowExecutor.tryExecuteWorkflow("расскажи анекдот")
        assertNull(result)
    }
}
