package com.jarvis.server.billing

import com.jarvis.server.license.BillingPlan
import com.jarvis.server.provider.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

data class PaddleBillingConfig(
    val apiKey: String?,
    val webhookSecret: String?,
    val apiBaseUrl: String = "https://api.paddle.com",
    val requestTimeoutMs: Long = 15_000,
    val webhookToleranceSeconds: Long = 300,
    val allowedCheckoutHosts: Set<String> = emptySet()
) {
    init {
        require(apiBaseUrl == "https://api.paddle.com" || apiBaseUrl == "https://sandbox-api.paddle.com") {
            "Paddle base URL must be an official live or sandbox endpoint"
        }
        require(requestTimeoutMs in 1_000..60_000)
        require(webhookToleranceSeconds in 5..900)
        require(allowedCheckoutHosts.all { host ->
            host.matches(Regex("[a-z0-9.-]{3,253}")) && !host.startsWith('.') && !host.endsWith('.')
        }) { "Invalid Paddle checkout host" }
    }

    val configured: Boolean get() = !apiKey.isNullOrBlank() && !webhookSecret.isNullOrBlank()
}

data class HeleketBillingConfig(
    val merchantId: String?,
    val apiKey: String?,
    val callbackUrl: String,
    val returnUrl: String,
    val successUrl: String,
    val apiBaseUrl: String = "https://api.heleket.com",
    val requestTimeoutMs: Long = 15_000,
    val invoiceLifetimeSeconds: Int = 3_600,
    val allowedWebhookIps: Set<String> = setOf("31.133.220.8"),
    val enforceWebhookIp: Boolean = true
) {
    init {
        require(apiBaseUrl == "https://api.heleket.com") { "Heleket base URL must be official" }
        listOf(callbackUrl, returnUrl, successUrl).forEach {
            require(it.startsWith("https://") && it.length <= 255) { "Heleket URLs must use HTTPS" }
        }
        require(requestTimeoutMs in 1_000..60_000)
        require(invoiceLifetimeSeconds in 300..43_200)
    }

    val configured: Boolean get() = !merchantId.isNullOrBlank() && !apiKey.isNullOrBlank()
}

