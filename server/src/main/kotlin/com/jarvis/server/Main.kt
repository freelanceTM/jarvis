package com.jarvis.server

import com.jarvis.server.auth.ClientTier
import com.jarvis.server.auth.CompositeAuthenticator
import com.jarvis.server.auth.LicenseTokenAuthenticator
import com.jarvis.server.auth.TierAuthorizer
import com.jarvis.server.auth.TokenAuthenticator
import com.jarvis.server.billing.BillingService
import com.jarvis.server.billing.HeleketBillingProvider
import com.jarvis.server.billing.HeleketWebhookVerifier
import com.jarvis.server.billing.JdbcBillingRepository
import com.jarvis.server.billing.PaddleBillingProvider
import com.jarvis.server.billing.PaddleWebhookVerifier
import com.jarvis.server.config.ServerConfig
import com.jarvis.server.http.HttpRequestContext
import com.jarvis.server.http.JarvisApiHandler
import com.jarvis.server.http.LicenseBillingHttpHandler
import com.jarvis.server.http.ProxyRequestSecurity
import com.jarvis.server.http.RequestOriginResult
import com.jarvis.server.license.JdbcLicenseRepository
import com.jarvis.server.license.LicenseCrypto
import com.jarvis.server.license.LicenseService
import com.jarvis.server.observability.ConsoleStructuredLogger
import com.jarvis.server.persistence.DatabaseFactory
import com.jarvis.server.persistence.DatabaseMigrator
import com.jarvis.server.persistence.PostgresSingleInstanceGuard
import com.jarvis.server.observability.Metrics
import com.jarvis.server.provider.GeminiProvider
import com.jarvis.server.provider.GroqProvider
import com.jarvis.server.provider.OkHttpTransport
import com.jarvis.server.provider.OpenRouterProvider
import com.jarvis.server.provider.ProviderHealthTracker
import com.jarvis.server.provider.ProviderManager
import com.jarvis.server.provider.ProviderSelectionPolicy
import com.jarvis.server.ratelimit.PostgresRateLimiter
import com.jarvis.server.router.AiRouter
import com.jarvis.server.usage.JdbcUsageRepository
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Composition root JARVIS API.
 *
 * HTTP-слой — встроенный в JDK `com.sun.net.httpserver`. Это осознанный выбор:
 * сервису нужен один POST-эндпоинт, а добавление Ktor/Spring притащило бы
 * десятки транзитивных зависимостей ради того же результата. Вся логика
 * лежит в [JarvisApiHandler], поэтому переезд на Ktor — это замена ~40 строк
 * транспортного кода.
 */
object ServerBootstrap {

    fun buildHandler(config: ServerConfig): JarvisApiHandler {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = false
        }

        val logger = ConsoleStructuredLogger()
        val metrics = Metrics()
        val transport = OkHttpTransport()

        val configsById = config.providers.associateBy { it.id }

        val providers = config.providers.map { providerConfig ->
            when (providerConfig.id) {
                com.jarvis.server.provider.ProviderId.GROQ ->
                    GroqProvider(providerConfig, transport, json)
                com.jarvis.server.provider.ProviderId.GEMINI ->
                    GeminiProvider(providerConfig, transport, json)
                com.jarvis.server.provider.ProviderId.OPENROUTER ->
                    OpenRouterProvider(providerConfig, transport, json)
            }
        }

        val health = ProviderHealthTracker(config.circuitBreaker)
        val selectionPolicy = ProviderSelectionPolicy(configsById, health)

        val providerManager = ProviderManager(
            providers = providers,
            configs = configsById,
            health = health,
            policy = config.executionPolicy,
            selectionPolicy = selectionPolicy,
            logger = logger,
            metrics = metrics
        )

