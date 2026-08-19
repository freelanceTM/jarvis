package com.jarvis.assistant.agent.decision

import android.util.Log
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.fast.FastCommandRouter
import com.jarvis.assistant.agent.memory.WorkingMemory
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.core.result.Resource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Execution Decision Engine v0.2 — тонкий слой ПРИНЯТИЯ РЕШЕНИЯ поверх уже
 * существующих механизмов выполнения.
 *
 * ```
 * Voice / Chat
 *      ↓
 *     STT
 *      ↓
 * FastCommandRouter
 *      ↓
 * ExecutionDecisionEngine
 *      ├── Device Tool  (ToolExecutor → JarvisTool)
 *      ├── Local AI     (WorkflowExecutor, офлайн)
 *      ├── Cloud AI     (AIRepository → UniversalAIClient)
 *      └── Agent        (CognitivePlanner → AgentCognitiveLoop)
 *           ↓
 *      ExecutionResult
 *           ↓
 *      Response / TTS
 * ```
 *
 * Приоритеты решения:
 *  1. DEVICE_TOOL — FastCommandRouter уверен (confidence ≥ порога);
 *  2. AGENT       — запрос многошаговый (CognitivePlanner построил план);
 *  3. LOCAL_AI    — офлайн-сценарий пользователя;
 *  4. CLOUD_AI    — всё остальное, если приватность позволяет.
 *
 * Порядок 2 и 3 сохраняет существующее поведение AgentPipeline: динамический
 * план проверялся ДО процедурной памяти.
 *
 * Движок НЕ знает, КАК выполнять действия: он выбирает, ЧТО выполнить,
 * и делегирует существующим executor'ам.
 */
