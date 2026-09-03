package com.jarvis.server

import com.jarvis.server.config.CircuitBreakerConfig
import com.jarvis.server.config.ProviderConfig
import com.jarvis.server.cost.CostClass
import com.jarvis.server.cost.RequestCostEstimator
import com.jarvis.server.provider.CostEstimate
import com.jarvis.server.provider.ProviderHealthTracker
import com.jarvis.server.provider.ProviderId
import com.jarvis.server.provider.ProviderPerformanceTracker
import com.jarvis.server.provider.ProviderRequirements
import com.jarvis.server.provider.SmartProviderSelectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cost Control: Request → Cost estimation → Budget policy → Provider.
 *
 * SIMPLE — цена доминирует (короткая реплика не должна платить за мощную
 * модель), MEDIUM — прежний баланс, HARD — качество доминирует (research
 * не экономит на модели). Класс детерминирован по форме запроса.
 */
class CostControlTest {

    /* ------------------------------------------------- Cost estimation (pure) */

    @Test
    fun `short prompt without context is SIMPLE`() {
        assertEquals(
            CostClass.SIMPLE,
            RequestCostEstimator.classify(promptChars = 80, historyChars = 0, requiresWeb = false)
        )
    }

    @Test
    fun `web research is always HARD`() {
        assertEquals(
            CostClass.HARD,
            RequestCostEstimator.classify(promptChars = 20, historyChars = 0, requiresWeb = true)
        )
    }

    @Test
    fun `long prompt or big history is HARD`() {
        assertEquals(
            CostClass.HARD,
            RequestCostEstimator.classify(promptChars = 2_500, historyChars = 0, requiresWeb = false)
        )
        assertEquals(
            CostClass.HARD,
            RequestCostEstimator.classify(promptChars = 100, historyChars = 5_000, requiresWeb = false)
        )
    }

    @Test
    fun `medium request is MEDIUM`() {
        assertEquals(
            CostClass.MEDIUM,
            RequestCostEstimator.classify(promptChars = 500, historyChars = 1_000, requiresWeb = false)
        )
    }

    @Test
    fun `simple class caps output budget`() {
        assertEquals(256, RequestCostEstimator.outputBudget(CostClass.SIMPLE, configuredMaxTokens = 512))
        // Конфигурационный максимум меньше капы — уважается.
        assertEquals(128, RequestCostEstimator.outputBudget(CostClass.SIMPLE, configuredMaxTokens = 128))
        assertEquals(512, RequestCostEstimator.outputBudget(CostClass.MEDIUM, configuredMaxTokens = 512))
        assertEquals(512, RequestCostEstimator.outputBudget(CostClass.HARD, configuredMaxTokens = 512))
    }

    /* ------------------------------- Budget policy → provider (weights flip) */

    private fun cfg(id: ProviderId, priority: Int) = ProviderConfig(
        id = id,
        enabled = true,
        priority = priority,
        apiKey = "test-key",
        model = "test-model",
        baseUrl = "https://example.invalid",
        connectTimeoutMs = 1000,
        requestTimeoutMs = 2000
    )

    private val configs = mapOf(
        ProviderId.GROQ to cfg(ProviderId.GROQ, 1),
        ProviderId.GEMINI to cfg(ProviderId.GEMINI, 2)
    )

    private fun select(
        costClass: CostClass,
        prices: Map<ProviderId, CostEstimate>
    ): ProviderId {
        val tracker = ProviderPerformanceTracker()
        // GROQ — быстрый (100ms), GEMINI — медленный (400ms); надёжность равная.
        repeat(8) { tracker.recordSuccess(ProviderId.GROQ, 100) }
        repeat(8) { tracker.recordSuccess(ProviderId.GEMINI, 400) }
        val health = ProviderHealthTracker(CircuitBreakerConfig())
        val policy = SmartProviderSelectionPolicy(
            configs, health,
            performance = tracker,
            prices = { prices }
        )
        val all = listOf(
            com.jarvis.server.FakeAiProvider.ok(ProviderId.GROQ),
            com.jarvis.server.FakeAiProvider.ok(ProviderId.GEMINI)
        )
        return policy.select(all, ProviderRequirements(costClass = costClass)).first().id
    }

    @Test
    fun `simple class routes to the cheapest provider despite slower latency`() {
        val expensiveButFast = mapOf(
            ProviderId.GROQ to CostEstimate(usdPer1kInput = 0.05, usdPer1kOutput = 0.10),
            ProviderId.GEMINI to CostEstimate(usdPer1kInput = 0.0005, usdPer1kOutput = 0.001)
        )
        // MEDIUM: быстрый GROQ выигрывает (латентность 0.35 > цена 0.15).
        assertEquals(ProviderId.GROQ, select(CostClass.MEDIUM, expensiveButFast))
        // SIMPLE: цена доминирует — та же раскладка отправляет к дешёвому GEMINI.
        assertEquals(ProviderId.GEMINI, select(CostClass.SIMPLE, expensiveButFast))
    }

    @Test
    fun `hard class routes to the most reliable provider ignoring price`() {
        val tracker = ProviderPerformanceTracker()
        // GEMINI: дешёвый, но нестабильный (5/8); OPENROUTER: надёжный (8/8).
        repeat(5) { tracker.recordSuccess(ProviderId.GEMINI, 500) }
        repeat(3) { tracker.recordFailure(ProviderId.GEMINI, com.jarvis.server.provider.ProviderFailureKind.SERVER_ERROR) }
        repeat(8) { tracker.recordSuccess(ProviderId.OPENROUTER, 500) }
        val health = ProviderHealthTracker(CircuitBreakerConfig())
        val policy = SmartProviderSelectionPolicy(
            mapOf(
                ProviderId.GEMINI to cfg(ProviderId.GEMINI, 1),
                ProviderId.OPENROUTER to cfg(ProviderId.OPENROUTER, 2)
            ),
            health,
            performance = tracker,
            prices = {
                mapOf(
                    ProviderId.GEMINI to CostEstimate(usdPer1kInput = 0.0005, usdPer1kOutput = 0.001),
                    ProviderId.OPENROUTER to CostEstimate(usdPer1kInput = 0.05, usdPer1kOutput = 0.10)
                )
            }
        )
        val all = listOf(
            com.jarvis.server.FakeAiProvider.ok(ProviderId.GEMINI),
            com.jarvis.server.FakeAiProvider.ok(ProviderId.OPENROUTER)
        )

        // MEDIUM: дешёвый GEMINI выигрывает (цена 0.15 перевешивает надёжность).
        assertEquals(
            ProviderId.GEMINI,
            policy.select(all, ProviderRequirements(costClass = CostClass.MEDIUM)).first().id
        )
        // HARD: качество доминирует (надёжность 0.5, цена 0) — OPENROUTER.
        assertEquals(
            ProviderId.OPENROUTER,
            policy.select(all, ProviderRequirements(costClass = CostClass.HARD)).first().id
        )
    }
}
