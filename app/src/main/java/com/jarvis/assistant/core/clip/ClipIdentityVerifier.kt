package com.jarvis.assistant.core.clip

import java.security.PublicKey
import java.security.Signature

/**
 * Чистая ECDSA-проверка подписи Clip — JVM-тестируемая, без Android-зависимостей.
 *
 * Консервативна: любая ошибка декодирования/подписи → false (fail-closed),
 * никогда не исключение наружу.
 */
object ClipIdentityVerifier {

    /** Подпись валидна для сообщения и зарегистрированного публичного ключа. */
    fun verify(publicKey: PublicKey, message: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            val verifier = Signature.getInstance(ClipAttestationProtocol.SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(message)
            verifier.verify(signature)
        }.getOrDefault(false)

    fun verify(publicKey: PublicKey, message: ByteArray, signatureBase64: String): Boolean =
        runCatching {
            verify(publicKey, message, java.util.Base64.getDecoder().decode(signatureBase64))
        }.getOrDefault(false)
}
