package com.jarvis.server.http

import com.jarvis.server.api.ApiErrorCode
import com.jarvis.server.api.BillingCheckoutRequest
import com.jarvis.server.api.BillingCheckoutResponse
import com.jarvis.server.api.BillingWebhookResponse
import com.jarvis.server.api.LicenseIssueRequest
import com.jarvis.server.api.LicenseIssueResponse
import com.jarvis.server.api.LicenseRedeemRequest
import com.jarvis.server.api.LicenseRedeemResponse
import com.jarvis.server.api.LicenseRevokeRequest
import com.jarvis.server.api.LicenseValidateRequest
import com.jarvis.server.api.LicenseValidateResponse
import com.jarvis.server.auth.AuthResult
import com.jarvis.server.auth.AuthenticatedClient
import com.jarvis.server.auth.Authenticator
import com.jarvis.server.auth.Authorizer
import com.jarvis.server.auth.Permission
import com.jarvis.server.billing.BillingEventApplyResult
import com.jarvis.server.billing.BillingOrder
import com.jarvis.server.billing.BillingProviderId
import com.jarvis.server.billing.BillingService
import com.jarvis.server.billing.CreateCheckoutOutcome
import com.jarvis.server.billing.HeleketWebhookVerifier
import com.jarvis.server.billing.PaddleWebhookVerifier
import com.jarvis.server.billing.WebhookVerificationResult
import com.jarvis.server.config.ValidationConfig
import com.jarvis.server.license.IssueLicenseCommand
import com.jarvis.server.license.LicenseService
import com.jarvis.server.license.LicenseValidationOutcome
import com.jarvis.server.license.RedeemOutcome
import com.jarvis.server.license.ValidationFailure
import com.jarvis.server.observability.StructuredLogger
import com.jarvis.server.ratelimit.RateLimitDecision
import com.jarvis.server.ratelimit.RateLimiter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class LicenseBillingHttpHandler(
    private val authenticator: Authenticator,
    private val authorizer: Authorizer,
    private val licenseService: LicenseService,
    private val billingService: BillingService,
    private val paddleWebhookVerifier: PaddleWebhookVerifier,
    private val heleketWebhookVerifier: HeleketWebhookVerifier,
    private val redeemRateLimiter: RateLimiter,
    private val authenticatedRateLimiter: RateLimiter,
    private val webhookRateLimiter: RateLimiter,
    private val validation: ValidationConfig,
    private val logger: StructuredLogger,
    private val json: Json
) {
    companion object {
        const val PATH_ISSUE = "/v1/admin/licenses/issue"
        const val PATH_REVOKE = "/v1/admin/licenses/revoke"
        const val PATH_REDEEM = "/v1/license/redeem"
        const val PATH_VALIDATE = "/v1/license/validate"
        const val PATH_CHECKOUT = "/v1/billing/checkout"
        const val PATH_PADDLE_WEBHOOK = "/v1/billing/webhooks/paddle"
        const val PATH_HELEKET_WEBHOOK = "/v1/billing/webhooks/heleket"

        private val PATHS = setOf(
            PATH_ISSUE, PATH_REVOKE, PATH_REDEEM, PATH_VALIDATE, PATH_CHECKOUT,
            PATH_PADDLE_WEBHOOK, PATH_HELEKET_WEBHOOK
        )
    }

    suspend fun handle(request: HttpRequestContext): HttpResponseContext? {
        if (request.path !in PATHS) return null
        val fallbackRequestId = UUID.randomUUID().toString()
        if (request.method != "POST") return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId, 405)
        if (request.contentLength > validation.maxBodyBytes ||
            request.body.toByteArray(StandardCharsets.UTF_8).size > validation.maxBodyBytes
        ) {
            return error(ApiErrorCode.PAYLOAD_TOO_LARGE, fallbackRequestId)
        }

        return try {
            when (request.path) {
                PATH_ISSUE -> issue(request, fallbackRequestId)
                PATH_REVOKE -> revoke(request, fallbackRequestId)
                PATH_REDEEM -> redeem(request, fallbackRequestId)
                PATH_VALIDATE -> validate(request, fallbackRequestId)
                PATH_CHECKOUT -> checkout(request, fallbackRequestId)
                PATH_PADDLE_WEBHOOK -> paddleWebhook(request)
                PATH_HELEKET_WEBHOOK -> heleketWebhook(request)
                else -> null
            }
        } catch (failure: Exception) {
            logger.error(
                "license/billing request failed",
                "path" to request.path,
                "requestId" to fallbackRequestId,
                "error" to failure.javaClass.simpleName
            )
            error(ApiErrorCode.INTERNAL_ERROR, fallbackRequestId)
        }
    }

    private fun issue(request: HttpRequestContext, fallbackRequestId: String): HttpResponseContext {
        val client = authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        if (!authorizer.isAllowed(client, Permission.MANAGE_LICENSES)) {
            return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        }
        rateLimit(authenticatedRateLimiter, "admin:${client.clientId}", fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, LicenseIssueRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        if (parsed.metadata.toString().toByteArray(StandardCharsets.UTF_8).size > 2_048) {
            return error(ApiErrorCode.INVALID_REQUEST, requestId)
        }
        val startsAt = parsed.startsAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val expiresAt = parsed.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if ((parsed.startsAt != null && startsAt == null) || (parsed.expiresAt != null && expiresAt == null)) {
            return error(ApiErrorCode.INVALID_REQUEST, requestId)
        }
        val issued = runCatching {
            licenseService.issue(
                IssueLicenseCommand(
                    planId = parsed.planId,
                    accountExternalRef = parsed.accountRef,
                    startsAt = startsAt,
                    expiresAt = expiresAt,
                    oneTime = parsed.oneTime,
                    metadataJson = parsed.metadata.toString(),
                    actorId = client.clientId,
                    requestId = requestId,
                    remoteAddress = request.remoteAddress
                )
            )
        }.getOrElse { return error(ApiErrorCode.INVALID_REQUEST, requestId) }
        return HttpResponseContext(
            201,
            json.encodeToString(
                LicenseIssueResponse.serializer(),
                LicenseIssueResponse(
                    licenseId = issued.licenseId.toString(),
                    code = issued.code,
                    status = issued.status.name,
                    planId = issued.planId,
                    issuedAt = issued.issuedAt.toString(),
                    expiresAt = issued.expiresAt?.toString(),
                    requestId = requestId
                )
            ),
            headers = mapOf("Cache-Control" to "no-store")
        )
    }

    private fun revoke(request: HttpRequestContext, fallbackRequestId: String): HttpResponseContext {
        val client = authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        if (!authorizer.isAllowed(client, Permission.MANAGE_LICENSES)) {
            return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        }
        rateLimit(authenticatedRateLimiter, "admin:${client.clientId}", fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, LicenseRevokeRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        val licenseId = runCatching { UUID.fromString(parsed.licenseId) }.getOrNull()
            ?: return error(ApiErrorCode.INVALID_REQUEST, requestId)
        if (parsed.reason.length !in 3..256) return error(ApiErrorCode.INVALID_REQUEST, requestId)
        return if (licenseService.revoke(
                licenseId, parsed.reason, client.clientId, requestId, request.remoteAddress
            )
        ) {
            HttpResponseContext(200, """{"success":true,"request_id":"$requestId"}""")
        } else {
            error(ApiErrorCode.LICENSE_NOT_REDEEMABLE, requestId)
        }
    }

    private fun redeem(request: HttpRequestContext, fallbackRequestId: String): HttpResponseContext {
        val rateKey = "redeem:${request.remoteAddress ?: "unknown"}"
        rateLimit(redeemRateLimiter, rateKey, fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, LicenseRedeemRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        if (parsed.code.length !in 8..64 || parsed.deviceId.length !in 8..128) {
            return error(ApiErrorCode.INVALID_REQUEST, requestId)
        }
        return when (val outcome = licenseService.redeem(
            parsed.code, parsed.deviceId, requestId, request.remoteAddress
        )) {
            is RedeemOutcome.Success -> HttpResponseContext(
                200,
                json.encodeToString(
                    LicenseRedeemResponse.serializer(),
                    LicenseRedeemResponse(
                        accessToken = outcome.accessToken,
                        planId = outcome.planId,
                        productId = outcome.productId,
                        startsAt = outcome.startsAt.toString(),
                        expiresAt = outcome.expiresAt.toString(),
                        billingStatus = outcome.billingStatus.name,
                        requestId = requestId
                    )
                ),
                headers = mapOf("Cache-Control" to "no-store")
            )
            RedeemOutcome.InvalidOrUnknown,
            RedeemOutcome.AlreadyRedeemed,
            RedeemOutcome.Expired,
            RedeemOutcome.RevokedOrDisabled,
            RedeemOutcome.InvalidPlan,
            RedeemOutcome.InvalidState -> error(ApiErrorCode.LICENSE_NOT_REDEEMABLE, requestId)
        }
    }

    private fun validate(request: HttpRequestContext, fallbackRequestId: String): HttpResponseContext {
        val client = authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        val accountId = client.accountId ?: return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        rateLimit(authenticatedRateLimiter, "validate:$accountId", fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, LicenseValidateRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        if (parsed.deviceId.length !in 8..128) return error(ApiErrorCode.INVALID_REQUEST, requestId)
        return when (val result = licenseService.validate(accountId, parsed.deviceId)) {
            is LicenseValidationOutcome.Valid -> {
                // V007 self-heal: device_id уже сверен с лицензией — привязываем
                // legacy-токен (до миграции) к устройству, чтобы enforcement-путь
                // (AI) начал его принимать. Одноразовая привязка (IS NULL).
                bearerLicenseToken(request)?.let { licenseService.bindTokenDevice(it, parsed.deviceId) }
                HttpResponseContext(
                    200,
                    json.encodeToString(
                        LicenseValidateResponse.serializer(),
                        LicenseValidateResponse(
                            planId = result.planId,
                            productId = result.productId,
                            startsAt = result.startsAt.toString(),
                            expiresAt = result.expiresAt.toString(),
                            billingStatus = result.billingStatus.name,
                            requestId = requestId
                        )
                    ),
                    headers = mapOf("Cache-Control" to "no-store")
                )
            }
            is LicenseValidationOutcome.Invalid -> error(mapValidationFailure(result.reason), requestId)
        }
    }

    /** Сырой jrv_-токен из заголовка (для одноразовой привязки устройства). */
    private fun bearerLicenseToken(request: HttpRequestContext): String? =
        request.authorizationHeader
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring("Bearer ".length)?.trim()
            ?.takeIf { it.startsWith("jrv_") }

    private suspend fun checkout(request: HttpRequestContext, fallbackRequestId: String): HttpResponseContext {
        val client = authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        if (!authorizer.isAllowed(client, Permission.CREATE_BILLING_CHECKOUT)) {
            return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        }
        val accountId = client.accountId ?: return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        rateLimit(authenticatedRateLimiter, "checkout:$accountId", fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, BillingCheckoutRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        val provider = runCatching { BillingProviderId.valueOf(parsed.provider.uppercase()) }.getOrNull()
            ?: return error(ApiErrorCode.INVALID_REQUEST, requestId)
        if (provider == BillingProviderId.LOCAL_TMT) {
            return error(ApiErrorCode.BILLING_PROVIDER_UNAVAILABLE, requestId)
        }
        return when (val result = billingService.createCheckout(
            accountId, parsed.planId, provider, parsed.idempotencyKey
        )) {
            is CreateCheckoutOutcome.Success -> HttpResponseContext(
                if (result.order.status == com.jarvis.server.billing.BillingOrderStatus.PAID) 200 else 201,
                json.encodeToString(
                    BillingCheckoutResponse.serializer(),
                    BillingCheckoutResponse(
                        orderId = result.order.id.toString(),
                        status = result.order.status.name,
                        provider = result.order.provider.name,
                        checkoutUrl = result.order.checkoutUrl,
                        requestId = requestId
                    )
                ),
                headers = mapOf("Cache-Control" to "no-store")
            )
            is CreateCheckoutOutcome.InProgress ->
                acceptedCheckout(result.order, "PROCESSING", requestId)
            is CreateCheckoutOutcome.ReconciliationRequired ->
                acceptedCheckout(result.order, "RECONCILIATION_REQUIRED", requestId)
            CreateCheckoutOutcome.ProviderUnavailable ->
                error(ApiErrorCode.BILLING_PROVIDER_UNAVAILABLE, requestId)
            CreateCheckoutOutcome.NoActiveLicense -> error(ApiErrorCode.LICENSE_NOT_REDEEMABLE, requestId)
            CreateCheckoutOutcome.UnknownPlan,
            CreateCheckoutOutcome.InvalidRequest -> error(ApiErrorCode.INVALID_REQUEST, requestId)
            CreateCheckoutOutcome.ProviderFailure -> error(ApiErrorCode.PROVIDER_ERROR, requestId)
        }
    }

    private fun acceptedCheckout(
        order: BillingOrder,
        status: String,
        requestId: String
    ) = HttpResponseContext(
        202,
        json.encodeToString(
            BillingCheckoutResponse.serializer(),
            BillingCheckoutResponse(
                orderId = order.id.toString(),
                status = status,
                provider = order.provider.name,
                checkoutUrl = null,
                requestId = requestId
            )
        ),
        headers = mapOf("Cache-Control" to "no-store")
    )

    private fun paddleWebhook(request: HttpRequestContext): HttpResponseContext {
        rateLimit(webhookRateLimiter, "paddle:${request.remoteAddress ?: "unknown"}", "-")?.let { return it }
        return applyWebhook(
            paddleWebhookVerifier.verify(request.body, request.header("Paddle-Signature"))
        )
    }

    private fun heleketWebhook(request: HttpRequestContext): HttpResponseContext {
        rateLimit(webhookRateLimiter, "heleket:${request.remoteAddress ?: "unknown"}", "-")?.let { return it }
        return applyWebhook(heleketWebhookVerifier.verify(request.body, request.remoteAddress))
    }

    private fun applyWebhook(result: WebhookVerificationResult): HttpResponseContext = when (result) {
        is WebhookVerificationResult.Invalid -> {
            val code = if (result.code == "PAYLOAD_TOO_LARGE") ApiErrorCode.PAYLOAD_TOO_LARGE
            else ApiErrorCode.BILLING_EVENT_INVALID
            error(code, "-")
        }
        is WebhookVerificationResult.Valid -> when (val applied = billingService.applyVerifiedEvent(result.event)) {
            BillingEventApplyResult.PROCESSED,
            BillingEventApplyResult.DUPLICATE,
            BillingEventApplyResult.UNKNOWN_ORDER -> HttpResponseContext(
                200,
                json.encodeToString(
                    BillingWebhookResponse.serializer(),
                    BillingWebhookResponse(result = applied.name)
                )
            )
            BillingEventApplyResult.INVALID_STATE -> error(ApiErrorCode.LICENSE_INVALID_STATE, "-")
        }
    }

    private fun authenticate(request: HttpRequestContext, requestId: String): AuthenticatedClient? =
        when (val result = authenticator.authenticate(request.authorizationHeader)) {
            is AuthResult.Success -> result.client
            AuthResult.InvalidCredentials, AuthResult.MissingCredentials -> {
                logger.warn("license/billing unauthorized", "path" to request.path, "requestId" to requestId)
                null
            }
        }

    private fun unauthorized(requestId: String) = error(ApiErrorCode.UNAUTHORIZED, requestId)

    private fun rateLimit(
        limiter: RateLimiter,
        key: String,
        requestId: String
    ): HttpResponseContext? = when (val result = limiter.check(key)) {
        RateLimitDecision.Allowed -> null
        is RateLimitDecision.Limited -> error(
            ApiErrorCode.RATE_LIMITED,
            requestId,
            headers = mapOf("Retry-After" to result.retryAfterSeconds.toString())
        )
    }

    private fun mapValidationFailure(failure: ValidationFailure): ApiErrorCode = when (failure) {
        ValidationFailure.NO_LICENSE -> ApiErrorCode.LICENSE_NOT_REDEEMABLE
        ValidationFailure.EXPIRED -> ApiErrorCode.LICENSE_EXPIRED
        ValidationFailure.REVOKED_OR_DISABLED,
        ValidationFailure.ACCOUNT_DISABLED -> ApiErrorCode.LICENSE_REVOKED
        ValidationFailure.WRONG_DEVICE -> ApiErrorCode.LICENSE_WRONG_DEVICE
        ValidationFailure.BILLING_INACTIVE -> ApiErrorCode.PAYMENT_REQUIRED
        ValidationFailure.INVALID_PLAN,
        ValidationFailure.INVALID_STATE -> ApiErrorCode.LICENSE_INVALID_STATE
    }

    private fun <T> decode(body: String, serializer: KSerializer<T>): T? =
        runCatching { json.decodeFromString(serializer, body) }.getOrNull()

    private fun validRequestId(value: String?): String? =
        value?.takeIf { it.isNotBlank() && it.length <= 64 && it.none(Char::isISOControl) }

    private fun error(
        code: ApiErrorCode,
        requestId: String,
        statusOverride: Int? = null,
        headers: Map<String, String> = emptyMap()
    ) = HttpResponseContext(
        statusOverride ?: code.httpStatus,
        json.encodeToString(
            com.jarvis.server.api.ApiErrorResponse.serializer(),
            code.toResponse(requestId)
        ),
        headers
    )
}
