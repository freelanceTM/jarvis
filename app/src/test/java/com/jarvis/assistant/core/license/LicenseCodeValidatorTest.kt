package com.jarvis.assistant.core.license

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункты аудита #1 (мастер-коды) и #2 (серверная валидация box-кодов).
 *
 * Главные инварианты:
 *  - мастер-коды работают только через удалённый конфиг;
 *  - box-коды валидирует СЕРВЕР (источник правды); локальный checksum —
 *    только временный fallback, когда сервер недоступен.
 */
class LicenseCodeValidatorTest {

    private val validator = LicenseCodeValidator()
    private val hardwareId = "JRV-TEST-DEVICE-ABCDEF"

    /** Фейковый серверный валидатор с программируемым ответом. */
    private class FakeServerValidator(
        private val result: ServerValidationResult
    ) : LicenseServerValidator {
        var lastCode: String? = null
            private set
        var lastHardwareId: String? = null
            private set

        override suspend fun validate(code: String, hardwareId: String): ServerValidationResult {
            lastCode = code
            lastHardwareId = hardwareId
            return result
        }
    }

    private fun validate(
        code: String,
        remote: LicenseConfigData? = null,
        server: ServerValidationResult = ServerValidationResult.Valid(),
        used: Set<String> = emptySet(),
        boundTo: String? = null
    ) = runBlocking {
        validator.validate(
            cleanCode = code,
            remoteConfig = remote,
            serverValidator = FakeServerValidator(server),
            currentHardwareId = hardwareId,
            usedMasterCodes = used,
            codeBoundToHardwareId = boundTo
        )
    }

    // --------------------------------------------------------- box-коды: сервер — источник правды

    @Test
    fun `server valid verdict activates box code`() {
        assertEquals(
            LicenseCodeValidator.CodeVerdict.BoxCodeValid,
            validate(code = "ABCDEFGHIJKL", server = ServerValidationResult.Valid(30))
        )
    }

    @Test
    fun `server invalid verdict rejects code even with passable checksum`() {
        // "ABCDEFGHIJKL" проходит локальную контрольную сумму, но сервер
        // отклоняет — вердикт сервера имеет приоритет.
        assertEquals(
            LicenseCodeValidator.CodeVerdict.Invalid,
            validate(code = "ABCDEFGHIJKL", server = ServerValidationResult.Invalid("Код уже использован"))
        )
    }

    @Test
    fun `server unavailable falls back to local checksum temporarily`() {
        // Сервер не развёрнут → временный локальный fallback (TODO(server): удалить).
        assertEquals(
            LicenseCodeValidator.CodeVerdict.BoxCodeValid,
            validate(code = "ABCDEFGHIJKL", server = ServerValidationResult.ServiceUnavailable)
        )
    }

    @Test
    fun `server unavailable and bad checksum rejects code`() {
        assertEquals(
            LicenseCodeValidator.CodeVerdict.Invalid,
            validate(code = "ABCDEFGHIJKM", server = ServerValidationResult.ServiceUnavailable)
        )
    }

    @Test
    fun `server receives code and hardware id`() = runBlocking {
        val server = FakeServerValidator(ServerValidationResult.Valid())
        validator.validate(
            cleanCode = "ABCDEFGHIJKL",
            remoteConfig = null,
            serverValidator = server,
            currentHardwareId = hardwareId,
            usedMasterCodes = emptySet(),
            codeBoundToHardwareId = null
        )

        assertEquals("ABCDEFGHIJKL", server.lastCode)
        assertEquals(hardwareId, server.lastHardwareId)
    }

    // --------------------------------------------------------- мастер-коды (удалённый конфиг)

    @Test
    fun `master codes do not work when remote config is unavailable`() {
        // Сервер не развёрнут / офлайн → remoteConfig = null → мастер-код невалиден.
        assertEquals(
            LicenseCodeValidator.CodeVerdict.Invalid,
            validate(code = "JARVIS2026", remote = null, server = ServerValidationResult.ServiceUnavailable)
        )
    }

    @Test
    fun `master codes do not work when remotely disabled`() {
        val remote = LicenseConfigData(masterCodes = listOf("JARVIS2026"), masterCodesEnabled = false)

        assertEquals(
            LicenseCodeValidator.CodeVerdict.Invalid,
            validate(code = "JARVIS2026", remote = remote, server = ServerValidationResult.ServiceUnavailable)
        )
    }

    @Test
    fun `master code works when enabled and listed remotely`() {
        val remote = LicenseConfigData(masterCodes = listOf("JARVIS2026"), masterCodesEnabled = true)

        assertEquals(
            LicenseCodeValidator.CodeVerdict.MasterCodeValid,
            validate(code = "JARVIS2026", remote = remote)
        )
    }

    @Test
    fun `master code not in remote list is invalid`() {
        // Код не мастер-код (нет в списке), и сервер отклоняет его как box-код.
        val remote = LicenseConfigData(masterCodes = listOf("OTHER2026"), masterCodesEnabled = true)

        assertEquals(
            LicenseCodeValidator.CodeVerdict.Invalid,
            validate(code = "JARVIS2026", remote = remote, server = ServerValidationResult.Invalid("Код не найден"))
        )
    }

    @Test
    fun `master code reuse on same device is rejected`() {
        val remote = LicenseConfigData(masterCodes = listOf("JARVIS2026"), masterCodesEnabled = true)

        assertEquals(
            LicenseCodeValidator.CodeVerdict.MasterCodeAlreadyUsed,
            validate(code = "JARVIS2026", remote = remote, used = setOf("JARVIS2026"), boundTo = hardwareId)
        )
    }

    @Test
    fun `master code bound to another hardware is rejected`() {
        val remote = LicenseConfigData(masterCodes = listOf("JARVIS2026"), masterCodesEnabled = true)

        assertEquals(
            LicenseCodeValidator.CodeVerdict.MasterCodeAlreadyUsed,
            validate(code = "JARVIS2026", remote = remote, boundTo = "JRV-OTHER-DEVICE-123456")
        )
    }

    // --------------------------------------------------------- общие

    @Test
    fun `former master codes no longer pass as box codes`() {
        // JARVIS2026/STARTUP2026/ASHGABAT2026/JARVISEARCLIP удалены из кода
        // и НЕ должны проходить валидацию ни при каком раскладе (даже офлайн,
        // когда и конфиг, и сервер недоступны).
        listOf("JARVIS2026", "STARTUP2026", "ASHGABAT2026", "JARVISEARCLIP").forEach { code ->
            val verdict = validate(
                code = code,
                remote = null,
                server = ServerValidationResult.ServiceUnavailable
            )
            assertEquals("$code не должен быть валиден", LicenseCodeValidator.CodeVerdict.Invalid, verdict)
        }
    }

    @Test
    fun `short codes are rejected before server call`() {
        assertEquals(
            LicenseCodeValidator.CodeVerdict.Invalid,
            validate(code = "SHORT")
        )
    }

    @Test
    fun `box code failing local checksum is invalid when server down`() {
        val verdict = validate(code = "ABCDEFGHIJKM", server = ServerValidationResult.ServiceUnavailable)
        assertTrue(verdict is LicenseCodeValidator.CodeVerdict.Invalid)
    }
}
