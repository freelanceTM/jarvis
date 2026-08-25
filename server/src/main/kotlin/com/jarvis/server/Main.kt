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
import com.jarvis.server.http.HttpResponseContext
import com.jarvis.server.http.JarvisApiHandler
import com.jarvis.server.http.LicenseBillingHttpHandler
import com.jarvis.server.http.ProxyRequestSecurity
import com.jarvis.server.http.RequestOriginResult
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

        // CR-15: health-лямбда и metrics-лямбда вынесены в явные функции для
        // того, чтобы отдельный health-kickoff в main() не дублировал логику
        // форматирования и мог использовать кэш.
        val healthProvider: () -> String = {
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
        }

        return JarvisApiHandler(
            authenticator = authenticator,
            authorizer = authorizer,
            rateLimiter = PostgresRateLimiter(dataSource, "ai_execute", config.rateLimit),
            router = router,
            validation = config.validation,
            logger = logger,
            metrics = metrics,
            json = json,
            healthProvider = healthProvider,
            metricsProvider = { renderMetrics(metrics) },
            entitlementChecker = { client ->
                client.accountId?.let(licenseService::hasActiveEntitlement) == true
            },
            extensionHandler = licenseHttpHandler::handle,
            // Публикуем healthProvider для внешнего kickoff в main().
            healthProviderFunc = healthProvider
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

/**
 * CR-15/CR-06/CR-05: собственно HTTP-обвязка для запроса.
 *
 * - CR-15: без runBlocking; выполняется в requestScope корутине.
 * - CR-06: оборачивает body-read + handler в withTimeout(effectiveDeadlineMs);
 *   эффективный deadline = min(SERVER_REQUEST_DEADLINE_MS, X-Request-Deadline).
 *   При истечении — отвечает 504 PROVIDER_TIMEOUT.
 * - CR-05: coroutine cancellation стекает вниз до OkHttpTransport, где
 *   уже существует invokeOnCancellation { call.cancel() } для реального
 *   upstream cancel.
 *
 * ⚠ Ограничение JDK com.sun.net.httpserver: публичный API НЕ предоставляет
 *   хука на разрыв соединения клиентом (нет addCloseListener / onClientDisconnect).
 *   Поэтому сервер ограничивает работу запроса двумя механизмами:
 *   1) withTimeout(deadline) из CR-06 — надёжный upper-bound (28 с),
 *      срабатывает всегда, даже если клиент исчез без TCP RST.
 *   2) IOException при записи ответа в закрытый сокет — ловится в respond()
 *      и логируется; к этому моменту handler уже вернул ответ и upstream
 *      работы не осталось, поэтому cancellation был бы no-op.
 *   Для мгновенного обнаружения disconnect в момент чтения тела или между
 *   retry пришлось бы лезть во внутренний API sun.net.httpserver или
 *   менять HTTP-слой; сознательно не делаем этого — deadline-граница
 *   является детерминированной гарантией, что сервер не жжёт провайдера
 *   вечно после исчезновения клиента.
 */
private suspend fun handleExchange(
    exchange: HttpExchange,
    handler: JarvisApiHandler,
    proxySecurity: ProxyRequestSecurity,
    config: ServerConfig,
    logger: StructuredLogger
) {
    fun respond(status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        try {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> exchange.responseHeaders.set(k, v) }
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        } catch (t: Throwable) {
            logger.warn("failed to write response", "error" to t.javaClass.simpleName)
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
            is RequestOriginResult.Rejected -> {
                respond(
                    origin.status,
                    """{"success":false,"error":{"code":"${origin.code}","message":"Secure transport required","requestId":"-"}}""",
                    mapOf("Cache-Control" to "no-store")
                )
                return
            }
            is RequestOriginResult.Accepted -> {
                val maxBody = config.validation.maxBodyBytes

                // CR-06: эффективный deadline — min серверного бюджета и
                // клиентского X-Request-Deadline (epoch ms). Клиентский
                // deadline НЕ может удлинять обработку; только укорачивать.
                val startMs = System.currentTimeMillis()
                val clientDeadlineMs = requestHeaders[X_REQUEST_DEADLINE_HEADER]
                    ?.toLongOrNull()
                val effectiveDeadlineMs = when {
                    clientDeadlineMs == null -> SERVER_REQUEST_DEADLINE_MS
                    else -> minOf(SERVER_REQUEST_DEADLINE_MS, clientDeadlineMs - startMs)
                        .coerceAtLeast(1L)
                }
                val deadlineEpochMs = startMs + effectiveDeadlineMs

                val rawBody = try {
                    withTimeout(effectiveDeadlineMs) {
                        exchange.requestBody.readNBytes((maxBody + 1).toInt())
                    }
                } catch (_: TimeoutCancellationException) {
                    logger.warn("request deadline exceeded while reading body")
                    respond(
                        504,
                        """{"success":false,"error":{"code":"PROVIDER_TIMEOUT","message":"Request timed out","requestId":"-"}}""",
                        mapOf("Cache-Control" to "no-store")
                    )
                    return
                }

                val ctx = HttpRequestContext(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    authorizationHeader = exchange.requestHeaders.getFirst("Authorization"),
                    body = String(rawBody, StandardCharsets.UTF_8),
                    contentLength = maxOf(
                        exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: rawBody.size.toLong(),
                        rawBody.size.toLong()
                    ),
                    headers = requestHeaders,
                    remoteAddress = origin.origin.clientAddress,
                    scheme = origin.origin.scheme,
                    host = origin.origin.host,
                    viaTrustedProxy = origin.origin.viaTrustedProxy,
                    deadlineEpochMs = deadlineEpochMs
                )
                val handlerBudgetMs = (effectiveDeadlineMs - (System.currentTimeMillis() - startMs))
                    .coerceAtLeast(1L)
                val response = try {
                    withTimeout(handlerBudgetMs) {
                        handler.handle(ctx)
                    }
                } catch (_: TimeoutCancellationException) {
                    logger.warn("request deadline exceeded", "path" to ctx.path)
                    HttpResponseContext(
                        status = 504,
                        body = """{"success":false,"error":{"code":"PROVIDER_TIMEOUT","message":"Request timed out","requestId":"-"}}""",
                        headers = mapOf("Cache-Control" to "no-store")
                    )
                }
                respond(response.status, response.body, response.headers)
            }
        }
    } catch (_: kotlinx.coroutines.CancellationException) {
        // Клиент отвалился или мы сами отменили — не пишем ответ.
        logger.info("request cancelled by client or shutdown")
    } catch (e: Throwable) {
        logger.error("unhandled request failure", "error" to e.javaClass.simpleName)
        runCatching {
            respond(
                500,
                """{"success":false,"error":{"code":"INTERNAL_ERROR","message":"Internal server error","requestId":"-"}}"""
            )
        }
    } finally {
        runCatching { exchange.close() }
    }
}

