package com.jarvis.server

import com.jarvis.server.config.CircuitBreakerConfig
import com.jarvis.server.config.ExecutionPolicyConfig
import com.jarvis.server.config.ProviderConfig
import com.jarvis.server.observability.ConsoleStructuredLogger
import com.jarvis.server.observability.Metrics
import com.jarvis.server.provider.CircuitState
import com.jarvis.server.provider.HealthStatus
import com.jarvis.server.provider.ManagerOutcome
import com.jarvis.server.provider.ProviderCapabilities
import com.jarvis.server.provider.ProviderFailureKind
import com.jarvis.server.provider.ProviderHealthTracker
import com.jarvis.server.provider.ProviderId
import com.jarvis.server.provider.ProviderManager
import com.jarvis.server.provider.ProviderRequest
import com.jarvis.server.provider.ProviderRequirements
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider selection, health, retry, fallback и circuit breaker.
 */
class ProviderManagerTest {

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

    private fun manager(
        providers: List<com.jarvis.server.provider.AiProvider>,
        health: ProviderHealthTracker = ProviderHealthTracker(CircuitBreakerConfig()),
        policy: ExecutionPolicyConfig = ExecutionPolicyConfig(maxRetriesPerProvider = 0),
        configs: Map<ProviderId, ProviderConfig> = this.configs
    ): ProviderManager = ProviderManager(
        providers = providers,
        configs = configs,
        health = health,
        policy = policy,
        selectionPolicy = DefaultProviderSelectionPolicy(configs, health),
        logger = ConsoleStructuredLogger(sink = {}),
        metrics = Metrics()
    )

    private fun request() = ProviderRequest(
        requestId = "req-1",
        prompt = "тест",
        systemPrompt = "system",
        maxTokens = 100,
        temperature = 0.5
    )

    /** Здоровый primary выбирается по приоритету. */
    @Test
    fun `healthy primary is selected`() = runBlocking {
        val groq = FakeAiProvider.ok(ProviderId.GROQ, "от groq")
        val gemini = FakeAiProvider.ok(ProviderId.GEMINI, "от gemini")

        val outcome = manager(listOf(gemini, groq)).execute(request(), ProviderRequirements())

        assertTrue(outcome is ManagerOutcome.Success)
        assertEquals(ProviderId.GROQ, (outcome as ManagerOutcome.Success).providerId)
        assertEquals(1, groq.calls.get())
        assertEquals("fallback не нужен", 0, gemini.calls.get())
    }

    /** Primary недоступен → идём во вторичный. */
    @Test
    fun `primary failure falls back to secondary`() = runBlocking {
        val groq = FakeAiProvider.failing(ProviderId.GROQ, ProviderFailureKind.SERVER_ERROR, 500)
        val gemini = FakeAiProvider.ok(ProviderId.GEMINI, "от gemini")

        val outcome = manager(listOf(groq, gemini)).execute(request(), ProviderRequirements())

        assertTrue(outcome is ManagerOutcome.Success)
        assertEquals(ProviderId.GEMINI, (outcome as ManagerOutcome.Success).providerId)
        assertEquals(1, groq.calls.get())
        assertEquals(1, gemini.calls.get())
    }

    /** Timeout primary → fallback. */
    @Test
    fun `timeout triggers fallback`() = runBlocking {
        val groq = FakeAiProvider.failing(ProviderId.GROQ, ProviderFailureKind.TIMEOUT)
        val gemini = FakeAiProvider.ok(ProviderId.GEMINI)

        val outcome = manager(listOf(groq, gemini)).execute(request(), ProviderRequirements())

        assertEquals(
            ProviderId.GEMINI,
            (outcome as ManagerOutcome.Success).providerId
        )
    }

    /** Все провайдеры недоступны → Failure со списком попыток. */
    @Test
    fun `all providers unavailable`() = runBlocking {
        val providers = listOf(
            FakeAiProvider.failing(ProviderId.GROQ, ProviderFailureKind.SERVER_ERROR, 500),
            FakeAiProvider.failing(ProviderId.GEMINI, ProviderFailureKind.CONNECTION),
            FakeAiProvider.failing(ProviderId.OPENROUTER, ProviderFailureKind.TIMEOUT)
        )

        val outcome = manager(providers).execute(request(), ProviderRequirements())

        assertTrue(outcome is ManagerOutcome.Failure)
        assertEquals(3, (outcome as ManagerOutcome.Failure).attempted.size)
    }

