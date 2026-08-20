package com.jarvis.server.observability

import com.jarvis.server.provider.ProviderFailureKind
import com.jarvis.server.provider.ProviderId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Структурированное логирование (пункт 21 ТЗ).
 *
 * Пишет строки вида `level=INFO msg="..." key=value key=value`.
 *
 * ЗАПРЕЩЕНО логировать: API-ключи, Authorization-заголовки, полный текст
 * приватных промптов. Для этого есть [redact] и правило: в лог уходит длина
 * текста, а не сам текст.
 */
interface StructuredLogger {
    fun info(message: String, vararg fields: Pair<String, String>)
    fun warn(message: String, vararg fields: Pair<String, String>)
    fun error(message: String, vararg fields: Pair<String, String>)
}

class ConsoleStructuredLogger(
    private val sink: (String) -> Unit = ::println,
    private val clock: () -> Long = System::currentTimeMillis
) : StructuredLogger {

    override fun info(message: String, vararg fields: Pair<String, String>) =
        log("INFO", message, fields)

    override fun warn(message: String, vararg fields: Pair<String, String>) =
        log("WARN", message, fields)

    override fun error(message: String, vararg fields: Pair<String, String>) =
        log("ERROR", message, fields)

    private fun log(level: String, message: String, fields: Array<out Pair<String, String>>) {
        val rendered = buildString {
            append("ts=").append(clock())
            append(" level=").append(level)
            append(" msg=\"").append(message.replace("\"", "'")).append('"')
            for ((k, v) in fields) {
                append(' ').append(k).append('=')
                val safe = v.replace("\"", "'")
                if (safe.contains(' ')) append('"').append(safe).append('"') else append(safe)
            }
        }
        sink(rendered)
    }
}

/** Маскирование секретов на случай, если значение всё же попало в поле лога. */
object LogSanitizer {

    private val secretPatterns = listOf(
        Regex("""(?i)bearer\s+[A-Za-z0-9._\-]+"""),
        Regex("""sk-or-[A-Za-z0-9._\-]+"""),
        Regex("""gsk_[A-Za-z0-9._\-]+"""),
        Regex("""sk-[A-Za-z0-9._\-]{10,}"""),
        Regex("""AIza[A-Za-z0-9._\-]{10,}""")
    )

    fun redact(value: String): String =
        secretPatterns.fold(value) { acc, rx -> rx.replace(acc, "[REDACTED]") }

    /** Безопасное представление текста промпта: только длина. */
    fun describeText(text: String): String = "<${text.length} chars>"
}

/**
 * Минимальные in-memory метрики (пункт 35 ТЗ).
 *
 * Полноценный Prometheus не поднимаем — его в проекте нет, а ТЗ прямо
 * разрешает ограничиться практичным механизмом. Значения отдаются
 * через `GET /v1/admin/metrics`.
 */
class Metrics {
    val requestsTotal = AtomicLong(0)
    val requestsSuccess = AtomicLong(0)
    val requestsFailed = AtomicLong(0)
    val rateLimitedTotal = AtomicLong(0)
    val unauthorizedTotal = AtomicLong(0)
    val privacyBlockedTotal = AtomicLong(0)
    val totalTokens = AtomicLong(0)

    private val providerSuccess = ConcurrentHashMap<ProviderId, AtomicLong>()
    private val providerFailure = ConcurrentHashMap<ProviderId, AtomicLong>()
    private val providerLatencySum = ConcurrentHashMap<ProviderId, AtomicLong>()
    private val failureKinds = ConcurrentHashMap<String, AtomicLong>()

    private fun counter(map: ConcurrentHashMap<ProviderId, AtomicLong>, id: ProviderId) =
        map.computeIfAbsent(id) { AtomicLong(0) }

    fun recordRequest() = requestsTotal.incrementAndGet()
    fun recordSuccess(tokens: Long?) {
        requestsSuccess.incrementAndGet()
        tokens?.let { totalTokens.addAndGet(it) }
    }
    fun recordFailure() = requestsFailed.incrementAndGet()
    fun recordRateLimited() = rateLimitedTotal.incrementAndGet()
    fun recordUnauthorized() = unauthorizedTotal.incrementAndGet()
    fun recordPrivacyBlocked() = privacyBlockedTotal.incrementAndGet()

    fun recordProviderSuccess(id: ProviderId, latencyMs: Long) {
        counter(providerSuccess, id).incrementAndGet()
        counter(providerLatencySum, id).addAndGet(latencyMs)
    }

    fun recordProviderFailure(id: ProviderId, kind: ProviderFailureKind) {
        counter(providerFailure, id).incrementAndGet()
        failureKinds.computeIfAbsent(kind.name) { AtomicLong(0) }.incrementAndGet()
    }

    fun snapshot(): Map<String, Any> = mapOf(
        "requests_total" to requestsTotal.get(),
        "requests_success" to requestsSuccess.get(),
        "requests_failed" to requestsFailed.get(),
        "rate_limited_total" to rateLimitedTotal.get(),
        "unauthorized_total" to unauthorizedTotal.get(),
        "privacy_blocked_total" to privacyBlockedTotal.get(),
        "tokens_total" to totalTokens.get(),
        "provider_success" to providerSuccess.mapKeys { it.key.name }.mapValues { it.value.get() },
        "provider_failure" to providerFailure.mapKeys { it.key.name }.mapValues { it.value.get() },
        "provider_latency_sum_ms" to providerLatencySum.mapKeys { it.key.name }
            .mapValues { it.value.get() },
        "failure_kinds" to failureKinds.mapValues { it.value.get() }
    )
}
