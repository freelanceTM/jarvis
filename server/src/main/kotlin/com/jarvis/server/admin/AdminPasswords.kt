package com.jarvis.server.admin

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Хеширование паролей админ-аккаунтов: PBKDF2WithHmacSHA256.
 *
 * Выбран PBKDF2, а не bcrypt/argon2, потому что он входит в JDK (проект не
 * тянет новых зависимостей — Control Plane ТЗ §32/§33). Формат строки:
 * `pbkdf2$<iterations>$<saltBase64>$<hashBase64>` — параметры едут вместе с
 * хешем, что позволяет поднять iterations без миграции данных.
 */
object AdminPasswords {

    const val MIN_PASSWORD_LENGTH = 12

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    const val DEFAULT_ITERATIONS = 210_000

    private val random = SecureRandom()

    fun hash(password: String, iterations: Int = DEFAULT_ITERATIONS): String {
        require(password.length >= MIN_PASSWORD_LENGTH) {
            "password must be at least $MIN_PASSWORD_LENGTH characters"
        }
        require(iterations in 100_000..2_000_000) { "iterations out of range" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = pbkdf2(password, salt, iterations)
        return "pbkdf2$$iterations$${b64(salt)}$${b64(hash)}"
    }

    /** Constant-time verify; возвращает false для повреждённых строк, не бросает. */
    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations !in 100_000..2_000_000) return false
        val salt = runCatching { java.util.Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { java.util.Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        val actual = pbkdf2(password, salt, iterations)
        return constantTimeEquals(expected, actual)
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance(ALGORITHM).generateSecret(
            PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        ).encoded

    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /** SHA-256 hex (для хеширования session-токенов; сырой токен не хранится). */
    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
