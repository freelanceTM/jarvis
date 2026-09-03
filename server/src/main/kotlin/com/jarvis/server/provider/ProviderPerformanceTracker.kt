package com.jarvis.server.provider

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Измеренные (не оценочные) показатели провайдера для Smart Router.
 *
 * Хранит по каждому провайдеру:
 *  - latency: EMA времени успешных вызовов (измеряется в ProviderManager);
 *  - errors: всего успехов/сбоев → success rate;
 *  - 429: количество RATE_LIMITED и его доля;
 *  - availability: по success rate (доступность в момент отбора дополнительно
 *    проверяет [ProviderHealthTracker] — circuit breaker);
 *  - cost: НЕ здесь — цены конфигурируемые (admin settings, USD за 1М токенов,
 *    см. [com.jarvis.server.admin.CostSettings]), политика получает их источником.
 *
 * Намеренно in-memory, как и [ProviderHealthTracker]: сервер single-instance;
 * между рестартами статистика копится заново — на этот период Smart Router
 * честно откатывается к статическому приоритету (cold start).
 */
class ProviderPerformanceTracker {

    private class Entry {
        val successes = AtomicLong(0)
        val failures = AtomicLong(0)
        val rateLimited = AtomicLong(0)
        val latencyEmaMs = AtomicReference<Double?>(null)
    }

    private val entries = ConcurrentHashMap<ProviderId, Entry>()

    private fun entry(id: ProviderId): Entry = entries.computeIfAbsent(id) { Entry() }

    fun recordSuccess(id: ProviderId, latencyMs: Long) {
        val e = entry(id)
        e.successes.incrementAndGet()
        // EMA: недавние измерения важнее старых; α=0.2 — сглаживание без
        // взрыва от одного медленного ответа.
        e.latencyEmaMs.updateAndGet { current ->
            if (current == null) latencyMs.toDouble()
            else current + EMA_ALPHA * (latencyMs - current)
        }
    }

    fun recordFailure(id: ProviderId, kind: ProviderFailureKind) {
        val e = entry(id)
        e.failures.incrementAndGet()
        if (kind == ProviderFailureKind.RATE_LIMITED) {
            e.rateLimited.incrementAndGet()
        }
    }

    fun snapshot(id: ProviderId): PerformanceSnapshot? {
        val e = entries[id] ?: return null
        return build(id, e)
    }

    fun snapshotAll(): Map<ProviderId, PerformanceSnapshot> =
        entries.mapValues { (id, e) -> build(id, e) }

    /** Сброс — для тестов и ручного восстановления. */
    fun reset(id: ProviderId) {
        entries.remove(id)
    }

    private fun build(id: ProviderId, e: Entry): PerformanceSnapshot {
        val successes = e.successes.get()
        val failures = e.failures.get()
        val samples = successes + failures
        val rateLimited = e.rateLimited.get()
        return PerformanceSnapshot(
            providerId = id,
            samples = samples,
            avgLatencyMs = e.latencyEmaMs.get(),
            successRate = if (samples == 0L) 0.0 else successes.toDouble() / samples,
            rateLimitedCount = rateLimited,
            rateLimitedShare = if (samples == 0L) 0.0 else rateLimited.toDouble() / samples
        )
    }

    data class PerformanceSnapshot(
        val providerId: ProviderId,
        /** Успехи + сбои: объём измерений. */
        val samples: Long,
        /** EMA латентности успешных вызовов; null = ещё не измерялась. */
        val avgLatencyMs: Double?,
        /** successes / (successes + failures). */
        val successRate: Double,
        val rateLimitedCount: Long,
        /** rateLimited / samples. */
        val rateLimitedShare: Double
    )

    companion object {
        /**
         * Минимум измерений, после которого показатели провайдера влияют на
         * отбор. До этого — статический приоритет (cold start): единичный
         * замер не должен управлять роутингом.
         */
        const val MIN_SAMPLES = 5L

        /** Коэффициент EMA латентности. */
        const val EMA_ALPHA = 0.2
    }
}
