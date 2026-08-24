package com.jarvis.assistant.benchmark

import com.jarvis.assistant.agent.capability.FakeCapabilityRegistry
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.decision.AgentExecutor
import com.jarvis.assistant.agent.decision.CloudAiExecutor
import com.jarvis.assistant.agent.decision.ExecutionDecisionConfig
import com.jarvis.assistant.agent.decision.ExecutionDecisionEngine
import com.jarvis.assistant.agent.decision.ExecutionRequest
import com.jarvis.assistant.agent.decision.ExecutionResult
import com.jarvis.assistant.agent.decision.ExecutionType
import com.jarvis.assistant.agent.decision.LocalAiExecutor
import com.jarvis.assistant.agent.decision.LocalAiOutcome
import com.jarvis.assistant.agent.discovery.ToolDiscoveryEngine
import com.jarvis.assistant.agent.engine.AgentCognitiveLoop
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.fast.FastCommandRouter
import com.jarvis.assistant.agent.localai.GenerationConfig
import com.jarvis.assistant.agent.localai.InferenceMetrics
import com.jarvis.assistant.agent.localai.JarvisLocalPromptBuilder
import com.jarvis.assistant.agent.localai.LocalAiResult
import com.jarvis.assistant.agent.localai.LocalGeneration
import com.jarvis.assistant.agent.localai.LocalModelManager
import com.jarvis.assistant.agent.localai.LocalModelRuntime
import com.jarvis.assistant.agent.localai.LocalModelState
import com.jarvis.assistant.agent.localai.OnDeviceLocalAi
import com.jarvis.assistant.agent.memory.WorkingMemory
import com.jarvis.assistant.agent.memory.context.AnaphoraContextEngine
import com.jarvis.assistant.agent.memory.context.ReferenceResolver
import com.jarvis.assistant.agent.memory.semantic.SemanticTextMatcher
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.agent.observation.AgentObservationEngine
import com.jarvis.assistant.agent.parser.ToolCallParser
import com.jarvis.assistant.agent.planner.CognitivePlanner
import com.jarvis.assistant.agent.planner.ExecutionPlan
import com.jarvis.assistant.agent.registry.ToolRegistry
import com.jarvis.assistant.agent.safety.ToolPermissionManager
import com.jarvis.assistant.core.dispatcher.CoroutineDispatchers
import com.jarvis.assistant.core.result.Resource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Стенд benchmark.
 *
 * ВАЖНО (п. 46 Phase C): используется **настоящий** [ExecutionDecisionEngine],
 * настоящий [FastCommandRouter], настоящие [CognitivePlanner],
 * [AgentCognitiveLoop], [ToolExecutor] и [OnDeviceLocalAi]. Имитация
 * маршрутизации НЕ создаётся.
 *
 * Подменяются только ВНЕШНИЕ границы, недоступные на JVM:
 *  - нативный инференс MediaPipe → [SimulatedLocalRuntime];
 *  - реальные Android-тулы → [BenchmarkTool];
 *  - сеть до JARVIS API → [SimulatedCloudExecutor].
 *
 * Все подмены снабжены фиксированными задержками, поэтому измеренная latency
 * отражает ЛОГИКУ маршрутизации, а не реальные сетевые/GPU-задержки.
 * Это явно указано в ограничениях отчёта.
 */
object BenchmarkHarness {

    /** Профиль задержек — грубая модель реального поведения. */
    object Timing {
        const val DEVICE_TOOL_MS = 12L
        const val LOCAL_COLD_LOAD_MS = 1800L
        const val LOCAL_WARM_BASE_MS = 240L
        /** ~45 ток/с ≈ 22 мс на токен; токен ≈ 2.5 символа. */
        const val LOCAL_MS_PER_CHAR = 9L
        const val CLOUD_NETWORK_MS = 180L
        const val CLOUD_PROVIDER_BASE_MS = 420L
        const val AGENT_STEP_MS = 60L
    }

    class TestDispatchers(
        private val d: CoroutineDispatcher = Dispatchers.Default
    ) : CoroutineDispatchers {
        override val main = d
        override val io = d
        override val default = d
        override val unconfined = Dispatchers.Unconfined
    }

    /** Инструмент-заглушка: фиксирует вызовы и имитирует задержку Android API. */
    class BenchmarkTool(override val toolId: String) : JarvisTool {
        override val description = "Benchmark tool $toolId"
        override val category = ToolCategory.DEVICE
        override val riskLevel = ToolRisk.LOW
        override val parametersSchema: JsonObject = buildJsonObject { }
        val calls = AtomicInteger(0)

