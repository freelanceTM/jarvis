package com.jarvis.server

import com.jarvis.server.auth.ClientTier
import com.jarvis.server.auth.TierAuthorizer
import com.jarvis.server.auth.TokenAuthenticator
import com.jarvis.server.config.ServerConfig
import com.jarvis.server.http.HttpRequestContext
import com.jarvis.server.http.JarvisApiHandler
import com.jarvis.server.observability.ConsoleStructuredLogger
import com.jarvis.server.observability.Metrics
import com.jarvis.server.provider.GeminiProvider
import com.jarvis.server.provider.GroqProvider
import com.jarvis.server.provider.OkHttpTransport
import com.jarvis.server.provider.OpenRouterProvider
import com.jarvis.server.provider.ProviderHealthTracker
import com.jarvis.server.provider.ProviderManager
import com.jarvis.server.provider.ProviderSelectionPolicy
import com.jarvis.server.ratelimit.SlidingWindowRateLimiter
import com.jarvis.server.router.AiRouter
import com.jarvis.server.usage.InMemoryUsageRepository
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
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

        val usageRepository = InMemoryUsageRepository()

        val router = AiRouter(
            providerManager = providerManager,
            usageRepository = usageRepository,
            validation = config.validation,
            privacyPolicy = config.privacy,
            generation = config.generation,
            logger = logger,
            metrics = metrics
        )

        // ADMIN-права выдаются только явно перечисленным клиентам.
        val adminClients = (System.getenv("JARVIS_ADMIN_CLIENTS") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        val authenticator = TokenAuthenticator(config.staticClientTokens) { clientId ->
            if (clientId in adminClients) ClientTier.ADMIN else ClientTier.FREE
        }

        return JarvisApiHandler(
            authenticator = authenticator,
            authorizer = TierAuthorizer(),
            rateLimiter = SlidingWindowRateLimiter(config.rateLimit),
            router = router,
            validation = config.validation,
            logger = logger,
            metrics = metrics,
            json = json,
            healthProvider = {
                val snapshot = providerManager.healthSnapshot()
                buildString {
                    append("""{"status":"ok","providers":{""")
                    append(
                        snapshot.entries.joinToString(",") { (id, s) ->
                            """"${id.name}":{"status":"${s.status}","circuit":"${s.circuitState}"}"""
                        }
                    )
                    append("}}")
                }
            },
            metricsProvider = { renderMetrics(metrics) }
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
    val server = HttpServer.create(InetSocketAddress("0.0.0.0", config.port), 0)
    server.executor = Executors.newFixedThreadPool(8)

    server.createContext("/") { exchange: HttpExchange ->
        try {
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
                        body = String(rawBody),
                        contentLength = maxOf(declaredLength, rawBody.size.toLong())
                    )
                )
            }

            val bytes = response.body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            response.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (e: Throwable) {
            // Наружу — никаких деталей исключения.
            logger.error("unhandled request failure", "error" to e.javaClass.simpleName)
            val body = """{"success":false,"error":{"code":"INTERNAL_ERROR",""" +
                """"message":"Internal server error","requestId":"-"}}"""
            val bytes = body.toByteArray()
            runCatching {
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(500, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        } finally {
            exchange.close()
        }
    }

    logger.info("JARVIS API started", "port" to config.port.toString())
    server.start()
}
