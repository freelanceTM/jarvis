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
            append(" msg=\"").append(LogSanitizer.forLog(message)).append('"')
            for ((k, v) in fields) {
                append(' ').append(k).append('=')
                val safe = LogSanitizer.forLog(v)
                if (safe.contains(' ')) append('"').append(safe).append('"') else append(safe)
            }
        }
        sink(rendered)
    }
}

/** Маскирование секретов на случай, если значение всё же попало в поле лога. */
object LogSanitizer {

    private val secretPatterns = listOf(
        Regex("""(?i)bearer\s+[^\s,;]+"""),
        Regex("""sk-or-[A-Za-z0-9._\-]+"""),
        Regex("""gsk_[A-Za-z0-9._\-]+"""),
        Regex("""sk-[A-Za-z0-9._\-]{10,}"""),
        Regex("""AIza[A-Za-z0-9._\-]{10,}""")
    )

    fun redact(value: String): String =
        secretPatterns.fold(value) { acc, rx -> rx.replace(acc, "[REDACTED]") }

    /**
     * Делает значение безопасным для однострочного structured log:
     * маскирует секреты и исключает CR/LF/control-character injection.
     */
    fun forLog(value: String): String = redact(value)
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
        .map { ch -> if (ch.code < 0x20 || ch.code == 0x7f) '?' else ch }
        .joinToString("")
        .replace('"', '\'')

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

    /**
     * AR-05: универсальный инкремент именованного счётчика (для трекера usage
     * и других встроенных метрик, чтобы не раздувать Metrics отдельными
     * полями на каждый чих). Возвращает новое значение.
     */
    private val namedCounters = ConcurrentHashMap<String, AtomicLong>()
    fun increment(name: String): Long =
        namedCounters.computeIfAbsent(name) { AtomicLong(0) }.incrementAndGet()

    fun snapshot(): Map<String, Any> = buildMap {
        put("requests_total", requestsTotal.get())
        put("requests_success", requestsSuccess.get())
        put("requests_failed", requestsFailed.get())
        put("rate_limited_total", rateLimitedTotal.get())
        put("unauthorized_total", unauthorizedTotal.get())
        put("privacy_blocked_total", privacyBlockedTotal.get())
        put("tokens_total", totalTokens.get())
        put("provider_success", providerSuccess.mapKeys { it.key.name }.mapValues { it.value.get() })
        put("provider_failure", providerFailure.mapKeys { it.key.name }.mapValues { it.value.get() })
        put(
            "provider_latency_sum_ms",
            providerLatencySum.mapKeys { it.key.name }.mapValues { it.value.get() }
        )
        put("failure_kinds", failureKinds.mapValues { it.value.get() })
        // AR-05: любые именованные счетчики (usage_dropped / usage_recorded /
        // usage_failed / usage_retry и т.п.) тоже показываем в /metrics.
        namedCounters.forEach { (k, v) -> put(k, v.get()) }
    }
}
