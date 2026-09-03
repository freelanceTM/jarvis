package com.jarvis.server.clip

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Криптографическое доказательство владения Clip (V008).
 *
 * Телефон НЕ доверяет Bluetooth-имени/MAC (копируемые). При подключении:
 *
 * ```
 * Phone → challenge (server, single-use) → Clip signs canonical message
 *       → Server verifies signature with REGISTERED public key
 *       → VALID (и первая привязка владельца)
 * ```
 *
 * Каноническое сообщение (см. [canonicalMessage]): домен-разделитель версии,
 * serial, nonce из challenge, issuedAtMs из challenge — всё, кроме подписи,
 * контролируется сервером, поэтому replay и подменаserial/таймINGS невозможны.
 */
class ClipAttestationService(
    private val repository: JdbcClipDeviceRepository,
    private val clock: Clock = Clock.systemUTC()
) {

    /** TTL challenge: достаточно для BLE-обмена, недостаточно для offline brute. */
    val challengeTtl: Duration = Duration.ofSeconds(120)

    fun provision(clipSerial: String, publicKeyBase64: String): ClipProvisionOutcome {
        val serial = clipSerial.trim()
        if (serial.isEmpty() || serial.length > 64) return ClipProvisionOutcome.InvalidPublicKey
        val key = runCatching { decodePublicKey(publicKeyBase64) }.getOrNull()
            ?: return ClipProvisionOutcome.InvalidPublicKey
        return repository.provision(serial, key.encoded, clock.instant())
    }

    fun createChallenge(clipSerial: String): ClipChallengeOutcome {
        val clip = repository.findBySerial(clipSerial.trim())
            ?: return ClipChallengeOutcome.UnknownClip
        if (clip.status == ClipDeviceStatus.REVOKED) return ClipChallengeOutcome.ClipRevoked
        val now = clock.instant()
        val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
        return ClipChallengeOutcome.Issued(
            repository.issueChallenge(
                clipSerial = clip.clipSerial,
                nonce = nonce,
                issuedAtMs = now.toEpochMilli(),
                expiresAt = now.plus(challengeTtl),
                now = now
            )
        )
    }

    /**
     * Проверяет подпись и применяет бизнес-правила владельца.
     *
     * @param accountId аккаунт аутентифицированного вызывателя (jrv_-токен);
     *        первый attest привязывает клип к его аккаунту.
     */
    fun attest(
        clipSerial: String,
        challengeId: UUID,
        signatureBase64: String,
        accountId: UUID
    ): ClipAttestOutcome {
        val now = clock.instant()
        // Потребление challenge ДО проверки подписи: одноразовость гарантируем
        // даже при кривой подписи (не даём атакующему бесплатных попыток
        // подбора на одном nonce).
        val challenge = repository.consumeChallenge(challengeId, now)
            ?: return ClipAttestOutcome.ChallengeInvalid

        val clip = repository.findBySerial(clipSerial.trim())
            ?: return ClipAttestOutcome.UnknownClip
        if (clip.status == ClipDeviceStatus.REVOKED) return ClipAttestOutcome.ClipRevoked
        // Challenge выдан именно этому клипу.
        if (challenge.clipSerial != clip.clipSerial) return ClipAttestOutcome.ChallengeInvalid

        val signature = runCatching { Base64.getDecoder().decode(signatureBase64.trim()) }
            .getOrNull() ?: return ClipAttestOutcome.BadSignature
        val message = canonicalMessage(clip.clipSerial, challenge.nonce, challenge.issuedAtMs)
        val valid = runCatching {
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(decodePublicKey(clip.publicKey))
            verifier.update(message)
            verifier.verify(signature)
        }.getOrDefault(false)
        if (!valid) return ClipAttestOutcome.BadSignature

        // Владелец: первый attest привязывает; дальнейшие — только владелец.
        val currentOwner = repository.findBySerial(clip.clipSerial)?.ownerAccountId
        val boundNow = when {
            currentOwner == null -> repository.bindOwner(clip.clipSerial, accountId, now)
            currentOwner == accountId -> false
            else -> return ClipAttestOutcome.OwnerMismatch
        }
        if (!boundNow && currentOwner == null) return ClipAttestOutcome.OwnerMismatch
        repository.markVerified(clip.clipSerial, now)
        return ClipAttestOutcome.Valid(
            clipSerial = clip.clipSerial,
            ownerAccountId = accountId,
            boundNow = boundNow,
            status = ClipDeviceStatus.ACTIVE,
            publicKeyBase64 = Base64.getEncoder().encodeToString(clip.publicKey)
        )
    }

    fun revoke(clipSerial: String): Boolean =
        repository.setStatus(clipSerial.trim(), ClipDeviceStatus.REVOKED, clock.instant())

    companion object {
        const val PROTOCOL_DOMAIN = "JARVIS-CLIP-ATTEST-v1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val EC_CURVE = "secp256r1"
        const val NONCE_BYTES = 32

        private val secureRandom = java.security.SecureRandom()

        /**
         * Каноническое подписываемое сообщение — ЕДИНОЕ для сервера, Android и
         * firmware Clip:
         * `DOMAIN \0 serial \0 nonce(32) \0 issuedAtMs(8, big-endian)`.
         */
        fun canonicalMessage(clipSerial: String, nonce: ByteArray, issuedAtMs: Long): ByteArray {
            val domain = PROTOCOL_DOMAIN.toByteArray(Charsets.UTF_8)
            val serial = clipSerial.toByteArray(Charsets.UTF_8)
            val time = ByteArray(8)
            for (index in 0 until 8) {
                time[index] = (issuedAtMs ushr ((7 - index) * 8)).toByte()
            }
            return domain + byteArrayOf(0) + serial + byteArrayOf(0) + nonce + byteArrayOf(0) + time
        }

        fun decodePublicKey(encoded: ByteArray): PublicKey {
            val factory = KeyFactory.getInstance("EC")
            val key = factory.generatePublic(X509EncodedKeySpec(encoded))
            require(key.algorithm == "EC") { "public key must be EC" }
            val spec = key.getParameter(java.security.spec.ECParameterSpec::class.java)
                ?: error("public key must carry explicit EC parameters")
            // P-256: поле кривой 256 бит (переносимая проверка без парсинга
            // имён кривых, разных на JVM/Android).
            require(spec.curve.field.size == 256) { "public key must be $EC_CURVE" }
            return key
        }

        fun decodePublicKey(base64: String): PublicKey =
            decodePublicKey(Base64.getDecoder().decode(base64.trim()))
    }
}
