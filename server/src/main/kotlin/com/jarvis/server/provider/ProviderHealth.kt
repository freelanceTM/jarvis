package com.jarvis.server.provider

import com.jarvis.server.config.CircuitBreakerConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Состояние здоровья провайдера (пункт 12 ТЗ). */
enum class HealthStatus { HEALTHY, DEGRADED, UNAVAILABLE }

/** Состояние circuit breaker (пункт 24 ТЗ). */
enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

/**
 * Health + circuit breaker одного провайдера.
 *
 * Намеренно простая in-memory реализация: сервер пока single-instance,
 * распределённый breaker был бы преждевременным усложнением (пункт 24 ТЗ).
 * Потокобезопасность — через атомарные примитивы.
 *
 * ```
 * CLOSED --(N подряд сбоев)--> OPEN --(cooldown)--> HALF_OPEN --(успех)--> CLOSED
 *                                                        └--(сбой)--> OPEN
 * ```
 *
 * Отдельно обрабатывается PERMANENT-сбой (неверный ключ, провайдер не
 * сконфигурирован): такой провайдер сразу выводится из ротации, чтобы не
 * долбить его на каждом пользовательском запросе.
 */
class ProviderHealthTracker(
    private val config: CircuitBreakerConfig,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Entry {
        val consecutiveFailures = AtomicInteger(0)
        val halfOpenSuccesses = AtomicInteger(0)
        val halfOpenProbeInFlight = AtomicBoolean(false)
        val openedAtMs = AtomicLong(0)
        val state = AtomicReference(CircuitState.CLOSED)
        val permanentlyDisabled = AtomicReference<String?>(null)
        val totalFailures = AtomicLong(0)
        val totalSuccesses = AtomicLong(0)
    }

    private val entries = ConcurrentHashMap<ProviderId, Entry>()

    private fun entry(id: ProviderId): Entry = entries.computeIfAbsent(id) { Entry() }

    /**
     * Может ли провайдер быть кандидатом для нового запроса.
     *
     * Проверка намеренно не меняет состояние: раньше каждый вызов после
     * cooldown переводил OPEN в HALF_OPEN прямо во время сортировки кандидатов.
     * В результате несколько конкурентных запросов одновременно проходили как
     * «единственная проба», а кандидаты за пределами maxProviderAttempts могли
     * навсегда остаться в HALF_OPEN, так и не будучи вызванными.
     */
    fun isAvailable(id: ProviderId): Boolean {
        val e = entry(id)
        if (e.permanentlyDisabled.get() != null) return false

        return when (e.state.get()) {
            CircuitState.CLOSED -> true
            CircuitState.HALF_OPEN -> !e.halfOpenProbeInFlight.get()
            CircuitState.OPEN -> clock() - e.openedAtMs.get() >= config.openCooldownMs
        }
    }

    /**
     * Атомарно резервирует право на вызов провайдера.
     *
     * CLOSED допускает обычный параллелизм. После cooldown ровно один поток
     * выигрывает CAS OPEN -> HALF_OPEN; остальные не создают probe storm.
     */
    fun tryAcquire(id: ProviderId): Boolean {
        val e = entry(id)
        if (e.permanentlyDisabled.get() != null) return false

        return when (e.state.get()) {
            CircuitState.CLOSED -> true
            CircuitState.HALF_OPEN -> e.halfOpenProbeInFlight.compareAndSet(false, true)
            CircuitState.OPEN -> {
                val elapsed = clock() - e.openedAtMs.get()
                if (elapsed < config.openCooldownMs) {
                    false
                } else if (!e.halfOpenProbeInFlight.compareAndSet(false, true)) {
                    false
                } else if (e.state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                    e.halfOpenSuccesses.set(0)
                    true
                } else {
                    e.halfOpenProbeInFlight.set(false)
                    false
                }
            }
        }
    }

    fun status(id: ProviderId): HealthStatus {
        val e = entry(id)
        if (e.permanentlyDisabled.get() != null) return HealthStatus.UNAVAILABLE
        return when (e.state.get()) {
            CircuitState.OPEN -> HealthStatus.UNAVAILABLE
            CircuitState.HALF_OPEN -> HealthStatus.DEGRADED
            else ->
                if (e.consecutiveFailures.get() > 0) HealthStatus.DEGRADED else HealthStatus.HEALTHY
        }
    }

    fun circuitState(id: ProviderId): CircuitState = entry(id).state.get()

    fun recordSuccess(id: ProviderId) {
        val e = entry(id)
        e.totalSuccesses.incrementAndGet()
        e.consecutiveFailures.set(0)

        // Успех активного запроса доказывает доступность провайдера. Это также
        // закрывает OPEN, который мог быть открыт конкурентным сбоем или
        // первой неудачной retry-попыткой того же запроса.
        if (e.permanentlyDisabled.get() == null) {
            if (e.state.get() == CircuitState.HALF_OPEN) {
                val ok = e.halfOpenSuccesses.incrementAndGet()
                if (ok >= config.halfOpenSuccessesToClose.coerceAtLeast(1)) {
                    e.state.set(CircuitState.CLOSED)
                    e.halfOpenSuccesses.set(0)
                }
                e.halfOpenProbeInFlight.set(false)
            } else {
                e.state.set(CircuitState.CLOSED)
                e.halfOpenSuccesses.set(0)
                e.halfOpenProbeInFlight.set(false)
            }
        }
    }

    fun recordFailure(id: ProviderId, kind: ProviderFailureKind, detail: String) {
        val e = entry(id)
        e.totalFailures.incrementAndGet()

        // Постоянный сбой: ключ неверный / провайдер не настроен.
        // Бессмысленно ретраить — выводим из ротации до перезапуска/переконфига.
        if (kind.isPermanent) {
            e.permanentlyDisabled.set("${kind.name}: $detail")
            e.state.set(CircuitState.OPEN)
            e.halfOpenProbeInFlight.set(false)
            e.openedAtMs.set(clock())
            return
        }

        // Пробная попытка в HALF_OPEN не удалась — снова открываем.
        if (e.state.get() == CircuitState.HALF_OPEN) {
            e.state.set(CircuitState.OPEN)
            e.openedAtMs.set(clock())
            e.halfOpenSuccesses.set(0)
            e.halfOpenProbeInFlight.set(false)
            return
        }

        val failures = e.consecutiveFailures.incrementAndGet()
        if (failures >= config.failureThreshold) {
            e.state.set(CircuitState.OPEN)
            e.halfOpenProbeInFlight.set(false)
            e.openedAtMs.set(clock())
        }
    }

    /** Причина постоянного отключения (для логов/диагностики), либо null. */
    fun permanentReason(id: ProviderId): String? = entry(id).permanentlyDisabled.get()

    /** Сброс — для тестов и ручного восстановления после переконфигурации. */
    fun reset(id: ProviderId) {
        entries.remove(id)
    }

    fun snapshot(): Map<ProviderId, HealthSnapshot> =
        entries.mapValues { (id, e) ->
            HealthSnapshot(
                status = status(id),
                circuitState = e.state.get(),
                consecutiveFailures = e.consecutiveFailures.get(),
                totalSuccesses = e.totalSuccesses.get(),
                totalFailures = e.totalFailures.get(),
                permanentReason = e.permanentlyDisabled.get()
            )
        }
}

data class HealthSnapshot(
    val status: HealthStatus,
    val circuitState: CircuitState,
    val consecutiveFailures: Int,
    val totalSuccesses: Long,
    val totalFailures: Long,
    val permanentReason: String?
)
