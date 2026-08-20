package com.jarvis.server.provider

/** Идентификатор провайдера. Значение попадает только в логи/usage, не клиенту. */
enum class ProviderId { GROQ, GEMINI, OPENROUTER }

/**
 * Возможности провайдера (пункт 16 ТЗ).
 *
 * Реализованы только реально используемые сейчас поля; остальные оставлены
 * как явные false, чтобы политика отбора уже сегодня могла на них опираться.
 */
data class ProviderCapabilities(
    val supportsChat: Boolean = true,
    /** Есть ли у провайдера доступ к актуальным данным из сети. */
    val supportsWeb: Boolean = false,
    val supportsStreaming: Boolean = false,
    val supportsToolCalling: Boolean = false
)

/** Нормализованный запрос к провайдеру. */
data class ProviderRequest(
    val requestId: String,
    val prompt: String,
    val systemPrompt: String,
    val maxTokens: Int,
    val temperature: Double
)

/** Классификация сбоя — определяет, можно ли ретраить и делать ли fallback. */
enum class ProviderFailureKind {
    /** Таймаут запроса. Transient → retry/fallback разрешены. */
    TIMEOUT,

    /** Сеть/соединение. Transient. */
    CONNECTION,

    /** HTTP 5xx. Transient. */
    SERVER_ERROR,

    /** HTTP 429. Transient, но ретраить этого же провайдера бессмысленно. */
    RATE_LIMITED,

    /**
     * Неверный/просроченный ключ, нет доступа (401/403).
     * PERMANENT: ретраить на каждый пользовательский запрос запрещено
     * (пункт 12 и 23 ТЗ) — провайдер выводится из ротации.
     */
    AUTH,

    /** Некорректный запрос/модель (400/404/422). PERMANENT для этого запроса. */
    BAD_REQUEST,

    /** Провайдер выключен или не сконфигурирован (нет ключа). PERMANENT. */
    NOT_CONFIGURED,

    /** Прочее. Считаем непереходящим, чтобы не зацикливаться. */
    UNKNOWN;

    /** Можно ли повторить попытку у ТОГО ЖЕ провайдера. */
    val isRetryable: Boolean
        get() = this == TIMEOUT || this == CONNECTION || this == SERVER_ERROR

    /**
     * Является ли сбой постоянным (провайдер надолго выводится из ротации).
     * Для RATE_LIMITED — временная деградация, но не «поломка конфигурации».
     */
    val isPermanent: Boolean
        get() = this == AUTH || this == NOT_CONFIGURED
}

/** Результат обращения к провайдеру. */
sealed class ProviderResult {

    data class Success(
        val text: String,
        val model: String,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val totalTokens: Long? = null
    ) : ProviderResult()

    /**
     * @param detail техническая деталь ТОЛЬКО для логов. Клиенту не уходит.
     */
    data class Failure(
        val kind: ProviderFailureKind,
        val detail: String,
        val httpStatus: Int? = null
    ) : ProviderResult()
}

/**
 * Контракт провайдера (пункт 10 ТЗ).
 *
 * Каждая реализация отвечает ТОЛЬКО за общение со своим API.
 * Никакой orchestration-логики (выбор, fallback, health) внутри быть не должно.
 */
interface AiProvider {

    val id: ProviderId

    val capabilities: ProviderCapabilities

    /** Сконфигурирован ли провайдер (есть ключ и он включён). */
    fun isConfigured(): Boolean

    suspend fun execute(request: ProviderRequest): ProviderResult
}
