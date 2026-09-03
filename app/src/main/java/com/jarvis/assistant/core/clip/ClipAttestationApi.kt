package com.jarvis.assistant.core.clip

import com.jarvis.assistant.core.constants.AppConstants
import com.jarvis.assistant.core.network.readUtf8Bounded
import com.jarvis.assistant.core.security.SecurityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Транспорт подписи к Clip. КОНТРАКТ ДЛЯ FIRMWARE (в репо firmware/SDK Clip
 * отсутствует — честная граница, см. docs/OMNIX_WEARABLE_AUDIT_v0.3.md §1.3).
 *
 * Реализация появится вместе с firmware: BLE GATT-запись канонического
 * сообщения → ответ DER-подпись (base64). Имя/MAC устройством доверия НЕ
 * являются и в проверку не входят.
 */
interface ClipTransport {

    /**
     * Просит Clip подписать каноническое сообщение приватным ключом.
     * Реализация обязана быть честной: нет соединения/firmware —
     * [ClipTransportResult.Unavailable], а НЕ сымплированный успех.
     */
    suspend fun signAttestation(clipSerial: String, message: ByteArray): ClipTransportResult
}

sealed interface ClipTransportResult {
    /** DER-подпись (base64) от Clip. */
    data class Signed(val signatureBase64: String) : ClipTransportResult

    /** Clip недоступен (не подключён, транспорт не реализован). */
    data object Unavailable : ClipTransportResult
}

/* ------------------------------------------------------------------ API */

/** Серверная часть attest-протокола (challenge/attest). */
interface ClipAttestationApi {

    sealed interface ChallengeOutcome {
        data class Issued(
            val challengeId: String,
            val nonce: ByteArray,
            val issuedAtMs: Long
        ) : ChallengeOutcome

        data object UnknownClip : ChallengeOutcome
        data object ClipRevoked : ChallengeOutcome
        data object ServiceUnavailable : ChallengeOutcome
    }

    sealed interface AttestOutcome {
        data class Valid(
            val clipSerial: String,
            val boundNow: Boolean,
            val publicKeyBase64: String
        ) : AttestOutcome

        data object UnknownClip : AttestOutcome
        data object ClipRevoked : AttestOutcome
        data object ChallengeInvalid : AttestOutcome
        data object BadSignature : AttestOutcome
        data object OwnerMismatch : AttestOutcome
        data object ServiceUnavailable : AttestOutcome
    }

    suspend fun createChallenge(clipSerial: String): ChallengeOutcome

    suspend fun attest(clipSerial: String, challengeId: String, signatureBase64: String): AttestOutcome
}