        val licenseConfig = requireNotNull(config.licenseSubsystem) {
            "Persistent license subsystem is required; configure DATABASE_URL, LICENSE_CODE_PEPPER and BILLING_PLANS"
        }
        val dataSource = DatabaseFactory.create(licenseConfig.database)
        val instanceGuard = try {
            DatabaseMigrator(dataSource).migrate()
            if (config.deployment.isProduction) {
                PostgresSingleInstanceGuard.acquire(dataSource)
            } else {
                null
            }
        } catch (failure: Throwable) {
            dataSource.close()
            throw failure
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            instanceGuard?.close()
            dataSource.close()
        })

        val usageRepository = JdbcUsageRepository(dataSource)

        val router = AiRouter(
            providerManager = providerManager,
            usageRepository = usageRepository,
            validation = config.validation,
            privacyPolicy = config.privacy,
            generation = config.generation,
            logger = logger,
            metrics = metrics
        )

        val licenseCrypto = LicenseCrypto(licenseConfig.codePepper)
        val licenseRepository = JdbcLicenseRepository(dataSource, licenseCrypto)
        val licenseService = LicenseService(licenseRepository, licenseCrypto)
        licenseConfig.plans.forEach(licenseService::upsertPlan)

        val billingRepository = JdbcBillingRepository(dataSource)
        val billingProviders = listOf(
            PaddleBillingProvider(licenseConfig.paddle, transport, json),
            HeleketBillingProvider(licenseConfig.heleket, transport, json)
        )
        val billingService = BillingService(
            billingRepository = billingRepository,
            licenseRepository = licenseRepository,
            providers = billingProviders
        )

        // ADMIN-права выдаются только явно перечисленным статическим клиентам.
        val adminClients = (System.getenv("JARVIS_ADMIN_CLIENTS") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val staticAuthenticator = TokenAuthenticator(config.staticClientTokens) { clientId ->
            if (clientId in adminClients) ClientTier.ADMIN else ClientTier.FREE
        }
        val authenticator = CompositeAuthenticator(
            staticAuthenticator,
            LicenseTokenAuthenticator(licenseService)
        )
        val authorizer = TierAuthorizer()
        val licenseHttpHandler = LicenseBillingHttpHandler(
            authenticator = authenticator,
            authorizer = authorizer,
            licenseService = licenseService,
            billingService = billingService,
            paddleWebhookVerifier = PaddleWebhookVerifier(licenseConfig.paddle, json),
            heleketWebhookVerifier = HeleketWebhookVerifier(licenseConfig.heleket, json),
            redeemRateLimiter = PostgresRateLimiter(
                dataSource, "license_redeem", licenseConfig.redeemRateLimit
            ),
            authenticatedRateLimiter = PostgresRateLimiter(
                dataSource, "license_auth", licenseConfig.authenticatedRateLimit
            ),
            webhookRateLimiter = PostgresRateLimiter(
                dataSource, "billing_webhook", licenseConfig.webhookRateLimit
            ),
            validation = config.validation,
            logger = logger,
            json = json
        )

        return JarvisApiHandler(
            authenticator = authenticator,
            authorizer = authorizer,
            rateLimiter = PostgresRateLimiter(dataSource, "ai_execute", config.rateLimit),
            router = router,
            validation = config.validation,
            logger = logger,
            metrics = metrics,
            json = json,
            healthProvider = {
                val snapshot = providerManager.healthSnapshot()
                buildString {
                    append("""{"status":"ok","database":"ok","providers":{""")
                    append(
                        snapshot.entries.joinToString(",") { (id, s) ->
                            """"${id.name}":{"status":"${s.status}","circuit":"${s.circuitState}"}"""
                        }
                    )
                    append("}}")
                }
            },
            metricsProvider = { renderMetrics(metrics) },
            entitlementChecker = { client ->
                client.accountId?.let(licenseService::hasActiveEntitlement) == true
            },
            extensionHandler = licenseHttpHandler::handle
        )
    }

    private fun renderMetrics(metrics: Metrics): String {
        fun render(value: Any): String = when (value) {
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) ->
                """"$k":${render(v ?: 0)}"""
            }
            is Number -> value.toString()
            else -> """"$value""""
        }
        return render(metrics.snapshot())
    }
}

fun main() {
    val config = ServerConfig.fromEnv()
    val logger = ConsoleStructuredLogger()

    if (config.staticClientTokens.isEmpty()) {
        logger.error(
            "no client tokens configured; every request will be rejected with 401",
            "hint" to "set JARVIS_CLIENT_TOKENS=token:clientId"
        )
    }

    val configuredProviders = config.providers.filter { it.enabled && it.hasKey }
    if (configuredProviders.isEmpty()) {
        logger.error(
            "no AI provider configured; all requests will fail",
            "hint" to "set GROQ_API_KEY / GEMINI_API_KEY / OPENROUTER_API_KEY"
        )
    } else {
        logger.info(
            "providers configured",
            "providers" to configuredProviders.joinToString("|") { it.id.name }
        )
    }

    val handler = ServerBootstrap.buildHandler(config)
    val proxySecurity = ProxyRequestSecurity(config.deployment)
    val server = HttpServer.create(
        InetSocketAddress(config.deployment.bindHost, config.port), 0
    )
    server.executor = Executors.newFixedThreadPool(8)

    server.createContext("/") { exchange: HttpExchange ->
        fun respond(status: Int, body: String, headers: Map<String, String> = emptyMap()) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (key, value) -> exchange.responseHeaders.set(key, value) }
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }

        try {
            val requestHeaders = exchange.requestHeaders.entries.associate { (name, values) ->
                name to values.joinToString(",")
            }
            when (val origin = proxySecurity.resolve(
                peerAddress = exchange.remoteAddress.address.hostAddress,
                path = exchange.requestURI.path,
                headers = requestHeaders
            )) {
                is RequestOriginResult.Rejected -> respond(
                    origin.status,
                    """{"success":false,"error":{"code":"${origin.code}",""" +
                        """"message":"Secure transport required","requestId":"-"}}""",
                    mapOf("Cache-Control" to "no-store")
                )
                is RequestOriginResult.Accepted -> {
                    val maxBody = config.validation.maxBodyBytes
                    val rawBody = exchange.requestBody.readNBytes((maxBody + 1).toInt())
                    val declaredLength = exchange.requestHeaders.getFirst("Content-Length")
                        ?.toLongOrNull() ?: rawBody.size.toLong()
                    val response = runBlocking {
                        handler.handle(
                            HttpRequestContext(
                                method = exchange.requestMethod,
                                path = exchange.requestURI.path,
                                authorizationHeader = exchange.requestHeaders.getFirst("Authorization"),
                                body = String(rawBody, StandardCharsets.UTF_8),
                                contentLength = maxOf(declaredLength, rawBody.size.toLong()),
                                headers = requestHeaders,
                                remoteAddress = origin.origin.clientAddress,
                                scheme = origin.origin.scheme,
                                host = origin.origin.host,
                                viaTrustedProxy = origin.origin.viaTrustedProxy
                            )
                        )
                    }
                    respond(response.status, response.body, response.headers)
                }
            }
        } catch (e: Throwable) {
            // Наружу — никаких деталей исключения или заголовков запроса.
            logger.error("unhandled request failure", "error" to e.javaClass.simpleName)
            runCatching {
                respond(
                    500,
                    """{"success":false,"error":{"code":"INTERNAL_ERROR",""" +
                        """"message":"Internal server error","requestId":"-"}}"""
                )
            }
        } finally {
            exchange.close()
        }
    }

    logger.info(
        "JARVIS API started",
        "environment" to config.deployment.environment.name,
        "bindHost" to config.deployment.bindHost,
        "port" to config.port.toString()
    )
    server.start()
}
