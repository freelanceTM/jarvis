package com.jarvis.server.ratelimit

import com.jarvis.server.config.RateLimitConfig
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/** Решение лимитера. */
sealed class RateLimitDecision {
    data object Allowed : RateLimitDecision()

    /** @param retryAfterSeconds значение для заголовка `Retry-After`. */
    data class Limited(val retryAfterSeconds: Long, val scope: String) : RateLimitDecision()
}

/**
 * Rate limiting на СЕРВЕРЕ (пункт 8 ТЗ), с привязкой к identity клиента.
 *
 * Скользящее окно на две шкалы: запросов в минуту и в сутки. Значения —
 * из конфигурации, а не выдуманные продуктовые лимиты.
 *
 * Реализация in-memory и потокобезопасная (синхронизация по записи клиента).
 * Для одного инстанса этого достаточно; при горизонтальном масштабировании
 * понадобится общий Redis — отмечено в известных ограничениях.
 */
class SlidingWindowRateLimiter(
    private val config: RateLimitConfig,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }

    private class ClientWindow {
        val minuteHits = ArrayDeque<Long>()
        val dayHits = ArrayDeque<Long>()
    }

    private val windows = ConcurrentHashMap<String, ClientWindow>()

    /**
     * Регистрирует попытку запроса.
     *
     * Вызывается ОДИН раз на запрос, до обращения к AI Router.
     */
    fun check(clientId: String): RateLimitDecision {
        val window = windows.computeIfAbsent(clientId) { ClientWindow() }
        val now = clock()

        synchronized(window) {
            prune(window.minuteHits, now, MINUTE_MS)
            prune(window.dayHits, now, DAY_MS)

            if (window.minuteHits.size >= config.perMinute) {
                val oldest = window.minuteHits.peekFirst() ?: now
                val retryAfter = ((oldest + MINUTE_MS - now) / 1000).coerceAtLeast(1)
                return RateLimitDecision.Limited(retryAfter, "per_minute")
            }

            if (window.dayHits.size >= config.perDay) {
                val oldest = window.dayHits.peekFirst() ?: now
                val retryAfter = ((oldest + DAY_MS - now) / 1000).coerceAtLeast(1)
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

    fun reset(clientId: String) {
        windows.remove(clientId)
    }
}
