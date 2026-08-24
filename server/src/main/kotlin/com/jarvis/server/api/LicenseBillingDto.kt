package com.jarvis.server.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class LicenseIssueRequest(
    @SerialName("plan_id") val planId: String,
    @SerialName("account_ref") val accountRef: String? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("one_time") val oneTime: Boolean = true,
    @SerialName("metadata") val metadata: JsonObject = JsonObject(emptyMap()),
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
data class LicenseIssueResponse(
    @SerialName("success") val success: Boolean = true,
    @SerialName("license_id") val licenseId: String,
    /** Shown once and never persisted in plaintext. */
    @SerialName("code") val code: String,
    @SerialName("status") val status: String,
    @SerialName("plan_id") val planId: String,
    @SerialName("issued_at") val issuedAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("request_id") val requestId: String
)

@Serializable
data class LicenseRedeemRequest(
    @SerialName("code") val code: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
data class LicenseRedeemResponse(
    @SerialName("success") val success: Boolean = true,
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("plan_id") val planId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("billing_status") val billingStatus: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
data class LicenseValidateRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
data class LicenseValidateResponse(
    @SerialName("success") val success: Boolean = true,
    @SerialName("valid") val valid: Boolean = true,
    @SerialName("plan_id") val planId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("billing_status") val billingStatus: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
data class LicenseRevokeRequest(
    @SerialName("license_id") val licenseId: String,
    @SerialName("reason") val reason: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
data class BillingCheckoutRequest(
    @SerialName("plan_id") val planId: String,
    @SerialName("provider") val provider: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
data class BillingCheckoutResponse(
    @SerialName("success") val success: Boolean = true,
    @SerialName("order_id") val orderId: String,
    @SerialName("status") val status: String,
    @SerialName("provider") val provider: String,
    @SerialName("checkout_url") val checkoutUrl: String? = null,
    @SerialName("request_id") val requestId: String
)

@Serializable
data class BillingWebhookResponse(
    @SerialName("success") val success: Boolean = true,
    @SerialName("result") val result: String
)
