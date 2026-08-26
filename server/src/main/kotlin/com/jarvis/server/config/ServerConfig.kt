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

/**
 * AR-05: лимиты usage по количеству/токенам/стоимости (дневные).
 *
 * Это — мягкий in-process precheck в дополнение к Postgres rate limiter;
 * они не являются security boundary сами по себе, но сглаживают всплески
 * между синхронизациями с БД. Значение 0 = лимит отключён.
 */
data class UsageLimitConfig(
    val perDayRequests: Int = 2000,
    val perDayTokens: Long = 1_000_000L,
    val perDayCostUsd: Double = 5.0
) {
    init {
        require(perDayRequests >= 0) { "perDayRequests must be non-negative" }
        require(perDayTokens >= 0) { "perDayTokens must be non-negative" }
        require(perDayCostUsd >= 0) { "perDayCostUsd must be non-negative" }
    }
}

/**
 * AR-05: фолбэк-стоимость для провайдеров, у которых стоимость не указана
 * в конфигурации. Используется только для perDayCost лимита.
 */
data class TokenCostConfig(
    /** USD за 1K токенов (input+output average), если у провайдера нет цены. */
    val fallbackUsdPer1k: Double? = null
) {
    init {
        require(fallbackUsdPer1k == null || fallbackUsdPer1k >= 0) { "fallbackUsdPer1k must be non-negative" }
    }
}

