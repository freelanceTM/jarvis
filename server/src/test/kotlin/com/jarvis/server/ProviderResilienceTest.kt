package com.jarvis.server

import com.jarvis.server.config.CircuitBreakerConfig
import com.jarvis.server.config.ExecutionPolicyConfig
import com.jarvis.server.config.ProviderConfig
import com.jarvis.server.observability.ConsoleStructuredLogger
import com.jarvis.server.observability.Metrics
import com.jarvis.server.provider.AiProvider
import com.jarvis.server.provider.CircuitState
import com.jarvis.server.provider.ManagerOutcome
import com.jarvis.server.provider.ProviderCapabilities
import com.jarvis.server.provider.ProviderFailureKind
import com.jarvis.server.provider.ProviderHealthTracker
import com.jarvis.server.provider.ProviderId
import com.jarvis.server.provider.ProviderManager
import com.jarvis.server.provider.ProviderRequest
import com.jarvis.server.provider.ProviderRequirements
import com.jarvis.server.provider.ProviderResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ProviderResilienceTest {

    @Test
    fun `only one half-open probe can be acquired concurrently`() {
        var now = 0L
        val health = ProviderHealthTracker(
            CircuitBreakerConfig(failureThreshold = 1, openCooldownMs = 100),
            clock = { now }
        )
        health.recordFailure(ProviderId.GROQ, ProviderFailureKind.SERVER_ERROR, "down")
        now = 101L
        assertTrue(health.isAvailable(ProviderId.GROQ))

        val pool = Executors.newFixedThreadPool(32)
        try {
            val acquired = pool.invokeAll(
                List(200) { Callable { health.tryAcquire(ProviderId.GROQ) } }
            ).count { it.get() }
            assertEquals(1, acquired)
            assertEquals(CircuitState.HALF_OPEN, health.circuitState(ProviderId.GROQ))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `configured number of sequential half-open successes closes circuit`() {
        var now = 0L
        val health = ProviderHealthTracker(
            CircuitBreakerConfig(
                failureThreshold = 1,
                openCooldownMs = 0,
                halfOpenSuccessesToClose = 2
            ),
            clock = { now }
        )
        health.recordFailure(ProviderId.GROQ, ProviderFailureKind.SERVER_ERROR, "down")

        assertTrue(health.tryAcquire(ProviderId.GROQ))
        health.recordSuccess(ProviderId.GROQ)
        assertEquals(CircuitState.HALF_OPEN, health.circuitState(ProviderId.GROQ))
        assertTrue("next sequential probe must be allowed", health.tryAcquire(ProviderId.GROQ))
        health.recordSuccess(ProviderId.GROQ)
        assertEquals(CircuitState.CLOSED, health.circuitState(ProviderId.GROQ))
    }

    @Test
    fun `success after retry closes circuit opened by first failed attempt`() = runBlocking {
        val health = ProviderHealthTracker(CircuitBreakerConfig(failureThreshold = 1))
        val provider = FakeAiProvider.failThenOk(ProviderId.GROQ, ProviderFailureKind.SERVER_ERROR, 1)
        val manager = manager(
            provider,
            health,
            requestTimeoutMs = 1_000,
            policy = ExecutionPolicyConfig(maxRetriesPerProvider = 1, retryBackoffMs = 0)
        )

        val outcome = manager.execute(request(), ProviderRequirements())

        assertTrue(outcome is ManagerOutcome.Success)
        assertEquals(CircuitState.CLOSED, health.circuitState(ProviderId.GROQ))
        assertTrue(health.isAvailable(ProviderId.GROQ))
    }

    @Test
    fun `manager timeout protects against hanging provider and allows fallback`() = runBlocking {
        val slow = DelayedProvider(ProviderId.GROQ, delayMs = 250)
        val fast = FakeAiProvider.ok(ProviderId.GEMINI, "fallback")
        val configs = mapOf(
            ProviderId.GROQ to config(ProviderId.GROQ, priority = 1, requestTimeoutMs = 25),
            ProviderId.GEMINI to config(ProviderId.GEMINI, priority = 2, requestTimeoutMs = 1_000)
        )
        val health = ProviderHealthTracker(CircuitBreakerConfig())
        val manager = ProviderManager(
            providers = listOf(slow, fast),
            configs = configs,
            health = health,
            policy = ExecutionPolicyConfig(maxRetriesPerProvider = 0),
            selectionPolicy = DefaultProviderSelectionPolicy(configs, health),
            logger = ConsoleStructuredLogger(sink = {}),
            metrics = Metrics()
        )

        val started = System.nanoTime()
        val outcome = manager.execute(request(), ProviderRequirements())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals("fallback", (outcome as ManagerOutcome.Success).text)
        assertEquals(1, slow.calls.get())
        assertTrue("manager timeout should avoid the full provider delay: $elapsedMs ms", elapsedMs < 200)
    }

    @Test
    fun `concurrent requests after cooldown execute one probe not a storm`() = runBlocking {
        var now = 0L
        val health = ProviderHealthTracker(
            CircuitBreakerConfig(failureThreshold = 1, openCooldownMs = 10),
            clock = { now }
        )
        health.recordFailure(ProviderId.GROQ, ProviderFailureKind.SERVER_ERROR, "down")
        now = 11L

        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val probe = object : AiProvider {
            override val id = ProviderId.GROQ
            override val capabilities = ProviderCapabilities()
            val calls = AtomicInteger(0)
            override fun isConfigured() = true
            override suspend fun execute(request: ProviderRequest): ProviderResult {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                return ProviderResult.Success("ok", "m")
            }
        }
        val manager = manager(probe, health, requestTimeoutMs = 1_000)

        val first = async(Dispatchers.Default) { manager.execute(request(), ProviderRequirements()) }
        started.await()
        val blocked = List(49) {
            async(Dispatchers.Default) { manager.execute(request(), ProviderRequirements()) }
        }.awaitAll()
        release.complete(Unit)
        val successfulProbe = first.await()

        assertEquals(1, probe.calls.get())
        assertTrue(successfulProbe is ManagerOutcome.Success)
        assertEquals(49, blocked.count { it is ManagerOutcome.Failure })
    }

    private class DelayedProvider(
        override val id: ProviderId,
        private val delayMs: Long
    ) : AiProvider {
        override val capabilities = ProviderCapabilities()
        val calls = AtomicInteger(0)
        override fun isConfigured() = true
        override suspend fun execute(request: ProviderRequest): ProviderResult {
            calls.incrementAndGet()
            delay(delayMs)
            return ProviderResult.Success("ok", "m")
        }
    }

    private fun manager(
        provider: AiProvider,
        health: ProviderHealthTracker,
        requestTimeoutMs: Long,
        policy: ExecutionPolicyConfig = ExecutionPolicyConfig(maxRetriesPerProvider = 0)
    ): ProviderManager {
        val configs = mapOf(provider.id to config(provider.id, 1, requestTimeoutMs))
        return ProviderManager(
            providers = listOf(provider),
            configs = configs,
            health = health,
            policy = policy,
            selectionPolicy = DefaultProviderSelectionPolicy(configs, health),
            logger = ConsoleStructuredLogger(sink = {}),
            metrics = Metrics()
        )
    }

    private fun config(id: ProviderId, priority: Int, requestTimeoutMs: Long) = ProviderConfig(
        id = id,
        enabled = true,
        priority = priority,
        apiKey = "k",
        model = "m",
        baseUrl = "https://example.invalid",
        connectTimeoutMs = 100,
        requestTimeoutMs = requestTimeoutMs
    )

    private fun request() = ProviderRequest(
        requestId = "req",
        prompt = "prompt",
        systemPrompt = "system",
        maxTokens = 10,
        temperature = 0.5
    )
}
