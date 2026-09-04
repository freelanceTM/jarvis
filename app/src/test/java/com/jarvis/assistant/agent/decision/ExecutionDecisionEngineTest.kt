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
import com.jarvis.assistant.agent.metrics.ExecutionRouterMetrics
import com.jarvis.assistant.agent.metrics.VoiceLatencyMetrics
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
        private val throwOnExecute: Boolean = false,
        override val isOffline: Boolean = true
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
        agent: AgentExecutor = FakeAgent(),
        metrics: ExecutionRouterMetrics = ExecutionRouterMetrics(),
        latency: VoiceLatencyMetrics = VoiceLatencyMetrics()
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
            config = ExecutionDecisionConfig(),
            metrics = metrics,
            latency = latency
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

    /** Настоящий многошаговый план (для AGENT-полосы). */
    private fun multiStepPlan() = ExecutionPlan(
        goal = "multi-step",
        steps = listOf(
            PlanStep(toolCall = ToolCall("device.volume", buildJsonObject { })),
            PlanStep(toolCall = ToolCall("system.battery", buildJsonObject { }))
        )
    )

    // ------------------------------------------------------------------ tests

    @Test
    fun `underspecified commands request clarification before any executor`() = runBlocking {
        val local = FakeLocalAi(LocalAiOutcome.Handled("must not run"))
        val cloud = FakeCloudAi()
        val agent = FakeAgent(plan = simplePlan())
        val engine = buildEngine(localAi = local, cloudAi = cloud, agent = agent)

        for (query in listOf("Открой", "Найди это", "Что лучше?", "Проверь", "Переведи", "Сделай это")) {
            val result = engine.execute(request(query))
            assertTrue("query=$query result=$result", result is ExecutionResult.ClarificationRequired)
            assertTrue((result as ExecutionResult.ClarificationRequired).promptMessage.endsWith("?"))
        }
        assertEquals(0, local.calls)
        assertEquals(0, cloud.calls)
        assertEquals(0, agent.planCalls)
    }

    @Test
    fun `blank request is rejected before any executor is called`() = runBlocking {
        val local = FakeLocalAi(LocalAiOutcome.Handled("must not run"))
        val cloud = FakeCloudAi()
        val agent = FakeAgent(plan = simplePlan())
        val engine = buildEngine(localAi = local, cloudAi = cloud, agent = agent)

        val result = engine.execute(request("   "))

        assertTrue(result is ExecutionResult.Error)
        assertEquals(DecisionReason.INVALID_REQUEST, (result as ExecutionResult.Error).reason)
        assertEquals(0, local.calls)
        assertEquals(0, cloud.calls)
        assertEquals(0, agent.planCalls)
    }

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

    @Test
    fun `automatically detected credential is never sent to cloud by default`() = runBlocking {
        val local = FakeLocalAi(LocalAiOutcome.Uncertain)
        val cloud = FakeCloudAi()
        val engine = buildEngine(localAi = local, cloudAi = cloud)

        val result = engine.execute(request("мой пароль от банка: 4821-secret"))

        assertTrue(result is ExecutionResult.Error)
        assertEquals(DecisionReason.CLOUD_BLOCKED_BY_PRIVACY, (result as ExecutionResult.Error).reason)
        assertEquals(0, cloud.calls)
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

    @Test
    fun `AGENT-010 intentionally uses cloud because no executable local comparison plan exists`() = runBlocking {
        val local = FakeLocalAi(LocalAiOutcome.Uncertain, hasWebCapability = false)
        val cloud = FakeCloudAi(Resource.Success("Сравнение по актуальному каталогу"))
        val agent = FakeAgent(plan = null)
        val engine = buildEngine(localAi = local, cloudAi = cloud, agent = agent)

        val result = engine.execute(
            request(
                "Сравни десять вариантов ноутбуков и выбери лучший в пределах бюджета",
                requiresWeb = true
            )
        )

        assertTrue(result is ExecutionResult.Success)
        assertEquals(ExecutionType.CLOUD_AI, (result as ExecutionResult.Success).executionType)
        assertEquals(0, agent.runCalls)
        assertEquals(0, local.calls)
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

    /** Дополнительно: МНОГОшаговый план из tool_calls облачной модели исполняет агент. */
    @Test
    fun `cloud tool calls are executed by agent`() = runBlocking {
        val agent = FakeAgent(plan = null, planForLlmOutput = multiStepPlan())
        val cloud = FakeCloudAi(Resource.Success("""{"tool":"device.volume"}"""))
        val engine = buildEngine(cloudAi = cloud, agent = agent)

        val result = engine.execute(request("сделай как я люблю по вечерам"))

        assertTrue(result is ExecutionResult.Success)
        assertEquals(ExecutionType.AGENT, (result as ExecutionResult.Success).executionType)
        assertEquals(1, agent.runCalls)
    }

    /**
     * AGENT-CORE принцип: «не использовать агента там, где достаточно Tool».
     * Ровно ОДИН tool_call из ответа модели исполняется прямым путём
     * (DEVICE_TOOL), cognitive loop не запускается.
     */
    @Test
    fun `single tool call from cloud runs as Tool not Agent`() = runBlocking {
        val tool = ScriptedTool("device.volume", result = ToolExecutionResult.success("Громкость 50"))
        val agent = FakeAgent(plan = null, planForLlmOutput = simplePlan()) // ровно 1 шаг
        val cloud = FakeCloudAi(Resource.Success("""{"tool":"device.volume"}"""))
        val engine = buildEngine(tools = setOf(tool), cloudAi = cloud, agent = agent)

        val result = engine.execute(request("сделай как я люблю по вечерам"))

        assertTrue(result is ExecutionResult.Success)
        val success = result as ExecutionResult.Success
        assertEquals(ExecutionType.DEVICE_TOOL, success.executionType)
        assertTrue(success.text.contains("Громкость 50"))
        assertEquals(0, agent.runCalls) // loop НЕ запускался
        assertEquals(1, tool.calls)
    }

    /**
     * AGENT-CORE: LLM предлагает — policy решает. Многошаговый план из ответа
     * модели с внешними инструментами при не-NORMAL приватности блокируется
     * тем же fail-closed гейтом, что и детерминированный план.
     */
    @Test
    fun `external tools in cloud plan are privacy gated`() = runBlocking {
        val externalTool = ScriptedTool("intelligence.web_search", isOffline = false)
        val disclosingPlan = ExecutionPlan(
            goal = "search",
            steps = listOf(
                PlanStep(toolCall = ToolCall("intelligence.web_search", buildJsonObject { put("query", "x") })),
                PlanStep(toolCall = ToolCall("intelligence.web_search", buildJsonObject { put("query", "y") }))
            )
        )
        val agent = FakeAgent(plan = null, planForLlmOutput = disclosingPlan)
        val cloud = FakeCloudAi(Resource.Success("here is my plan"))
        val engine = buildEngine(tools = setOf(externalTool), cloudAi = cloud, agent = agent)

        val result = engine.execute(
            request("сделай как я люблю по вечерам", privacy = PrivacyLevel.PRIVATE, cloudAllowed = true)
        )

        assertTrue(result is ExecutionResult.Error)
        assertEquals(
            DecisionReason.EXTERNAL_TOOL_BLOCKED_BY_PRIVACY,
            (result as ExecutionResult.Error).reason
        )
        assertEquals(0, agent.runCalls)
        assertEquals(0, externalTool.calls)
    }

    /**
     * OBSERVABILITY: единый request id (`omx_…`) сопровождает результат и
     * сохраняется при копиях ExecutionRequest — по нему собирается весь путь
     * Voice → Router → Tool → AI → Server.
     */
    @Test
    fun `request id flows into execution metadata unchanged`() = runBlocking {
        val tool = ScriptedTool("device.flashlight", result = ToolExecutionResult.success("Включил"))
        val engine = buildEngine(tools = setOf(tool))

        val rid = com.jarvis.assistant.core.request.RequestIds.newId()
        val req = ExecutionRequest(
            text = "включи фонарик",
            source = RequestSource.VOICE,
            requestId = rid
        )
        val result = engine.execute(req)

        assertTrue(result is ExecutionResult.Success)
        val meta = (result as ExecutionResult.Success).metadata
        assertEquals(rid, meta["request_id"])
        // Тот же запрос через copy() сохраняет id (контракты-«наследники»).
        assertEquals(rid, req.copy(text = "другой текст").requestId)
        assertTrue(com.jarvis.assistant.core.request.RequestIds.looksLikeOmnixId(rid))
    }

    @Test
    fun `request id is generated by default and unique per request`() = runBlocking {
        val a = ExecutionRequest(text = "привет", source = RequestSource.VOICE)
        val b = ExecutionRequest(text = "привет", source = RequestSource.VOICE)
        assertTrue(a.requestId != b.requestId)
        assertTrue(a.requestId.startsWith("omx_"))
    }

    @Test
    fun `unknown classifier result never reaches cloud even with consent`() = runBlocking {
        val cloud = FakeCloudAi()
        val engine = buildEngine(cloudAi = cloud)
        val request = ExecutionRequest(
            text = "ordinary question",
            source = RequestSource.CHAT,
            privacyLevel = PrivacyLevel.NORMAL,
            cloudExplicitlyAllowed = true,
            privacyClassification = PrivacyClassification.unknown(PrivacyReason.CLASSIFIER_FAILURE)
        )

        val result = engine.execute(request)

        assertTrue(result is ExecutionResult.Error)
        assertEquals(DecisionReason.CLOUD_BLOCKED_BY_PRIVACY, (result as ExecutionResult.Error).reason)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `sensitive history blocks otherwise normal cloud request`() = runBlocking {
        val cloud = FakeCloudAi()
        val engine = buildEngine(cloudAi = cloud)
        val request = ExecutionRequest(
            text = "continue our discussion",
            source = RequestSource.CHAT,
            history = listOf(
                com.jarvis.assistant.domain.models.Message(
                    role = com.jarvis.assistant.domain.models.MessageRole.USER,
                    text = "password=history-secret"
                )
            )
        )

        val result = engine.execute(request)

        assertTrue(result is ExecutionResult.Error)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `sensitive fast route cannot call external weather tool`() = runBlocking {
        val weather = ScriptedTool(
            toolId = "intelligence.weather",
            result = ToolExecutionResult.success("must not execute"),
            isOffline = false
        )
        val cloud = FakeCloudAi()
        val engine = buildEngine(tools = setOf(weather), cloudAi = cloud)

        val result = engine.execute(request("какая погода, password=actual-secret"))

        assertTrue(result is ExecutionResult.Error)
        assertEquals(
            DecisionReason.EXTERNAL_TOOL_BLOCKED_BY_PRIVACY,
            (result as ExecutionResult.Error).reason
        )
        assertEquals(0, weather.calls)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun `sensitive agent plan cannot call external tool`() = runBlocking {
        val web = ScriptedTool("intelligence.web_search", isOffline = false)
        val plan = ExecutionPlan(
            goal = "external",
            steps = listOf(PlanStep(toolCall = ToolCall(web.toolId, buildJsonObject { })))
        )
        val agent = FakeAgent(plan = plan)
        val engine = buildEngine(tools = setOf(web), agent = agent)

        val result = engine.execute(request("password=actual-secret затем найди в сети"))

        assertTrue(result is ExecutionResult.Error)
        assertEquals(0, agent.runCalls)
        assertEquals(0, web.calls)
    }

    /** Любой prompt, включая NORMAL, в логах представлен только размером. */
    @Test
    fun `request text is always redacted for logging`() {
        val normal = request("обычный запрос", privacy = PrivacyLevel.NORMAL)
        val private = request("секретный текст", privacy = PrivacyLevel.PRIVATE)

        assertFalse(normal.loggableText.contains("обычный запрос"))
        assertTrue(normal.loggableText.startsWith("<redacted:"))
        assertFalse(private.loggableText.contains("секретный"))
        assertTrue(private.loggableText.startsWith("<redacted:"))
    }
    // ---------------- Local-first метрики ExecutionRouter ----------------

    @Test
    fun `tool lane is counted with local percent and no escalation`() = runBlocking {
        val tool = ScriptedTool("device.flashlight", ToolExecutionResult.success("Фонарик включен"))
        val local = FakeLocalAi(LocalAiOutcome.Uncertain)
        val cloud = FakeCloudAi()
        val metrics = ExecutionRouterMetrics()
        val engine = buildEngine(tools = setOf(tool), localAi = local, cloudAi = cloud, metrics = metrics)

        engine.execute(request("включи фонарик"))

        val snap = metrics.snapshot()
        assertEquals(1L, snap.totalRequests)
        assertEquals(1L, snap.toolRequests)
        assertEquals(0L, snap.cloudRequests)
        assertEquals(0L, snap.cloudEscalations)
        assertEquals(0L, snap.failedLocal)
        assertEquals(100.0, snap.localExecutionPercent, 0.01)
        assertEquals(0.0, snap.cloudExecutionPercent, 0.01)
    }

    @Test
    fun `local handled counts as local execution`() = runBlocking {
        val local = FakeLocalAi(LocalAiOutcome.Handled("Сценарий выполнен, сэр."))
        val cloud = FakeCloudAi()
        val metrics = ExecutionRouterMetrics()
        val engine = buildEngine(localAi = local, cloudAi = cloud, metrics = metrics)

        engine.execute(request("активируй мой личный сценарий"))

        val snap = metrics.snapshot()
        assertEquals(1L, snap.localRequests)
        assertEquals(0L, snap.cloudRequests)
        assertEquals(100.0, snap.localExecutionPercent, 0.01)
    }

    @Test
    fun `local failure counts failed_local without escalation or cloud`() = runBlocking {
        val local = FakeLocalAi(LocalAiOutcome.Failed("локальный сценарий упал"))
        val cloud = FakeCloudAi()
        val metrics = ExecutionRouterMetrics()
        val engine = buildEngine(localAi = local, cloudAi = cloud, metrics = metrics)

        val result = engine.execute(request("активируй мой личный сценарий"))

        assertTrue(result is ExecutionResult.Error)
        val snap = metrics.snapshot()
        assertEquals(1L, snap.failedLocal)
        assertEquals(0L, snap.cloudRequests)
        assertEquals(0L, snap.cloudEscalations)
    }

    @Test
    fun `uncertain local escalates and is counted as escalation`() = runBlocking {
        val local = FakeLocalAi(LocalAiOutcome.Uncertain)
        val cloud = FakeCloudAi(Resource.Success("Ответ облака"))
        val metrics = ExecutionRouterMetrics()
        val engine = buildEngine(localAi = local, cloudAi = cloud, metrics = metrics)

        engine.execute(request("расскажи что-нибудь интересное про космос"))

        val snap = metrics.snapshot()
        assertEquals(1L, snap.cloudRequests)
        assertEquals(1L, snap.cloudEscalations)
        assertEquals(0.0, snap.localExecutionPercent, 0.01)
        assertEquals(100.0, snap.cloudExecutionPercent, 0.01)
    }

    @Test
    fun `requiresWeb skip is not an escalation`() = runBlocking {
        // Локальная полоса не опрашивалась (skip) — облако взяло запрос
        // само, эскалацией это не считается.
        val local = FakeLocalAi(LocalAiOutcome.Uncertain, hasWebCapability = false)
        val cloud = FakeCloudAi(Resource.Success("веб-ответ"))
        val metrics = ExecutionRouterMetrics()
        val engine = buildEngine(localAi = local, cloudAi = cloud, metrics = metrics)

        engine.execute(request("найди в интернете погоду в Ашхабаде", requiresWeb = true))

        val snap = metrics.snapshot()
        assertEquals(1L, snap.cloudRequests)
        assertEquals(0L, snap.cloudEscalations)
    }

    @Test
    fun `clarifications count in total but not in any lane`() = runBlocking {
        val metrics = ExecutionRouterMetrics()
        val engine = buildEngine(metrics = metrics)

        engine.execute(request("   "))
        engine.execute(request("открой"))

        val snap = metrics.snapshot()
        assertEquals(2L, snap.totalRequests)
        assertEquals(0L, snap.toolRequests)
        assertEquals(0L, snap.localRequests)
        assertEquals(0L, snap.cloudRequests)
        assertEquals(2L, snap.notExecuted)
        assertEquals(0.0, snap.localExecutionPercent, 0.01)
    }

    @Test
    fun `mixed lanes produce target-style percentages`() = runBlocking {
        val tool = ScriptedTool("device.flashlight", ToolExecutionResult.success("Фонарик включен"))
        val metrics = ExecutionRouterMetrics()
        // 2 запроса → tool; 1 → облако (локальный Uncertain).
        val local = FakeLocalAi(LocalAiOutcome.Uncertain)
        val engine = buildEngine(
            tools = setOf(tool),
            localAi = local,
            cloudAi = FakeCloudAi(Resource.Success("облако")),
            metrics = metrics
        )

        engine.execute(request("включи фонарик"))
        engine.execute(request("включи фонарик"))
        engine.execute(request("расскажи что-нибудь интересное про космос"))

        val snap = metrics.snapshot()
        assertEquals(3L, snap.totalRequests)
        assertEquals(2L, snap.localExecuted)
        assertEquals(1L, snap.cloudRequests)
        assertEquals(66.6, snap.localExecutionPercent, 0.1)
        assertEquals(33.3, snap.cloudExecutionPercent, 0.1)
        // Ориентир — метрика, не правило: при выборке 20+ он просто читается.
        assertFalse(snap.meetsFirstVersionTarget) // выборка < 20
    }

    // ---------------- Voice Latency: сегменты пайплайна ----------------

    @Test
    fun `voice segments recorded per lane - device tool path`() = runBlocking {
        val tool = ScriptedTool("device.flashlight", ToolExecutionResult.success("Фонарик включен"))
        val latency = VoiceLatencyMetrics()
        val engine = buildEngine(tools = setOf(tool), latency = latency)
        val sttFinal = latency.nowMs()
        Thread.sleep(5) // ощутимый STT→Router интервал

        val result = engine.execute(request("включи фонарик", source = RequestSource.VOICE).copy(originTimestampMs = sttFinal))

        assertTrue(result is ExecutionResult.Success)
        val snap = latency.snapshot()
        // STT→Router зафиксирован (голосовой запрос с origin).
        assertTrue(snap.containsKey(VoiceLatencyMetrics.SeriesKey(VoiceLatencyMetrics.VoiceStage.STT_TO_ROUTER, VoiceLatencyMetrics.VoiceLane.UNSPECIFIED)))
        // ROUTER_DISPATCH и TOOL — в LOCAL-разрезе.
        assertTrue(snap.containsKey(VoiceLatencyMetrics.SeriesKey(VoiceLatencyMetrics.VoiceStage.ROUTER_DISPATCH, VoiceLatencyMetrics.VoiceLane.LOCAL)))
        assertTrue(snap.containsKey(VoiceLatencyMetrics.SeriesKey(VoiceLatencyMetrics.VoiceStage.TOOL, VoiceLatencyMetrics.VoiceLane.LOCAL)))
        // AI-сегмента у device-полосы нет — честно.
        assertFalse(snap.containsKey(VoiceLatencyMetrics.SeriesKey(VoiceLatencyMetrics.VoiceStage.AI, VoiceLatencyMetrics.VoiceLane.CLOUD)))
    }

    @Test
    fun `cloud lane latency is recorded separately from local`() = runBlocking {
        val local = FakeLocalAi(LocalAiOutcome.Uncertain)
        val cloud = FakeCloudAi(Resource.Success("Ответ облака"))
        val latency = VoiceLatencyMetrics()
        val engine = buildEngine(localAi = local, cloudAi = cloud, latency = latency)

        engine.execute(request("расскажи что-нибудь интересное про космос"))

        val snap = latency.snapshot()
        assertTrue(snap.containsKey(VoiceLatencyMetrics.SeriesKey(VoiceLatencyMetrics.VoiceStage.AI, VoiceLatencyMetrics.VoiceLane.CLOUD)))
        // Локальная AI-фаза тоже записана (попытка была — Uncertain).
        assertTrue(snap.containsKey(VoiceLatencyMetrics.SeriesKey(VoiceLatencyMetrics.VoiceStage.AI, VoiceLatencyMetrics.VoiceLane.LOCAL)))
        // ROUTER_DISPATCH в CLOUD-разрезе (полоса выбрана облачная).
        assertTrue(snap.containsKey(VoiceLatencyMetrics.SeriesKey(VoiceLatencyMetrics.VoiceStage.ROUTER_DISPATCH, VoiceLatencyMetrics.VoiceLane.CLOUD)))
    }

    @Test
    fun `chat request without origin has no stt segment`() = runBlocking {
        val local = FakeLocalAi(LocalAiOutcome.Handled("локально"))
        val latency = VoiceLatencyMetrics()
        val engine = buildEngine(localAi = local, latency = latency)

        engine.execute(request("активируй мой личный сценарий")) // CHAT, origin == null

        assertFalse(
            latency.snapshot().containsKey(
                VoiceLatencyMetrics.SeriesKey(VoiceLatencyMetrics.VoiceStage.STT_TO_ROUTER, VoiceLatencyMetrics.VoiceLane.UNSPECIFIED)
            )
        )
    }

}
