package com.jarvis.assistant.agent.decision

import com.jarvis.assistant.agent.capability.FakeCapabilityRegistry
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.discovery.ToolDiscoveryEngine
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.fast.FastCommandRouter
import com.jarvis.assistant.agent.memory.WorkingMemory
import com.jarvis.assistant.agent.memory.context.AnaphoraContextEngine
import com.jarvis.assistant.agent.memory.context.ReferenceResolver
import com.jarvis.assistant.agent.memory.semantic.SemanticTextMatcher
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.planner.ExecutionPlan
import com.jarvis.assistant.agent.planner.PlanStep
import com.jarvis.assistant.agent.model.ToolCall
import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.agent.safety.ToolPermissionManager
import com.jarvis.assistant.core.result.Resource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Этап 1 — Execution Decision Engine v0.2.
 *
 * Проверяется ТОЛЬКО слой принятия решения: настоящий FastCommandRouter
 * и настоящий ToolExecutor (существующие компоненты), а Local/Cloud/Agent —
 * управляемые тестовые дублёры портов.
 */
class ExecutionDecisionEngineTest {

    // ------------------------------------------------------------------ fakes

    private class ScriptedTool(
        override val toolId: String,
        private val result: ToolExecutionResult? = null,
        private val throwOnExecute: Boolean = false
    ) : JarvisTool {
        var calls = 0
            private set

        override val description: String = "Scripted $toolId"
        override val category: ToolCategory = ToolCategory.DEVICE
        override val riskLevel: ToolRisk = ToolRisk.SAFE
        override val parametersSchema: JsonObject = buildJsonObject { }

        override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
            calls++
            if (throwOnExecute) throw IllegalStateException("boom: hardware failure")
            return result ?: ToolExecutionResult.success("Готово")
        }
    }

    private class FakeLocalAi(
        private val outcome: LocalAiOutcome,
        override val hasWebCapability: Boolean = false
    ) : LocalAiExecutor {
        var calls = 0
            private set

        override suspend fun tryHandle(request: ExecutionRequest): LocalAiOutcome {
            calls++
            return outcome
        }
    }

    private class FakeCloudAi(
        private val response: Resource<String> = Resource.Success("Облачный ответ"),
        private val available: Boolean = true
    ) : CloudAiExecutor {
        var calls = 0
            private set

        override fun isAvailable(): Boolean = available

        override suspend fun complete(request: ExecutionRequest): Resource<String> {
            calls++
            return response
        }
    }

    private class FakeAgent(
        private val plan: ExecutionPlan? = null,
        private val planForLlmOutput: ExecutionPlan? = null,
        private val result: ExecutionResult = ExecutionResult.Success(
            text = "План выполнен, сэр.",
            executionType = ExecutionType.AGENT
        )
    ) : AgentExecutor {
        var planCalls = 0
            private set
        var runCalls = 0
            private set

        override fun planFor(request: ExecutionRequest, llmRawOutput: String?): ExecutionPlan? {
            planCalls++
            return if (llmRawOutput == null) plan else planForLlmOutput
        }

        override suspend fun run(plan: ExecutionPlan): ExecutionResult {
            runCalls++
            return result
        }
    }

    // ------------------------------------------------------------------ setup

    private fun buildEngine(
        tools: Set<JarvisTool> = emptySet(),
        localAi: LocalAiExecutor = FakeLocalAi(LocalAiOutcome.Uncertain),
        cloudAi: CloudAiExecutor = FakeCloudAi(),
        agent: AgentExecutor = FakeAgent()
    ): ExecutionDecisionEngine {
        val registry = ToolRegistry(tools, ToolDiscoveryEngine(SemanticTextMatcher()))
        val toolExecutor = ToolExecutor(registry, ToolPermissionManager(FakeCapabilityRegistry.create()))
        val workingMemory = WorkingMemory(AnaphoraContextEngine(), ReferenceResolver())

        return ExecutionDecisionEngine(
            fastCommandRouter = FastCommandRouter(),
            toolExecutor = toolExecutor,
            agentExecutor = agent,
            localAi = localAi,
            cloudAi = cloudAi,
            workingMemory = workingMemory,
            config = ExecutionDecisionConfig()
        )
    }

    private fun request(
        text: String,
        source: RequestSource = RequestSource.VOICE,
        requiresWeb: Boolean = false,
        privacy: PrivacyLevel = PrivacyLevel.NORMAL,
        cloudAllowed: Boolean = false
    ) = ExecutionRequest(
        text = text,
        source = source,
        requiresWeb = requiresWeb,
        privacyLevel = privacy,
        cloudExplicitlyAllowed = cloudAllowed
    )

    private fun simplePlan() = ExecutionPlan(
        goal = "multi-step",
        steps = listOf(PlanStep(toolCall = ToolCall("device.volume", buildJsonObject { })))
    )

    // ------------------------------------------------------------------ tests

    /** Test 1: device-команда + уверенный роутер → DEVICE_TOOL через ToolExecutor. */
    @Test
    fun `confident router routes to device tool`() = runBlocking {
        val tool = ScriptedTool("device.flashlight", ToolExecutionResult.success("Фонарик включен"))
        val local = FakeLocalAi(LocalAiOutcome.Uncertain)
        val cloud = FakeCloudAi()
        val engine = buildEngine(tools = setOf(tool), localAi = local, cloudAi = cloud)

        val result = engine.execute(request("включи фонарик"))

        assertTrue("Ожидался Success, получено: $result", result is ExecutionResult.Success)
        assertEquals(ExecutionType.DEVICE_TOOL, (result as ExecutionResult.Success).executionType)
        assertTrue(result.text.contains("Фонарик включен"))
        assertEquals(1, tool.calls)
        assertEquals("Local AI не должен вызываться", 0, local.calls)
        assertEquals("Cloud AI не должен вызываться", 0, cloud.calls)
    }

    /** Test 2: роутер не уверен → Local AI обрабатывает → LOCAL_AI. */
    @Test
    fun `uncertain router falls back to local ai`() = runBlocking {
        val local = FakeLocalAi(LocalAiOutcome.Handled("Сценарий 'сон' выполнен, сэр."))
        val cloud = FakeCloudAi()
        val engine = buildEngine(localAi = local, cloudAi = cloud)

        val result = engine.execute(request("активируй мой личный сценарий"))

        assertTrue(result is ExecutionResult.Success)
        assertEquals(ExecutionType.LOCAL_AI, (result as ExecutionResult.Success).executionType)
        assertEquals(1, local.calls)
        assertEquals(0, cloud.calls)
    }

    /** Test 3: Local AI не уверен → Cloud AI → CLOUD_AI. */
    @Test
    fun `local ai uncertain escalates to cloud ai`() = runBlocking {
        val local = FakeLocalAi(LocalAiOutcome.Uncertain)
        val cloud = FakeCloudAi(Resource.Success("Ответ облачной модели"))
        val engine = buildEngine(localAi = local, cloudAi = cloud)

        val result = engine.execute(request("расскажи что-нибудь интересное про космос"))

        assertTrue(result is ExecutionResult.Success)
        val success = result as ExecutionResult.Success
        assertEquals(ExecutionType.CLOUD_AI, success.executionType)
        assertEquals("Ответ облачной модели", success.text)
        assertEquals(1, local.calls)
        assertEquals(1, cloud.calls)
    }

    /** Test 4: многошаговый запрос → AgentCognitiveLoop → AGENT. */
    @Test
    fun `complex multi step request routes to agent`() = runBlocking {
        val agent = FakeAgent(plan = simplePlan())
        val local = FakeLocalAi(LocalAiOutcome.Handled("не должно вызваться"))
        val cloud = FakeCloudAi()
        val engine = buildEngine(localAi = local, cloudAi = cloud, agent = agent)

        val result = engine.execute(request("подготовь телефон ко сну и выключи всё лишнее"))

        assertTrue(result is ExecutionResult.Success)
        assertEquals(ExecutionType.AGENT, (result as ExecutionResult.Success).executionType)
        assertEquals(1, agent.runCalls)
        assertEquals(0, local.calls)
        assertEquals(0, cloud.calls)
    }

    /** Test 5: PRIVATE + Local AI не справился → Cloud AI НЕ вызывается. */
    @Test
    fun `private request is never sent to cloud`() = runBlocking {
        val local = FakeLocalAi(LocalAiOutcome.Uncertain)
        val cloud = FakeCloudAi()
        val engine = buildEngine(localAi = local, cloudAi = cloud)

        val result = engine.execute(
            request("мой пароль от банковского приложения", privacy = PrivacyLevel.PRIVATE)
        )

        assertTrue("Ожидался Error, получено: $result", result is ExecutionResult.Error)
        assertEquals(DecisionReason.CLOUD_BLOCKED_BY_PRIVACY, (result as ExecutionResult.Error).reason)
        assertEquals("Cloud AI не должен вызываться для PRIVATE", 0, cloud.calls)
    }

    /** Test 6: SENSITIVE — без явного разрешения нет облака, с разрешением — есть. */
    @Test
    fun `sensitive request requires explicit permission for cloud`() = runBlocking {
        val blockedCloud = FakeCloudAi()
        val blockedEngine = buildEngine(cloudAi = blockedCloud)

        val blocked = blockedEngine.execute(
            request("данные моей медицинской карты", privacy = PrivacyLevel.SENSITIVE)
        )

        assertTrue(blocked is ExecutionResult.Error)
        assertEquals(DecisionReason.CLOUD_BLOCKED_BY_PRIVACY, (blocked as ExecutionResult.Error).reason)
        assertEquals(0, blockedCloud.calls)

        val allowedCloud = FakeCloudAi(Resource.Success("Обработано по явному разрешению"))
        val allowedEngine = buildEngine(cloudAi = allowedCloud)

        val allowed = allowedEngine.execute(
            request(
                "данные моей медицинской карты",
                privacy = PrivacyLevel.SENSITIVE,
                cloudAllowed = true
            )
        )

        assertTrue(allowed is ExecutionResult.Success)
        assertEquals(ExecutionType.CLOUD_AI, (allowed as ExecutionResult.Success).executionType)
        assertEquals(1, allowedCloud.calls)
    }

    /** Test 7: requiresWeb — Local AI без web-возможности пропускается. */
    @Test
    fun `web request skips local ai without web capability`() = runBlocking {
        val local = FakeLocalAi(
            outcome = LocalAiOutcome.Handled("Я всё знаю сам"),
            hasWebCapability = false
        )
        val cloud = FakeCloudAi(Resource.Success("Актуальные новости из сети"))
        val engine = buildEngine(localAi = local, cloudAi = cloud)

        val result = engine.execute(request("какие сейчас новости", requiresWeb = true))

        assertTrue(result is ExecutionResult.Success)
        val success = result as ExecutionResult.Success
        assertEquals(ExecutionType.CLOUD_AI, success.executionType)
        assertEquals("Local AI не должен трогаться при requiresWeb", 0, local.calls)
        assertEquals(1, cloud.calls)
    }

    /** Test 7b: requiresWeb + PRIVATE → ни локально, ни в облако — честный Error. */
    @Test
    fun `private web request is refused instead of leaking to cloud`() = runBlocking {
        val local = FakeLocalAi(LocalAiOutcome.Handled("нельзя"), hasWebCapability = false)
        val cloud = FakeCloudAi()
        val engine = buildEngine(localAi = local, cloudAi = cloud)

        val result = engine.execute(
            request("найди в сети мои личные данные", requiresWeb = true, privacy = PrivacyLevel.PRIVATE)
        )

        assertTrue(result is ExecutionResult.Error)
        assertEquals(DecisionReason.CLOUD_BLOCKED_BY_PRIVACY, (result as ExecutionResult.Error).reason)
        assertEquals(0, cloud.calls)
        assertEquals(0, local.calls)
    }

    /** Test 8a: инструмент бросает исключение → ExecutionResult.Error, без утечки stack trace. */
    @Test
    fun `tool execution failure becomes error result`() = runBlocking {
        val tool = ScriptedTool("device.flashlight", throwOnExecute = true)
        val engine = buildEngine(tools = setOf(tool))

        val result = engine.execute(request("включи фонарик"))

        assertTrue("Ожидался Error, получено: $result", result is ExecutionResult.Error)
        val error = result as ExecutionResult.Error
        assertEquals(DecisionReason.DEVICE_TOOL_FAILED, error.reason)
        assertFalse("Stack trace не должен попадать пользователю", error.message.contains("at com.jarvis"))
    }

    /** Test 8b: инструмент не зарегистрирован → Error, а не исключение. */
    @Test
    fun `missing tool becomes error result`() = runBlocking {
        val engine = buildEngine(tools = emptySet())

        val result = engine.execute(request("включи фонарик"))

        assertTrue(result is ExecutionResult.Error)
        assertEquals(DecisionReason.DEVICE_TOOL_FAILED, (result as ExecutionResult.Error).reason)
    }

    /** Test 9a: сбой Cloud AI → ExecutionResult.Error. */
    @Test
    fun `cloud failure becomes error result`() = runBlocking {
        val cloud = FakeCloudAi(Resource.Error(java.io.IOException("timeout"), "Сервер AI недоступен"))
        val engine = buildEngine(cloudAi = cloud)

        val result = engine.execute(request("объясни теорию относительности простыми словами"))

        assertTrue(result is ExecutionResult.Error)
        val error = result as ExecutionResult.Error
        assertEquals(DecisionReason.CLOUD_FAILED, error.reason)
        assertEquals("Сервер AI недоступен", error.message)
    }

    /** Test 9b: сети нет → Error без вызова облака. */
    @Test
    fun `offline network yields error without cloud call`() = runBlocking {
        val cloud = FakeCloudAi(available = false)
        val engine = buildEngine(cloudAi = cloud)

        val result = engine.execute(request("расскажи анекдот про программистов"))

        assertTrue(result is ExecutionResult.Error)
        assertEquals(DecisionReason.CLOUD_FAILED, (result as ExecutionResult.Error).reason)
        assertEquals(0, cloud.calls)
    }

    /** Дополнительно: подтверждение опасного действия не превращается в ошибку. */
    @Test
    fun `tool requiring confirmation returns confirmation result`() = runBlocking {
        val confirmTool = object : JarvisTool {
            override val toolId: String = "communication.call"
            override val description: String = "Звонок"
            override val category: ToolCategory = ToolCategory.COMMUNICATION
            override val riskLevel: ToolRisk = ToolRisk.CONFIRMATION_REQUIRED
            override val parametersSchema: JsonObject = buildJsonObject { }
            override suspend fun execute(arguments: JsonObject): ToolExecutionResult =
                ToolExecutionResult.success("Звоню")
        }
        val engine = buildEngine(tools = setOf(confirmTool))

        val result = engine.execute(request("позвони маме"))

        assertTrue("Ожидался ConfirmationRequired, получено: $result",
            result is ExecutionResult.ConfirmationRequired)
        assertEquals(
            "communication.call",
            (result as ExecutionResult.ConfirmationRequired).toolCall.toolId
        )
    }

    /** Дополнительно: локальный ответ роутера без инструмента («привет»). */
    @Test
    fun `router direct response is handled without ai`() = runBlocking {
        val local = FakeLocalAi(LocalAiOutcome.Handled("нет"))
        val cloud = FakeCloudAi()
        val engine = buildEngine(localAi = local, cloudAi = cloud)

        val result = engine.execute(request("привет"))

        assertTrue(result is ExecutionResult.Success)
        assertEquals(ExecutionType.DEVICE_TOOL, (result as ExecutionResult.Success).executionType)
        assertEquals(0, local.calls)
        assertEquals(0, cloud.calls)
    }

    /** Дополнительно: план из tool_calls облачной модели исполняет агент. */
    @Test
    fun `cloud tool calls are executed by agent`() = runBlocking {
        val agent = FakeAgent(plan = null, planForLlmOutput = simplePlan())
        val cloud = FakeCloudAi(Resource.Success("""{"tool":"device.volume"}"""))
        val engine = buildEngine(cloudAi = cloud, agent = agent)

        val result = engine.execute(request("сделай как я люблю по вечерам"))

        assertTrue(result is ExecutionResult.Success)
        assertEquals(ExecutionType.AGENT, (result as ExecutionResult.Success).executionType)
        assertEquals(1, agent.runCalls)
    }

    /** Дополнительно: приватный текст не попадает в логируемое представление. */
    @Test
    fun `private request text is redacted for logging`() {
        val normal = request("обычный запрос", privacy = PrivacyLevel.NORMAL)
        val private = request("секретный текст", privacy = PrivacyLevel.PRIVATE)

        assertEquals("обычный запрос", normal.loggableText)
        assertFalse(private.loggableText.contains("секретный"))
        assertTrue(private.loggableText.startsWith("<redacted:"))
    }
}
