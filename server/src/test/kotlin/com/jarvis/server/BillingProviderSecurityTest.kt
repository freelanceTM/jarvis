package com.jarvis.server

import com.jarvis.server.billing.BillingOrder
import com.jarvis.server.billing.BillingOrderStatus
import com.jarvis.server.billing.BillingProviderException
import com.jarvis.server.billing.BillingProviderId
import com.jarvis.server.billing.HeleketBillingConfig
import com.jarvis.server.billing.HeleketBillingProvider
import com.jarvis.server.billing.HeleketWebhookVerifier
import com.jarvis.server.billing.PaddleBillingConfig
import com.jarvis.server.billing.PaddleBillingProvider
import com.jarvis.server.billing.PaddleWebhookVerifier
import com.jarvis.server.billing.WebhookVerificationResult
import com.jarvis.server.billing.heleketSign
import com.jarvis.server.billing.hmacSha256Hex
import com.jarvis.server.license.BillingPlan
import com.jarvis.server.provider.HttpTransport
import com.jarvis.server.provider.HttpTransportResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class BillingProviderSecurityTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val accountId = UUID.randomUUID()
    private val orderId = UUID.randomUUID()
    private val order = BillingOrder(
        id = orderId,
        accountId = accountId,
        licenseId = UUID.randomUUID(),
        planId = "earclip-monthly",
        provider = BillingProviderId.PADDLE,
        status = BillingOrderStatus.PROCESSING,
        amountMinor = 1_400,
        currency = "USD",
        idempotencyKey = "test-idem-00000000",
        providerOrderId = null,
        providerSubscriptionId = null,
        checkoutUrl = null,
        paidAt = null
    )
    private val plan = BillingPlan(
        "earclip-monthly", "jarvis-earclip", "Monthly", 30, 1_400, "USD",
        paddlePriceId = "pri_1234567890", heleketCurrency = "USDT"
    )

    private class FakeTransport(private val response: HttpTransportResponse) : HttpTransport {
        var url: String? = null
        var headers: Map<String, String> = emptyMap()
        var body: String = ""
        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            connectTimeoutMs: Long,
            requestTimeoutMs: Long
        ): HttpTransportResponse {
            this.url = url
            this.headers = headers
            this.body = body
            return response
        }
    }

    @Test
    fun `Paddle checkout uses server price id and trusted URL`() = runBlocking {
        val transport = FakeTransport(
            HttpTransportResponse(
                200,
                """{"data":{"id":"txn_1234567890abcdef","checkout":{"url":"https://checkout.paddle.com/pay/abc"}}}"""
            )
        )
        val provider = PaddleBillingProvider(
            PaddleBillingConfig("pdl_api_key", "pdl_webhook_secret"), transport, json
        )

        val checkout = provider.createCheckout(order, plan)

        assertEquals("txn_1234567890abcdef", checkout.providerOrderId)
        assertTrue(transport.body.contains("pri_1234567890"))
        assertTrue(transport.body.contains(orderId.toString()))
        assertFalse(transport.body.contains("amount_minor"))
        assertEquals("Bearer pdl_api_key", transport.headers["Authorization"])
    }

    @Test(expected = BillingProviderException::class)
    fun `Paddle rejects attacker controlled checkout host`() {
        runBlocking {
            val transport = FakeTransport(
                HttpTransportResponse(
                    200,
                    """{"data":{"id":"txn_1234567890abcdef","checkout":{"url":"https://evil.example/pay"}}}"""
                )
            )
            PaddleBillingProvider(
                PaddleBillingConfig("key", "secret"), transport, json
            ).createCheckout(order, plan)
        }
    }

    @Test
    fun `provider HTTP rejection and ambiguous response classes are explicit`() = runBlocking {
        fun paddle(response: HttpTransportResponse) = PaddleBillingProvider(
            PaddleBillingConfig("key", "secret"), FakeTransport(response), json
        )
        fun heleket(response: HttpTransportResponse) = HeleketBillingProvider(
            heleketConfig(), FakeTransport(response), json
        )

        assertFalse(providerFailure { paddle(HttpTransportResponse(400, "rejected"))
            .createCheckout(order, plan) }.ambiguous)
        assertTrue(providerFailure { paddle(HttpTransportResponse(500, "unknown"))
            .createCheckout(order, plan) }.ambiguous)
        assertTrue(providerFailure { paddle(HttpTransportResponse(200, "{}"))
            .createCheckout(order, plan) }.ambiguous)
        assertFalse(providerFailure { heleket(HttpTransportResponse(200, "{\"state\":1}"))
            .createCheckout(order.copy(provider = BillingProviderId.HELEKET), plan) }.ambiguous)
        assertTrue(providerFailure { heleket(HttpTransportResponse(200, "{}"))
            .createCheckout(order.copy(provider = BillingProviderId.HELEKET), plan) }.ambiguous)
        assertTrue(providerFailure { heleket(HttpTransportResponse(200, "{\"state\":0}"))
            .createCheckout(order.copy(provider = BillingProviderId.HELEKET), plan) }.ambiguous)
    }

    @Test
    fun `Paddle signature validates raw body and rejects stale or modified payload`() {
        val now = Instant.parse("2026-08-20T12:00:00Z")
        val config = PaddleBillingConfig("key", "webhook-secret", webhookToleranceSeconds = 300)
        val verifier = PaddleWebhookVerifier(config, json, Clock.fixed(now, ZoneOffset.UTC))
        val body = """{"event_id":"evt_12345678","event_type":"transaction.completed","occurred_at":"$now","data":{"id":"txn_1234567890","subscription_id":"sub_1234567890","custom_data":{"jarvis_order_id":"$orderId"}}}"""
        val ts = now.epochSecond
        val signature = "ts=$ts;h1=${hmacSha256Hex("webhook-secret", "$ts:$body")}"

        assertTrue(verifier.verify(body, signature) is WebhookVerificationResult.Valid)
        assertTrue(verifier.verify("$body ", signature) is WebhookVerificationResult.Invalid)
        val staleTs = ts - 301
        val stale = "ts=$staleTs;h1=${hmacSha256Hex("webhook-secret", "$staleTs:$body")}"
        assertTrue(verifier.verify(body, stale) is WebhookVerificationResult.Invalid)
        val extremeTs = Long.MIN_VALUE
        val extreme = "ts=$extremeTs;h1=${hmacSha256Hex("webhook-secret", "$extremeTs:$body")}"
        assertTrue(verifier.verify(body, extreme) is WebhookVerificationResult.Invalid)
    }

    @Test
    fun `Heleket invoice is signed and forces exact payment`() = runBlocking {
        val providerResponse = """{"state":0,"result":{"uuid":"3b9d0dc5-02a3-4a13-8cec-02dc0efc1234","url":"https://new-pay.heleket.com/pay/abc"}}"""
        val transport = FakeTransport(HttpTransportResponse(200, providerResponse))
        val config = heleketConfig()
        val provider = HeleketBillingProvider(config, transport, json)

        provider.createCheckout(order.copy(provider = BillingProviderId.HELEKET), plan)

        assertEquals(heleketSign(transport.body, "heleket-secret"), transport.headers["sign"])
        assertTrue(transport.body.contains("\"is_payment_multiple\":false"))
        assertTrue(transport.body.contains("\"accuracy_payment_percent\":0"))
        assertTrue(transport.body.contains("\"amount\":\"14.00\""))
    }

    @Test
    fun `Heleket webhook verifies signature source amount and replay identity`() {
        val config = heleketConfig()
        val verifier = HeleketWebhookVerifier(config, json)
        val unsigned = linkedMapOf(
            "type" to JsonPrimitive("payment"),
            "uuid" to JsonPrimitive("3b9d0dc5-02a3-4a13-8cec-02dc0efc1234"),
            "order_id" to JsonPrimitive(orderId.toString()),
            "amount" to JsonPrimitive("14.00"),
            "payment_amount" to JsonPrimitive("14.00"),
            "currency" to JsonPrimitive("USD"),
            "status" to JsonPrimitive("paid"),
            "is_final" to JsonPrimitive(true),
            "txid" to JsonPrimitive("blockchain-tx")
        )
        val canonical = json.encodeToString(JsonObject.serializer(), JsonObject(unsigned)).replace("/", "\\/")
        val body = json.encodeToString(
            JsonObject.serializer(),
            JsonObject(LinkedHashMap(unsigned).apply { put("sign", JsonPrimitive(heleketSign(canonical, "heleket-secret"))) })
        )

        val valid = verifier.verify(body, "31.133.220.8")
        assertTrue(valid is WebhookVerificationResult.Valid)
        val event = (valid as WebhookVerificationResult.Valid).event
        assertEquals(1_400L, event.expectedAmountMinor)
        assertEquals(orderId, event.localOrderId)
        assertTrue(verifier.verify(body, "203.0.113.9") is WebhookVerificationResult.Invalid)
        assertTrue(verifier.verify(body.replace("14.00", "15.00"), "31.133.220.8") is WebhookVerificationResult.Invalid)
    }

    private suspend fun providerFailure(block: suspend () -> Unit): BillingProviderException =
        try {
            block()
            throw AssertionError("Expected BillingProviderException")
        } catch (failure: BillingProviderException) {
            failure
        }

    private fun heleketConfig() = HeleketBillingConfig(
        merchantId = "merchant-id",
        apiKey = "heleket-secret",
        callbackUrl = "https://api.example.com/v1/billing/webhooks/heleket",
        returnUrl = "https://example.com/return",
        successUrl = "https://example.com/success"
    )
}
