package com.jarvis.server.ratelimit

import com.jarvis.server.config.RateLimitConfig
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * M-10: in-memory rate limiter ИСКЛЮЧИТЕЛЬНО для изолированных unit/integration
 * тестов. Перенесён из main sourceSet, т.к. production всегда использует
 * PostgresRateLimiter (shared-state между инстансами), а держать этот класс
 * в main вводило в заблуждение о наличии второй production-реализации.
 *
 * Скользящее окно на две шкалы (perMinute/perDay) на основе ArrayDeque.
 * Не потокобезопасен на уровне JVM при строгой параллели (synchronized
 * на per-client окне достаточен для тестовых сценариев).
 */
class SlidingWindowRateLimiter(
    private val config: RateLimitConfig,
    private val clock: () -> Long = System::currentTimeMillis
) : RateLimiter {
    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }

    private class ClientWindow {
        val minuteHits = ArrayDeque<Long>()
        val dayHits = ArrayDeque<Long>()
    }

    private val windows = ConcurrentHashMap<String, ClientWindow>()

    override fun check(clientId: String): RateLimitDecision {
        val window = windows.computeIfAbsent(clientId) { ClientWindow() }
        val now = clock()

        synchronized(window) {
            prune(window.minuteHits, now, MINUTE_MS)
            prune(window.dayHits, now, DAY_MS)

            if (window.minuteHits.size >= config.perMinute) {
                val oldest = window.minuteHits.peekFirst() ?: now
                val retryAfter = retryAfterSeconds(oldest + MINUTE_MS - now)
                return RateLimitDecision.Limited(retryAfter, "per_minute")
            }

            if (window.dayHits.size >= config.perDay) {
                val oldest = window.dayHits.peekFirst() ?: now
                val retryAfter = retryAfterSeconds(oldest + DAY_MS - now)
                return RateLimitDecision.Limited(retryAfter, "per_day")
            }

            window.minuteHits.addLast(now)
            window.dayHits.addLast(now)
            return RateLimitDecision.Allowed
        }
    }

    private fun prune(hits: ArrayDeque<Long>, now: Long, windowMs: Long) {
        while (hits.isNotEmpty() && now - hits.peekFirst() >= windowMs) {
            hits.pollFirst()
        }
    }

    /** HTTP Retry-After округляется вверх: 59.999 с нельзя объявлять как 59 с. */
    private fun retryAfterSeconds(remainingMs: Long): Long =
        ((remainingMs.coerceAtLeast(1L) + 999L) / 1000L).coerceAtLeast(1L)

    override fun reset(clientId: String) {
        windows.remove(clientId)
    }
}
