package com.jarvis.assistant.core.clip

import java.security.SecureRandom

/**
 * Протокол привязки OMNIX Clip (V008, зеркало серверного
 * `com.jarvis.server.clip.ClipAttestationService`).
 *
 * Идентичность Clip = ПАРА КЛЮЧЕЙ (EC P-256), сгенерированная при
 * производстве; приватный ключ не покидает устройство. Bluetooth-имя и MAC —
 * только транспорт (копируемые/подменяемые, доверием не являются):
 *
 * ```
 * Phone → challenge (сервер, single-use) → Clip подписывает каноническое
 *        сообщение приватным ключом → Server/Local verifier → VALID
 * ```
 */
object ClipAttestationProtocol {

    /** Домен-разделитель — единый с сервером и firmware. */
    const val PROTOCOL_DOMAIN = "JARVIS-CLIP-ATTEST-v1"

    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    const val NONCE_BYTES = 32

    /**
     * Каноническое подписываемое сообщение — БАЙТ-В-БАЙТ совпадает с сервером:
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

    fun newNonce(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(NONCE_BYTES).also(random::nextBytes)
}