/** Политика fallback и retry (пункты 14 и 23 ТЗ). */
data class ExecutionPolicyConfig(
    /** Максимум провайдеров, которые будут опробованы за один запрос. */
    val maxProviderAttempts: Int = 2,
    /**
     * CR-06: повторы у одного провайдера по умолчанию отключены.
     * Worst-case бюджет: 2 провайдера × (per-provider timeout 10s) + backoff
     * ≤ ~21 секунд, что укладывается в серверный deadline 28 секунд и даёт
     * запас клиентскому callTimeout=30с. Включение retries с нестандартными
     * таймаутами провайдера может привести к тому, что клиент не дождётся
     * ответа; рекомендуется держать maxRetriesPerProvider = 0.
     */
    val maxRetriesPerProvider: Int = 0,
    val retryBackoffMs: Long = 200
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
    val usageLimits: UsageLimitConfig = UsageLimitConfig(),
    val tokenCosts: TokenCostConfig = TokenCostConfig(),
    val circuitBreaker: CircuitBreakerConfig,
    val executionPolicy: ExecutionPolicyConfig,
    val validation: ValidationConfig,
    val privacy: PrivacyPolicyConfig,
    val generation: AiGenerationConfig,
    val deployment: DeploymentSecurityConfig = DeploymentSecurityConfig(),
    /** Persistent licensing/billing; null is accepted only by isolated legacy tests. */
    val licenseSubsystem: LicenseSubsystemConfig? = null,
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
        if (deployment.isProduction) {
            require(providers.filter { it.enabled && it.hasKey }.all { it.baseUrl.startsWith("https://") }) {
                "Enabled production providers with credentials must use HTTPS"
            }
            require(!privacy.allowPrivate && !privacy.allowSensitive) {
                "Production cannot globally allow PRIVATE/SENSITIVE cloud routing without per-request consent"
            }
            licenseSubsystem?.let {
                require(it.database.maxPoolSize >= 2) {
                    "Production database pool must reserve one connection for the single-instance guard"
                }
            }
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
            fun strictBool(key: String, def: Boolean): Boolean = str(key)?.lowercase()?.let {
                when (it) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> throw IllegalArgumentException("$key must be true or false")
                }
            } ?: def

            val environment = DeploymentEnvironment.parse(str("APP_ENV") ?: "development")
            if (environment == DeploymentEnvironment.PRODUCTION) {
                require(str("BIND_HOST") != null) { "Production requires an explicit BIND_HOST" }
                require(str("APPLICATION_REPLICA_COUNT") != null) {
                    "Production requires explicit APPLICATION_REPLICA_COUNT=1"
                }
            }
            val publicBaseUrl = str("PUBLIC_BASE_URL")?.trimEnd('/')
            val deployment = DeploymentSecurityConfig(
                environment = environment,
                bindHost = str("BIND_HOST") ?: "127.0.0.1",
                publicBaseUrl = publicBaseUrl,
                tlsTerminatedByProxy = strictBool("PRODUCTION_TLS_TERMINATED", false),
                trustProxyHeaders = strictBool("TRUST_PROXY_HEADERS", false),
                trustedProxyCidrs = str("TRUSTED_PROXY_CIDRS")
                    ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
                    ?.map(IpCidr::parse) ?: emptyList(),
                applicationReplicaCount = int("APPLICATION_REPLICA_COUNT", 1)
            )

            val providers = listOf(
                ProviderConfig(
                    id = ProviderId.GROQ,
                    enabled = bool("GROQ_ENABLED", true),
                    priority = int("GROQ_PRIORITY", 1),
                    apiKey = str("GROQ_API_KEY"),
                    model = str("GROQ_MODEL") ?: "llama-3.3-70b-versatile",
                    baseUrl = str("GROQ_BASE_URL") ?: "https://api.groq.com/openai/v1/chat/completions",
                    connectTimeoutMs = long("GROQ_CONNECT_TIMEOUT_MS", 2_000),
                    // CR-06: request timeout ≤ budget/maxAttempts (28s/2 = 14s,
                    // берём 10s с запасом).
                    requestTimeoutMs = long("GROQ_REQUEST_TIMEOUT_MS", 10_000)
                ),
                ProviderConfig(
                    id = ProviderId.GEMINI,
                    enabled = bool("GEMINI_ENABLED", true),
                    priority = int("GEMINI_PRIORITY", 2),
                    apiKey = str("GEMINI_API_KEY"),
                    model = str("GEMINI_MODEL") ?: "gemini-1.5-flash",
                    baseUrl = str("GEMINI_BASE_URL")
                        ?: "https://generativelanguage.googleapis.com/v1beta/models",
                    connectTimeoutMs = long("GEMINI_CONNECT_TIMEOUT_MS", 2_000),
                    requestTimeoutMs = long("GEMINI_REQUEST_TIMEOUT_MS", 10_000)
                ),
                ProviderConfig(
                    id = ProviderId.OPENROUTER,
                    enabled = bool("OPENROUTER_ENABLED", true),
                    priority = int("OPENROUTER_PRIORITY", 3),
                    apiKey = str("OPENROUTER_API_KEY"),
                    model = str("OPENROUTER_MODEL") ?: "meta-llama/llama-3.3-70b-instruct",
                    baseUrl = str("OPENROUTER_BASE_URL") ?: "https://openrouter.ai/api/v1/chat/completions",
                    connectTimeoutMs = long("OPENROUTER_CONNECT_TIMEOUT_MS", 2_000),
                    requestTimeoutMs = long("OPENROUTER_REQUEST_TIMEOUT_MS", 10_000)
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

            val licenseSignals = listOf(
                str("DATABASE_URL"), str("LICENSE_CODE_PEPPER"), str("BILLING_PLANS")
            )
            val licenseSubsystem = if (licenseSignals.all { it == null }) {
                null
            } else {
                val databaseUrl = requireNotNull(str("DATABASE_URL")) { "DATABASE_URL is required" }
                val databaseUser = requireNotNull(str("DATABASE_USER")) { "DATABASE_USER is required" }
                val databasePassword = requireNotNull(str("DATABASE_PASSWORD")) { "DATABASE_PASSWORD is required" }
                val pepper = requireNotNull(str("LICENSE_CODE_PEPPER")) { "LICENSE_CODE_PEPPER is required" }
                val plansRaw = requireNotNull(str("BILLING_PLANS")) { "BILLING_PLANS is required" }
                val plans = plansRaw.split(';').filter { it.isNotBlank() }.map { entry ->
                    val fields = entry.split('|')
                    require(fields.size == 8) {
                        "Each BILLING_PLANS entry requires id|product|name|days|amountMinor|currency|paddlePriceId|heleketCurrency; use - for an unavailable provider"
                    }
                    com.jarvis.server.license.BillingPlan(
                        id = fields[0].trim(),
                        productId = fields[1].trim(),
                        displayName = fields[2].trim(),
                        durationDays = fields[3].trim().toInt(),
                        amountMinor = fields[4].trim().toLong(),
                        currency = fields[5].trim().uppercase(),
                        paddlePriceId = fields[6].trim().takeUnless { it == "-" || it.isEmpty() },
                        heleketCurrency = fields[7].trim().takeUnless { it == "-" || it.isEmpty() }
                    )
                }
                val paddleEnvironment = (str("PADDLE_ENVIRONMENT") ?: "sandbox").lowercase()
                require(paddleEnvironment in setOf("sandbox", "production")) {
                    "PADDLE_ENVIRONMENT must be sandbox or production"
                }
                val heleketConfigured = str("HELEKET_MERCHANT_ID") != null || str("HELEKET_API_KEY") != null
                if (heleketConfigured) require(!publicBaseUrl.isNullOrBlank()) {
                    "PUBLIC_BASE_URL is required when HELEKET is configured"
                }
                val callbackBase = publicBaseUrl ?: "https://disabled.invalid"
                LicenseSubsystemConfig(
                    database = com.jarvis.server.persistence.DatabaseConfig(
                        jdbcUrl = databaseUrl,
                        user = databaseUser,
                        password = databasePassword,
                        maxPoolSize = int("DATABASE_POOL_SIZE", 8),
                        connectionTimeoutMs = long("DATABASE_CONNECTION_TIMEOUT_MS", 5_000)
                    ),
                    codePepper = pepper,
                    plans = plans,
                    redeemRateLimit = RateLimitConfig(
                        int("LICENSE_REDEEM_PER_MINUTE", 5),
                        int("LICENSE_REDEEM_PER_DAY", 30)
                    ),
                    authenticatedRateLimit = RateLimitConfig(
                        int("LICENSE_AUTH_PER_MINUTE", 30),
                        int("LICENSE_AUTH_PER_DAY", 1_000)
                    ),
                    webhookRateLimit = RateLimitConfig(
                        int("BILLING_WEBHOOK_PER_MINUTE", 120),
                        int("BILLING_WEBHOOK_PER_DAY", 20_000)
                    ),
                    paddle = com.jarvis.server.billing.PaddleBillingConfig(
                        apiKey = str("PADDLE_API_KEY"),
                        webhookSecret = str("PADDLE_WEBHOOK_SECRET"),
                        apiBaseUrl = if (paddleEnvironment == "production") {
                            "https://api.paddle.com"
                        } else {
                            "https://sandbox-api.paddle.com"
                        },
                        requestTimeoutMs = long("PADDLE_REQUEST_TIMEOUT_MS", 15_000),
                        webhookToleranceSeconds = long("PADDLE_WEBHOOK_TOLERANCE_SECONDS", 300),
                        allowedCheckoutHosts = str("PADDLE_CHECKOUT_HOSTS")
                            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
                            ?: emptySet()
                    ),
                    heleket = com.jarvis.server.billing.HeleketBillingConfig(
                        merchantId = str("HELEKET_MERCHANT_ID"),
                        apiKey = str("HELEKET_API_KEY"),
                        callbackUrl = "$callbackBase/v1/billing/webhooks/heleket",
                        returnUrl = str("BILLING_RETURN_URL") ?: "$callbackBase/billing/return",
                        successUrl = str("BILLING_SUCCESS_URL") ?: "$callbackBase/billing/success",
                        requestTimeoutMs = long("HELEKET_REQUEST_TIMEOUT_MS", 15_000),
                        invoiceLifetimeSeconds = int("HELEKET_INVOICE_LIFETIME_SECONDS", 3_600),
                        allowedWebhookIps = (str("HELEKET_WEBHOOK_IPS") ?: "31.133.220.8")
                            .split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
                        enforceWebhookIp = bool("HELEKET_ENFORCE_WEBHOOK_IP", true)
                    )
                )
            }

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
                    // CR-06: defaults подобраны так, чтобы worst-case
                    // (2 провайдера × 10s timeout + backoff) ≤ ~21 с, что
                    // укладывается в серверный deadline 28 с.
                    maxProviderAttempts = int("MAX_PROVIDER_ATTEMPTS", 2),
                    maxRetriesPerProvider = int("MAX_RETRIES_PER_PROVIDER", 0),
                    retryBackoffMs = long("RETRY_BACKOFF_MS", 200)
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
                deployment = deployment,
                licenseSubsystem = licenseSubsystem,
                staticClientTokens = tokens
            )
        }
    }
}