    /** Неверный ключ — постоянный сбой: провайдер выводится из ротации. */
    @Test
    fun `invalid api key disables provider without pointless retries`() = runBlocking {
        val health = ProviderHealthTracker(CircuitBreakerConfig())
        val groq = FakeAiProvider.failing(ProviderId.GROQ, ProviderFailureKind.AUTH, 401)
        val gemini = FakeAiProvider.ok(ProviderId.GEMINI)
        val mgr = manager(
            listOf(groq, gemini),
            health,
            ExecutionPolicyConfig(maxRetriesPerProvider = 2)
        )

        mgr.execute(request(), ProviderRequirements())

        // AUTH не ретраится: ровно одна попытка, несмотря на maxRetries=2.
        assertEquals("AUTH не должен ретраиться", 1, groq.calls.get())
        assertEquals(HealthStatus.UNAVAILABLE, health.status(ProviderId.GROQ))
        assertFalse(health.isAvailable(ProviderId.GROQ))

        // Второй запрос вообще не идёт в сломанный провайдер.
        mgr.execute(request(), ProviderRequirements())
        assertEquals("провайдер выведен из ротации", 1, groq.calls.get())
    }

    /** Transient-сбой ретраится у того же провайдера. */
    @Test
    fun `transient failure is retried on same provider`() = runBlocking {
        val groq = FakeAiProvider.failThenOk(ProviderId.GROQ, ProviderFailureKind.SERVER_ERROR, 1)
        val mgr = manager(
            listOf(groq),
            policy = ExecutionPolicyConfig(maxRetriesPerProvider = 1, retryBackoffMs = 1)
        )

        val outcome = mgr.execute(request(), ProviderRequirements())

        assertTrue(outcome is ManagerOutcome.Success)
        assertEquals(2, groq.calls.get())
    }

    /** RATE_LIMITED не ретраится у того же провайдера, но допускает fallback. */
    @Test
    fun `rate limited provider is not retried but falls back`() = runBlocking {
        val groq = FakeAiProvider.failing(ProviderId.GROQ, ProviderFailureKind.RATE_LIMITED, 429)
        val gemini = FakeAiProvider.ok(ProviderId.GEMINI)
        val mgr = manager(
            listOf(groq, gemini),
            policy = ExecutionPolicyConfig(maxRetriesPerProvider = 2, retryBackoffMs = 1)
        )

        val outcome = mgr.execute(request(), ProviderRequirements())

        assertEquals(1, groq.calls.get())
        assertEquals(ProviderId.GEMINI, (outcome as ManagerOutcome.Success).providerId)
    }

    /** Circuit breaker: после N сбоев провайдер уходит в OPEN. */
    @Test
    fun `circuit opens after repeated failures and recovers after cooldown`() = runBlocking {
        var now = 1000L
        val health = ProviderHealthTracker(
            CircuitBreakerConfig(failureThreshold = 2, openCooldownMs = 5_000),
            clock = { now }
        )
        val groq = FakeAiProvider(
            ProviderId.GROQ,
            listOf(
                com.jarvis.server.provider.ProviderResult.Failure(ProviderFailureKind.SERVER_ERROR, "500"),
                com.jarvis.server.provider.ProviderResult.Failure(ProviderFailureKind.SERVER_ERROR, "500"),
                com.jarvis.server.provider.ProviderResult.Success("ожил", "test-model")
            )
        )
        val mgr = manager(listOf(groq), health)

        mgr.execute(request(), ProviderRequirements())
        mgr.execute(request(), ProviderRequirements())

        assertEquals(CircuitState.OPEN, health.circuitState(ProviderId.GROQ))
        assertFalse(health.isAvailable(ProviderId.GROQ))

        // Пока cooldown не истёк — запросы не идут.
        val blocked = mgr.execute(request(), ProviderRequirements())
        assertTrue((blocked as ManagerOutcome.Failure).noCandidates)
        assertEquals(2, groq.calls.get())

        // После cooldown — HALF_OPEN, пробная попытка, успех → CLOSED.
        now += 6_000
        val recovered = mgr.execute(request(), ProviderRequirements())
        assertTrue(recovered is ManagerOutcome.Success)
        assertEquals(CircuitState.CLOSED, health.circuitState(ProviderId.GROQ))
    }

    /** requiresWeb: провайдеры без web-возможности не выбираются. */
    @Test
    fun `web requirement filters providers without web capability`() = runBlocking {
        val noWeb = FakeAiProvider.ok(ProviderId.GROQ)
        val withWeb = FakeAiProvider(
            ProviderId.GEMINI,
            listOf(com.jarvis.server.provider.ProviderResult.Success("web ответ", "m")),
            capabilities = ProviderCapabilities(supportsWeb = true)
        )

        val outcome = manager(listOf(noWeb, withWeb))
            .execute(request(), ProviderRequirements(requiresWeb = true))

        assertEquals(ProviderId.GEMINI, (outcome as ManagerOutcome.Success).providerId)
        assertEquals(0, noWeb.calls.get())
    }

