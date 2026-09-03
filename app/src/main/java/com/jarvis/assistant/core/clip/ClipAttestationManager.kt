package com.jarvis.assistant.core.clip

import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.util.Base64

/**
 * Оркестратор проверки владения OMNIX Clip.
 *
 * Онлайн — серверная истина (challenge single-use + подпись + реестр:
 * серийник, публичный ключ, владелец, статус/revocation); офлайн — локальная
 * проверка закреплённым ключом (якорь — серверный ответ). Имя/MAC устройством
 * доверия не являются.
 *
 * Честные границы:
 *  - [ClipTransport] реализуется firmware-стороной; его нет — результаты
 *    TransportUnavailable, никакого сымплированного VALID;
 *  - локальная проверка не видит revocation в реальном времени — когда
 *    сеть есть, используйте [verifyWithServer].
 */
class ClipAttestationManager(
    private val api: ClipAttestationApi,
    private val trustStore: ClipTrustStore,
    private val clock: Clock = Clock.systemUTC()
) {

    /** Офлайн-проверка допустима только для свежего закреплённого ключа. */
    val localFreshnessMs: Long = 90_000L

    sealed interface Result {
        /**
         * @param boundNow true — сервер ПЕРВЫМ этим attest привязал клип
         *        к нашему аккаунту.
         */
        data class Verified(
            val clipSerial: String,
            val source: Source,
            val boundNow: Boolean
        ) : Result

        enum class Source { SERVER, LOCAL }

        data object Revoked : Result
        data object UnknownClip : Result
        data object OwnerMismatch : Result
        data object ChallengeInvalid : Result
        data object BadSignature : Result

        /** Офлайн-проверка без закреплённого сервером ключа невозможна. */
        data object NotPinned : Result
        data object TransportUnavailable : Result
        data object ServiceUnavailable : Result
    }

    /**
     * Полная серверная проверка: challenge → подпись Clip → attest.
     * При успехе закрепляет публичный ключ (якорь сервера) для офлайна.
     */
    suspend fun verifyWithServer(
        clipSerial: String,
        transport: ClipTransport
    ): Result {
        val challenge = api.createChallenge(clipSerial)
        val nonce = when (challenge) {
            is ClipAttestationApi.ChallengeOutcome.Issued -> challenge
            ClipAttestationApi.ChallengeOutcome.UnknownClip -> return Result.UnknownClip
            ClipAttestationApi.ChallengeOutcome.ClipRevoked -> return Result.Revoked
            ClipAttestationApi.ChallengeOutcome.ServiceUnavailable -> return Result.ServiceUnavailable
        }
        val message = ClipAttestationProtocol.canonicalMessage(
            clipSerial.trim(), nonce.nonce, nonce.issuedAtMs
        )
        val signature = when (val signed = transport.signAttestation(clipSerial, message)) {
            is ClipTransportResult.Signed -> signed.signatureBase64
            ClipTransportResult.Unavailable -> return Result.TransportUnavailable
        }
        return when (val verdict = api.attest(clipSerial, nonce.challengeId, signature)) {
            is ClipAttestationApi.AttestOutcome.Valid -> {
                if (verdict.publicKeyBase64.isNotBlank()) {
                    trustStore.pin(clipSerial.trim(), verdict.publicKeyBase64)
                }
                Result.Verified(clipSerial.trim(), Result.Source.SERVER, verdict.boundNow)
            }
            ClipAttestationApi.AttestOutcome.UnknownClip -> Result.UnknownClip
            ClipAttestationApi.AttestOutcome.ClipRevoked -> Result.Revoked
            ClipAttestationApi.AttestOutcome.ChallengeInvalid -> Result.ChallengeInvalid
            ClipAttestationApi.AttestOutcome.BadSignature -> Result.BadSignature
            ClipAttestationApi.AttestOutcome.OwnerMismatch -> Result.OwnerMismatch
            ClipAttestationApi.AttestOutcome.ServiceUnavailable -> Result.ServiceUnavailable
        }
    }

    /**
     * Локальная проверка владения без сети: nonce+время телефона → подпись
     * Clip → ECDSA закреплённым ключом + проверка свежести.
     */
    fun verifyLocally(
        clipSerial: String,
        transport: ClipTransport,
        nowMs: Long = clock.millis()
    ): Result {
        val pinned = trustStore.pinnedPublicKey(clipSerial)
            ?: return Result.NotPinned
        val publicKey = runCatching { decodePublicKey(pinned) }.getOrNull()
            ?: return Result.NotPinned

        val issuedAtMs = nowMs
        val message = ClipAttestationProtocol.canonicalMessage(
            clipSerial.trim(),
            ClipAttestationProtocol.newNonce(),
            issuedAtMs
        )
        val signature = when (val signed = transport.signAttestation(clipSerial, message)) {
            is ClipTransportResult.Signed -> signed.signatureBase64
            ClipTransportResult.Unavailable -> return Result.TransportUnavailable
        }
        val valid = ClipIdentityVerifier.verify(publicKey, message, signature)
        if (!valid) return Result.BadSignature

        // Подпись делалась над нашим issuedAtMs:Transport мог отвечать долго —
        // допускаем ограниченный дрейф, старые/будущие подписи отвергаем.
        val drift = kotlin.math.abs(clock.millis() - issuedAtMs)
        if (drift > localFreshnessMs) return Result.ChallengeInvalid

        return Result.Verified(clipSerial.trim(), Result.Source.LOCAL, boundNow = false)
    }

    /** Явный сброс доверия (например, после SERVER-ответа Revoked). */
    fun forget(clipSerial: String) = trustStore.forget(clipSerial.trim())

    private fun decodePublicKey(base64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(base64))
        )
}
