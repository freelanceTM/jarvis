package com.jarvis.assistant.core.license

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Лицензирование на клиенте всегда fail closed и не знает master/checksum. */
class LicenseCodeValidatorTest {

    private val validator = LicenseCodeValidator()
    private val hardwareId = "JRV-TEST-DEVICE-ABCDEF"

    private class FakeServerValidator(
        private val result: ServerValidationResult
    ) : LicenseServerValidator {
        var calls = 0
        var lastCode: String? = null
        var lastHardwareId: String? = null

        override suspend fun validate(code: String, hardwareId: String): ServerValidationResult {
            calls++
            lastCode = code
            lastHardwareId = hardwareId
            return result
        }
    }

    private fun validate(code: String, result: ServerValidationResult): LicenseCodeValidator.CodeVerdict =
        runBlocking {
            validator.validate(code, FakeServerValidator(result), hardwareId)
        }

    @Test
    fun `server valid verdict preserves license duration`() {
        assertEquals(
            LicenseCodeValidator.CodeVerdict.BoxCodeValid(30),
            validate("ABCDEFGHIJKL", ServerValidationResult.Valid(30))
        )
        assertEquals(
            LicenseCodeValidator.CodeVerdict.BoxCodeValid(90),
            validate("ABCDEFGHIJKL", ServerValidationResult.Valid(90))
        )
    }

    @Test
    fun `server rejection always wins even for formerly passable checksum`() {
        assertEquals(
            LicenseCodeValidator.CodeVerdict.Invalid,
            validate("ABCDEFGHIJKL", ServerValidationResult.Invalid("used"))
        )
    }

    @Test
    fun `server unavailable fails closed for every code shape`() {
        listOf("ABCDEFGHIJKL", "ABCDEFGHIJKM", "JARVIS2026", "STARTUP2026").forEach { code ->
            assertEquals(
                "code=$code",
                LicenseCodeValidator.CodeVerdict.ServiceUnavailable,
                validate(code, ServerValidationResult.ServiceUnavailable)
            )
        }
    }

    @Test
    fun `former master codes have no client-side special path`() {
        val server = FakeServerValidator(ServerValidationResult.Invalid("not issued"))
        runBlocking { validator.validate("JARVIS2026", server, hardwareId) }
        assertEquals(1, server.calls)
        assertEquals("JARVIS2026", server.lastCode)
    }

    @Test
    fun `invalid server durations are rejected`() {
        for (days in listOf(Int.MIN_VALUE, -1, 0, 3_651, Int.MAX_VALUE)) {
            assertEquals(
                "days=$days",
                LicenseCodeValidator.CodeVerdict.Invalid,
                validate("ABCDEFGHIJKL", ServerValidationResult.Valid(days))
            )
        }
    }

    @Test
    fun `duration boundaries are accepted`() {
        assertEquals(
            LicenseCodeValidator.CodeVerdict.BoxCodeValid(1),
            validate("ABCDEFGHIJKL", ServerValidationResult.Valid(1))
        )
        assertEquals(
            LicenseCodeValidator.CodeVerdict.BoxCodeValid(3_650),
            validate("ABCDEFGHIJKL", ServerValidationResult.Valid(3_650))
        )
    }

    @Test
    fun `short codes are rejected before network call`() = runBlocking {
        val server = FakeServerValidator(ServerValidationResult.Valid())
        val verdict = validator.validate("SHORT", server, hardwareId)
        assertTrue(verdict is LicenseCodeValidator.CodeVerdict.Invalid)
        assertEquals(0, server.calls)
    }

    @Test
    fun `server receives canonical code and hardware id`() = runBlocking {
        val server = FakeServerValidator(ServerValidationResult.Valid())
        validator.validate("ABCDEFGHIJKL", server, hardwareId)
        assertEquals("ABCDEFGHIJKL", server.lastCode)
        assertEquals(hardwareId, server.lastHardwareId)
    }
}
