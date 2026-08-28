package com.jarvis.server

import com.jarvis.server.auth.AlwaysGrantedEntitlementChecker
import com.jarvis.server.auth.ClientTier
import com.jarvis.server.auth.CompositeAuthenticator
import com.jarvis.server.auth.EntitlementChecker
import com.jarvis.server.auth.LicenseEntitlementChecker
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
import com.zaxxer.hikari.HikariDataSource
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
import com.jarvis.server.observability.StructuredLogger
import com.jarvis.server.persistence.DatabaseFactory
import com.jarvis.server.persistence.DatabaseMigrator
import com.jarvis.server.persistence.PostgresSingleInstanceGuard
import com.jarvis.server.observability.Metrics
import com.jarvis.server.provider.DefaultProviderSelectionPolicy
import com.jarvis.server.provider.GeminiProvider
import com.jarvis.server.provider.GroqProvider
import com.jarvis.server.provider.OkHttpTransport
import com.jarvis.server.provider.OpenRouterProvider
import com.jarvis.server.provider.ProviderHealthTracker
import com.jarvis.server.provider.ProviderManager
import com.jarvis.server.ratelimit.PostgresRateLimiter
import com.jarvis.server.router.AiRouter
import com.jarvis.server.usage.AsyncUsageTracker
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * C-01 fix: контейнер для ресурсов с временем жизни == время жизни JVM.
     *
     * До этого [buildHandler] возвращал только [JarvisApiHandler], а
     * `dataSource` / `usageTracker` / `instanceGuard` оставались в локальных
     * переменных — shutdown hook из `main()` не мог до них дотянуться, и
     * модуль не компилировался.
     *
     * Порядок закрытия в [close] важен: сначала останавливаем usage worker
     * (он пишет в БД и ему нужен живой dataSource), затем снимаем advisory
     * lock, затем закрываем пул соединений. Все шаги обёрнуты в
     * `runCatching`, чтобы сбой на одном шаге не помешал закрыть остальные.
     */
    data class ServerResources(
        val handler: JarvisApiHandler,
        val dataSource: HikariDataSource,
        val usageTracker: AsyncUsageTracker,
        val instanceGuard: PostgresSingleInstanceGuard?,
    ) : AutoCloseable {
        override fun close() {
            runCatching { usageTracker.shutdown() }
            runCatching { instanceGuard?.close() }
            runCatching { dataSource.close() }
        }
    }

    /**
     * C-01 test helper: handle на запущенный сервер с тем же shutdown-порядком,
     * что и JVM shutdown hook. Используется интеграционным тестом на старт/
     * стоп и в будущем — всеми in-process smoke tests.
     *
     * Порядок остановки в [close] должен в точности совпадать с порядком в
     * shutdown hook main(): stop(0) → cancel scope'ы → shutdown executors →
     * close resources. Любое рассинхронизирование здесь и в main() — это
     * скрытый баг, поэтому логика написана один раз и переиспользуется.
     */
    class RunningServer(
        val server: HttpServer,
        private val requestScope: CoroutineScope,
        private val healthScope: CoroutineScope,
        private val healthExecutor: ExecutorService,
        private val kickoffExecutor: ExecutorService,
        private val resources: ServerResources,
    ) : AutoCloseable {
        val localPort: Int get() = server.address.port

        override fun close() {
            // 1) Прекращаем принимать новые соединения.
            runCatching { server.stop(0) }
            // 2) Отменяем in-flight корутины (cancellation стекет в OkHttpTransport).
            runCatching { requestScope.cancel() }
            runCatching { healthScope.cancel() }
            // 3) Закрываем тред-пулы.
            runCatching { healthExecutor.shutdown() }
            runCatching { kickoffExecutor.shutdown() }
            runCatching { healthExecutor.awaitTermination(3, TimeUnit.SECONDS) }
            runCatching { kickoffExecutor.awaitTermination(3, TimeUnit.SECONDS) }
            // 4) Закрываем DB / usage tracker / instance guard.
            runCatching { resources.close() }
        }
    }

    /**
     * Поднимает HttpServer на указанном порту (0 = random port, полезно в
     * тестах) и регистрирует все обработчики. Возвращает [RunningServer],
     * который [AutoCloseable] — в тестах используем `.use { ... }`.
     */
    fun bindServer(
        config: ServerConfig,
        resources: ServerResources,
        port: Int = config.port,
    ): RunningServer {
        val handler = resources.handler
        val proxySecurity = ProxyRequestSecurity(config.deployment)
        val logger = ConsoleStructuredLogger()

        val ioDispatcher = Dispatchers.IO
        val requestScope = CoroutineScope(SupervisorJob() + ioDispatcher)

        val healthExecutor = Executors.newCachedThreadPool { r ->
            Thread(r, "jarvis-health").apply { isDaemon = true }
        }
        val healthScope = CoroutineScope(
            SupervisorJob() + healthExecutor.asCoroutineDispatcher()
        )
        // @Volatile неприменим к локальным переменным Kotlin; кэш health-ответа
        // защищается AtomicReference (чтение/запись из корутин healthScope).
        val cachedHealth = AtomicReference<Pair<Long, String>?>(null)

        val maxInFlight = Runtime.getRuntime().availableProcessors() * 4
        val inFlight = Semaphore(maxInFlight)
        logger.info("server backpressure configured", "maxInFlight" to maxInFlight.toString())

        val server = HttpServer.create(
            InetSocketAddress(config.deployment.bindHost, port), 64
        )
        val kickoffExecutor = Executors.newCachedThreadPool { r ->
            Thread(r, "jarvis-kickoff").apply { isDaemon = true }
        }
        server.executor = kickoffExecutor

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

        server.createContext("/v1/health") { exchange ->
            healthScope.launch {
                val now = System.currentTimeMillis()
                val body = cachedHealth.get()?.takeIf { now - it.first < HEALTH_RESPONSE_CACHE_MS }?.second
                    ?: run {
                        // handler.healthSnapshot() уже возвращает готовый JSON
                        // (собирается в buildResources из provider snapshot);
                        // мы только кэшируем его на 2 секунды, чтобы балансер
                        // не гонял formatter на каждый ping.
                        val fresh = handler.healthSnapshot()
                        cachedHealth.set(now to fresh)
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

        server.createContext("/") { exchange: HttpExchange ->
            requestScope.launch {
                val acquired = inFlight.tryAcquire()
                if (!acquired) {
                    logger.warn("server overloaded: in-flight limit reached", "limit" to maxInFlight.toString())
                    val body = """{"success":false,"error":{"code":"RATE_LIMITED","message":"Server is overloaded, retry later","requestId":"-"}}"""
                    respond(
                        exchange,
                        HttpResponseContext(
                            status = 503,
                            body = body,
                            headers = mapOf("Retry-After" to "1", "Cache-Control" to "no-store")
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

        server.start()
        return RunningServer(server, requestScope, healthScope, healthExecutor, kickoffExecutor, resources)
    }

    fun buildResources(config: ServerConfig): ServerResources {
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
        val selectionPolicy = DefaultProviderSelectionPolicy(configsById, health)

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

        val usageRepository = JdbcUsageRepository(dataSource)

        // AR-05: асинхронный пайплайн записи usage с bounded retry и лимитами.
        val usageTracker = AsyncUsageTracker(
            repository = usageRepository,
            limits = config.usageLimits,
            costs = config.tokenCosts,
            logger = logger,
            metrics = metrics
        ).also { it.start() }

        val router = AiRouter(
            providerManager = providerManager,
            usageRepository = usageRepository,
            usageTracker = usageTracker,
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
        // S-02: строгая regex-валидация clientId. Невалидные значения отбрасываются
        // (fail-closed) и логируются без раскрытия полного значения — достаточно
        // длины/позиции для диагностики. clientId НЕ является секретом (это
        // мнемоника из конфига вроде "android-1"), но мы всё равно не логируем
        // его целиком, т.к. админ мог по ошибке положить туда токен.
        val adminClientIdRegex = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
        val adminClients = (System.getenv("JARVIS_ADMIN_CLIENTS") ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { candidate ->
                if (adminClientIdRegex.matches(candidate)) {
                    candidate
                } else {
                    logger.warn(
                        "ignoring invalid JARVIS_ADMIN_CLIENTS entry",
                        "length" to candidate.length.toString(),
                        "reason" to "does not match clientId pattern [A-Za-z0-9][A-Za-z0-9_.-]{0,63}"
                    )
                    null
                }
            }
            .toSet()
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

        val handler = JarvisApiHandler(
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
            // AR-06: выбор реализации чекера — production-по-умолчанию через
            // LicenseService, а при dev-режиме — AlwaysGranted (без биллинга).
            // НЕ допускаем AlwaysGranted в production-окружении: fail-closed.
            entitlementChecker = buildEntitlementChecker(
                config = config,
                licenseService = licenseService,
                logger = logger
            )::isEntitled,
            extensionHandler = licenseHttpHandler::handle,
            // Публикуем healthProvider для внешнего kickoff в main().
            healthProviderFunc = healthProvider
        )
        return ServerResources(
            handler = handler,
            dataSource = dataSource,
            usageTracker = usageTracker,
            instanceGuard = instanceGuard,
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
 * AR-06: выбор EntitlementChecker на основании DeploymentEnvironment.
 *
 * - В PRODUCTION — только [LicenseEntitlementChecker] (обязательно должен
 *   видеть активную лицензию/подписку). AlwaysGranted в этом окружении
 *   под запретом: fail-closed (приложение не стартует).
 * - В staging/development/testing можно включить AlwaysGranted только явно
 *   через `JARVIS_DEV_SKIP_ENTITLEMENT=true` — это требуется для запуска AI
 *   пути без поднятой billing/DB-инфраструктуры.
 */
private fun buildEntitlementChecker(
    config: ServerConfig,
    licenseService: LicenseService,
    logger: StructuredLogger
): EntitlementChecker {
    val devSkipEntitlement = System.getenv("JARVIS_DEV_SKIP_ENTITLEMENT")
        ?.lowercase()?.let { it == "true" || it == "1" || it == "yes" } == true

    return when {
        config.deployment.isProduction && devSkipEntitlement -> {
            throw IllegalStateException(
                "JARVIS_DEV_SKIP_ENTITLEMENT must NOT be enabled in production"
            )
        }
        !config.deployment.isProduction && devSkipEntitlement -> {
            logger.warn(
                "entitlement checks DISABLED (AlwaysGranted); do NOT use in production",
                "env" to config.deployment.environment.name
            )
            AlwaysGrantedEntitlementChecker()
        }
        else -> LicenseEntitlementChecker(licenseService)
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

    // C-01: buildResources() собирает ВСЕ JVM-long ресурсы (handler + DB +
    // usage tracker + instance guard), чтобы shutdown hook мог их корректно
    // закрыть. До этого dataSource/usageTracker/instanceGuard оставались
    // локальными переменными buildHandler() и были недоступны из main().
    val resources = ServerBootstrap.buildResources(config)

    // bindServer() создаёт HttpServer, тред-пулы, скоупы и регистрирует все
    // контексты (/v1/health + /), вычисляет maxInFlight и пишет лог о нём.
    // Возвращает AutoCloseable хэндл с тем же shutdown-порядком, что и JVM
    // shutdown hook — это единый источник истины для остановки
    // (используется main() и интеграционными тестами).
    val running = ServerBootstrap.bindServer(config, resources)

    // JVM shutdown hook делегирует в RunningServer.close() — тот же самый
    // порядок остановки, что и в тестах. Это исключает рассинхрон, когда
    // shutdown hook что-то забывает закрыть или делает это в неверном порядке.
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("shutting down")
        runCatching { running.close() }
    })

    logger.info(
        "JARVIS API started",
        "environment" to config.deployment.environment.name,
        "bindHost" to config.deployment.bindHost,
        "port" to running.localPort.toString()
    )
    // server.start() уже вызван внутри bindServer(); поток main() паркуется
    // на ожидании завершения JVM (shutdown hook закроет running).
    Thread.currentThread().join()
}