@Singleton
class ExecutionDecisionEngine @Inject constructor(
    private val fastCommandRouter: FastCommandRouter,
    private val toolExecutor: ToolExecutor,
    private val agentExecutor: AgentExecutor,
    private val localAi: LocalAiExecutor,
    private val cloudAi: CloudAiExecutor,
    private val workingMemory: WorkingMemory,
    private val config: ExecutionDecisionConfig = ExecutionDecisionConfig()
) {
    companion object {
        private const val TAG = "DecisionEngine"

        private const val OFFLINE_MESSAGE =
            "Нет подключения к интернету. Локальные команды (фонарик, звук, батарея, приложения, память) работают офлайн."

        private const val PRIVACY_BLOCKED_MESSAGE =
            "Запрос помечен как приватный, сэр. Локально выполнить его не удалось, а в облако я его без вашего явного разрешения не отправлю."

        private const val GENERIC_ERROR_MESSAGE = "Не удалось выполнить запрос, сэр."
    }

    /**
     * Единственная точка входа. Исключения наружу не выбрасываются: любая
     * неожиданная ошибка логируется и превращается в [ExecutionResult.Error]
     * без раскрытия stack trace пользователю (пункт 11 ТЗ).
     */
    suspend fun execute(request: ExecutionRequest): ExecutionResult {
        logRequest(request)

        return try {
            decide(request)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected failure while executing request (source=${request.source})", e)
            ExecutionResult.Error(
                message = GENERIC_ERROR_MESSAGE,
                reason = DecisionReason.UNEXPECTED_ERROR
            )
        }
    }

    private suspend fun decide(request: ExecutionRequest): ExecutionResult {
        // ------------------------------------------------ PRIORITY 1: DEVICE TOOL
        val routing = FastRouteConfidence.from(fastCommandRouter.route(request.text))
        val deviceResult = tryDeviceTool(request, routing)
        if (deviceResult != null) return deviceResult

        // ------------------------------------------------ PRIORITY 4*: AGENT
        // Проверяется раньше локального слоя: многошаговый запрос не должен
        // быть «съеден» одиночным офлайн-сценарием (поведение AgentPipeline).
        val plan = agentExecutor.planFor(request)
        if (plan != null) {
            logRoute(ExecutionType.AGENT, DecisionReason.COMPLEX_MULTI_STEP, routing.confidence)
            return agentExecutor.run(plan)
        }

        // ------------------------------------------------ PRIORITY 2: LOCAL AI
        val localOutcome = tryLocalAi(request, routing)
        when (localOutcome) {
            is LocalAiOutcome.Handled -> {
                logRoute(ExecutionType.LOCAL_AI, DecisionReason.LOCAL_AI_HANDLED, routing.confidence)
                return ExecutionResult.Success(
                    text = localOutcome.text,
                    executionType = ExecutionType.LOCAL_AI,
                    metadata = mapOf("reason" to DecisionReason.LOCAL_AI_HANDLED.name)
                )
            }

            is LocalAiOutcome.Failed -> {
                Log.w(TAG, "route=LOCAL_AI outcome=FAILED — не эскалируем в облако")
                return ExecutionResult.Error(
                    message = localOutcome.message,
                    reason = DecisionReason.LOCAL_AI_UNCERTAIN
                )
            }

            LocalAiOutcome.Uncertain -> Unit // идём дальше
        }

        // ------------------------------------------------ PRIORITY 3: CLOUD AI
        return runCloud(request, routing)
    }

    // =====================================================================
    // PRIORITY 1 — Device Tool через существующий ToolExecutor / JarvisTool
    // =====================================================================
    private suspend fun tryDeviceTool(
        request: ExecutionRequest,
        routing: CommandRoutingResult
    ): ExecutionResult? {
        // requiresDeviceControl — подсказка вызывающего слоя: если роутер уже
        // собрал вызов инструмента, порог не должен мешать device-пути.
        val threshold = if (request.requiresDeviceControl) {
            minOf(config.deviceConfidenceThreshold, FastRouteConfidence.DIRECT_RESPONSE)
        } else {
            config.deviceConfidenceThreshold
        }
        if (routing.confidence < threshold) return null

        return when (routing) {
            is CommandRoutingResult.DeviceCommand -> {
                logRoute(ExecutionType.DEVICE_TOOL, DecisionReason.FAST_ROUTER_CONFIDENT, routing.confidence)
                executeDeviceTool(routing)
            }

            // Готовая локальная реплика («привет») — тоже устройство-локальный
            // путь, просто без вызова инструмента.
            is CommandRoutingResult.DirectResponse -> {
                logRoute(ExecutionType.DEVICE_TOOL, DecisionReason.FAST_ROUTER_CONFIDENT, routing.confidence)
                ExecutionResult.Success(
                    text = routing.text,
                    executionType = ExecutionType.DEVICE_TOOL,
                    metadata = mapOf("reason" to DecisionReason.FAST_ROUTER_CONFIDENT.name)
                )
            }

            is CommandRoutingResult.Unknown -> null
        }
    }

    private suspend fun executeDeviceTool(
        routing: CommandRoutingResult.DeviceCommand
    ): ExecutionResult {
        val call = routing.toolCall

        val result = try {
            toolExecutor.execute(call)
        } catch (e: Exception) {
            // ToolExecutor уже конвертирует исключения инструментов в failure,
            // но контракт защищаем и здесь: наружу исключение не уходит.
            Log.e(TAG, "ToolExecutor threw for tool=${call.toolId}", e)
            return ExecutionResult.Error(
                message = "Не удалось выполнить команду устройства, сэр.",
                reason = DecisionReason.DEVICE_TOOL_FAILED
            )
        }

        if (result.status == ToolExecutionStatus.REQUIRES_USER_CONFIRMATION) {
            return ExecutionResult.ConfirmationRequired(
                toolCall = call,
                promptMessage = result.summary
            )
        }

        workingMemory.setLastAction(call.toolId)

        // Никакого fake success: оптимистичная фраза роутера заменяется реальным
        // итогом (существующее правило проекта).
        return when {
            result.isSuccess -> ExecutionResult.Success(
                text = "${result.summary}, сэр.",
                executionType = ExecutionType.DEVICE_TOOL,
                metadata = mapOf(
                    "tool_id" to call.toolId,
                    "confidence" to routing.confidence.toString()
                )
            )

            // Разрешение / системный UI / неподдерживаемая возможность —
            // это честное объяснение, а не техническая ошибка.
            result.isBlockedByAndroid -> ExecutionResult.Success(
                text = result.summary,
                executionType = ExecutionType.DEVICE_TOOL,
                metadata = mapOf(
                    "tool_id" to call.toolId,
                    "blocked_by_android" to "true",
                    "status" to result.status.name
                )
            )

            else -> {
                Log.w(TAG, "route=DEVICE_TOOL tool=${call.toolId} status=${result.status}")
                ExecutionResult.Error(
                    message = result.summary,
                    reason = DecisionReason.DEVICE_TOOL_FAILED
                )
            }
        }
    }

    // =====================================================================
    // PRIORITY 2 — Local AI (офлайн)
    // =====================================================================
    private suspend fun tryLocalAi(
        request: ExecutionRequest,
        routing: CommandRoutingResult
    ): LocalAiOutcome {
        // Пункт 8 ТЗ: локальный слой без web-возможности не имеет права
        // притворяться, что выполнил web-запрос.
        if (request.requiresWeb && !localAi.hasWebCapability) {
            Log.d(
                TAG,
                "route=SKIP_LOCAL_AI reason=${DecisionReason.LOCAL_AI_NO_WEB_CAPABILITY} requiresWeb=true"
            )
            return LocalAiOutcome.Uncertain
        }

        logRoute(ExecutionType.LOCAL_AI, DecisionReason.FAST_ROUTER_UNCERTAIN, routing.confidence)
        return localAi.tryHandle(request)
    }

    // =====================================================================
    // PRIORITY 3 — Cloud AI (+ privacy gate)
    // =====================================================================
    private suspend fun runCloud(
        request: ExecutionRequest,
        routing: CommandRoutingResult
    ): ExecutionResult {
        // Privacy gate: приватный/чувствительный запрос НИКОГДА не уходит
        // в облако без явного разрешения (пункт 7 ТЗ).
        if (!request.isCloudAllowed) {
            Log.i(
                TAG,
                "route=BLOCKED reason=${DecisionReason.CLOUD_BLOCKED_BY_PRIVACY} " +
                    "privacy=${request.privacyLevel}"
            )
            return ExecutionResult.Error(
                message = PRIVACY_BLOCKED_MESSAGE,
                reason = DecisionReason.CLOUD_BLOCKED_BY_PRIVACY
            )
        }

        if (!cloudAi.isAvailable()) {
            Log.i(TAG, "route=BLOCKED reason=OFFLINE")
            return ExecutionResult.Error(
                message = OFFLINE_MESSAGE,
                reason = DecisionReason.CLOUD_FAILED
            )
        }

        val reason = if (request.requiresWeb) {
            DecisionReason.LOCAL_AI_NO_WEB_CAPABILITY
        } else {
            DecisionReason.LOCAL_AI_UNCERTAIN
        }
        logRoute(ExecutionType.CLOUD_AI, reason, routing.confidence)

        return when (val cloudResult = cloudAi.complete(request)) {
            is Resource.Success -> {
                val rawOutput = cloudResult.data.trim()

                // Ответ модели может содержать tool_calls — тогда исполняет агент
                // (существующее поведение AgentPipeline).
                val llmPlan = agentExecutor.planFor(request, rawOutput)
                if (llmPlan != null) {
                    logRoute(ExecutionType.AGENT, DecisionReason.CLOUD_PLAN_DETECTED, routing.confidence)
                    return agentExecutor.run(llmPlan)
                }

                ExecutionResult.Success(
                    text = rawOutput,
                    executionType = ExecutionType.CLOUD_AI,
                    metadata = mapOf("reason" to reason.name)
                )
            }

            is Resource.Error -> {
                Log.w(TAG, "route=CLOUD_AI outcome=FAILED reason=${DecisionReason.CLOUD_FAILED}")
                ExecutionResult.Error(
                    message = cloudResult.message
                        ?: "Не удалось связаться с сервером AI. Проверьте ключ в настройках.",
                    reason = DecisionReason.CLOUD_FAILED
                )
            }

            Resource.Loading -> ExecutionResult.Error(
                message = GENERIC_ERROR_MESSAGE,
                reason = DecisionReason.CLOUD_FAILED
            )
        }
    }

    // =====================================================================
    // Structured logging (текст приватного запроса не логируется)
    // =====================================================================
    private fun logRequest(request: ExecutionRequest) {
        Log.d(
            TAG,
            "request received | source=${request.source} | privacy=${request.privacyLevel} | " +
                "requiresWeb=${request.requiresWeb} | requiresDeviceControl=${request.requiresDeviceControl} | " +
                "text=${request.loggableText}"
        )
    }

    private fun logRoute(type: ExecutionType, reason: DecisionReason, confidence: Float) {
        Log.d(TAG, "route=$type | reason=$reason | confidence=$confidence")
    }
}