    /** Отключённый в конфиге провайдер не используется. */
    @Test
    fun `disabled provider is skipped`() = runBlocking {
        val disabledConfigs = configs.toMutableMap().apply {
            put(ProviderId.GROQ, cfg(ProviderId.GROQ, 1, enabled = false))
        }
        val groq = FakeAiProvider.ok(ProviderId.GROQ)
        val gemini = FakeAiProvider.ok(ProviderId.GEMINI)

        val outcome = manager(listOf(groq, gemini), configs = disabledConfigs)
            .execute(request(), ProviderRequirements())

        assertEquals(ProviderId.GEMINI, (outcome as ManagerOutcome.Success).providerId)
        assertEquals(0, groq.calls.get())
    }

    /**
     * CR-16: когда требуется веб-поиск (requiresWeb=true), роутер обязан
     * выбрать провайдера с supportsWeb=true; провайдеры без такой
     * возможности должны быть отфильтрованы (в реальной конфигурации это
     * только Gemini с Google Search grounding).
     */
    @Test
    fun `requiresWeb routes only to web-capable providers`() = runBlocking {
        val groqNoWeb = FakeAiProvider.ok(ProviderId.GROQ, "groq")
        val openRouterNoWeb = FakeAiProvider.ok(ProviderId.OPENROUTER, "openrouter")
        val geminiWeb = FakeAiProvider(
            id = ProviderId.GEMINI,
            script = listOf(ProviderResult.Success("gemini-web", "gemini-test", 1, 2, 3)),
            capabilities = ProviderCapabilities(supportsWeb = true, supportsChat = true)
        )

        val outcome = manager(listOf(groqNoWeb, openRouterNoWeb, geminiWeb))
            .execute(request(), ProviderRequirements(requiresWeb = true))

        assertTrue(outcome is ManagerOutcome.Success)
        assertEquals(ProviderId.GEMINI, (outcome as ManagerOutcome.Success).providerId)
        assertEquals("gemini-web", outcome.text)
        assertEquals(0, groqNoWeb.calls.get())
        assertEquals(0, openRouterNoWeb.calls.get())
        assertEquals(1, geminiWeb.calls.get())
    }

    /** CR-16: если ни один провайдер не поддерживает web, возвращается no-candidates failure. */
    @Test
    fun `requiresWeb with no web-capable provider returns no-candidates failure`() = runBlocking {
        val groqNoWeb = FakeAiProvider.ok(ProviderId.GROQ, "groq")
        val outcome = manager(listOf(groqNoWeb))
            .execute(request(), ProviderRequirements(requiresWeb = true))

        assertTrue(outcome is ManagerOutcome.Failure)
        assertTrue((outcome as ManagerOutcome.Failure).noCandidates)
        assertEquals(0, groqNoWeb.calls.get())
    }

    /** maxProviderAttempts ограничивает длину цепочки fallback. */
    @Test
    fun `fallback chain respects max provider attempts`() = runBlocking {
        val providers = listOf(
            FakeAiProvider.failing(ProviderId.GROQ, ProviderFailureKind.SERVER_ERROR),
            FakeAiProvider.failing(ProviderId.GEMINI, ProviderFailureKind.SERVER_ERROR),
            FakeAiProvider.failing(ProviderId.OPENROUTER, ProviderFailureKind.SERVER_ERROR)
        )
        val mgr = manager(
            providers,
            policy = ExecutionPolicyConfig(maxProviderAttempts = 2, maxRetriesPerProvider = 0)
        )

        val outcome = mgr.execute(request(), ProviderRequirements())

        assertEquals(2, (outcome as ManagerOutcome.Failure).attempted.size)
        assertEquals(0, providers[2].calls.get())
    }

    // ------------------------------------------------------------------ CR-06

    /** CR-06: если общий deadline уже истёк — менеджер не запускает провайдеров. */
    @Test
    fun `deadline already expired skips provider call`() = runBlocking {
        val groq = FakeAiProvider.ok(ProviderId.GROQ, "should-never-run")
        val mgr = manager(listOf(groq))
        val now = System.currentTimeMillis()
        val req = request().copy(deadlineEpochMs = now - 1) // истёк 1 мс назад

        val outcome = mgr.execute(req, ProviderRequirements())

        assertTrue(outcome is ManagerOutcome.Failure)
        assertEquals(ProviderFailureKind.TIMEOUT, (outcome as ManagerOutcome.Failure).lastKind)
        assertEquals(0, groq.calls.get())
    }

