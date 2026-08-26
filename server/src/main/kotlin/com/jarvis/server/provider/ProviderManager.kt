package com.jarvis.server.provider

import com.jarvis.server.config.ExecutionPolicyConfig
import com.jarvis.server.config.ProviderConfig
import com.jarvis.server.observability.Metrics
import com.jarvis.server.observability.StructuredLogger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Требования запроса к провайдеру (пункт 16 ТЗ).
 */
data class ProviderRequirements(
    val requiresWeb: Boolean = false,
    val requiresToolCalling: Boolean = false
)

/**
 * AR-02: политика отбора провайдеров (пункт 15 ТЗ).
 *
 * Политика решает, КАКОЙ порядок провайдеров использовать для данного
 * запроса, опираясь на их [CapabilityProfile], конфигурацию и health.
 *
 * Конкретные стратегии:
 *  - [DefaultProviderSelectionPolicy] — текущая семантика: по здоровью +
 *    приоритету из конфига, с фильтром по обязательным capabilities
 *    (web/toolCalling). Это дефолт и единственная стратегия, которая
 *    используется на проде сегодня;
 *  - в будущем сюда могут добавиться FastestProviderSelectionPolicy,
 *    CheapestProviderSelectionPolicy, BalancedProviderSelectionPolicy без
 *    изменений в [ProviderManager].
 *
 * Execution code (ProviderManager) не привязан к конкретной стратегии —
 * он только вызывает [select].
 */
interface ProviderSelectionPolicy {
    /**
     * @return упорядоченный список кандидатов. Пустой список = нет
     *         подходящего провайдера для запроса.
     */
    fun select(
        providers: List<AiProvider>,
        requirements: ProviderRequirements
    ): List<AiProvider>
}

/**
 * Дефолтная политика отбора: HEALTHY → DEGRADED → по приоритету из конфига.
 *
 * Это та самая логика, что жила в ProviderSelectionPolicy-классе до AR-02;
 * сохранена без изменений семантики.
 */
class DefaultProviderSelectionPolicy(
    private val configs: Map<ProviderId, ProviderConfig>,
    private val health: ProviderHealthTracker
) : ProviderSelectionPolicy {
    override fun select(
        providers: List<AiProvider>,
        requirements: ProviderRequirements
    ): List<AiProvider> = providers
        .asSequence()
        .filter { provider ->
            val cfg = configs[provider.id]
            cfg != null && cfg.enabled && provider.isConfigured()
        }
        .filter { provider ->
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

        // Fallback: считаем только реально зарезервированные/вызванные
        // провайдеры. tryAcquire() особенно важен для HALF_OPEN: ровно один
        // конкурентный запрос получает право на пробу после cooldown.
        val maxProviderAttempts = policy.maxProviderAttempts.coerceAtLeast(0)
        for (provider in candidates) {
            if (attempted.size >= maxProviderAttempts) break

            // CR-06: сперва проверяем бюджет. Бюджетный early-out НЕ должен
            // тратить HALF_OPEN-слот провайдера и не должен дёргать
            // health.tryAcquire — мы заранее знаем, что у нас нет шансов
            // дождаться ответа.
            val providerTimeoutMs = configs[provider.id]?.requestTimeoutMs
                ?.coerceAtLeast(1L)
                ?: 30_000L
            val remainingMs = request.deadlineEpochMs?.let { it - clock() }
            if (remainingMs != null && remainingMs < providerTimeoutMs) {
                logger.warn(
                    "insufficient remaining budget for next provider; failing fast",
                    "requestId" to request.requestId,
                    "provider" to provider.id.name,
                    "remainingMs" to remainingMs.toString(),
                    "providerTimeoutMs" to providerTimeoutMs.toString()
                )
                lastKind = ProviderFailureKind.TIMEOUT
                break
            }

            if (!health.tryAcquire(provider.id)) continue

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

        return ManagerOutcome.Failure(
            lastKind = lastKind,
            attempted = attempted,
            noCandidates = attempted.isEmpty()
        )
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
                // Менеджер обеспечивает timeout независимо от качества
                // реализации провайдера. HTTP-транспорт имеет собственный
                // callTimeout, но fake/будущий SDK-провайдер тоже не должен
                // иметь возможность зависнуть навсегда.
                // CR-06: берём min(per-provider timeout, оставшийся общий
                // deadline), чтобы не стартовать попытку, которая гарантированно
                // истечёт по общему deadline раньше, чем закончится таймаут
                // провайдера.
                val providerTimeoutMs = configs[provider.id]?.requestTimeoutMs
                    ?.coerceAtLeast(1L)
                    ?: 30_000L
                val remainingMs = request.deadlineEpochMs?.let { it - clock() }
                val effectiveTimeoutMs = when {
                    remainingMs == null -> providerTimeoutMs
                    else -> minOf(providerTimeoutMs, remainingMs).coerceAtLeast(1L)
                }
                withTimeout(effectiveTimeoutMs) {
                    provider.execute(request)
                }
            } catch (_: TimeoutCancellationException) {
                ProviderResult.Failure(
                    kind = ProviderFailureKind.TIMEOUT,
                    detail = "manager timeout"
                )
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

                    // CR-06: не ждём backoff, если общий deadline уже истечёт
                    // за время ожидания — бессмысленно тратить время.
                    val remainingMs = request.deadlineEpochMs?.let { it - clock() }
                    if (remainingMs != null && remainingMs <= policy.retryBackoffMs + 100) {
                        logger.warn(
                            "skipping retry backoff; deadline too near",
                            "requestId" to request.requestId,
                            "provider" to provider.id.name,
                            "remainingMs" to remainingMs.toString()
                        )
                        return ManagerOutcome.Failure(
                            lastKind = ProviderFailureKind.TIMEOUT,
                            attempted = listOf(provider.id)
                        )
                    }

                    delay(policy.retryBackoffMs)
                }
            }
        }

        return ManagerOutcome.Failure(lastKind, listOf(provider.id))
    }

    fun healthSnapshot(): Map<ProviderId, HealthSnapshot> = health.snapshot()
}