class PaddleBillingProvider(
    private val config: PaddleBillingConfig,
    private val transport: HttpTransport,
    private val json: Json
) : BillingProvider {
    override val id: BillingProviderId = BillingProviderId.PADDLE
    override fun isConfigured(): Boolean = config.configured

    override suspend fun createCheckout(order: BillingOrder, plan: BillingPlan): ProviderCheckout {
        if (!isConfigured()) throw BillingProviderException(false, "Paddle is not configured")
        val priceId = plan.paddlePriceId
            ?: throw BillingProviderException(false, "Paddle price is not configured")
        if (!priceId.matches(Regex("pri_[a-z0-9]{10,64}"))) {
            throw BillingProviderException(false, "Invalid Paddle price ID")
        }
        val body = buildJsonObject {
            putJsonArray("items") {
                add(buildJsonObject {
                    put("price_id", priceId)
                    put("quantity", 1)
                })
            }
            put("collection_mode", "automatic")
            putJsonObject("custom_data") {
                put("jarvis_order_id", order.id.toString())
                put("jarvis_account_id", order.accountId.toString())
                put("jarvis_plan_id", plan.id)
            }
        }.toString()
        val response = transport.post(
            url = "${config.apiBaseUrl}/transactions",
            headers = mapOf(
                "Authorization" to "Bearer ${config.apiKey}",
                "Content-Type" to "application/json"
            ),
            body = body,
            connectTimeoutMs = 3_000,
            requestTimeoutMs = config.requestTimeoutMs
        )
        if (response.status !in 200..299) {
            throw BillingProviderException(
                ambiguous = response.status >= 500,
                message = "Paddle checkout failed with HTTP ${response.status}"
            )
        }
        return try {
            val data = json.parseToJsonElement(response.body).jsonObject["data"]?.jsonObject
                ?: error("Paddle response has no data")
            val providerId = data["id"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.matches(Regex("txn_[a-z0-9]{10,64}")) }
                ?: error("Paddle response has invalid transaction ID")
            val checkoutUrl = data["checkout"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                ?.takeIf(::isTrustedPaddleCheckoutUrl)
                ?: error("Paddle response has invalid checkout URL")
            val subscriptionId = data["subscription_id"]?.jsonPrimitive?.contentOrNull
            ProviderCheckout(providerId, checkoutUrl, subscriptionId)
        } catch (failure: BillingProviderException) {
            throw failure
        } catch (failure: Exception) {
            throw BillingProviderException(true, "Paddle returned an ambiguous response", failure)
        }
    }

    private fun isTrustedPaddleCheckoutUrl(url: String): Boolean = runCatching {
        val parsed = java.net.URI(url)
        parsed.scheme == "https" && parsed.host != null &&
            (parsed.host == "paddle.com" || parsed.host.endsWith(".paddle.com") ||
                parsed.host in config.allowedCheckoutHosts)
    }.getOrDefault(false)
}

class HeleketBillingProvider(
    private val config: HeleketBillingConfig,
    private val transport: HttpTransport,
    private val json: Json
) : BillingProvider {
    override val id: BillingProviderId = BillingProviderId.HELEKET
    override fun isConfigured(): Boolean = config.configured

    override suspend fun createCheckout(order: BillingOrder, plan: BillingPlan): ProviderCheckout {
        if (!isConfigured()) throw BillingProviderException(false, "Heleket is not configured")
        val targetCurrency = plan.heleketCurrency
            ?: throw BillingProviderException(false, "Heleket currency is not configured")
        if (!targetCurrency.matches(Regex("[A-Z0-9]{2,16}"))) {
            throw BillingProviderException(false, "Invalid Heleket target currency")
        }
        val amount = BigDecimal.valueOf(order.amountMinor, 2).toPlainString()
        val body = buildJsonObject {
            put("amount", amount)
            put("currency", order.currency)
            put("order_id", order.id.toString())
            put("to_currency", targetCurrency)
            put("url_callback", config.callbackUrl)
            put("url_return", config.returnUrl)
            put("url_success", config.successUrl)
            put("is_payment_multiple", false)
            put("lifetime", config.invoiceLifetimeSeconds)
            put("accuracy_payment_percent", 0)
            put("additional_data", "plan:${plan.id}")
        }.toString()
        val response = transport.post(
            url = "${config.apiBaseUrl}/v1/payment",
            headers = mapOf(
                "merchant" to requireNotNull(config.merchantId),
                "sign" to heleketSign(body, requireNotNull(config.apiKey)),
                "Content-Type" to "application/json"
            ),
            body = body,
            connectTimeoutMs = 3_000,
            requestTimeoutMs = config.requestTimeoutMs
        )
        if (response.status !in 200..299) {
            throw BillingProviderException(
                ambiguous = response.status >= 500,
                message = "Heleket checkout failed with HTTP ${response.status}"
            )
        }
        val root = try {
            json.parseToJsonElement(response.body).jsonObject
        } catch (failure: Exception) {
            throw BillingProviderException(true, "Heleket returned malformed JSON", failure)
        }
        val state = root["state"]?.jsonPrimitive?.intOrNull
            ?: throw BillingProviderException(true, "Heleket returned an invalid state")
        if (state != 0) {
            throw BillingProviderException(false, "Heleket rejected invoice")
        }
        return try {
            val result = root["result"]?.jsonObject ?: error("Heleket response has no result")
            val providerId = result["uuid"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { runCatching { java.util.UUID.fromString(it) }.isSuccess }
                ?: error("Heleket response has invalid UUID")
            val checkoutUrl = result["url"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(::isTrustedHeleketCheckoutUrl)
                ?: error("Heleket response has invalid checkout URL")
            ProviderCheckout(providerId, checkoutUrl)
        } catch (failure: Exception) {
            throw BillingProviderException(true, "Heleket returned an ambiguous response", failure)
        }
    }

    private fun isTrustedHeleketCheckoutUrl(url: String): Boolean = runCatching {
        val parsed = java.net.URI(url)
        parsed.scheme == "https" && parsed.host != null &&
            (parsed.host == "heleket.com" || parsed.host.endsWith(".heleket.com"))
    }.getOrDefault(false)
}

internal fun heleketSign(body: String, apiKey: String): String {
    val encoded = Base64.getEncoder().encodeToString(body.toByteArray(StandardCharsets.UTF_8))
    return MessageDigest.getInstance("MD5")
        .digest((encoded + apiKey).toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
