package com.jarvis.server

import com.jarvis.server.api.ApiErrorResponse
import com.jarvis.server.api.BillingCheckoutResponse
import com.jarvis.server.api.LicenseIssueResponse
import com.jarvis.server.api.LicenseRedeemResponse
import com.jarvis.server.api.LicenseValidateResponse
import com.jarvis.server.auth.ClientTier
import com.jarvis.server.auth.CompositeAuthenticator
import com.jarvis.server.auth.LicenseTokenAuthenticator
import com.jarvis.server.auth.TierAuthorizer
import com.jarvis.server.auth.TokenAuthenticator
import com.jarvis.server.billing.BillingOrder
import com.jarvis.server.billing.BillingProvider
import com.jarvis.server.billing.BillingProviderException
import com.jarvis.server.billing.BillingProviderId
import com.jarvis.server.billing.BillingService
import com.jarvis.server.billing.HeleketBillingConfig
import com.jarvis.server.billing.HeleketWebhookVerifier
import com.jarvis.server.billing.JdbcBillingRepository
import com.jarvis.server.billing.PaddleBillingConfig
import com.jarvis.server.billing.PaddleWebhookVerifier
import com.jarvis.server.billing.ProviderCheckout
import com.jarvis.server.billing.hmacSha256Hex
import com.jarvis.server.config.RateLimitConfig
import com.jarvis.server.config.ValidationConfig
import com.jarvis.server.http.HttpRequestContext
import com.jarvis.server.http.LicenseBillingHttpHandler
import com.jarvis.server.license.BillingPlan
import com.jarvis.server.license.JdbcLicenseRepository
import com.jarvis.server.license.LicenseCrypto
import com.jarvis.server.license.LicenseService
import com.jarvis.server.observability.ConsoleStructuredLogger
import com.jarvis.server.ratelimit.SlidingWindowRateLimiter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class LicenseApiIntegrationTest : PostgresTestSupport() {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val adminToken = "a".repeat(64)
    private val userStaticToken = "u".repeat(64)
    private lateinit var handler: LicenseBillingHttpHandler
    private val providerCalls = AtomicInteger()
    private var providerFailure: BillingProviderException? = null

    @Before
    fun buildLicenseHandler() {
        providerCalls.set(0)
        providerFailure = null
        val crypto = LicenseCrypto("test-license-pepper-32-bytes-minimum-value")
        val licenseRepository = JdbcLicenseRepository(dataSource, crypto)
        val licenseService = LicenseService(licenseRepository, crypto)
        licenseService.upsertPlan(
            BillingPlan(
                "earclip-monthly", "jarvis-earclip", "Monthly", 30, 1_400, "USD",
                paddlePriceId = "pri_1234567890", heleketCurrency = "USDT"
            )
        )
        val fakeProvider = object : BillingProvider {
            override val id = BillingProviderId.PADDLE
            override fun isConfigured() = true
            override suspend fun createCheckout(
                order: BillingOrder,
                plan: BillingPlan
            ): ProviderCheckout {
                providerCalls.incrementAndGet()
                providerFailure?.let { throw it }
                return ProviderCheckout(
                    "txn_${order.id.toString().replace("-", "")}",
                    "https://checkout.paddle.com/pay/${order.id}"
                )
            }
        }
        val billingService = BillingService(
            JdbcBillingRepository(dataSource), licenseRepository, listOf(fakeProvider)
        )
        val static = TokenAuthenticator(
            mapOf(adminToken to "admin", userStaticToken to "legacy-user")
        ) { if (it == "admin") ClientTier.ADMIN else ClientTier.FREE }
        val auth = CompositeAuthenticator(static, LicenseTokenAuthenticator(licenseService))
        val paddleConfig = PaddleBillingConfig("api-key", "webhook-secret")
        val heleketConfig = HeleketBillingConfig(
            "merchant", "heleket-secret",
            "https://api.example.com/v1/billing/webhooks/heleket",
            "https://example.com/return",
            "https://example.com/success"
        )
        handler = LicenseBillingHttpHandler(
            authenticator = auth,
            authorizer = TierAuthorizer(),
            licenseService = licenseService,
            billingService = billingService,
            paddleWebhookVerifier = PaddleWebhookVerifier(paddleConfig, json),
            heleketWebhookVerifier = HeleketWebhookVerifier(heleketConfig, json),
            redeemRateLimiter = SlidingWindowRateLimiter(RateLimitConfig(10, 100)),
            authenticatedRateLimiter = SlidingWindowRateLimiter(RateLimitConfig(20, 100)),
            webhookRateLimiter = SlidingWindowRateLimiter(RateLimitConfig(100, 1_000)),
            validation = ValidationConfig(),
            logger = ConsoleStructuredLogger(sink = {}),
            json = json
        )
    }

    @Test
    fun `admin issuance redeem authenticated validation and checkout reach database`() = runBlocking {
        val issue = handler.handle(
            request(
                path = LicenseBillingHttpHandler.PATH_ISSUE,
                token = adminToken,
                body = """{"plan_id":"earclip-monthly","request_id":"issue-api"}"""
            )
        )!!
        assertEquals(201, issue.status)
        val issued = json.decodeFromString(LicenseIssueResponse.serializer(), issue.body)
        assertTrue(issued.code.startsWith("JRV-"))

        val redeem = handler.handle(
            request(
                path = LicenseBillingHttpHandler.PATH_REDEEM,
                body = """{"code":"${issued.code}","device_id":"device-abcdefgh","request_id":"redeem-api"}"""
            )
        )!!
        assertEquals(200, redeem.status)
        assertEquals("no-store", redeem.headers["Cache-Control"])
        val redeemed = json.decodeFromString(LicenseRedeemResponse.serializer(), redeem.body)
        assertTrue(redeemed.accessToken.startsWith("jrv_"))

        val validate = handler.handle(
            request(
                path = LicenseBillingHttpHandler.PATH_VALIDATE,
                token = redeemed.accessToken,
                body = """{"device_id":"device-abcdefgh","request_id":"validate-api"}"""
            )
        )!!
        assertEquals(200, validate.status)
        val validated = json.decodeFromString(LicenseValidateResponse.serializer(), validate.body)
        assertTrue(validated.valid)
        assertEquals("earclip-monthly", validated.planId)

        val checkout = handler.handle(
            request(
                path = LicenseBillingHttpHandler.PATH_CHECKOUT,
                token = redeemed.accessToken,
                body = """{"plan_id":"earclip-monthly","provider":"PADDLE","idempotency_key":"checkout-12345678"}"""
            )
        )!!
        assertEquals(201, checkout.status)
        assertEquals(1, providerCalls.get())
        assertTrue(
            json.decodeFromString(BillingCheckoutResponse.serializer(), checkout.body)
                .checkoutUrl?.startsWith("https://checkout.paddle.com") == true
        )
    }

    @Test
    fun `ambiguous checkout has stable retry safe HTTP contract and signed recovery`() = runBlocking {
        val token = json.decodeFromString(
            LicenseRedeemResponse.serializer(),
            redeemCode(issueCode()).body
        ).accessToken
        val before = json.decodeFromString(
            LicenseValidateResponse.serializer(),
            handler.handle(
                request(
                    path = LicenseBillingHttpHandler.PATH_VALIDATE,
                    token = token,
                    body = """{"device_id":"device-abcdefgh"}"""
                )
            )!!.body
        )
        providerFailure = BillingProviderException(true, "provider response timed out")

        val first = handler.handle(
            request(
                path = LicenseBillingHttpHandler.PATH_CHECKOUT,
                token = token,
                body = """{"plan_id":"earclip-monthly","provider":"PADDLE","idempotency_key":"ambiguous-api-1"}"""
            )
        )!!
        val retry = handler.handle(
            request(
                path = LicenseBillingHttpHandler.PATH_CHECKOUT,
                token = token,
                body = """{"plan_id":"earclip-monthly","provider":"PADDLE","idempotency_key":"ambiguous-api-2"}"""
            )
        )!!

        assertEquals(202, first.status)
        assertEquals(202, retry.status)
        assertEquals("no-store", first.headers["Cache-Control"])
        assertEquals(1, providerCalls.get())
        val firstCheckout = json.decodeFromString(BillingCheckoutResponse.serializer(), first.body)
        val retryCheckout = json.decodeFromString(BillingCheckoutResponse.serializer(), retry.body)
        assertEquals("RECONCILIATION_REQUIRED", firstCheckout.status)
        assertEquals(firstCheckout.orderId, retryCheckout.orderId)

        val occurredAt = Instant.now()
        val webhookBody =
            """{"event_id":"evt_reconcile_api_123","event_type":"transaction.completed","occurred_at":"$occurredAt","data":{"id":"txn_reconciled_1234567890","custom_data":{"jarvis_order_id":"${firstCheckout.orderId}"}}}"""
        val timestamp = occurredAt.epochSecond
        val signature = "ts=$timestamp;h1=${hmacSha256Hex("webhook-secret", "$timestamp:$webhookBody")}"
        val webhook = handler.handle(
            request(
                path = LicenseBillingHttpHandler.PATH_PADDLE_WEBHOOK,
                body = webhookBody,
                headers = mapOf("Paddle-Signature" to signature)
            )
        )!!
        assertEquals(200, webhook.status)

        val after = json.decodeFromString(
            LicenseValidateResponse.serializer(),
            handler.handle(
                request(
                    path = LicenseBillingHttpHandler.PATH_VALIDATE,
                    token = token,
                    body = """{"device_id":"device-abcdefgh"}"""
                )
            )!!.body
        )
        assertEquals(
            Instant.parse(before.expiresAt).plusSeconds(30L * 86_400),
            Instant.parse(after.expiresAt)
        )
    }

    @Test
    fun `issuance authorization and validation authentication fail closed`() = runBlocking {
        val missing = handler.handle(
            request(LicenseBillingHttpHandler.PATH_ISSUE, body = """{"plan_id":"earclip-monthly"}""")
        )!!
        assertEquals(401, missing.status)

        val nonAdmin = handler.handle(
            request(
                LicenseBillingHttpHandler.PATH_ISSUE,
                token = userStaticToken,
                body = """{"plan_id":"earclip-monthly"}"""
            )
        )!!
        assertEquals(403, nonAdmin.status)

        val validate = handler.handle(
            request(
                LicenseBillingHttpHandler.PATH_VALIDATE,
                body = """{"device_id":"device-abcdefgh"}"""
            )
        )!!
        assertEquals(401, validate.status)
    }

    @Test
    fun `unknown and reused codes have indistinguishable public response`() = runBlocking {
        val unknown = handler.handle(
            request(
                LicenseBillingHttpHandler.PATH_REDEEM,
                body = """{"code":"JRV-AAAAA-AAAAA-AAAAA-AAAAA","device_id":"device-abcdefgh"}"""
            )
        )!!
        val issued = issueCode()
        val first = redeemCode(issued)
        assertEquals(200, first.status)
        val replay = redeemCode(issued)

        assertEquals(404, unknown.status)
        assertEquals(404, replay.status)
        val unknownCode = json.decodeFromString(ApiErrorResponse.serializer(), unknown.body).error.code
        val replayCode = json.decodeFromString(ApiErrorResponse.serializer(), replay.body).error.code
        assertEquals(unknownCode, replayCode)
        assertEquals(
            json.decodeFromString(ApiErrorResponse.serializer(), unknown.body).error.message,
            json.decodeFromString(ApiErrorResponse.serializer(), replay.body).error.message
        )
    }

    @Test
    fun `authenticated admin revoke is rate limited before request parsing`() = runBlocking {
        var response = handler.handle(
            request(LicenseBillingHttpHandler.PATH_REVOKE, token = adminToken, body = "{}")
        )!!
        repeat(20) {
            response = handler.handle(
                request(LicenseBillingHttpHandler.PATH_REVOKE, token = adminToken, body = "{}")
            )!!
        }

        assertEquals(429, response.status)
        assertTrue((response.headers["Retry-After"]?.toLongOrNull() ?: 0) > 0)
    }

    @Test
    fun `redeem brute force limiter returns retry after`() = runBlocking {
        val limitedHandler = handler
        var last = request(LicenseBillingHttpHandler.PATH_REDEEM, body = "{}")
        var response = limitedHandler.handle(last)!!
        repeat(11) {
            last = request(
                LicenseBillingHttpHandler.PATH_REDEEM,
                body = """{"code":"JRV-AAAAA-AAAAA-AAAAA-AAAAA","device_id":"device-abcdefgh"}"""
            )
            response = limitedHandler.handle(last)!!
        }
        assertEquals(429, response.status)
        assertTrue((response.headers["Retry-After"]?.toLongOrNull() ?: 0) > 0)
    }

    private suspend fun issueCode(): String {
        val response = handler.handle(
            request(
                LicenseBillingHttpHandler.PATH_ISSUE,
                token = adminToken,
                body = """{"plan_id":"earclip-monthly"}"""
            )
        )!!
        return json.decodeFromString(LicenseIssueResponse.serializer(), response.body).code
    }

    private suspend fun redeemCode(code: String) = handler.handle(
        request(
            LicenseBillingHttpHandler.PATH_REDEEM,
            body = """{"code":"$code","device_id":"device-abcdefgh"}"""
        )
    )!!

    private fun request(
        path: String,
        token: String? = null,
        body: String,
        headers: Map<String, String> = emptyMap()
    ) = HttpRequestContext(
        method = "POST",
        path = path,
        authorizationHeader = token?.let { "Bearer $it" },
        body = body,
        contentLength = body.toByteArray().size.toLong(),
        headers = headers,
        remoteAddress = "127.0.0.1"
    )
}
