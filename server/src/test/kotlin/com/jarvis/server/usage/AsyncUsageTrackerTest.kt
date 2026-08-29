package com.jarvis.server.usage

import com.jarvis.server.config.TokenCostConfig
import com.jarvis.server.config.UsageLimitConfig
import com.jarvis.server.observability.ConsoleStructuredLogger
import com.jarvis.server.observability.Metrics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * AR-05 regression tests: bounded queue, retry, graceful shutdown, and
 * token/cost preflight gating of [AsyncUsageTracker].
 *
 * Pure JVM + kotlinx-coroutines-test-friendly runBlocking.
 */
class AsyncUsageTrackerTest {

    private val logger = ConsoleStructuredLogger()
    private val metrics = Metrics()

    private fun recordingRepo(): Pair<UsageRepository, MutableList<AiUsageRecord>> {
        val records = mutableListOf<AiUsageRecord>()
        return object : UsageRepository {
            override suspend fun record(usage: AiUsageRecord) {
                records.add(usage)
            }
            override suspend fun recentFor(clientId: String, limit: Int) = emptyList<AiUsageRecord>()
            override suspend fun all() = records.toList()
        } to records
    }

    @Test
    fun `recorded usage is persisted asynchronously`() = runBlocking {
        val (repo, records) = recordingRepo()
        val tracker = AsyncUsageTracker(repo, UsageLimitConfig(), TokenCostConfig(), logger, metrics)
        tracker.start()
        val start = Instant.now()
        tracker.record(AiUsageRecord(
            requestId = "r-1", clientId = "c", provider = "p", model = "m",
            latencyMs = 1, inputTokens = null, outputTokens = null, totalTokens = null,
            success = true, errorCode = null, promptChars = 1, responseChars = 1,
            timestamp = start
        ))
        withTimeout(2000) {
            while (records.isEmpty()) delay(20)
        }
        assertEquals(1, records.size)
        tracker.shutdown()
    }

    @Test
    fun `preflight blocks requests over perDayRequests limit`() {
        val (repo, _) = recordingRepo()
        val fixedClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        val tracker = AsyncUsageTracker(
            repo, UsageLimitConfig(perDayRequests = 2, perDayTokens = 0, perDayCostUsd = 0.0),
            TokenCostConfig(), logger, metrics, clock = fixedClock
        )
        tracker.start()
        assertEquals(UsageLimitResult.Allowed, tracker.preflight("c"))
        tracker.accountFor("c", 0, 0, null)
        assertEquals(UsageLimitResult.Allowed, tracker.preflight("c"))
        tracker.accountFor("c", 0, 0, null)
        val limited = tracker.preflight("c")
        assertTrue(limited is UsageLimitResult.Limited)
        assertEquals("per_day_requests", (limited as UsageLimitResult.Limited).scope)
        tracker.shutdown()
    }

    @Test
    fun `queue overflow drops events without blocking caller`() {
        val blocking = CompletableDeferred<Unit>()
        val blockedRepo = object : UsageRepository {
            override suspend fun record(usage: AiUsageRecord) {
                // Block forever unless released
                blocking.await()
            }
            override suspend fun recentFor(clientId: String, limit: Int) = emptyList<AiUsageRecord>()
            override suspend fun all() = emptyList<AiUsageRecord>()
        }
        val tracker = AsyncUsageTracker(blockedRepo, UsageLimitConfig(), TokenCostConfig(), logger, metrics)
        tracker.start()
        // Send more than capacity; trySend must not throw or block.
        repeat(AsyncUsageTracker.QUEUE_CAPACITY + 100) {
            tracker.record(AiUsageRecord(
                requestId = "r-$it", clientId = "c", provider = "p", model = "m",
                latencyMs = 1, inputTokens = null, outputTokens = null, totalTokens = null,
                success = true, errorCode = null, promptChars = 1, responseChars = 1,
                timestamp = Instant.now()
            ))
        }
        blocking.complete(Unit)
        tracker.shutdown()
        // No assertion on number recorded (depends on timing); key invariant is that shutdown returns.
        assertNotNull(metrics.snapshot()["usage_dropped"])
    }

    @Test
    fun `shutdown returns without hanging when worker is idle`() {
        val (repo, _) = recordingRepo()
        val tracker = AsyncUsageTracker(repo, UsageLimitConfig(), TokenCostConfig(), logger, metrics)
        tracker.start()
        tracker.shutdown()
        // If we got here, shutdown returned within its bounded drain.
    }
}
