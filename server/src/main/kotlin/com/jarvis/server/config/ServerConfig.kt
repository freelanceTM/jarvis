package com.jarvis.server.config

import com.jarvis.server.provider.ProviderId

/**
 * Вся конфигурация сервера (пункт 36 ТЗ).
 *
 * Значения читаются из переменных окружения. Секреты НИКОГДА не хранятся
 * в коде и не попадают в git — см. server/.env.example.
 */

/** Настройки одного провайдера. */
data class ProviderConfig(
    val id: ProviderId,
    val enabled: Boolean,
    /** Меньше число — выше приоритет. */
    val priority: Int,
    val apiKey: String?,
    val model: String,
    val baseUrl: String,
    val connectTimeoutMs: Long,
    val requestTimeoutMs: Long
) {
    init {
        require(model.isNotBlank()) { "provider model must not be blank" }
        require(baseUrl.isNotBlank()) { "provider baseUrl must not be blank" }
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(requestTimeoutMs > 0) { "requestTimeoutMs must be positive" }
    }

    val hasKey: Boolean get() = !apiKey.isNullOrBlank()
}

/** Пороги circuit breaker (пункт 24 ТЗ). */
data class CircuitBreakerConfig(
    /** Сколько подряд сбоев переводят провайдера в OPEN. */
    val failureThreshold: Int = 3,
    /** Сколько держать OPEN до пробной попытки (HALF_OPEN). */
    val openCooldownMs: Long = 60_000,
    /** Сколько успехов в HALF_OPEN возвращают в HEALTHY. */
    val halfOpenSuccessesToClose: Int = 1
) {
    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(openCooldownMs >= 0) { "openCooldownMs must be non-negative" }
        require(halfOpenSuccessesToClose > 0) { "halfOpenSuccessesToClose must be positive" }
    }
}

/** Лимиты запросов (пункт 8 ТЗ). */
data class RateLimitConfig(
    val perMinute: Int = 20,
    val perDay: Int = 500
) {
    init {
        require(perMinute >= 0) { "perMinute must be non-negative" }
        require(perDay >= 0) { "perDay must be non-negative" }
    }
}

/** Политика fallback и retry (пункты 14 и 23 ТЗ). */
data class ExecutionPolicyConfig(
    /** Максимум провайдеров, которые будут опробованы за один запрос. */
    val maxProviderAttempts: Int = 3,
    /** Максимум повторов у ОДНОГО провайдера при transient-сбое. */
    val maxRetriesPerProvider: Int = 1,
    val retryBackoffMs: Long = 250
) {
    init {
        require(maxProviderAttempts >= 0) { "maxProviderAttempts must be non-negative" }
        require(maxRetriesPerProvider >= 0) { "maxRetriesPerProvider must be non-negative" }
        require(retryBackoffMs >= 0) { "retryBackoffMs must be non-negative" }
    }
}

/** Валидация входа (пункт 30 ТЗ). */
data class ValidationConfig(
    val maxTextLength: Int = 8_000,
    val maxBodyBytes: Long = 32 * 1024
) {
    init {
        require(maxTextLength > 0) { "maxTextLength must be positive" }
        require(maxBodyBytes in 1..(10L * 1024 * 1024)) {
            "maxBodyBytes must be in 1..10 MiB"
        }
    }
}

/**
 * Политика приватности (пункт 19 ТЗ).
 *
 * По умолчанию в облако выпускается ТОЛЬКО `NORMAL`. Это соответствует
 * поведению Android-клиента, где PRIVATE/SENSITIVE не уходят в cloud без
 * явного разрешения. Сервер — вторая линия защиты: даже если клиент
 * ошибётся, сервер откажет.
 */
data class PrivacyPolicyConfig(
    val allowPrivate: Boolean = false,
    val allowSensitive: Boolean = false
)

data class AiGenerationConfig(
    val maxTokens: Int = 512,
    val temperature: Double = 0.6,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) {
    init {
        require(maxTokens > 0) { "maxTokens must be positive" }
        require(temperature.isFinite() && temperature in 0.0..2.0) {
            "temperature must be finite and in 0.0..2.0"
        }
        require(systemPrompt.isNotBlank()) { "systemPrompt must not be blank" }
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "Ты JARVIS — персональный голосовой AI-ассистент. Отвечай кратко и по существу: " +
                "1-3 предложения. Без markdown, списков и спецсимволов — ответ будет озвучен вслух."
    }
}

