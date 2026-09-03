package com.jarvis.server

import com.jarvis.server.config.CircuitBreakerConfig
import com.jarvis.server.config.ExecutionPolicyConfig
import com.jarvis.server.config.ProviderConfig
import com.jarvis.server.observability.ConsoleStructuredLogger
import com.jarvis.server.observability.Metrics
import com.jarvis.server.provider.CostEstimate
import com.jarvis.server.provider.DefaultProviderSelectionPolicy
import com.jarvis.server.provider.ProviderHealthTracker
import com.jarvis.server.provider.ProviderId
import com.jarvis.server.provider.ProviderManager
import com.jarvis.server.provider.ProviderPerformanceTracker
import com.jarvis.server.provider.ProviderRequest
import com.jarvis.server.provider.ProviderRequirements
import com.jarvis.server.provider.SmartProviderSelectionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smart Cloud Router: выбор ЛУЧШЕГО провайдера по измеренным
 * latency / errors / 429 / availability и конфигурируемым ценам (cost),
 * а не по статическому приоритету. Cold start (нет измерений) — прежний
 * статический порядок; детерминированно, без случайности.
 */
class SmartProviderSelectionPolicyTest {

    private fun cfg(id: ProviderId, priority: Int, enabled: Boolean = true) = ProviderConfig(
        id = id,
        enabled = enabled,
        priority = priority,
        apiKey = "test-key",
        model = "test-model",
        baseUrl = "https://example.invalid",
        connectTimeoutMs = 1000,
        requestTimeoutMs = 2000
    )

    private val configs = mapOf(
        ProviderId.GROQ to cfg(ProviderId.GROQ, 1),
        ProviderId.GEMINI to cfg(ProviderId.GEMINI, 2),
        ProviderId.OPENROUTER to cfg(ProviderId.OPENROUTER, 3)
    )

    private fun providers(vararg ids: ProviderId) = ids.map { FakeAiProvider.ok(it) }

    private fun policy(
        performance: ProviderPerformanceTracker = ProviderPerformanceTracker(),
        prices: Map<ProviderId, CostEstimate> = emptyMap(),
        health: ProviderHealthTracker = ProviderHealthTracker(CircuitBreakerConfig())
    ) = SmartProviderSelectionPolicy(
        configs,
        health,
        performance = performance,
        prices = { prices }
    )

    private fun warmLatency(
        tracker: ProviderPerformanceTracker,
        id: ProviderId,
        count: Int,
        latencyMs: Long
    ) {
        repeat(count) { tracker.recordSuccess(id, latencyMs) }
    }

    @Test
    fun `cold start keeps static priority order`() {
        val tracker = ProviderPerformanceTracker()
        val p = policy(performance = tracker)
        // Ни у кого нет измерений.
        val selected = p.select(providers(ProviderId.GROQ, ProviderId.GEMINI, ProviderId.OPENROUTER), ProviderRequirements())
        assertEquals(listOf(ProviderId.GROQ, ProviderId.GEMINI, ProviderId.OPENROUTER), selected.map { it.id })
    }

    @Test
    fun `sub-threshold samples do not influence ranking`() {
        val tracker = ProviderPerformanceTracker()
        // GEMINI (статический второй) быстро, но измерений меньше MIN_SAMPLES.
        warmLatency(tracker, ProviderId.GEMINI, 3, 50)
        val p = policy(performance = tracker)
        val selected = p.select(providers(ProviderId.GROQ, ProviderId.GEMINI), ProviderRequirements())
        assertEquals(ProviderId.GROQ, selected.first().id)
    }

    @Test
    fun `measured faster provider outranks static priority`() {
        val tracker = ProviderPerformanceTracker()
        // GROQ — статический первый, но стабильно медленный; GEMINI — быстрый.
        warmLatency(tracker, ProviderId.GROQ, 8, 2_000)
        warmLatency(tracker, ProviderId.GEMINI, 8, 100)
        val p = policy(performance = tracker)

        val selected = p.select(providers(ProviderId.GROQ, ProviderId.GEMINI), ProviderRequirements())

        assertEquals(ProviderId.GEMINI, selected.first().id)
    }

    @Test
    fun `rate limited provider is deprioritized`() {
        val tracker = ProviderPerformanceTracker()
        // Одинаковая латентность; GEMINI вдобавок ловит 429.
        warmLatency(tracker, ProviderId.GROQ, 6, 500)
        warmLatency(tracker, ProviderId.GEMINI, 6, 500)
        repeat(3) { tracker.recordFailure(ProviderId.GEMINI, com.jarvis.server.provider.ProviderFailureKind.RATE_LIMITED) }
        val p = policy(performance = tracker)

        val selected = p.select(providers(ProviderId.GROQ, ProviderId.GEMINI), ProviderRequirements())

        assertEquals(ProviderId.GROQ, selected.first().id)
    }

