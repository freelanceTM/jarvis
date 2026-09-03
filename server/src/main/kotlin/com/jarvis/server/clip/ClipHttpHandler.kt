package com.jarvis.server.clip

import com.jarvis.server.auth.AuthResult
import com.jarvis.server.auth.Authenticator
import com.jarvis.server.auth.Authorizer
import com.jarvis.server.auth.AuthenticatedClient
import com.jarvis.server.auth.Permission
import com.jarvis.server.api.ApiErrorCode
import com.jarvis.server.http.HttpRequestContext
import com.jarvis.server.http.HttpResponseContext
import com.jarvis.server.ratelimit.RateLimitDecision
import com.jarvis.server.ratelimit.RateLimiter
import com.jarvis.server.config.ValidationConfig
import com.jarvis.server.observability.StructuredLogger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/* ------------------------------------------------------------------ DTO */

@Serializable
private data class ClipProvisionRequest(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("public_key") val publicKey: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
private data class ClipProvisionResponse(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("status") val status: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class ClipChallengeRequest(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
private data class ClipChallengeResponse(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("issued_at_ms") val issuedAtMs: Long,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class ClipAttestRequest(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("signature") val signature: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
private data class ClipAttestResponse(
    @SerialName("valid") val valid: Boolean,
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("status") val status: String,
    @SerialName("bound_now") val boundNow: Boolean,
    @SerialName("public_key") val publicKey: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class ClipRevokeRequest(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("reason") val reason: String = "",
    @SerialName("request_id") val requestId: String? = null
)

/* --------------------------------------------------------------- handler */

/**
 * OMNIX Clip: криптографическая привязка устройства (V008).
 *
 * Телефон не доверяет имени/MAC — только подписи challenge зарегистрированным
 * ключом. Provision/revoke — manufacturing/support (ADMIN), challenge/attest —
 * аутентифицированный аккаунт (первый attest привязывает клип к аккаунту).
 */
class ClipHttpHandler(
    private val authenticator: Authenticator,
    private val authorizer: Authorizer,
    private val attestationService: ClipAttestationService,
    private val rateLimiter: RateLimiter,
    private val validation: ValidationConfig,
    private val logger: StructuredLogger,
    private val json: Json
) {
    companion object {
        const val PATH_PROVISION = "/v1/admin/clips/provision"
        const val PATH_REVOKE = "/v1/admin/clips/revoke"
        const val PATH_CHALLENGE = "/v1/clip/challenge"
        const val PATH_ATTEST = "/v1/clip/attest"

        val HANDLED_PATHS = setOf(PATH_PROVISION, PATH_REVOKE, PATH_CHALLENGE, PATH_ATTEST)
    }

    fun handle(request: HttpRequestContext): HttpResponseContext? {
        if (request.path !in HANDLED_PATHS) return null
        val fallbackRequestId = UUID.randomUUID().toString()
        if (request.contentLength > validation.maxBodyBytes ||
            request.body.toByteArray(StandardCharsets.UTF_8).size > validation.maxBodyBytes
        ) {
            return error(ApiErrorCode.PAYLOAD_TOO_LARGE, fallbackRequestId)
        }
        return try {
            when (request.path) {
                PATH_PROVISION -> provision(request, fallbackRequestId)
                PATH_REVOKE -> revoke(request, fallbackRequestId)
                PATH_CHALLENGE -> challenge(request, fallbackRequestId)
                PATH_ATTEST -> attest(request, fallbackRequestId)
                else -> null
            }
        } catch (failure: Exception) {
            logger.error(
                "clip request failed",
                "path" to request.path,
                "requestId" to fallbackRequestId,
                "error" to failure.javaClass.simpleName
            )
            error(ApiErrorCode.INTERNAL_ERROR, fallbackRequestId)
        }
    }

    private fun provision(request: HttpRequestContext, fallbackRequestId: String): HttpResponseContext {
        val client = authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        if (!authorizer.isAllowed(client, Permission.MANAGE_LICENSES)) {
            return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        }
        rateLimit("clip-admin:${client.clientId}", fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, ClipProvisionRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        return when (val outcome = attestationService.provision(parsed.clipSerial, parsed.publicKey)) {
            is ClipProvisionOutcome.Created -> HttpResponseContext(
                201,
                json.encodeToString(
                    ClipProvisionResponse.serializer(),
                    ClipProvisionResponse(
                        clipSerial = outcome.clip.clipSerial,
                        status = outcome.clip.status.name,
                        requestId = requestId
                    )
                ),
                headers = mapOf("Cache-Control" to "no-store")
            )
            ClipProvisionOutcome.AlreadyExists ->
                error(ApiErrorCode.CLIP_ALREADY_PROVISIONED, requestId)
            ClipProvisionOutcome.InvalidPublicKey ->
                error(ApiErrorCode.INVALID_REQUEST, requestId)
        }
    }

    private fun revoke(request: HttpRequestContext, fallbackRequestId: String): HttpResponseContext {
        val client = authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        if (!authorizer.isAllowed(client, Permission.MANAGE_LICENSES)) {
            return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        }
        rateLimit("clip-admin:${client.clientId}", fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, ClipRevokeRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        return if (attestationService.revoke(parsed.clipSerial)) {
            HttpResponseContext(
                200,
                json.encodeToString(
                    ClipProvisionResponse.serializer(),
                    ClipProvisionResponse(
                        clipSerial = parsed.clipSerial.trim(),
                        status = ClipDeviceStatus.REVOKED.name,
                        requestId = requestId
                    )
                ),
                headers = mapOf("Cache-Control" to "no-store")
            )
        } else {
            error(ApiErrorCode.CLIP_UNKNOWN, requestId)
        }
    }

    private fun challenge(request: HttpRequestContext, fallbackRequestId: String): HttpResponseContext {
        val client = authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        rateLimit("clip-challenge:${client.clientId}", fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, ClipChallengeRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        return when (val outcome = attestationService.createChallenge(parsed.clipSerial)) {
            is ClipChallengeOutcome.Issued -> HttpResponseContext(
                200,
                json.encodeToString(
                    ClipChallengeResponse.serializer(),
                    ClipChallengeResponse(
                        clipSerial = outcome.challenge.clipSerial,
                        challengeId = outcome.challenge.challengeId.toString(),
                        nonce = Base64.getEncoder().encodeToString(outcome.challenge.nonce),
                        issuedAtMs = outcome.challenge.issuedAtMs,
                        expiresAt = outcome.challenge.expiresAt.toString(),
                        requestId = requestId
                    )
                ),
                headers = mapOf("Cache-Control" to "no-store")
            )
            ClipChallengeOutcome.UnknownClip -> error(ApiErrorCode.CLIP_UNKNOWN, requestId)
            ClipChallengeOutcome.ClipRevoked -> error(ApiErrorCode.CLIP_REVOKED, requestId)
        }
    }

    private fun attest(request: HttpRequestContext, fallbackRequestId: String): HttpResponseContext {
        val client = authenticate(request, fallbackRequestId) ?: return unauthorized(fallbackRequestId)
        val accountId = client.accountId
            ?: return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
        rateLimit("clip-attest:${client.clientId}", fallbackRequestId)?.let { return it }
        val parsed = decode(request.body, ClipAttestRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        val challengeId = runCatching { UUID.fromString(parsed.challengeId.trim()) }.getOrNull()
            ?: return error(ApiErrorCode.INVALID_REQUEST, requestId)
        return when (val outcome = attestationService.attest(parsed.clipSerial, challengeId, parsed.signature, accountId)) {
            is ClipAttestOutcome.Valid -> HttpResponseContext(
                200,
                json.encodeToString(
                    ClipAttestResponse.serializer(),
                    ClipAttestResponse(
                        valid = true,
                        clipSerial = outcome.clipSerial,
                        status = outcome.status.name,
                        boundNow = outcome.boundNow,
                        publicKey = outcome.publicKeyBase64,
                        requestId = requestId
                    )
                ),
                headers = mapOf("Cache-Control" to "no-store")
            )
            ClipAttestOutcome.UnknownClip -> error(ApiErrorCode.CLIP_UNKNOWN, requestId)
            ClipAttestOutcome.ClipRevoked -> error(ApiErrorCode.CLIP_REVOKED, requestId)
            ClipAttestOutcome.ChallengeInvalid -> error(ApiErrorCode.CLIP_CHALLENGE_INVALID, requestId)
            ClipAttestOutcome.BadSignature -> error(ApiErrorCode.CLIP_BAD_SIGNATURE, requestId)
            ClipAttestOutcome.OwnerMismatch -> error(ApiErrorCode.CLIP_OWNER_MISMATCH, requestId)
        }
    }

    /* ------------------------------------------------------------- helpers */

    private fun authenticate(request: HttpRequestContext, requestId: String): AuthenticatedClient? =
        when (val result = authenticator.authenticate(request.authorizationHeader)) {
            is AuthResult.Success -> result.client
            AuthResult.InvalidCredentials, AuthResult.MissingCredentials -> {
                logger.warn(
                    "clip unauthorized",
                    "path" to request.path,
                    "requestId" to requestId
                )
                null
            }
        }

    private fun unauthorized(requestId: String) = error(ApiErrorCode.UNAUTHORIZED, requestId)

    private fun rateLimit(key: String, requestId: String): HttpResponseContext? =
        when (val decision = rateLimiter.check(key)) {
            RateLimitDecision.Allowed -> null
            is RateLimitDecision.Limited -> error(
                ApiErrorCode.RATE_LIMITED,
                requestId,
                headers = mapOf("Retry-After" to decision.retryAfterSeconds.toString())
            )
        }

    private fun <T> decode(body: String, serializer: KSerializer<T>): T? =
        runCatching { json.decodeFromString(serializer, body) }.getOrNull()

    private fun validRequestId(value: String?): String? =
        value?.takeIf { it.isNotBlank() && it.length <= 64 && it.none(Char::isISOControl) }

    private fun error(
        code: ApiErrorCode,
        requestId: String,
        headers: Map<String, String> = emptyMap()
    ) = HttpResponseContext(
        code.httpStatus,
        json.encodeToString(
            com.jarvis.server.api.ApiErrorResponse.serializer(),
            code.toResponse(requestId)
        ),
        headers
    )
}
