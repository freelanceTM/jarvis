package com.jarvis.server.usage

import com.jarvis.server.config.TokenCostConfig
import com.jarvis.server.config.UsageLimitConfig
import com.jarvis.server.observability.ConsoleStructuredLogger
import com.jarvis.server.observability.Metrics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * M-02 regression tests: stale daily-bucket entries are pruned so that
 * one-off clients / rotated licenses don't accumulate in the in-memory
 * ConcurrentHashMap maps of [AsyncUsageTracker] for the lifetime of the
 * JVM.
 *
 * Доступ к внутреннему состоянию идёт через рефлексию — это
 * white-box тест на утечку памяти, и приватные поля (dailyRequests и т.п.)
 * являются частью производственной реализации. Рефакторинг имён полей
 * требует обновления теста; это осознанный trade-off ради покрытия.
 */
class AsyncUsageTrackerPruneTest {

    private val logger = ConsoleStructuredLogger()
    private val metrics = Metrics()
    private val fakeRepo = object : UsageRepository {
        override suspend fun record(usage: AiUsageRecord) = Unit
        override suspend fun recentFor(clientId: String, limit: Int) = emptyList<AiUsageRecord>()
        override suspend fun all() = emptyList<AiUsageRecord>()
    }

    private fun clockAt(isoUtc: String) =
        Clock.fixed(Instant.parse(isoUtc), ZoneOffset.UTC)

    private fun AsyncUsageTracker.invokePrune() {
        // Reflection: вызов private pruneStaleEntries() без старта worker-loop.
        val m = this::class.java.getDeclaredMethod("pruneStaleEntries")
        m.isAccessible = true
        m.invoke(this)
    }

    private fun AsyncUsageTracker.mapSize(fieldName: String): Int {
        val f = this::class.java.getDeclaredField(fieldName)
        f.isAccessible = true
        return (f.get(this) as Map<*, *>).size
    }

    @Test
    fun `prune removes stale zero-count entries but keeps today entries`() {
        val day1 = clockAt("2026-01-01T12:00:00Z")
        val tracker = AsyncUsageTracker(
            fakeRepo,
            UsageLimitConfig(perDayRequests = 100, perDayTokens = 0, perDayCostUsd = 0.0),
            TokenCostConfig(), logger, metrics, clock = day1
        )
        tracker.start()
        try {
            // Два клиента с ненулевым count — активные, не должны уходить.
            tracker.accountFor("active-1", 0, 0, null)
            tracker.accountFor("active-2", 0, 0, null)
            // Искусственно создаём stale-клиента с нулевым count через отражение:
            // в production rolloverIfNeeded сам сбрасывает count при смене дня,
            // поэтому проверяем что с утёкшим bucket и count=0 prune вычистит.
            val drf = tracker::class.java.getDeclaredField("dailyRequests")
            drf.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val dr = drf.get(tracker) as MutableMap<String, Any>
            val ctor = Class.forName(
                "com.jarvis.server.usage.AsyncUsageTracker\$DailyCounter"
            ).getDeclaredConstructor(Long::class.javaPrimitiveType)
            ctor.isAccessible = true
            val stale = ctor.newInstance(0L) // bucket=0 (1970-01-01)
            dr["stale"] = stale

            assertTrue("stale entry should exist before prune", tracker.mapSize("dailyRequests") >= 3)
            tracker.invokePrune()
            val after = tracker.mapSize("dailyRequests")
            assertTrue(
                "stale zero-count entry must be pruned; size after=$after (expected 2)",
                after == 2
            )
            assertFalse(
                "stale entry removed",
                dr.containsKey("stale")
            )
        } finally {
            tracker.shutdown()
        }
    }

    @Test
    fun `prune does not remove entries from active clients with count gt 0`() {
        val day1 = clockAt("2026-01-01T12:00:00Z")
        val tracker = AsyncUsageTracker(
            fakeRepo,
            UsageLimitConfig(perDayRequests = 100, perDayTokens = 0, perDayCostUsd = 0.0),
            TokenCostConfig(), logger, metrics, clock = day1
        )
        tracker.start()
        try {
            repeat(5) { tracker.accountFor("c-$it", 0, 0, null) }
            val before = tracker.mapSize("dailyRequests")
            tracker.invokePrune()
            val after = tracker.mapSize("dailyRequests")
            assertTrue("Active entries must not be pruned (before=$before, after=$after)", after == before)
        } finally {
            tracker.shutdown()
        }
    }
}