data class ServerConfig(
    val port: Int,
    val providers: List<ProviderConfig>,
    val rateLimit: RateLimitConfig,
    val circuitBreaker: CircuitBreakerConfig,
    val executionPolicy: ExecutionPolicyConfig,
    val validation: ValidationConfig,
    val privacy: PrivacyPolicyConfig,
    val generation: AiGenerationConfig,
    /**
     * Токены клиентов: token → clientId.
     * В проде заменяется на БД/Secret Manager (см. AuthService).
     */
    val staticClientTokens: Map<String, String>
) {
    init {
        require(port in 1..65_535) { "port must be in 1..65535" }
        require(staticClientTokens.keys.none { it.isBlank() }) { "client token must not be blank" }
        require(
            staticClientTokens.keys.all { token ->
                token.length in 32..256 && token.none { it.isWhitespace() || it.isISOControl() }
            }
        ) {
            "client tokens must contain 32..256 characters without whitespace"
        }
        require(staticClientTokens.values.all { it.isNotBlank() && it.length <= 128 }) {
            "clientId must contain 1..128 characters"
        }
    }

    companion object {

        fun fromEnv(env: (String) -> String? = System::getenv): ServerConfig {
            fun str(key: String): String? = env(key)?.takeIf { it.isNotBlank() }
            fun int(key: String, def: Int) = str(key)?.toIntOrNull() ?: def
            fun long(key: String, def: Long) = str(key)?.toLongOrNull() ?: def
            fun bool(key: String, def: Boolean) = str(key)?.lowercase()?.let {
                it == "true" || it == "1" || it == "yes"
            } ?: def

            val providers = listOf(
                ProviderConfig(
                    id = ProviderId.GROQ,
                    enabled = bool("GROQ_ENABLED", true),
                    priority = int("GROQ_PRIORITY", 1),
                    apiKey = str("GROQ_API_KEY"),
                    model = str("GROQ_MODEL") ?: "llama-3.3-70b-versatile",
                    baseUrl = str("GROQ_BASE_URL") ?: "https://api.groq.com/openai/v1/chat/completions",
                    connectTimeoutMs = long("GROQ_CONNECT_TIMEOUT_MS", 3_000),
                    requestTimeoutMs = long("GROQ_REQUEST_TIMEOUT_MS", 15_000)
                ),
                ProviderConfig(
                    id = ProviderId.GEMINI,
                    enabled = bool("GEMINI_ENABLED", true),
                    priority = int("GEMINI_PRIORITY", 2),
                    apiKey = str("GEMINI_API_KEY"),
                    model = str("GEMINI_MODEL") ?: "gemini-1.5-flash",
                    baseUrl = str("GEMINI_BASE_URL")
                        ?: "https://generativelanguage.googleapis.com/v1beta/models",
                    connectTimeoutMs = long("GEMINI_CONNECT_TIMEOUT_MS", 3_000),
                    requestTimeoutMs = long("GEMINI_REQUEST_TIMEOUT_MS", 20_000)
                ),
                ProviderConfig(
                    id = ProviderId.OPENROUTER,
                    enabled = bool("OPENROUTER_ENABLED", true),
                    priority = int("OPENROUTER_PRIORITY", 3),
                    apiKey = str("OPENROUTER_API_KEY"),
                    model = str("OPENROUTER_MODEL") ?: "meta-llama/llama-3.3-70b-instruct",
                    baseUrl = str("OPENROUTER_BASE_URL") ?: "https://openrouter.ai/api/v1/chat/completions",
                    connectTimeoutMs = long("OPENROUTER_CONNECT_TIMEOUT_MS", 3_000),
                    requestTimeoutMs = long("OPENROUTER_REQUEST_TIMEOUT_MS", 25_000)
                )
            )

            // Формат: "token1:clientA,token2:clientB"
            val tokenPairs = str("JARVIS_CLIENT_TOKENS")
                ?.split(",")
                ?.mapNotNull { entry ->
                    val parts = entry.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                        parts[0].trim() to parts[1].trim()
                    } else {
                        null
                    }
                }
                ?: emptyList()
            require(tokenPairs.map { it.first }.distinct().size == tokenPairs.size) {
                "duplicate client token in JARVIS_CLIENT_TOKENS"
            }
            val tokens = tokenPairs.toMap()

            return ServerConfig(
                port = int("PORT", 8080),
                providers = providers,
                rateLimit = RateLimitConfig(
                    perMinute = int("RATE_LIMIT_PER_MINUTE", 20),
                    perDay = int("RATE_LIMIT_PER_DAY", 500)
                ),
                circuitBreaker = CircuitBreakerConfig(
                    failureThreshold = int("CB_FAILURE_THRESHOLD", 3),
                    openCooldownMs = long("CB_OPEN_COOLDOWN_MS", 60_000),
                    halfOpenSuccessesToClose = int("CB_HALF_OPEN_SUCCESSES", 1)
                ),
                executionPolicy = ExecutionPolicyConfig(
                    maxProviderAttempts = int("MAX_PROVIDER_ATTEMPTS", 3),
                    maxRetriesPerProvider = int("MAX_RETRIES_PER_PROVIDER", 1),
                    retryBackoffMs = long("RETRY_BACKOFF_MS", 250)
                ),
                validation = ValidationConfig(
                    maxTextLength = int("MAX_TEXT_LENGTH", 8_000),
                    maxBodyBytes = long("MAX_BODY_BYTES", 32 * 1024)
                ),
                privacy = PrivacyPolicyConfig(
                    allowPrivate = bool("ALLOW_PRIVATE_CLOUD", false),
                    allowSensitive = bool("ALLOW_SENSITIVE_CLOUD", false)
                ),
                generation = AiGenerationConfig(
                    maxTokens = int("AI_MAX_TOKENS", 512),
                    temperature = str("AI_TEMPERATURE")?.toDoubleOrNull() ?: 0.6
                ),
                staticClientTokens = tokens
            )
        }
    }
}