@Serializable
private data class ClipChallengeRequestDto(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class ClipChallengeResponseDto(
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("nonce") val nonce: String,
    @SerialName("issued_at_ms") val issuedAtMs: Long
)

@Serializable
private data class ClipAttestRequestDto(
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("signature") val signature: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
private data class ClipAttestResponseDto(
    @SerialName("valid") val valid: Boolean,
    @SerialName("clip_serial") val clipSerial: String,
    @SerialName("bound_now") val boundNow: Boolean,
    @SerialName("public_key") val publicKey: String = ""
)

@Serializable
private data class ClipErrorEnvelopeDto(
    @SerialName("error") val error: ClipErrorBodyDto = ClipErrorBodyDto()
)

@Serializable
private data class ClipErrorBodyDto(@SerialName("code") val code: String = "")

/**
 * HTTP-реализация над JARVIS API — паттерн HttpLicenseServerValidator:
 * bearer jrv_-токен, ограниченное чтение ответа, короткие таймауты, без
 * редиректов, честный ServiceUnavailable при сетевых сбоях.
 */
class HttpClipAttestationApi(
    private val securityManager: SecurityManager
) : ClipAttestationApi {

    private companion object {
        const val MAX_RESPONSE_BYTES = 64L * 1024
        val BASE_URL: String = AppConstants.JARVIS_LICENSE_BASE_URL
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun createChallenge(serial: String): ClipAttestationApi.ChallengeOutcome =
        withContext(Dispatchers.IO) {
            val token = securityManager.getAccessToken().trim()
            if (token.isEmpty()) return@withContext ClipAttestationApi.ChallengeOutcome.ServiceUnavailable
            val body = json.encodeToString(
                ClipChallengeRequestDto(serial.trim(), UUID.randomUUID().toString())
            )
            val (code, payload) = execute(
                Request.Builder()
                    .url("$BASE_URL/v1/clip/challenge")
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody(mediaType))
                    .build()
            ) ?: return@withContext ClipAttestationApi.ChallengeOutcome.ServiceUnavailable
            when (code) {
                200 -> parseJson<ClipChallengeResponseDto>(payload)?.let {
                    runCatching {
                        ClipAttestationApi.ChallengeOutcome.Issued(
                            it.challengeId,
                            Base64.getDecoder().decode(it.nonce),
                            it.issuedAtMs
                        )
                    }.getOrNull()
                } ?: ClipAttestationApi.ChallengeOutcome.ServiceUnavailable

                404 -> ClipAttestationApi.ChallengeOutcome.UnknownClip
                403 -> ClipAttestationApi.ChallengeOutcome.ClipRevoked
                else -> ClipAttestationApi.ChallengeOutcome.ServiceUnavailable
            }
        }

    override suspend fun attest(
        serial: String,
        challengeId: String,
        signatureBase64: String
    ): ClipAttestationApi.AttestOutcome = withContext(Dispatchers.IO) {
        val token = securityManager.getAccessToken().trim()
        if (token.isEmpty()) return@withContext ClipAttestationApi.AttestOutcome.ServiceUnavailable
        val body = json.encodeToString(
            ClipAttestRequestDto(serial.trim(), challengeId, signatureBase64, UUID.randomUUID().toString())
        )
        val (code, payload) = execute(
            Request.Builder()
                .url("$BASE_URL/v1/clip/attest")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(mediaType))
                .build()
        ) ?: return@withContext ClipAttestationApi.AttestOutcome.ServiceUnavailable
        when (code) {
            200 -> parseJson<ClipAttestResponseDto>(payload)?.takeIf { it.valid }?.let {
                ClipAttestationApi.AttestOutcome.Valid(it.clipSerial, it.boundNow, it.publicKey)
            } ?: ClipAttestationApi.AttestOutcome.ServiceUnavailable

            404 -> ClipAttestationApi.AttestOutcome.UnknownClip
            403 -> when (errorCode(payload)) {
                "CLIP_OWNER_MISMATCH" -> ClipAttestationApi.AttestOutcome.OwnerMismatch
                "CLIP_REVOKED" -> ClipAttestationApi.AttestOutcome.ClipRevoked
                else -> ClipAttestationApi.AttestOutcome.ServiceUnavailable
            }
            401 -> when (errorCode(payload)) {
                "CLIP_BAD_SIGNATURE" -> ClipAttestationApi.AttestOutcome.BadSignature
                "CLIP_CHALLENGE_INVALID" -> ClipAttestationApi.AttestOutcome.ChallengeInvalid
                else -> ClipAttestationApi.AttestOutcome.ServiceUnavailable
            }
            else -> ClipAttestationApi.AttestOutcome.ServiceUnavailable
        }
    }

    /** @return (HTTP-код, ограниченное тело) или null при сетевом сбое. */
    private fun execute(request: Request): Pair<Int, String>? = try {
        client.newCall(request).execute().use { response ->
            Pair(response.code, response.body?.readUtf8Bounded(MAX_RESPONSE_BYTES).orEmpty())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private inline fun <reified T> parseJson(payload: String): T? =
        runCatching { json.decodeFromString<T>(payload) }.getOrNull()

    private fun errorCode(payload: String): String = runCatching {
        json.decodeFromString(ClipErrorEnvelopeDto.serializer(), payload).error.code
    }.getOrDefault("")
}
