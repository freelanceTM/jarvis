package com.jarvis.server

import com.jarvis.server.config.RateLimitConfig
import com.jarvis.server.ratelimit.PostgresRateLimiter
import com.jarvis.server.ratelimit.RateLimitDecision
import com.jarvis.server.usage.AiUsageRecord
import com.jarvis.server.usage.JdbcUsageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

class SharedStateReconnectTest : PostgresTestSupport() {
    private fun failFirstConnection(delegate: DataSource): DataSource {
        val failed = AtomicBoolean(false)
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(DataSource::class.java)
        ) { _, method, arguments ->
            if (method.name == "getConnection" && failed.compareAndSet(false, true)) {
                throw SQLException("simulated transient outage")
            }
            try {
                method.invoke(delegate, *(arguments ?: emptyArray()))
            } catch (wrapped: InvocationTargetException) {
                throw wrapped.targetException
            }
        } as DataSource
    }

    @Test
    fun `next rate limit request reconnects after a transient store outage`() {
        val limiter = PostgresRateLimiter(
            failFirstConnection(dataSource),
            "reconnect_rate",
            RateLimitConfig(1, 10)
        )

        assertTrue(runCatching { limiter.check("client") }.exceptionOrNull() is SQLException)
        assertTrue(limiter.check("client") is RateLimitDecision.Allowed)
        assertTrue(limiter.check("client") is RateLimitDecision.Limited)
    }

    @Test
    fun `next usage write reconnects after a transient store outage`() = runBlocking {
        val repository = JdbcUsageRepository(failFirstConnection(dataSource))
        val usage = AiUsageRecord(
            requestId = "reconnect-usage",
            clientId = "client",
            provider = null,
            model = null,
            latencyMs = 0,
            inputTokens = null,
            outputTokens = null,
            totalTokens = null,
            success = false,
            errorCode = "TEST",
            promptChars = 0,
            responseChars = 0,
            timestamp = Instant.parse("2026-08-22T00:00:00Z")
        )

        assertTrue(runCatching { repository.record(usage) }.exceptionOrNull() is SQLException)
        repository.record(usage)
        assertEquals(listOf("reconnect-usage"), repository.all().map { it.requestId })
    }
}
