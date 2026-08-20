package com.jarvis.server

import com.jarvis.server.observability.ConsoleStructuredLogger
import com.jarvis.server.observability.LogSanitizer
import com.jarvis.server.observability.Metrics
import com.jarvis.server.provider.ProviderFailureKind
import com.jarvis.server.provider.ProviderId
import com.jarvis.server.usage.AiUsageRecord
import com.jarvis.server.usage.InMemoryUsageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ObservabilityUsageTest {

    @Test
    fun `logger redacts secrets and prevents log forging`() {
        val lines = mutableListOf<String>()
        val logger = ConsoleStructuredLogger(sink = lines::add, clock = { 42L })

        logger.warn(
            "attempt\nlevel=ERROR msg=forged",
            "requestId" to "safe\r\nts=0 level=ERROR",
            "authorization" to "Bearer abc+/=_-secret",
            "providerKey" to "gsk_abcdefghijklmnopqrstuvwxyz"
        )

        assertEquals(1, lines.size)
        val line = lines.single()
        assertEquals("logger output must remain one physical line", 1, line.lineSequence().count())
        assertFalse(line.contains("abc+/=_-secret"))
        assertFalse(line.contains("gsk_abcdefghijklmnopqrstuvwxyz"))
        assertTrue(line.contains("[REDACTED]"))
        assertTrue(line.contains("\\n"))
        assertTrue(line.startsWith("ts=42 level=WARN"))
    }

    @Test
    fun `sanitizer handles all supported secret formats`() {
        val raw = "Bearer token.value sk-or-abcdef gsk_abcdef sk-abcdefghijklmnop AIzaabcdefghijklmnop"
        val redacted = LogSanitizer.redact(raw)
        assertFalse(redacted.contains("token.value"))
        assertFalse(redacted.contains("sk-or-"))
        assertFalse(redacted.contains("gsk_"))
        assertFalse(redacted.contains("AIza"))
    }

    @Test
    fun `usage repository is bounded keeps newest records and handles boundary limits`() = runBlocking {
        val repo = InMemoryUsageRepository(maxRecords = 3)
        repeat(5) { i -> repo.record(record(i, if (i % 2 == 0) "a" else "b")) }

        assertEquals(3, repo.size())
        assertEquals(listOf("r-2", "r-3", "r-4"), repo.all().map { it.requestId })
        assertEquals(listOf("r-2", "r-4"), repo.recentFor("a", 100).map { it.requestId })
        assertTrue(repo.recentFor("a", 0).isEmpty())
        assertTrue(repo.recentFor("a", -10).isEmpty())

        val zero = InMemoryUsageRepository(maxRecords = 0)
        zero.record(record(1, "a"))
        assertEquals(0, zero.size())
        assertTrue(zero.all().isEmpty())
    }

    @Test
    fun `usage repository rejects negative capacity`() {
        var thrown = false
        try {
            InMemoryUsageRepository(-1)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `usage repository remains bounded under concurrent writes`() {
        val repo = InMemoryUsageRepository(maxRecords = 100)
        val pool = Executors.newFixedThreadPool(12)
        try {
            pool.invokeAll(List(1_000) { i -> Callable { runBlocking { repo.record(record(i, "same")) } } })
                .forEach { it.get() }
            assertEquals(100, repo.size())
            assertEquals(100, runBlocking { repo.all().size })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `metrics counters are atomic under contention`() {
        val metrics = Metrics()
        val pool = Executors.newFixedThreadPool(12)
        try {
            pool.invokeAll(List(2_000) {
                Callable {
                    metrics.recordRequest()
                    metrics.recordSuccess(2)
                    metrics.recordProviderSuccess(ProviderId.GROQ, 3)
                    metrics.recordProviderFailure(ProviderId.GROQ, ProviderFailureKind.TIMEOUT)
                }
            }).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        val snapshot = metrics.snapshot()
        assertEquals(2_000L, snapshot["requests_total"])
        assertEquals(4_000L, snapshot["tokens_total"])
        assertEquals(2_000L, (snapshot["provider_success"] as Map<*, *>)["GROQ"])
        assertEquals(6_000L, (snapshot["provider_latency_sum_ms"] as Map<*, *>)["GROQ"])
    }

    private fun record(i: Int, client: String) = AiUsageRecord(
        requestId = "r-$i",
        clientId = client,
        provider = null,
        model = null,
        latencyMs = i.toLong(),
        inputTokens = null,
        outputTokens = null,
        totalTokens = null,
        success = true,
        errorCode = null,
        promptChars = i,
        responseChars = 0,
        timestamp = Instant.ofEpochMilli(i.toLong())
    )
}
