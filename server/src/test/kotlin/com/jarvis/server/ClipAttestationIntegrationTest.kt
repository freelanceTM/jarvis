package com.jarvis.server

import com.jarvis.server.api.ApiErrorResponse
import com.jarvis.server.auth.ClientTier
import com.jarvis.server.auth.CompositeAuthenticator
import com.jarvis.server.auth.LicenseTokenAuthenticator
import com.jarvis.server.auth.TierAuthorizer
import com.jarvis.server.auth.TokenAuthenticator
import com.jarvis.server.billing.JdbcBillingRepository
import com.jarvis.server.billing.BillingService
import com.jarvis.server.clip.ClipAttestationService
import com.jarvis.server.clip.ClipHttpHandler
import com.jarvis.server.clip.JdbcClipDeviceRepository
import com.jarvis.server.config.RateLimitConfig
import com.jarvis.server.config.ValidationConfig
import com.jarvis.server.http.HttpRequestContext
import com.jarvis.server.http.LicenseBillingHttpHandler
import com.jarvis.server.license.BillingPlan
import com.jarvis.server.license.JdbcLicenseRepository
import com.jarvis.server.license.LicenseCrypto
import com.jarvis.server.license.LicenseService
import com.jarvis.server.observability.ConsoleStructuredLogger
import com.jarvis.server.ratelimit.SlidingWindowRateLimiter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * V008: криптографическая привязка OMNIX Clip.
 *
 * Идентичность = пара ключей (не имя/MAC). Сценарии: provisioning,
 * challenge → attest (VALID + первая привязка владельца), replay-challenge
 * отклоняется, чужая подпись отклоняется, второй аккаунт не проходит
 * (OWNER_MISMATCH), REVOKED клип не проходит вообще.
 */
