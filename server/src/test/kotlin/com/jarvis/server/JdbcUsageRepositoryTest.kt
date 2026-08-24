package com.jarvis.server

import com.jarvis.server.usage.AiUsageRecord
import com.jarvis.server.usage.JdbcUsageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class JdbcUsageRepositoryTest : PostgresTestSupport() {
    private fun record(
        requestId: String,
        clientId: String = "client-a",
        timestamp: Instant = Instant.parse("2026-08-22T00:00:00Z")
    ) = AiUsageRecord(
        requestId = requestId,
        clientId = clientId,
        provider = "GROQ",
        model = "model",
        latencyMs = 10,
        inputTokens = 2,
        outputTokens = 3,
        totalTokens = 5,
        success = true,
        errorCode = null,
        promptChars = 12,
        responseChars = 24,
        timestamp = timestamp
    )

    @Test
    fun `two repository instances share usage state and survive local recreation`() = runBlocking {
        val first = JdbcUsageRepository(dataSource)
        val second = JdbcUsageRepository(dataSource)

        first.record(record("request-1"))
        assertEquals(listOf("request-1"), second.all().map { it.requestId })

        val recreated = JdbcUsageRepository(dataSource)
        assertEquals(listOf("request-1"), recreated.recentFor("client-a").map { it.requestId })
    }

    @Test
    fun `concurrent updates are complete and duplicate request is idempotent`() = runBlocking {
        val first = JdbcUsageRepository(dataSource)
        val second = JdbcUsageRepository(dataSource)
        val pool = Executors.newFixedThreadPool(16)
        try {
            pool.invokeAll(
                List(100) { index ->
                    Callable {
                        runBlocking {
                            (if (index % 2 == 0) first else second).record(record("request-$index"))
                        }
                    }
                }
            ).forEach { it.get() }
            assertEquals(100, first.all().size)

            pool.invokeAll(
                List(20) { index ->
                    Callable {
                        runBlocking {
                            (if (index % 2 == 0) first else second).record(record("same-request"))
                        }
                    }
                }
            ).forEach { it.get() }
            assertEquals(101, second.all().size)
            assertEquals(1, second.all().count { it.requestId == "same-request" })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `retention expiration removes old usage but keeps current records`() = runBlocking {
        val now = Instant.parse("2026-08-22T00:00:00Z")
        val repository = JdbcUsageRepository(
            dataSource = dataSource,
            retention = Duration.ofDays(1),
            clock = Clock.fixed(now, ZoneOffset.UTC)
        )

        repository.record(record("expired", timestamp = now.minus(Duration.ofDays(2))))
        assertTrue(repository.all().isEmpty())
        repository.record(record("current", timestamp = now))
        assertEquals(listOf("current"), repository.all().map { it.requestId })
    }
}
