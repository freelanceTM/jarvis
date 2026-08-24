package com.jarvis.assistant.core.license

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Client activation only accepts a bounded, server-issued redemption record. */
class LicenseCodeValidatorTest {
    private val validator = LicenseCodeValidator()
    private val hardwareId = "JRV-TEST-DEVICE-ABCDEF"
    private val start = Instant.parse("2026-08-20T00:00:00Z")

    private class FakeServerValidator(
        private val redeemResult: ServerRedemptionResult
    ) : LicenseServerValidator {
        var calls = 0
        var lastCode: String? = null
        var lastHardwareId: String? = null

        override suspend fun redeem(code: String, hardwareId: String): ServerRedemptionResult {
            calls++
            lastCode = code
            lastHardwareId = hardwareId
            return redeemResult
        }

        override suspend fun validate(hardwareId: String): ServerLicenseValidationResult =
            ServerLicenseValidationResult.ServiceUnavailable
    }

    private fun record(days: Long, token: String? = "jrv_" + "x".repeat(43)) = ServerLicenseRecord(
        accessToken = token,
        planId = "earclip-monthly",
        productId = "jarvis-earclip",
        startsAt = start,
        expiresAt = start.plus(days, ChronoUnit.DAYS),
        billingStatus = "GRANTED"
    )

    private fun redeem(code: String, result: ServerRedemptionResult) = runBlocking {
        validator.redeem(code, FakeServerValidator(result), hardwareId)
    }

    @Test
    fun `server redemption preserves authoritative entitlement`() {
        val license = record(30)
        assertEquals(
            LicenseCodeValidator.CodeVerdict.BoxCodeValid(license),
            redeem("JRV-ABCDE-FGHJK-LMNPQ-RSTUV", ServerRedemptionResult.Success(license))
        )
    }

    @Test
    fun `server rejection and outage fail closed`() {
        assertEquals(
            LicenseCodeValidator.CodeVerdict.Invalid,
            redeem("JRV-ABCDE-FGHJK-LMNPQ-RSTUV", ServerRedemptionResult.NotRedeemable)
        )
        assertEquals(
            LicenseCodeValidator.CodeVerdict.ServiceUnavailable,
            redeem("JRV-ABCDE-FGHJK-LMNPQ-RSTUV", ServerRedemptionResult.ServiceUnavailable)
        )
        assertEquals(
            LicenseCodeValidator.CodeVerdict.RateLimited,
            redeem("JRV-ABCDE-FGHJK-LMNPQ-RSTUV", ServerRedemptionResult.RateLimited)
        )
    }

    @Test
    fun `missing token and invalid durations are rejected`() {
        for (license in listOf(record(0), record(-1), record(3_651), record(30, token = null))) {
            assertEquals(
                LicenseCodeValidator.CodeVerdict.Invalid,
                redeem("JRV-ABCDE-FGHJK-LMNPQ-RSTUV", ServerRedemptionResult.Success(license))
            )
        }
    }

    @Test
    fun `duration boundaries are accepted`() {
        for (days in listOf(1L, 3_650L)) {
            val license = record(days)
            assertEquals(
                LicenseCodeValidator.CodeVerdict.BoxCodeValid(license),
                redeem("JRV-ABCDE-FGHJK-LMNPQ-RSTUV", ServerRedemptionResult.Success(license))
            )
        }
    }

    @Test
    fun `short and oversized codes are rejected before network`() = runBlocking {
        val fake = FakeServerValidator(ServerRedemptionResult.Success(record(30)))
        assertTrue(validator.redeem("SHORT", fake, hardwareId) is LicenseCodeValidator.CodeVerdict.Invalid)
        assertTrue(
            validator.redeem("X".repeat(65), fake, hardwareId) is LicenseCodeValidator.CodeVerdict.Invalid
        )
        assertEquals(0, fake.calls)
    }

    @Test
    fun `canonical code and hardware id reach server`() = runBlocking {
        val fake = FakeServerValidator(ServerRedemptionResult.Success(record(30)))
        val code = "JRV-ABCDE-FGHJK-LMNPQ-RSTUV"
        validator.redeem(code, fake, hardwareId)
        assertEquals(code, fake.lastCode)
        assertEquals(hardwareId, fake.lastHardwareId)
    }
}
