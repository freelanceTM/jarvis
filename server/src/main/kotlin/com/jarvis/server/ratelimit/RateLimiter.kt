package com.jarvis.server.ratelimit

/**
 * Публичный контракт rate-limiter'а (пункт 8 ТЗ).
 *
 * Production реализация — [com.jarvis.server.ratelimit.PostgresRateLimiter]
 * (shared-state между несколькими инстансами сервера).
 *
 * In-memory реализация [SlidingWindowRateLimiter] перенесена в test sourceSet
 * (M-10): она не является production security boundary и использовалась
 * только изолированными unit/integration тестами. Держать её в main
 * означало вводить в заблуждение читателя о наличии двух конкурентных
 * реализаций в production.
 */

/** Решение лимитера. */
sealed class RateLimitDecision {
    data object Allowed : RateLimitDecision()

    /** @param retryAfterSeconds значение для заголовка `Retry-After`. */
    data class Limited(val retryAfterSeconds: Long, val scope: String) : RateLimitDecision()
}

interface RateLimiter {
    fun check(clientId: String): RateLimitDecision
    fun reset(clientId: String)
}
