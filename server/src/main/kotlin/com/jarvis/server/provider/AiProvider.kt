package com.jarvis.server.provider

/** Идентификатор провайдера. Значение попадает только в логи/usage, не клиенту. */
enum class ProviderId { GROQ, GEMINI, OPENROUTER }

/**
 * Оценочная стоимость вызова (опциональная).
 *
 * AR-02: значения приходят из конфигурации, а не выдуманы из головы.
 * Если стоимость провайдера неизвестна — все поля null, политика отбора
 * трактует это как «любая цена» и не использует в ranking.
 */
data class CostEstimate(
    /** USD за 1K input tokens, если известен. */
    val usdPer1kInput: Double? = null,
    /** USD за 1K output tokens, если известен. */
    val usdPer1kOutput: Double? = null
) {
    fun estimatedFor(inputTokens: Long, outputTokens: Long): Double? {
        val i = usdPer1kInput ?: return null
        val o = usdPer1kOutput ?: return null
        return i * inputTokens / 1000.0 + o * outputTokens / 1000.0
    }
}

/**
 * Профиль возможностей провайдера (AR-02, п.16 ТЗ).
 *
 * Ранее это был `ProviderCapabilities` с 4 булевыми флагами. Расширен до
 * профиля, в котором помимо supported-флагов присутствуют опциональные
 * метаданные о latency и стоимости. Политика выбора [ProviderSelectionPolicy]
 * НЕ жёстко захардкожена в провайдера и может принимать решения на основании
 * этого профиля.
 *
 * Правила:
 *  - [supportsStreaming] отражает реальную возможность провайдера стримить.
 *  - [p95LatencyMs] — ОЦЕНКА, только для ranking; если нет измерений — null.
 *  - [cost] — оценка из конфигурации; null означает «неизвестно».
 *  - булевы флаги соответствуют сегодняшней реальности провайдеров.
 */
data class CapabilityProfile(
    val supportsChat: Boolean = true,
    /** Есть ли у провайдера доступ к актуальным данным из сети. */
    val supportsWeb: Boolean = false,
    val supportsStreaming: Boolean = false,
    val supportsToolCalling: Boolean = false,
    /** P95 end-to-end latency в миллисекундах по последним измерениям, если известна. */
    val p95LatencyMs: Int? = null,
    /** Оценка стоимости (может отсутствовать для провайдеров без известной цены). */
    val cost: CostEstimate? = null,
    /** Идентификаторы моделей, которые провайдер может обслуживать. Пустой набор = любая. */
    val supportedModels: Set<String> = emptySet()
)

/**
 * Backwards-compatibility alias — на время миграции оставляем typealias,
 * чтобы существующие тесты и провайдеры не сломались при переименовании.
 */
@Deprecated("Renamed to CapabilityProfile (AR-02)", ReplaceWith("CapabilityProfile"))
typealias ProviderCapabilities = CapabilityProfile

/**
 * Один обмен сообщениями в истории диалога (CR-03).
 *
 * Роли унифицированы с OpenAI-совместимыми провайдерами (`system`, `user`,
 * `assistant`). Gemini и другие провайдеры делают маппинг у себя.
 */
data class ProviderMessage(
    val role: String,
    val content: String
)

/** Нормализованный запрос к провайдеру. */
data class ProviderRequest(
    val requestId: String,
    val prompt: String,
    val systemPrompt: String,
    /**
     * CR-03: История диалога (от старых к новым), БЕЗ текущего пользовательского
     * запроса ([prompt]) и БЕЗ системного промпта ([systemPrompt]). Провайдер
     * сам конструирует messages = [system, *history, {user, prompt}].
     *
     * Бюджет 32 KB на всё тело HTTP (см. ValidationConfig.maxBodyBytes) уже
     * ограничивает общий размер на входе в роутер; дополнительно провайдеры
     * режут историю при сериализации, чтобы не превысить лимит upstream.
     */
    val history: List<ProviderMessage> = emptyList(),
    /**
     * CR-16: требуются ли актуальные данные из сети (Google Search grounding
     * и т. п.). Провайдеры, поддерживающие web, используют этот флаг для
     * включения retrieval-инструмента.
     */
    val requiresWeb: Boolean = false,
    val maxTokens: Int,
    val temperature: Double,
    /**
     * CR-06: wall-clock deadline (epoch ms) до которого должен завершиться
     * ВЕСЬ orchestration (включая retries/fallbacks). Если null — менеджер
     * полагается только на per-provider requestTimeoutMs.
     */
    val deadlineEpochMs: Long? = null
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
