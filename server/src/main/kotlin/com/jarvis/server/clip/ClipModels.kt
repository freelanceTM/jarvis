package com.jarvis.server.clip

import java.time.Instant

/**
 * Криптографическая идентичность OMNIX Clip (V008).
 *
 * Идентичность устройства = пара ключей, сгенерированная при производстве;
 * Bluetooth-имя и MAC используются ТОЛЬКО для транспорта ( discovery),
 * не для доверия: их можно скопировать/подменить. Доверие = подпись challenge
 * приватным ключом, проверенная зарегистрированным публичным ключом.
 *
 * Сервер знает: Clip #serial, Public Key, Owner, License(=account), Status.
 */
enum class ClipDeviceStatus { PROVISIONED, ACTIVE, REVOKED }

data class ClipDevice(
    val id: java.util.UUID,
    val clipSerial: String,
    val publicKey: ByteArray,
    val ownerAccountId: java.util.UUID?,
    val status: ClipDeviceStatus,
    val boundAt: Instant?,
    val lastVerifiedAt: Instant?
)

/** Выпуск challenge для attest (single-use, ограничен по времени). */
data class ClipChallenge(
    val challengeId: java.util.UUID,
    val clipSerial: String,
    val nonce: ByteArray,
    /** Точное значение, входящее в подписываемое сообщение. */
    val issuedAtMs: Long,
    val expiresAt: Instant
)

sealed interface ClipProvisionOutcome {
    data class Created(val clip: ClipDevice) : ClipProvisionOutcome
    data object AlreadyExists : ClipProvisionOutcome
    data object InvalidPublicKey : ClipProvisionOutcome
}

sealed interface ClipChallengeOutcome {
    data class Issued(val challenge: ClipChallenge) : ClipChallengeOutcome
    data object UnknownClip : ClipChallengeOutcome
    data object ClipRevoked : ClipChallengeOutcome
}

sealed interface ClipAttestOutcome {
    /**
     * @param boundNow true — этот attest ПЕРВЫМ привязал клип к аккаунту
     *        вызывателя (предыдущий владелец отсутствовал).
     */
    data class Valid(
        val clipSerial: String,
        val ownerAccountId: java.util.UUID,
        val boundNow: Boolean,
        val status: ClipDeviceStatus,
        /** Зарегистрированный публичный ключ (base64 SPKI) — якорь офлайн-доверия. */
        val publicKeyBase64: String
    ) : ClipAttestOutcome

    data object UnknownClip : ClipAttestOutcome
    data object ClipRevoked : ClipAttestOutcome

    /** Challenge не существует / истёк / уже использован (replay). */
    data object ChallengeInvalid : ClipAttestOutcome

    /** Подпись не прошла проверку зарегистрированным публичным ключом. */
    data object BadSignature : ClipAttestOutcome

    /** Клип уже привязан к ДРУГОМУ аккаунту. */
    data object OwnerMismatch : ClipAttestOutcome
}
