package com.jarvis.server.provider

import com.jarvis.server.config.ExecutionPolicyConfig
import com.jarvis.server.config.ProviderConfig
import com.jarvis.server.observability.Metrics
import com.jarvis.server.observability.StructuredLogger
import kotlinx.coroutines.delay

/**
 * Требования запроса к провайдеру (пункт 16 ТЗ).
 */
data class ProviderRequirements(
    val requiresWeb: Boolean = false,
    val requiresToolCalling: Boolean = false
)

/**
 * Политика отбора провайдеров (пункт 15 ТЗ).
 *
 * На этом этапе — `priority + health + capabilities`, как и предписано ТЗ.
 * Никакого ML-роутинга: политика вынесена в отдельный класс, чтобы её можно
 * было усложнить (latency, cost) без правки ProviderManager.
 */
class ProviderSelectionPolicy(
    private val configs: Map<ProviderId, ProviderConfig>,
    private val health: ProviderHealthTracker
) {
    /**
     * @return упорядоченный список кандидатов: сначала HEALTHY, затем DEGRADED,
     *         внутри группы — по возрастанию priority.
     */
    fun select(
        providers: List<AiProvider>,
        requirements: ProviderRequirements
    ): List<AiProvider> = providers
        .asSequence()
        .filter { provider ->
            val cfg = configs[provider.id]
            cfg != null && cfg.enabled && provider.isConfigured()
        }
        .filter { provider ->
            // Возможности: если запросу нужен web, провайдер обязан его уметь.
            val caps = provider.capabilities
            (!requirements.requiresWeb || caps.supportsWeb) &&
                (!requirements.requiresToolCalling || caps.supportsToolCalling)
        }
        .filter { health.isAvailable(it.id) }
        .sortedWith(
            compareBy(
                { if (health.status(it.id) == HealthStatus.HEALTHY) 0 else 1 },
                { configs[it.id]?.priority ?: Int.MAX_VALUE }
            )
        )
        .toList()
}

/** Итог работы менеджера — уже нормализованный, без деталей провайдера. */
sealed class ManagerOutcome {

    data class Success(
        val providerId: ProviderId,
        val model: String,
        val text: String,
        val inputTokens: Long?,
        val outputTokens: Long?,
        val totalTokens: Long?,
        val providerLatencyMs: Long
    ) : ManagerOutcome()

    /**
     * Все кандидаты исчерпаны.
     * @param lastKind вид последнего сбоя — маппится в код ошибки API.
     * @param attempted какие провайдеры реально пробовались.
     */
    data class Failure(
        val lastKind: ProviderFailureKind?,
        val attempted: List<ProviderId>,
        val noCandidates: Boolean = false
    ) : ManagerOutcome()
}

/**
 * Provider Manager (пункт 11 ТЗ).
 *
 * Отвечает за: registry, отбор, timeout, retry, fallback, health.
 * НЕ содержит HTTP-кода конкретных провайдеров — только оркестрацию.
 *
 * ```
 * AI Router → ProviderManager → [Groq | Gemini | OpenRouter]
 * ```
 */
class ProviderManager(
    private val providers: List<AiProvider>,
    private val configs: Map<ProviderId, ProviderConfig>,
    private val health: ProviderHealthTracker,
    private val policy: ExecutionPolicyConfig,
    private val selectionPolicy: ProviderSelectionPolicy,
    private val logger: StructuredLogger,
    private val metrics: Metrics,
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun execute(
        request: ProviderRequest,
        requirements: ProviderRequirements
    ): ManagerOutcome {
        val candidates = selectionPolicy.select(providers, requirements)

        if (candidates.isEmpty()) {
            logger.warn(
                "no provider candidates",
                "requestId" to request.requestId,
                "requiresWeb" to requirements.requiresWeb.toString()
            )
            return ManagerOutcome.Failure(lastKind = null, attempted = emptyList(), noCandidates = true)
        }

        val attempted = mutableListOf<ProviderId>()
        var lastKind: ProviderFailureKind? = null

        // Fallback: перебираем провайдеров, но не больше maxProviderAttempts.
        for (provider in candidates.take(policy.maxProviderAttempts)) {
            attempted += provider.id

            when (val outcome = executeWithRetry(provider, request)) {
                is ManagerOutcome.Success -> return outcome
                is ManagerOutcome.Failure -> {
                    lastKind = outcome.lastKind
                    logger.warn(
                        "provider failed, trying fallback",
                        "requestId" to request.requestId,
                        "provider" to provider.id.name,
                        "failureKind" to (lastKind?.name ?: "UNKNOWN")
                    )
                }
            }
        }

        return ManagerOutcome.Failure(lastKind = lastKind, attempted = attempted)
    }

    /**
     * Ограниченный retry у ОДНОГО провайдера (пункт 23 ТЗ).
     *
     * Повторяем только transient-сбои (timeout, connection, 5xx).
     * AUTH / BAD_REQUEST / NOT_CONFIGURED / RATE_LIMITED не ретраятся:
     * повтор ничего не изменит и только потратит время пользователя.
     */
    private suspend fun executeWithRetry(
        provider: AiProvider,
        request: ProviderRequest
    ): ManagerOutcome {
        var lastKind: ProviderFailureKind? = null
        val maxAttempts = 1 + policy.maxRetriesPerProvider.coerceAtLeast(0)

        for (attempt in 1..maxAttempts) {
            val startedAt = clock()

            val result = try {
                provider.execute(request)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Провайдер не имеет права уронить сервер.
                ProviderResult.Failure(
                    kind = ProviderFailureKind.UNKNOWN,
                    detail = e.javaClass.simpleName
                )
            }

            val latency = clock() - startedAt

            when (result) {
                is ProviderResult.Success -> {
                    health.recordSuccess(provider.id)
                    metrics.recordProviderSuccess(provider.id, latency)
                    logger.info(
                        "provider success",
                        "requestId" to request.requestId,
                        "provider" to provider.id.name,
                        "model" to result.model,
                        "latencyMs" to latency.toString(),
                        "attempt" to attempt.toString()
                    )
                    return ManagerOutcome.Success(
                        providerId = provider.id,
                        model = result.model,
                        text = result.text,
                        inputTokens = result.inputTokens,
                        outputTokens = result.outputTokens,
                        totalTokens = result.totalTokens,
                        providerLatencyMs = latency
                    )
                }

                is ProviderResult.Failure -> {
                    lastKind = result.kind
                    health.recordFailure(provider.id, result.kind, result.detail)
                    metrics.recordProviderFailure(provider.id, result.kind)
                    logger.warn(
                        "provider failure",
                        "requestId" to request.requestId,
                        "provider" to provider.id.name,
                        "failureKind" to result.kind.name,
                        "httpStatus" to (result.httpStatus?.toString() ?: "-"),
                        "latencyMs" to latency.toString(),
                        "attempt" to attempt.toString(),
                        // detail — техническая деталь, остаётся в логах сервера
                        "detail" to result.detail
                    )

                    if (!result.kind.isRetryable || attempt == maxAttempts) {
                        return ManagerOutcome.Failure(lastKind, listOf(provider.id))
                    }

                    delay(policy.retryBackoffMs)
                }
            }
        }

        return ManagerOutcome.Failure(lastKind, listOf(provider.id))
    }

    fun healthSnapshot(): Map<ProviderId, HealthSnapshot> = health.snapshot()
}