    /** CR-06: оставшийся budget меньше per-provider timeout — early-out. */
    @Test
    fun `remaining budget smaller than provider timeout fails fast without call`() = runBlocking {
        val groq = FakeAiProvider.ok(ProviderId.GROQ, "should-never-run")
        val mgr = manager(listOf(groq))
        // Ставим deadline через 50мс — у провайдера 2000мс timeout, попытка
        // гарантированно истечёт по общему budget раньше, поэтому early-out.
        val req = request().copy(deadlineEpochMs = System.currentTimeMillis() + 50)

        val outcome = mgr.execute(req, ProviderRequirements())

        assertTrue(outcome is ManagerOutcome.Failure)
        assertEquals(ProviderFailureKind.TIMEOUT, (outcome as ManagerOutcome.Failure).lastKind)
        assertEquals(0, groq.calls.get())
    }

    /** CR-06: per-provider timeout режется remaining budget (не запускаем
     *  вызов, который провайдер никогда не успеет закончить). */
    @Test
    fun `provider timeout clamped to remaining budget finishes faster than native timeout`() = runBlocking {
        // Провайдер висит 2000мс, но у нас budget 100мс.
        val slow = FakeAiProvider(
            ProviderId.GROQ,
            listOf(com.jarvis.server.provider.ProviderResult.Success("too-late", "m")),
            delayMs = 2_000
        )
        val mgr = manager(
            listOf(slow),
            policy = ExecutionPolicyConfig(maxRetriesPerProvider = 0)
        )
        val req = request().copy(deadlineEpochMs = System.currentTimeMillis() + 100)

        val start = System.nanoTime()
        val outcome = mgr.execute(req, ProviderRequirements())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("expected failure, got $outcome", outcome is ManagerOutcome.Failure)
        assertEquals(
            ProviderFailureKind.TIMEOUT,
            (outcome as ManagerOutcome.Failure).lastKind
        )
        // Должен завершиться ~за 100мс (timeout budget), а не за 2000мс.
        assertTrue(
            "expected <500ms but took ${elapsedMs}ms",
            elapsedMs < 500
        )
        assertEquals(1, slow.calls.get())
    }

    /** CR-06: backoff не выполняется, если не влезает в оставшийся budget. */
    @Test
    fun `retry backoff skipped if budget insufficient`() = runBlocking {
        // Провайдер стабильно падает SERVER_ERROR (retriable), budget на весь
        // запрос — 30мс, backoff 200мс. После первой неудачи менеджер не должен
        // ждать 200мс — сразу early-out TIMEOUT.
        val flaky = FakeAiProvider(
            ProviderId.GROQ,
            List(5) { com.jarvis.server.provider.ProviderResult.Failure(ProviderFailureKind.SERVER_ERROR, "boom") }
        )
        val mgr = manager(
            listOf(flaky),
            policy = ExecutionPolicyConfig(maxRetriesPerProvider = 3, retryBackoffMs = 200)
        )
        val req = request().copy(deadlineEpochMs = System.currentTimeMillis() + 30)

        val start = System.nanoTime()
        val outcome = mgr.execute(req, ProviderRequirements())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue(outcome is ManagerOutcome.Failure)
        assertEquals(
            "должен вернуться TIMEOUT, не дожидаясь backoff",
            ProviderFailureKind.TIMEOUT,
            (outcome as ManagerOutcome.Failure).lastKind
        )
        assertTrue(
            "backoff должен быть пропущен; фактически ${elapsedMs}мс",
            elapsedMs < 150
        )
        // Ровно одна попытка — вторая была скипнута.
        assertEquals(1, flaky.calls.get())
    }

    /** CR-05: отмена корутины менеджера прерывает in-flight провайдера
     *  (подвешенный через delay провайдер не висит после cancel). */
    @Test
    fun `cancelling manager coroutine cancels hanging provider promptly`() = runBlocking {
        val hang = FakeAiProvider(
            ProviderId.GROQ,
            listOf(com.jarvis.server.provider.ProviderResult.Success("never", "m")),
            delayMs = 30_000
        )
        val mgr = manager(listOf(hang))
        val req = request()

        val job = launch {
            mgr.execute(req, ProviderRequirements())
        }
        // Даём провайдеру стартовать.
        delay(50)
        val start = System.nanoTime()
        job.cancel()
        job.join()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // Cancellation должна дойти до delay() внутри FakeAiProvider практически
        // мгновенно (structured concurrency), не за 30 секунд.
        assertTrue(
            "cancellation took ${elapsedMs}ms, expected <1000ms",
            elapsedMs < 1_000
        )
        assertEquals(1, hang.calls.get())
    }
}