class ClipAttestationIntegrationTest : PostgresTestSupport() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val adminToken = "a".repeat(64)

    private lateinit var licenseHandler: LicenseBillingHttpHandler
    private lateinit var clipHandler: ClipHttpHandler
    private lateinit var attestation: ClipAttestationService

    @Before
    fun buildHandlers() {
        val crypto = LicenseCrypto("test-license-pepper-32-bytes-minimum-value")
        val licenseRepository = JdbcLicenseRepository(dataSource, crypto)
        val licenseService = LicenseService(licenseRepository, crypto)
        licenseService.upsertPlan(
            BillingPlan(
                "clip-monthly", "jarvis-clip", "Monthly", 30, 1_400, "USD",
                paddlePriceId = "pri_clip_test", heleketCurrency = "USDT"
            )
        )
        val billingService = BillingService(
            JdbcBillingRepository(dataSource), licenseRepository, listOf()
        )
        val auth = CompositeAuthenticator(
            TokenAuthenticator(mapOf(adminToken to "admin")) { ClientTier.ADMIN },
            LicenseTokenAuthenticator(licenseService)
        )
        licenseHandler = LicenseBillingHttpHandler(
            authenticator = auth,
            authorizer = TierAuthorizer(),
            licenseService = licenseService,
            billingService = billingService,
            paddleWebhookVerifier = com.jarvis.server.billing.PaddleWebhookVerifier(
                com.jarvis.server.billing.PaddleBillingConfig("api-key", "webhook-secret"), json
            ),
            heleketWebhookVerifier = com.jarvis.server.billing.HeleketWebhookVerifier(
                com.jarvis.server.billing.HeleketBillingConfig(
                    "merchant", "heleket-secret",
                    "https://api.example.com/v1/billing/webhooks/heleket",
                    "https://example.com/return",
                    "https://example.com/success"
                ), json
            ),
            redeemRateLimiter = SlidingWindowRateLimiter(RateLimitConfig(50, 100)),
            authenticatedRateLimiter = SlidingWindowRateLimiter(RateLimitConfig(100, 100)),
            webhookRateLimiter = SlidingWindowRateLimiter(RateLimitConfig(100, 1_000)),
            validation = ValidationConfig(),
            logger = ConsoleStructuredLogger(sink = {}),
            json = json
        )
        attestation = ClipAttestationService(
            JdbcClipDeviceRepository(dataSource)
        )
        clipHandler = ClipHttpHandler(
            authenticator = auth,
            authorizer = TierAuthorizer(),
            attestationService = attestation,
            rateLimiter = SlidingWindowRateLimiter(RateLimitConfig(100, 100)),
            validation = ValidationConfig(),
            logger = ConsoleStructuredLogger(sink = {}),
            json = json
        )
    }

    /* ---------------------------------------------------------------- helpers */

    private fun newClipKey(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun sign(key: KeyPair, clipSerial: String, nonce: ByteArray, issuedAtMs: Long): String {
        val message = ClipAttestationService.canonicalMessage(clipSerial, nonce, issuedAtMs)
        val signer = Signature.getInstance(ClipAttestationService.SIGNATURE_ALGORITHM)
        signer.initSign(key.private)
        signer.update(message)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private suspend fun redeemNewAccount(deviceId: String): String {
        val issue = licenseHandler.handle(
            request(
                path = LicenseBillingHttpHandler.PATH_ISSUE,
                token = adminToken,
                body = """{"plan_id":"clip-monthly","request_id":"issue-$deviceId"}"""
            )
        )!!
        val code = Regex(""""code":"([^"]+)"""").find(issue.body)!!.groupValues[1]
        val redeem = licenseHandler.handle(
            request(
                path = LicenseBillingHttpHandler.PATH_REDEEM,
                body = """{"code":"$code","device_id":"$deviceId","request_id":"redeem-$deviceId"}"""
            )
        )!!
        assertEquals(200, redeem.status)
        return Regex(""""access_token":"([^"]+)"""").find(redeem.body)!!.groupValues[1]
    }

    private fun request(path: String, token: String? = null, body: String = "") = HttpRequestContext(
        method = "POST",
        path = path,
        authorizationHeader = token?.let { "Bearer $it" },
        body = body,
        contentLength = body.toByteArray().size.toLong(),
        headers = emptyMap(),
        remoteAddress = "127.0.0.1"
    )

    private fun errorCode(response: com.jarvis.server.http.HttpResponseContext): String =
        json.decodeFromString(ApiErrorResponse.serializer(), response.body).error.code

    private suspend fun requestChallenge(token: String, serial: String): Triple<String, ByteArray, Long> {
        val response = clipHandler.handle(
            request(
                path = ClipHttpHandler.PATH_CHALLENGE,
                token = token,
                body = """{"clip_serial":"$serial","request_id":"ch-1"}"""
            )
        )!!
        assertEquals(200, response.status)
        val nonce = Base64.getDecoder().decode(
            Regex(""""nonce":"([^"]+)"""").find(response.body)!!.groupValues[1]
        )
        val issuedAt = Regex(""""issued_at_ms":(\d+)""").find(response.body)!!.groupValues[1].toLong()
        val challengeId = Regex(""""challenge_id":"([^"]+)"""").find(response.body)!!.groupValues[1]
        return Triple(challengeId, nonce, issuedAt)
    }

    private suspend fun attest(
        token: String,
        serial: String,
        challengeId: String,
        signature: String
    ) = clipHandler.handle(
        request(
            path = ClipHttpHandler.PATH_ATTEST,
            token = token,
            body = """{"clip_serial":"$serial","challenge_id":"$challengeId","signature":"$signature","request_id":"at-1"}"""
        )
    )!!

    /* ------------------------------------------------------------------ tests */

    @Test
    fun `provision challenge attest binds owner cryptographically`() = runBlocking {
        val clipKeys = newClipKey()
        val serial = "CLIP-12345"
        val accountToken = redeemNewAccount("device-owner-1")

        // Provision (manufacturing uploads PUBLIC key only).
        val provision = clipHandler.handle(
            request(
                path = ClipHttpHandler.PATH_PROVISION,
                token = adminToken,
                body = """{"clip_serial":"$serial","public_key":"${Base64.getEncoder().encodeToString(clipKeys.public.encoded)}"}"""
            )
        )!!
        assertEquals(201, provision.status)
        assertTrue(provision.body.contains("PROVISIONED"))

        // Повторный provision той же идентичности — отказ (identity immutable).
        val duplicate = clipHandler.handle(
            request(
                path = ClipHttpHandler.PATH_PROVISION,
                token = adminToken,
                body = """{"clip_serial":"$serial","public_key":"${Base64.getEncoder().encodeToString(clipKeys.public.encoded)}"}"""
            )
        )!!
        assertEquals(409, duplicate.status)
        assertEquals("CLIP_ALREADY_PROVISIONED", errorCode(duplicate))

        // Challenge → подпись клипа → attest: VALID + первая привязка.
        val (challengeId, nonce, issuedAt) = requestChallenge(accountToken, serial)
        val first = attest(accountToken, serial, challengeId, sign(clipKeys, serial, nonce, issuedAt))
        assertEquals(200, first.status)
        assertTrue(first.body.contains(""""bound_now":true"""))
        assertTrue(first.body.contains("ACTIVE"))

        // Повторный attest новым challenge тем же владельцем — VALID, без ребинда.
        val (id2, nonce2, issued2) = requestChallenge(accountToken, serial)
        val second = attest(accountToken, serial, id2, sign(clipKeys, serial, nonce2, issued2))
        assertEquals(200, second.status)
        assertTrue(second.body.contains(""""bound_now":false"""))
    }

    @Test
    fun `replayed challenge is rejected`() = runBlocking {
        val clipKeys = newClipKey()
        val serial = "CLIP-REPLAY"
        val token = redeemNewAccount("device-replay-1")
        clipHandler.handle(
            request(
                path = ClipHttpHandler.PATH_PROVISION,
                token = adminToken,
                body = """{"clip_serial":"$serial","public_key":"${Base64.getEncoder().encodeToString(clipKeys.public.encoded)}"}"""
            )
        )!!
        val (challengeId, nonce, issuedAt) = requestChallenge(token, serial)
        assertEquals(200, attest(token, serial, challengeId, sign(clipKeys, serial, nonce, issuedAt)).status)

        // Replay ТОГО ЖЕ challenge (даже с валидной подписью) — отказ.
        val replay = attest(token, serial, challengeId, sign(clipKeys, serial, nonce, issuedAt))
        assertEquals(401, replay.status)
        assertEquals("CLIP_CHALLENGE_INVALID", errorCode(replay))
    }

    @Test
    fun `signature from a different key is rejected`() = runBlocking {
        val clipKeys = newClipKey()
        val attackerKeys = newClipKey()
        val serial = "CLIP-SPOOF"
        val token = redeemNewAccount("device-spoof-1")
        clipHandler.handle(
            request(
                path = ClipHttpHandler.PATH_PROVISION,
                token = adminToken,
                body = """{"clip_serial":"$serial","public_key":"${Base64.getEncoder().encodeToString(clipKeys.public.encoded)}"}"""
            )
        )!!
        val (challengeId, nonce, issuedAt) = requestChallenge(token, serial)

        // Клон с подменённым ключом (аналог подмены MAC): подпись не тем ключом.
        val spoof = attest(token, serial, challengeId, sign(attackerKeys, serial, nonce, issuedAt))
        assertEquals(401, spoof.status)
        assertEquals("CLIP_BAD_SIGNATURE", errorCode(spoof))
    }

    @Test
    fun `second account cannot attest a bound clip`() = runBlocking {
        val clipKeys = newClipKey()
        val serial = "CLIP-BOUND"
        val owner = redeemNewAccount("device-bound-owner")
        val stranger = redeemNewAccount("device-bound-stranger")
        clipHandler.handle(
            request(
                path = ClipHttpHandler.PATH_PROVISION,
                token = adminToken,
                body = """{"clip_serial":"$serial","public_key":"${Base64.getEncoder().encodeToString(clipKeys.public.encoded)}"}"""
            )
        )!!
        val (id1, nonce1, issued1) = requestChallenge(owner, serial)
        assertEquals(200, attest(owner, serial, id1, sign(clipKeys, serial, nonce1, issued1)).status)

        val (id2, nonce2, issued2) = requestChallenge(stranger, serial)
        val strangerAttest = attest(stranger, serial, id2, sign(clipKeys, serial, nonce2, issued2))
        assertEquals(403, strangerAttest.status)
        assertEquals("CLIP_OWNER_MISMATCH", errorCode(strangerAttest))
    }

    @Test
    fun `revoked clip is denied even with valid signature`() = runBlocking {
        val clipKeys = newClipKey()
        val serial = "CLIP-REVOKE"
        val token = redeemNewAccount("device-revoke-1")
        clipHandler.handle(
            request(
                path = ClipHttpHandler.PATH_PROVISION,
                token = adminToken,
                body = """{"clip_serial":"$serial","public_key":"${Base64.getEncoder().encodeToString(clipKeys.public.encoded)}"}"""
            )
        )!!
        val revoke = clipHandler.handle(
            request(
                path = ClipHttpHandler.PATH_REVOKE,
                token = adminToken,
                body = """{"clip_serial":"$serial","reason":"stolen"}"""
            )
        )!!
        assertEquals(200, revoke.status)

        // Challenge для отозванного клипа не выдаётся вообще.
        val challenge = clipHandler.handle(
            request(
                path = ClipHttpHandler.PATH_CHALLENGE,
                token = token,
                body = """{"clip_serial":"$serial"}"""
            )
        )!!
        assertEquals(403, challenge.status)
        assertEquals("CLIP_REVOKED", errorCode(challenge))
    }

    @Test
    fun `unknown clip is rejected at challenge time`() = runBlocking {
        val token = redeemNewAccount("device-unknown-clip")
        val challenge = clipHandler.handle(
            request(
                path = ClipHttpHandler.PATH_CHALLENGE,
                token = token,
                body = """{"clip_serial":"CLIP-NEVER-PROVISIONED"}"""
            )
        )!!
        assertEquals(404, challenge.status)
        assertEquals("CLIP_UNKNOWN", errorCode(challenge))
    }
}
