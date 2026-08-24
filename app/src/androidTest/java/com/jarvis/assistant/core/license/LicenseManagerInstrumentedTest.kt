package com.jarvis.assistant.core.license

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.core.security.SecurityManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** Exercises LicenseManagerImpl with real Android Keystore-backed encrypted preferences. */
@RunWith(AndroidJUnit4::class)
class LicenseManagerInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun cleanBefore() {
        context.deleteSharedPreferences(PREFS_NAME)
    }

    @After
    fun cleanAfter() {
        context.deleteSharedPreferences(PREFS_NAME)
    }

    @Test
    fun activationNormalizesCodePersistsEncryptedRecordAndUnlocksCurrentProcess() = runBlocking {
        val server = FakeServerValidator().apply { redeemResult = successfulRedemption() }
        val security = FakeSecurityManager()
        val manager = manager(server, security)

        val result = manager.activateWithCode(" ab_cd-1234!! ")

        assertTrue(result is ActivationResult.Success)
        assertEquals("ABCD-1234", server.redeemedCode)
        assertTrue(server.redeemedHardwareId.orEmpty().startsWith("JRV-"))
        assertEquals(VALID_TOKEN, security.getAccessToken())
        assertTrue(manager.isActivatedAndValid())
        val info = (result as ActivationResult.Success).licenseInfo
        assertEquals("phase3-pro", info.planId)
        assertEquals("android", info.productId)
        assertEquals("PAID", info.billingStatus)
        assertTrue(info.remainingDays in 29..30)

        val xml = encryptedPreferencesFile().readText()
        assertFalse("access token leaked to encrypted preferences", xml.contains(VALID_TOKEN))
        assertFalse("plan leaked as plaintext", xml.contains("phase3-pro"))
        assertFalse("hardware id leaked as plaintext", xml.contains(info.hardwareSerial))
    }

    @Test
    fun persistedRecordNeverUnlocksANewProcessWithoutServerValidation() = runBlocking {
        val server = FakeServerValidator().apply { redeemResult = successfulRedemption() }
        val security = FakeSecurityManager()
        val first = manager(server, security)
        assertTrue(first.activateWithCode("ABCD-1234") is ActivationResult.Success)
        val persistedHardwareId = first.getLicenseInfo().hardwareSerial

        val recreated = manager(server, security)

        assertFalse(recreated.isActivatedAndValid())
        assertFalse(recreated.getLicenseInfo().isActivated)
        assertEquals("phase3-pro", recreated.getLicenseInfo().planId)
        assertEquals(persistedHardwareId, recreated.getLicenseInfo().hardwareSerial)

        server.validationResult = ServerLicenseValidationResult.Valid(validRecord(accessToken = null))
        val refresh = recreated.refreshFromServer()
        assertTrue(refresh is LicenseRefreshResult.Valid)
        assertTrue(recreated.isActivatedAndValid())
        assertEquals(persistedHardwareId, server.validatedHardwareId)
    }

    @Test
    fun revokedAndUnauthorizedServerResultsFailClosedAndClearToken() = runBlocking {
        val server = FakeServerValidator().apply { redeemResult = successfulRedemption() }
        val security = FakeSecurityManager()
        val manager = manager(server, security)
        manager.activateWithCode("ABCD-1234")

        server.validationResult = ServerLicenseValidationResult.RevokedOrDisabled
        assertEquals(LicenseRefreshResult.Revoked, manager.refreshFromServer())
        assertFalse(manager.isActivatedAndValid())
        assertEquals("", security.getAccessToken())

        security.saveAccessToken(VALID_TOKEN)
        server.validationResult = ServerLicenseValidationResult.Unauthorized
        assertEquals(LicenseRefreshResult.Unauthorized, manager.refreshFromServer())
        assertEquals("", security.getAccessToken())
        assertFalse(manager.isActivatedAndValid())
    }

    @Test
    fun tokenPersistenceFailureDoesNotPublishActivatedLicense() = runBlocking {
        val server = FakeServerValidator().apply { redeemResult = successfulRedemption() }
        val security = FakeSecurityManager(failOnSave = true)
        val manager = manager(server, security)

        val result = manager.activateWithCode("ABCD-1234")

        assertTrue(result is ActivationResult.ServiceUnavailable)
        assertFalse(manager.isActivatedAndValid())
        assertNotEquals("phase3-pro", manager.getLicenseInfo().planId)
    }

    @Test
    fun invalidShortCodeNeverReachesServerOrPersistsState() = runBlocking {
        val server = FakeServerValidator().apply { redeemResult = successfulRedemption() }
        val manager = manager(server, FakeSecurityManager())

        val result = manager.activateWithCode("bad")

        assertTrue(result is ActivationResult.InvalidCode)
        assertEquals(0, server.redeemCalls)
        assertFalse(manager.isActivatedAndValid())
    }

    private fun manager(server: FakeServerValidator, security: FakeSecurityManager) =
        LicenseManagerImpl(context, server, LicenseCodeValidator(), security)

    private fun encryptedPreferencesFile(): File = File(
        context.applicationInfo.dataDir,
        "shared_prefs/$PREFS_NAME.xml"
    ).also { assertTrue("encrypted preferences file was not created", it.exists()) }

    private fun successfulRedemption() = ServerRedemptionResult.Success(validRecord(VALID_TOKEN))

    private fun validRecord(accessToken: String?) = ServerLicenseRecord(
        accessToken = accessToken,
        planId = "phase3-pro",
        productId = "android",
        startsAt = Instant.now().minusSeconds(60),
        expiresAt = Instant.now().plusSeconds(30L * 24 * 60 * 60),
        billingStatus = "PAID"
    )

    private class FakeServerValidator : LicenseServerValidator {
        var redeemResult: ServerRedemptionResult = ServerRedemptionResult.NotRedeemable
        var validationResult: ServerLicenseValidationResult = ServerLicenseValidationResult.Invalid
        var redeemCalls = 0
        var redeemedCode: String? = null
        var redeemedHardwareId: String? = null
        var validatedHardwareId: String? = null

        override suspend fun redeem(code: String, hardwareId: String): ServerRedemptionResult {
            redeemCalls++
            redeemedCode = code
            redeemedHardwareId = hardwareId
            return redeemResult
        }

        override suspend fun validate(hardwareId: String): ServerLicenseValidationResult {
            validatedHardwareId = hardwareId
            return validationResult
        }
    }

    private class FakeSecurityManager(
        private val failOnSave: Boolean = false
    ) : SecurityManager {
        private val token = MutableStateFlow("")
        override fun getAccessToken(): String = token.value
        override fun saveAccessToken(token: String) {
            if (failOnSave) throw IllegalStateException("secure storage unavailable")
            this.token.value = token
        }
        override fun clearAccessToken() { token.value = "" }
        override fun hasValidAccessToken(): Boolean = token.value.length >= 32
        override val accessTokenFlow: Flow<String> = token
    }

    private companion object {
        const val PREFS_NAME = "jarvis_hardware_license"
        const val VALID_TOKEN = "license-token-abcdefghijklmnopqrstuvwxyz"
    }
}
