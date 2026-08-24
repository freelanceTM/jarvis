package com.jarvis.server.license

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Cryptographic code/token generation and keyed-at-rest representation. */
class LicenseCrypto(
    pepper: String,
    private val random: SecureRandom = SecureRandom()
) {
    companion object {
        private const val CODE_PREFIX = "JRV"
        private const val API_TOKEN_PREFIX = "jrv_"
        private const val BASE32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val CODE_REGEX = Regex("^JRV(?:-[A-Z2-9]{5}){4}$")
    }

    private val key = pepper.toByteArray(StandardCharsets.UTF_8).also {
        require(it.size >= 32) { "LICENSE_CODE_PEPPER must contain at least 32 UTF-8 bytes" }
    }

    fun generateLicenseCode(): String {
        val bytes = ByteArray(20).also(random::nextBytes)
        val chars = CharArray(20) { index -> BASE32[bytes[index].toInt() and 31] }
        return CODE_PREFIX + chars.concatToString().chunked(5).joinToString(separator = "-", prefix = "-")
    }

    fun normalizeLicenseCode(raw: String): String? {
        val compact = raw.trim().uppercase()
            .replace(Regex("\\s+"), "")
        val canonical = if (compact.startsWith("JRV-") && compact.count { it == '-' } == 4) {
            compact
        } else {
            val noDash = compact.replace("-", "")
            if (!noDash.startsWith(CODE_PREFIX) || noDash.length != 23) return null
            CODE_PREFIX + noDash.removePrefix(CODE_PREFIX).chunked(5).joinToString("-", prefix = "-")
        }
        return canonical.takeIf(CODE_REGEX::matches)
    }

    fun licenseCodeHash(canonicalCode: String): ByteArray = hmac("license:$canonicalCode")

    fun codeHint(canonicalCode: String): String = canonicalCode.takeLast(5)

    fun generateAccessToken(): String = API_TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(random::nextBytes))

    fun accessTokenHash(token: String): ByteArray = hmac("token:$token")

    fun deviceHash(deviceId: String): ByteArray {
        val normalized = deviceId.trim()
        require(normalized.length in 8..128 && normalized.none(Char::isISOControl)) {
            "device identifier must contain 8..128 non-control characters"
        }
        return hmac("device:$normalized")
    }

    fun payloadHash(rawBody: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(rawBody.toByteArray(StandardCharsets.UTF_8))

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    private fun hmac(value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }
}