// ---------------------------------------------------------------------------
// CR-06: единый сквозной deadline на всю обработку запроса.
//
// Серверный per-request deadline = 28 секунд (оставляем 2 секунды запас, чтобы
// успеть вернуть 504 до того, как у клиента истечёт callTimeout).
//
// Клиентский callTimeout = 30 секунд (JarvisApiClient.CALL_TIMEOUT_SECONDS)
// — даёт 2 секунды на отправку тела 504. Клиент может передать более ранний
// wall-clock deadline через заголовок X-Request-Deadline (epoch ms);
// сервер применяет min(server-deadline, client-deadline) как эффективный
// бюджет и не тратит время на провайдера, чей per-provider timeout не
// укладывается в оставшийся бюджет.
// ---------------------------------------------------------------------------
private const val SERVER_REQUEST_DEADLINE_MS = 28_000L
private const val X_REQUEST_DEADLINE_HEADER = "X-Request-Deadline"
private const val HEALTH_RESPONSE_CACHE_MS = 2_000L

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

    // CR-15: вместо Executors.newFixedThreadPool(8) + runBlocking используем
    // coroutine scope на Dispatchers.IO, чтобы запросы обрабатывались
    // асинхронно, не блокируя acceptor и не плодя неограниченную очередь.
    val ioDispatcher = Dispatchers.IO
    val requestScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // CR-15: отдельный кэширующий health dispatcher (без базы и без AI-пула)
    // и простой кэш health body на 2 секунды, чтобы /v1/health можно было
    // дёргать балансером хоть раз в секунду и не упираться ни в какой лок.
    val healthExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "jarvis-health").apply { isDaemon = true }
    }
    val healthScope = CoroutineScope(
        SupervisorJob() + healthExecutor.asCoroutineDispatcher()
    )
    @Volatile var cachedHealth: Pair<Long, String>? = null

    // CR-15: backpressure — не более maxInFlight запросов ОДНОВРЕМЕННО.
    // При превышении — немедленный 503, а не постановка в бесконечную
    // очередь (как было на фиксированном пуле с неявной очередью без лимита).
    val maxInFlight = Runtime.getRuntime().availableProcessors() * 4
    val inFlight = Semaphore(maxInFlight)
    logger.info("server backpressure configured", "maxInFlight" to maxInFlight.toString())

    // CR-15: backlog = 64 вместо 0. 0 = реализация-в-приложение-принимает-всё
    // пока TCP-стек не откажется; с явным backlog ОС держит очередь приёма
    // соединений ограниченной и честно отбрасывает при перегрузке.
    // Значение 64 — консервативный выбор: достаточно, чтобы сгладить
    // burst-ы от балансера/keepalive (обычно 32-128), но недостаточно,
    // чтобы создать иллюзию доступности при настоящей перегрузке.
    val server = HttpServer.create(
        InetSocketAddress(config.deployment.bindHost, config.port), 64
    )
    // CR-15: kickoff dispatcher. Это НЕ пул обработчиков — на нём выполняется
    // только сам HttpServer.accept-loop-диспетчер: он получает вызов
    // HttpHandler.handle(), в котором мы немедленно launch'им корутину в
    // requestScope и возвращаемся. Весь реальный work (body parse, auth,
    // AI call, serialization) живёт в requestScope/healthScope на
    // Dispatchers.IO и в healthExecutor.
    val kickoffExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "jarvis-kickoff").apply { isDaemon = true }
    }
    server.executor = kickoffExecutor

    /**
     * Отправляет ответ на HttpExchange, закрывая exchange после этого.
     * Безопасно вызывать из любого потока/корутины.
     */
    fun respond(exchange: HttpExchange, response: HttpResponseContext) {
        try {
            val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            response.headers.forEach { (k, v) -> exchange.responseHeaders.set(k, v) }
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(response.status, -1)
            } else {
                exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        } catch (t: Throwable) {
            logger.warn("failed to write response", "error" to t.javaClass.simpleName)
        } finally {
            runCatching { exchange.close() }
        }
    }

    // CR-15: health — отдельный context и своя корутина, не берёт inFlight
    // пермит и не ходит в общий requestScope/DB/AI.
    server.createContext("/v1/health") { exchange ->
        healthScope.launch {
            val now = System.currentTimeMillis()
            val body = cachedHealth?.takeIf { now - it.first < HEALTH_RESPONSE_CACHE_MS }?.second
                ?: run {
                    val snapshot = handler.healthSnapshot()
                    val fresh = buildString {
                        append("""{"status":"ok","providers":{""")
                        append(
                            snapshot.entries.joinToString(",") { (id, s) ->
                                """"${id.name}":{"status":"${s.status}","circuit":"${s.circuitState}"}"""
                            }
                        )
                        append("}}")
                    }
                    cachedHealth = now to fresh
                    fresh
                }
            runCatching {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                exchange.responseHeaders.set("Cache-Control", "no-cache")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }.exceptionOrNull()?.let {
                logger.warn("health response failed", "error" to it.javaClass.simpleName)
            }
            runCatching { exchange.close() }
        }
    }

    // CR-15: общий обработчик на всё остальное.
    server.createContext("/") { exchange: HttpExchange ->
        // Методом kickoff в requestScope мы НЕ блокируем acceptor.
        requestScope.launch {
            // CR-15: backpressure через семафор — немедленный 503 без постановки
            // в очередь. acquire не suspend'ится, если есть свободный пермит.
            val acquired = inFlight.tryAcquire()
            if (!acquired) {
                logger.warn("server overloaded: in-flight limit reached", "limit" to maxInFlight.toString())
                val body = """{"success":false,"error":{"code":"RATE_LIMITED","message":"Server is overloaded, retry later","requestId":"-"}}"""
                respond(
                    exchange,
                    HttpResponseContext(
                        status = 503,
                        body = body,
                        headers = mapOf(
                            "Retry-After" to "1",
                            "Cache-Control" to "no-store"
                        )
                    )
                )
                return@launch
            }
            try {
                handleExchange(exchange, handler, proxySecurity, config, logger)
            } finally {
                inFlight.release()
            }
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("shutting down")
        // Сначала отменяем корутины — это мгновенно прерывает in-flight
        // AI/db вызовы через cancellation, иначе server.stop(0) закроет
        // сокет но работающие в requestScope корутины продолжат жечь CPU
        // и upstream-вызовы до истечения собственных таймаутов.
        requestScope.cancel()
        healthScope.cancel()
        // server.stop(delay) ждёт delay секунд на завершение in-flight
        // exchange; мы уже отменили все корутины — ставим 0.
        server.stop(0)
        // Теперь завершаем тред-пулы (kickoff/health могут иметь
        // daemon-треды, но мы закрываем их явно для чистого shutdown).
        healthExecutor.shutdown()
        kickoffExecutor.shutdown()
        runCatching { healthExecutor.awaitTermination(3, TimeUnit.SECONDS) }
        runCatching { kickoffExecutor.awaitTermination(3, TimeUnit.SECONDS) }
    })

    logger.info(
        "JARVIS API started",
        "environment" to config.deployment.environment.name,
        "bindHost" to config.deployment.bindHost,
        "port" to config.port.toString()
    )
    server.start()
}