        override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
            calls.incrementAndGet()
            delay(Timing.DEVICE_TOOL_MS)
            return ToolExecutionResult.success("Выполнено: $toolId")
        }
    }

    /**
     * Симулятор локального инференса.
     *
     * Считает cold start отдельно от warm: первый вызов включает загрузку
     * модели (~1.8 с по замерам карточки Gemma 3 1B int4), последующие — нет.
     */
    class SimulatedLocalRuntime : LocalModelRuntime {
        override val runtimeId = "simulated-mediapipe-llm"

        val inferences = AtomicInteger(0)
        var coldStartMs: Long = 0
            private set
        val warmLatencies = mutableListOf<Long>()
        val timeToFirstToken = mutableListOf<Long>()
        val tokensPerSecond = mutableListOf<Double>()

        @Volatile
        private var loaded = false

        override suspend fun generate(
            prompt: String,
            config: GenerationConfig,
            onToken: ((String) -> Unit)?
        ): LocalGeneration {
            val started = System.currentTimeMillis()
            var load = 0L

            if (!loaded) {
                delay(Timing.LOCAL_COLD_LOAD_MS)
                load = Timing.LOCAL_COLD_LOAD_MS
                loaded = true
            }

            // Ответ пропорционален бюджету токенов, но ограничен разумно.
            val responseChars = (config.maxTokens * 2.5).toInt().coerceAtMost(420)
            val genMs = Timing.LOCAL_WARM_BASE_MS + responseChars / 4 * Timing.LOCAL_MS_PER_CHAR / 4
            delay(genMs)

            val total = System.currentTimeMillis() - started
            val ttft = Timing.LOCAL_WARM_BASE_MS + load
            val tokens = responseChars / 2.5
            val tps = if (genMs > 0) tokens / (genMs / 1000.0) else 0.0

            inferences.incrementAndGet()
            if (load > 0) coldStartMs = total else warmLatencies += total
            timeToFirstToken += ttft
            tokensPerSecond += tps

            val text = "Локальный ответ (${responseChars} симв.)"
            return LocalGeneration(
                text = text,
                metrics = InferenceMetrics(
                    promptChars = prompt.length,
                    responseChars = text.length,
                    latencyMs = total,
                    timeToFirstTokenMs = ttft,
                    approxTokensPerSecond = tps.toFloat()
                )
            )
        }
    }

    /** Менеджер модели: модель «установлена» и готова. */
    class ReadyModelManager(private val runtime: LocalModelRuntime) : LocalModelManager {
        override val state = LocalModelState.Ready("gemma3-1b-it-int4", 0)
        override suspend fun initialize() = state
        override fun isReady() = true
        override suspend fun runtimeOrNull() = runtime
        override suspend fun unload() = Unit
    }

    /** Локальный слой без процедурной памяти (Room недоступен на JVM). */
    class BenchmarkLocalExecutor(
        private val localAi: OnDeviceLocalAi
    ) : LocalAiExecutor {
        override val hasWebCapability = false
        val declines = AtomicInteger(0)
        val handled = AtomicInteger(0)

        override suspend fun tryHandle(request: ExecutionRequest): LocalAiOutcome =
            when (val r = localAi.execute(request)) {
                is LocalAiResult.Success -> { handled.incrementAndGet(); LocalAiOutcome.Handled(r.text) }
                is LocalAiResult.Unsupported -> { declines.incrementAndGet(); LocalAiOutcome.Uncertain }
                is LocalAiResult.Error -> LocalAiOutcome.Failed(r.message)
            }
    }

    /**
     * Симулятор облака: Android → JARVIS API → провайдер.
     *
     * Воспроизводит серверную privacy-политику (PRIVATE/SENSITIVE не выпускаются)
     * и учёт токенов, чтобы benchmark видел те же отказы, что и реальный сервер.
     */
    class SimulatedCloudExecutor(
        private val allowPrivate: Boolean = false,
        private val allowSensitive: Boolean = false
    ) : CloudAiExecutor {
        val calls = AtomicInteger(0)
        val networkLatencies = mutableListOf<Long>()
        val providerLatencies = mutableListOf<Long>()
        var inputTokens = 0L
            private set
        var outputTokens = 0L
            private set
        var lastProvider: String? = null
            private set
        var lastModel: String? = null
            private set

        override fun isAvailable() = true

        override suspend fun complete(request: ExecutionRequest): Resource<String> {
            calls.incrementAndGet()

            // Серверная privacy-политика (вторая линия защиты).
            val blocked = when (request.effectivePrivacyLevel) {
                com.jarvis.assistant.agent.decision.PrivacyLevel.UNKNOWN -> true
                com.jarvis.assistant.agent.decision.PrivacyLevel.NORMAL -> false
                com.jarvis.assistant.agent.decision.PrivacyLevel.PRIVATE -> !allowPrivate
                com.jarvis.assistant.agent.decision.PrivacyLevel.SENSITIVE -> !allowSensitive
            }
            if (blocked) {
                return Resource.Error(
                    IllegalStateException("PRIVACY_POLICY_VIOLATION"),
                    "Запрос помечен как приватный и не может быть обработан в облаке."
                )
            }

            delay(Timing.CLOUD_NETWORK_MS)
            networkLatencies += Timing.CLOUD_NETWORK_MS

            val providerMs = Timing.CLOUD_PROVIDER_BASE_MS + request.text.length / 20
            delay(providerMs)
            providerLatencies += providerMs

            lastProvider = "GROQ"
            lastModel = "llama-3.3-70b-versatile"
            val inTok = (request.text.length / 3.0).toLong().coerceAtLeast(8)
            val outTok = 120L
            inputTokens += inTok
            outputTokens += outTok

            return Resource.Success("Облачный ответ для запроса длиной ${request.text.length}")
        }
    }

    /** Агент поверх НАСТОЯЩИХ CognitivePlanner и AgentCognitiveLoop. */
    class InstrumentedAgentExecutor(
        private val planner: CognitivePlanner,
        private val loop: AgentCognitiveLoop
    ) : AgentExecutor {
        val plansBuilt = AtomicInteger(0)
        val runs = AtomicInteger(0)
        val stepCounts = mutableListOf<Int>()

        override fun planFor(request: ExecutionRequest, llmRawOutput: String?): ExecutionPlan? =
            planner.planForGoal(request.text, llmRawOutput)
                ?.takeIf { it.steps.isNotEmpty() }
                ?.also { plansBuilt.incrementAndGet() }

        override suspend fun run(plan: ExecutionPlan): ExecutionResult {
            runs.incrementAndGet()
            stepCounts += plan.steps.size
            val summary = loop.runPlan(plan)
            summary.pendingConfirmation?.let { (call, prompt) ->
                return ExecutionResult.ConfirmationRequired(call, prompt)
            }
            return ExecutionResult.Success(
                text = summary.finalVoiceSummary,
                executionType = ExecutionType.AGENT,
                metadata = mapOf("steps" to plan.steps.size.toString())
            )
        }
    }

    /** Собранный стенд с доступом к инструментированным компонентам. */
    class Rig(
        val engine: ExecutionDecisionEngine,
        val localRuntime: SimulatedLocalRuntime,
        val localExecutor: BenchmarkLocalExecutor,
        val cloud: SimulatedCloudExecutor,
        val agent: InstrumentedAgentExecutor,
        val tools: Map<String, BenchmarkTool>
    )

    /** Полный список tool_id, которые может вернуть FastCommandRouter/планировщик. */
    private val ALL_TOOL_IDS = listOf(
        "device.open_app", "device.volume", "device.brightness", "device.flashlight",
        "device.bluetooth", "device.wifi", "device.dnd", "device.screenshot",
        "media.control", "location.navigation", "communication.call", "communication.sms",
        "system.battery", "system.time", "system.device_info", "system.network_status",
        "intelligence.weather", "intelligence.translate", "intelligence.web_search",
        "memory.forget", "memory.recall", "memory.remember",
        "productivity.create_automation", "productivity.ear_briefing",
        "productivity.alarm_timer", "productivity.calendar",
        "accessibility.screen_reader", "accessibility.ui_click", "accessibility.type_text"
    )

    fun build(): Rig {
        val tools = ALL_TOOL_IDS.associateWith { BenchmarkTool(it) }
        val registry = ToolRegistry(
            tools.values.toSet<JarvisTool>(),
            ToolDiscoveryEngine(SemanticTextMatcher())
        )
        val toolExecutor = ToolExecutor(registry, ToolPermissionManager(FakeCapabilityRegistry.create()))
        val workingMemory = WorkingMemory(AnaphoraContextEngine(), ReferenceResolver())

        val runtime = SimulatedLocalRuntime()
        val localAi = OnDeviceLocalAi(
            modelManager = ReadyModelManager(runtime),
            promptBuilder = JarvisLocalPromptBuilder(),
            dispatchers = TestDispatchers()
        )
        val localExecutor = BenchmarkLocalExecutor(localAi)

        val cloud = SimulatedCloudExecutor()

        val planner = CognitivePlanner(ToolCallParser(Json { ignoreUnknownKeys = true }))
        val loop = AgentCognitiveLoop(planner, toolExecutor, AgentObservationEngine(workingMemory))
        val agent = InstrumentedAgentExecutor(planner, loop)

        val engine = ExecutionDecisionEngine(
            fastCommandRouter = FastCommandRouter(),
            toolExecutor = toolExecutor,
            agentExecutor = agent,
            localAi = localExecutor,
            cloudAi = cloud,
            workingMemory = workingMemory,
            config = ExecutionDecisionConfig()
        )

        return Rig(engine, runtime, localExecutor, cloud, agent, tools)
    }
}
