package com.jarvis.assistant.core.clip

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * OMNIX Clip: криптографическая привязка (V008).
 *
 * Проверяем: подпись настоящего ключа принимается; подменённый/чужой ключ —
 * нет (аналог подмены MAC); офлайн-проверка без серверного закрепления
 * честно невозможна (NotPinned); transport без firmware честно даёт
 * TransportUnavailable; серверный путь закрепляет ключ и возвращается
 * VALID.
 */
class ClipAttestationManagerTest {

    private class FakeTrustStore : ClipTrustStore {
        val keys = ConcurrentHashMap<String, String>()
        override fun pinnedPublicKey(clipSerial: String): String? = keys[clipSerial]
        override fun pin(clipSerial: String, publicKeyBase64: String) { keys[clipSerial] = publicKeyBase64 }
        override fun forget(clipSerial: String) { keys.remove(clipSerial) }
    }

    /** Clip-сторона: подписывает приватным ключом (firmware-поведение). */
    private class SigningTransport(private val key: KeyPair?) : ClipTransport {
        override suspend fun signAttestation(clipSerial: String, message: ByteArray): ClipTransportResult {
            key ?: return ClipTransportResult.Unavailable
            val signer = Signature.getInstance(ClipAttestationProtocol.SIGNATURE_ALGORITHM)
            signer.initSign(key.private)
            signer.update(message)
            return ClipTransportResult.Signed(Base64.getEncoder().encodeToString(signer.sign()))
        }
    }

    /** API только для challenge-этапа (attest в этих сценариях не доходит). */
    private class ChallengeOnlyApi(private val registered: Boolean = true) : ClipAttestationApi {
        override suspend fun createChallenge(clipSerial: String): ClipAttestationApi.ChallengeOutcome =
            if (registered) {
                ClipAttestationApi.ChallengeOutcome.Issued("ch-1", ClipAttestationProtocol.newNonce(), 1_000L)
            } else {
                ClipAttestationApi.ChallengeOutcome.UnknownClip
            }

        override suspend fun attest(
            clipSerial: String,
            challengeId: String,
            signatureBase64: String
        ): ClipAttestationApi.AttestOutcome = ClipAttestationApi.AttestOutcome.ServiceUnavailable
    }

    private lateinit var trustStore: FakeTrustStore
    private lateinit var manager: ClipAttestationManager

    @Before
    fun setUp() {
        trustStore = FakeTrustStore()
        manager = ClipAttestationManager(FakeApi(), trustStore)
    }

    private fun newKey(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    @Test
    fun `verifier accepts genuine signature and rejects tampered or wrong key`() {
        val clip = newKey()
        val attacker = newKey()
        val message = ClipAttestationProtocol.canonicalMessage("CLIP-1", ClipAttestationProtocol.newNonce(), 42L)
        val signer = Signature.getInstance(ClipAttestationProtocol.SIGNATURE_ALGORITHM)
        signer.initSign(clip.private)
        signer.update(message)
        val signature = signer.sign()

        assertTrue(ClipIdentityVerifier.verify(clip.public, message, signature))
        assertTrue(ClipIdentityVerifier.verify(clip.public, message, Base64.getEncoder().encodeToString(signature)))

        val tampered = message.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(ClipIdentityVerifier.verify(clip.public, tampered, signature))
        assertFalse(ClipIdentityVerifier.verify(attacker.public, message, signature))
        assertFalse(ClipIdentityVerifier.verify(clip.public, message, "not-base64!!!"))
    }

    @Test
    fun `local verification requires server-pinned key - NotPinned otherwise`() = runBlocking {
        val clip = newKey()
        // Нет закрепления (никогда не было успешного серверного attest) —
        // офлайн-доверия нет, честный NotPinned.
        val result = manager.verifyLocally("CLIP-OFFLINE", SigningTransport(clip), nowMs = 5_000L)
        assertEquals(ClipAttestationManager.Result.NotPinned, result)
    }

    @Test
    fun `transport without firmware yields honest TransportUnavailable`() = runBlocking {
        val manager = ClipAttestationManager(ChallengeOnlyApi(), trustStore)
        val result = manager.verifyWithServer("CLIP-NOHW", SigningTransport(null))
        assertEquals(ClipAttestationManager.Result.TransportUnavailable, result)
    }

    @Test
    fun `server verification pins key and local flow then verifies offline`() = runBlocking {
        val clipKeys = newKey()
        val api = RecordingApi(clipKeys)
        val manager = ClipAttestationManager(api, trustStore)

        // Онлайн: серверная проверка — VALID и ключ закреплён.
        val online = manager.verifyWithServer("CLIP-HYBRID", SigningTransport(clipKeys))
        assertTrue(online is ClipAttestationManager.Result.Verified)
        assertEquals(ClipAttestationManager.Result.Source.SERVER, (online as ClipAttestationManager.Result.Verified).source)
        assertTrue(online.boundNow)
        assertNotNull(trustStore.pinnedPublicKey("CLIP-HYBRID"))

        // Офлайн: локальная проверка тем же ключом — VALID (LOCAL).
        val offline = manager.verifyLocally("CLIP-HYBRID", SigningTransport(clipKeys), nowMs = 1_000L)
        assertTrue(offline is ClipAttestationManager.Result.Verified)
        assertEquals(ClipAttestationManager.Result.Source.LOCAL, (offline as ClipAttestationManager.Result.Verified).source)

        // Подменённый Clip (другой ключ, то же имя) офлайн — BadSignature.
        val clone = newKey()
        val spoofed = manager.verifyLocally("CLIP-HYBRID", SigningTransport(clone), nowMs = 1_000L)
        assertEquals(ClipAttestationManager.Result.BadSignature, spoofed)
    }

    /** FakeApi с честной записью nonce по challengeId (серверная семантика). */
    private class RecordingApi(private val clipKeys: KeyPair) : ClipAttestationApi {
        private val nonces = mutableMapOf<String, ByteArray>()
        private var counter = 0

        override suspend fun createChallenge(clipSerial: String): ClipAttestationApi.ChallengeOutcome {
            counter++
            val id = "ch-$counter"
            nonces[id] = ClipAttestationProtocol.newNonce()
            return ClipAttestationApi.ChallengeOutcome.Issued(id, nonces[id]!!, 1_000L)
        }

        override suspend fun attest(
            clipSerial: String,
            challengeId: String,
            signatureBase64: String
        ): ClipAttestationApi.AttestOutcome {
            val nonce = nonces[challengeId] ?: return ClipAttestationApi.AttestOutcome.ChallengeInvalid
            val message = ClipAttestationProtocol.canonicalMessage(clipSerial, nonce, 1_000L)
            val verifier = Signature.getInstance(ClipAttestationProtocol.SIGNATURE_ALGORITHM)
            verifier.initVerify(clipKeys.public)
            verifier.update(message)
            if (!verifier.verify(Base64.getDecoder().decode(signatureBase64))) {
                return ClipAttestationApi.AttestOutcome.BadSignature
            }
            return ClipAttestationApi.AttestOutcome.Valid(
                clipSerial,
                boundNow = true,
                publicKeyBase64 = Base64.getEncoder().encodeToString(clipKeys.public.encoded)
            )
        }
    }
}
