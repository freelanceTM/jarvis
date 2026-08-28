package com.jarvis.server.billing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

sealed interface WebhookVerificationResult {
    data class Valid(val event: VerifiedBillingEvent) : WebhookVerificationResult
    data class Invalid(val code: String) : WebhookVerificationResult
}

class PaddleWebhookVerifier(
    private val config: PaddleBillingConfig,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC()
) {
    fun verify(rawBody: String, signatureHeader: String?): WebhookVerificationResult {
        val secret = config.webhookSecret?.takeIf { it.isNotBlank() }
            ?: return WebhookVerificationResult.Invalid("NOT_CONFIGURED")
        if (rawBody.toByteArray(StandardCharsets.UTF_8).size > 1024 * 1024) {
            return WebhookVerificationResult.Invalid("PAYLOAD_TOO_LARGE")
        }
        val parts = signatureHeader.orEmpty().split(';')
            .mapNotNull { item ->
                val pair = item.trim().split('=', limit = 2)
                if (pair.size == 2) pair[0] to pair[1] else null
            }
        val timestamp = parts.firstOrNull { it.first == "ts" }?.second?.toLongOrNull()
            ?: return WebhookVerificationResult.Invalid("MALFORMED_SIGNATURE")
        val signatures = parts.filter { it.first == "h1" }.map { it.second }
        if (signatures.isEmpty()) return WebhookVerificationResult.Invalid("MALFORMED_SIGNATURE")
        val nowSeconds = clock.instant().epochSecond
        if (timestamp < nowSeconds - config.webhookToleranceSeconds ||
            timestamp > nowSeconds + config.webhookToleranceSeconds
        ) {
            return WebhookVerificationResult.Invalid("STALE_SIGNATURE")
        }
        val expected = hmacSha256Hex(secret, "$timestamp:$rawBody")
        if (signatures.none { constantTimeHexEquals(expected, it) }) {
            return WebhookVerificationResult.Invalid("INVALID_SIGNATURE")
        }

        val root = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull()
            ?: return WebhookVerificationResult.Invalid("MALFORMED_BODY")
        val eventId = root["event_id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.length in 8..128 }
            ?: return WebhookVerificationResult.Invalid("MALFORMED_EVENT")
        val eventType = root["event_type"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.length in 3..96 }
            ?: return WebhookVerificationResult.Invalid("MALFORMED_EVENT")
        val occurredAt = root["occurred_at"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val data = root["data"]?.jsonObject ?: return WebhookVerificationResult.Invalid("MALFORMED_EVENT")

        val normalized = when (eventType) {
            "transaction.completed" -> Triple(
                BillingEventKind.PAID,
                data["id"]?.jsonPrimitive?.contentOrNull,
                data["subscription_id"]?.jsonPrimitive?.contentOrNull
            )
            "transaction.payment_failed" -> Triple(
                BillingEventKind.PAYMENT_FAILED,
                data["id"]?.jsonPrimitive?.contentOrNull,
                data["subscription_id"]?.jsonPrimitive?.contentOrNull
            )
            "transaction.canceled" -> Triple(
                BillingEventKind.CANCELED,
                data["id"]?.jsonPrimitive?.contentOrNull,
                data["subscription_id"]?.jsonPrimitive?.contentOrNull
            )
            "subscription.canceled", "subscription.paused", "subscription.past_due" -> Triple(
                if (eventType == "subscription.past_due") BillingEventKind.PAYMENT_FAILED else BillingEventKind.CANCELED,
                null,
                data["id"]?.jsonPrimitive?.contentOrNull
            )
            "adjustment.updated" -> {
                val action = data["action"]?.jsonPrimitive?.contentOrNull
                val status = data["status"]?.jsonPrimitive?.contentOrNull
                Triple(
                    if (action == "refund" && status == "approved") BillingEventKind.REFUNDED
                    else BillingEventKind.IGNORED,
                    data["transaction_id"]?.jsonPrimitive?.contentOrNull,
                    data["subscription_id"]?.jsonPrimitive?.contentOrNull
                )
            }
            else -> Triple(BillingEventKind.IGNORED, data["id"]?.jsonPrimitive?.contentOrNull, null)
        }
        if (normalized.second == null && normalized.third == null) {
            return WebhookVerificationResult.Invalid("MALFORMED_EVENT")
        }
        return WebhookVerificationResult.Valid(
            VerifiedBillingEvent(
                provider = BillingProviderId.PADDLE,
                providerEventId = eventId,
                providerOrderId = normalized.second,
                eventType = eventType,
                kind = normalized.first,
                payloadHash = sha256(rawBody),
                occurredAt = occurredAt,
                providerSubscriptionId = normalized.third,
                localOrderId = (data["custom_data"] as? JsonObject)
                    ?.get("jarvis_order_id")?.jsonPrimitive?.contentOrNull
                    ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            )
        )
    }
}

class HeleketWebhookVerifier(
    private val config: HeleketBillingConfig,
    private val json: Json
) {
    fun verify(rawBody: String, remoteAddress: String?): WebhookVerificationResult {
        val apiKey = config.apiKey?.takeIf { it.isNotBlank() }
            ?: return WebhookVerificationResult.Invalid("NOT_CONFIGURED")
        if (config.enforceWebhookIp && remoteAddress !in config.allowedWebhookIps) {
            return WebhookVerificationResult.Invalid("UNTRUSTED_SOURCE")
        }
        if (rawBody.toByteArray(StandardCharsets.UTF_8).size > 256 * 1024) {
            return WebhookVerificationResult.Invalid("PAYLOAD_TOO_LARGE")
        }
        val root = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull()
            ?: return WebhookVerificationResult.Invalid("MALFORMED_BODY")
        val suppliedSignature = root["sign"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) }
            ?: return WebhookVerificationResult.Invalid("MALFORMED_SIGNATURE")
        val unsigned = JsonObject(LinkedHashMap(root).apply { remove("sign") })
        // Heleket's PHP encoder escapes slashes before signing.
        val canonical = json.encodeToString(JsonObject.serializer(), unsigned).replace("/", "\\/")
        val expected = heleketSign(canonical, apiKey)
        if (!constantTimeHexEquals(expected, suppliedSignature)) {
            return WebhookVerificationResult.Invalid("INVALID_SIGNATURE")
        }

        val type = root["type"]?.jsonPrimitive?.contentOrNull
        if (type != "payment") return WebhookVerificationResult.Invalid("INVALID_TYPE")
        val uuid = root["uuid"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { runCatching { java.util.UUID.fromString(it) }.isSuccess }
            ?: return WebhookVerificationResult.Invalid("MALFORMED_EVENT")
        val orderId = root["order_id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { runCatching { java.util.UUID.fromString(it) }.isSuccess }
            ?: return WebhookVerificationResult.Invalid("MALFORMED_EVENT")
        val status = root["status"]?.jsonPrimitive?.contentOrNull
            ?: return WebhookVerificationResult.Invalid("MALFORMED_EVENT")
        val isFinal = root["is_final"]?.jsonPrimitive?.booleanOrNull ?: false
        val txid = root["txid"]?.jsonPrimitive?.contentOrNull
        val kind = when (status) {
            "paid", "paid_over" -> if (isFinal) BillingEventKind.PAID else BillingEventKind.IGNORED
            "fail", "wrong_amount", "system_fail" -> BillingEventKind.PAYMENT_FAILED
            "cancel" -> BillingEventKind.CANCELED
            "refund_paid" -> BillingEventKind.REFUNDED
            else -> BillingEventKind.IGNORED
        }
        val amountMinor = root["amount"]?.jsonPrimitive?.contentOrNull?.let(::toMinorExact)
            ?: return WebhookVerificationResult.Invalid("INVALID_AMOUNT")
        val currency = root["currency"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.matches(Regex("[A-Z]{3}")) }
            ?: return WebhookVerificationResult.Invalid("INVALID_CURRENCY")
        val eventId = listOf(uuid, status, txid ?: root["payment_amount"]?.jsonPrimitive?.contentOrNull.orEmpty())
            .joinToString(":").take(192)
        return WebhookVerificationResult.Valid(
            VerifiedBillingEvent(
                provider = BillingProviderId.HELEKET,
                providerEventId = eventId,
                providerOrderId = uuid,
                eventType = status,
                kind = kind,
                payloadHash = sha256(rawBody),
                occurredAt = null,
                localOrderId = java.util.UUID.fromString(orderId),
                expectedAmountMinor = amountMinor,
                expectedCurrency = currency
            )
        )
    }

    private fun toMinorExact(value: String): Long? = runCatching {
        BigDecimal(value).movePointRight(2).longValueExact()
    }.getOrNull()
}

internal fun hmacSha256Hex(secret: String, payload: String): String = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    doFinal(payload.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal fun constantTimeHexEquals(expected: String, supplied: String): Boolean =
    MessageDigest.isEqual(
        expected.lowercase().toByteArray(StandardCharsets.US_ASCII),
        supplied.lowercase().toByteArray(StandardCharsets.US_ASCII)
    )

private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