    @Test
    fun `cheaper provider wins on equal measured performance`() {
        val tracker = ProviderPerformanceTracker()
        warmLatency(tracker, ProviderId.GROQ, 8, 500)
        warmLatency(tracker, ProviderId.GEMINI, 8, 500)
        val p = policy(
            performance = tracker,
            prices = mapOf(
                ProviderId.GROQ to CostEstimate(usdPer1kInput = 0.005, usdPer1kOutput = 0.01),
                ProviderId.GEMINI to CostEstimate(usdPer1kInput = 0.0005, usdPer1kOutput = 0.001)
            )
        )

        val selected = p.select(providers(ProviderId.GROQ, ProviderId.GEMINI), ProviderRequirements())

        assertEquals(ProviderId.GEMINI, selected.first().id)
    }

    @Test
    fun `failing provider is ranked last among measured peers`() {
        val tracker = ProviderPerformanceTracker()
        warmLatency(tracker, ProviderId.GROQ, 8, 500)
        warmLatency(tracker, ProviderId.GEMINI, 8, 500)
        // OPENROUTER: сбои → низкий success rate (латентность та же).
        warmLatency(tracker, ProviderId.OPENROUTER, 4, 500)
        repeat(4) { tracker.recordFailure(ProviderId.OPENROUTER, com.jarvis.server.provider.ProviderFailureKind.SERVER_ERROR) }
        val p = policy(performance = tracker)

        val selected = p.select(providers(ProviderId.GROQ, ProviderId.GEMINI, ProviderId.OPENROUTER), ProviderRequirements())

        assertEquals(ProviderId.OPENROUTER, selected.last().id)
    }

    @Test
    fun `circuit-open provider is never selected regardless of score`() {
        val tracker = ProviderPerformanceTracker()
        // GROQ быстрейший, но breaker открыт — кандидатом быть не может.
        warmLatency(tracker, ProviderId.GROQ, 8, 50)
        val health = ProviderHealthTracker(CircuitBreakerConfig(failureThreshold = 1, openCooldownMs = 60_000))
        health.recordFailure(ProviderId.GROQ, com.jarvis.server.provider.ProviderFailureKind.SERVER_ERROR, "boom")
        val p = policy(performance = tracker, health = health)

        val selected = p.select(providers(ProviderId.GROQ, ProviderId.GEMINI), ProviderRequirements())

        assertTrue(selected.none { it.id == ProviderId.GROQ })
        assertEquals(ProviderId.GEMINI, selected.first().id)
    }

    @Test
    fun `cold-start policy ordering equals legacy default policy`() {
        val health = ProviderHealthTracker(CircuitBreakerConfig())
        val tracker = ProviderPerformanceTracker()
        val smart = SmartProviderSelectionPolicy(configs, health, performance = tracker)
        val legacy = DefaultProviderSelectionPolicy(configs, health)
        val all = providers(ProviderId.OPENROUTER, ProviderId.GROQ, ProviderId.GEMINI)

        assertEquals(
            legacy.select(all, ProviderRequirements()).map { it.id },
            smart.select(all, ProviderRequirements()).map { it.id }
        )
    }

    // ------------------------------------------------ manager integration

    @Test
    fun `provider manager records measured stats for smart ranking`() = runBlocking {
        val tracker = ProviderPerformanceTracker()
        val health = ProviderHealthTracker(CircuitBreakerConfig())
        val selectionPolicy = SmartProviderSelectionPolicy(configs, health, performance = tracker)
        val manager = ProviderManager(
            providers = listOf(
                FakeAiProvider.failing(ProviderId.GROQ, com.jarvis.server.provider.ProviderFailureKind.RATE_LIMITED, 429),
                FakeAiProvider.ok(ProviderId.GEMINI)
            ),
            configs = configs,
            health = health,
            policy = ExecutionPolicyConfig(maxRetriesPerProvider = 0),
            selectionPolicy = selectionPolicy,
            logger = ConsoleStructuredLogger(sink = {}),
            metrics = Metrics(),
            performance = tracker
        )

        val outcome = manager.execute(
            ProviderRequest(requestId = "r1", prompt = "тест", systemPrompt = "s", maxTokens = 10, temperature = 0.5),
            ProviderRequirements()
        )

        assertTrue(outcome is com.jarvis.server.provider.ManagerOutcome.Success)
        val groq = manager.performanceSnapshot()[ProviderId.GROQ]!!
        val gemini = manager.performanceSnapshot()[ProviderId.GEMINI]!!
        // Groq: 1 попытка, RATE_LIMITED засчитан как 429.
        assertEquals(1L, groq.samples)
        assertEquals(1L, groq.rateLimitedCount)
        assertEquals(0.0, groq.successRate, 1e-9)
        // Gemini: успех с измеренной латентностью.
        assertEquals(1L, gemini.samples)
        assertEquals(1.0, gemini.successRate, 1e-9)
        assertTrue((gemini.avgLatencyMs ?: -1.0) >= 0.0)
    }
}
