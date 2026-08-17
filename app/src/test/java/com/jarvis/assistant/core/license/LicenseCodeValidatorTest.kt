package com.jarvis.assistant.core.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пункт аудита #1 (CRITICAL): мастер-коды больше НЕ зашиты в приложение.
 *
 * Главный инвариант: без удалённого конфига (сервер не развёрнут / офлайн)
 * мастер-коды НЕ работают. Они приходят только из LicenseRemoteConfig и
 * могут быть отозваны без обновления APK.
 */
class LicenseCodeValidatorTest {

    private val validator = LicenseCodeValidator()
    private val hardwareId = "JRV-TEST-DEVICE-ABCDEF"

    // --------------------------------------------------------- мастер-коды

    @Test
    fun `master codes do not work when remote config is unavailable`() {
        // Сервер не развёрнут / офлайн → remoteConfig = null → мастер-код невалиден.
        val verdict = validator.validate(
            cleanCode = "JARVIS2026",
            remoteConfig = null,
            usedMasterCodes = emptySet(),
            codeBoundToHardwareId = null,
            currentHardwareId = hardwareId
        )

        assertEquals(LicenseCodeValidator.CodeVerdict.Invalid, verdict)
    }

    @Test
    fun `master codes do not work when remotely disabled`() {
        // Рубильник master_codes_enabled=false → коды отозваны сервером.
        val remote = LicenseConfigData(masterCodes = listOf("JARVIS2026"), masterCodesEnabled = false)

        val verdict = validator.validate(
            cleanCode = "JARVIS2026",
            remoteConfig = remote,
            usedMasterCodes = emptySet(),
            codeBoundToHardwareId = null,
            currentHardwareId = hardwareId
        )

        assertEquals(LicenseCodeValidator.CodeVerdict.Invalid, verdict)
    }

    @Test
    fun `master code works when enabled and listed remotely`() {
        val remote = LicenseConfigData(masterCodes = listOf("JARVIS2026"), masterCodesEnabled = true)

        val verdict = validator.validate(
            cleanCode = "JARVIS2026",
            remoteConfig = remote,
            usedMasterCodes = emptySet(),
            codeBoundToHardwareId = null,
            currentHardwareId = hardwareId
        )

        assertEquals(LicenseCodeValidator.CodeVerdict.MasterCodeValid, verdict)
    }

    @Test
    fun `master code not in remote list is invalid`() {
        val remote = LicenseConfigData(masterCodes = listOf("OTHER2026"), masterCodesEnabled = true)

        val verdict = validator.validate(
            cleanCode = "JARVIS2026",
            remoteConfig = remote,
            usedMasterCodes = emptySet(),
            codeBoundToHardwareId = null,
            currentHardwareId = hardwareId
        )

        assertEquals(LicenseCodeValidator.CodeVerdict.Invalid, verdict)
    }

    @Test
    fun `master code reuse on same device is rejected`() {
        val remote = LicenseConfigData(masterCodes = listOf("JARVIS2026"), masterCodesEnabled = true)

        val verdict = validator.validate(
            cleanCode = "JARVIS2026",
            remoteConfig = remote,
            usedMasterCodes = setOf("JARVIS2026"),
            codeBoundToHardwareId = hardwareId,
            currentHardwareId = hardwareId
        )

        assertEquals(LicenseCodeValidator.CodeVerdict.MasterCodeAlreadyUsed, verdict)
    }

    @Test
    fun `master code bound to another hardware is rejected`() {
        val remote = LicenseConfigData(masterCodes = listOf("JARVIS2026"), masterCodesEnabled = true)

        val verdict = validator.validate(
            cleanCode = "JARVIS2026",
            remoteConfig = remote,
            usedMasterCodes = emptySet(),
            codeBoundToHardwareId = "JRV-OTHER-DEVICE-123456",
            currentHardwareId = hardwareId
        )

        assertEquals(LicenseCodeValidator.CodeVerdict.MasterCodeAlreadyUsed, verdict)
    }

    // --------------------------------------------------------- box-коды

    @Test
    fun `former master codes no longer pass as box codes`() {
        // JARVIS2026/STARTUP2026/ASHGABAT2026/JARVISEARCLIP удалены из кода
        // и НЕ должны проходить валидацию ни при каком раскладе (даже офлайн,
        // когда remote-конфиг недоступен).
        listOf("JARVIS2026", "STARTUP2026", "ASHGABAT2026", "JARVISEARCLIP").forEach { code ->
            val verdict = validator.validate(
                cleanCode = code,
                remoteConfig = null,
                usedMasterCodes = emptySet(),
                codeBoundToHardwareId = null,
                currentHardwareId = hardwareId
            )
            assertEquals("$code не должен быть валиден", LicenseCodeValidator.CodeVerdict.Invalid, verdict)
        }
    }

    @Test
    fun `box code with valid checksum and documented length passes`() {
        // "ABCDEFGHIJKL": 12 символов, сумма с весами кратна 7 — валидный box-код.
        val verdict = validator.validate(
            cleanCode = "ABCDEFGHIJKL",
            remoteConfig = null, // box-коды не зависят от сервера
            usedMasterCodes = emptySet(),
            codeBoundToHardwareId = null,
            currentHardwareId = hardwareId
        )

        assertEquals(LicenseCodeValidator.CodeVerdict.BoxCodeValid, verdict)
    }

    @Test
    fun `short codes are rejected`() {
        val verdict = validator.validate(
            cleanCode = "SHORT",
            remoteConfig = null,
            usedMasterCodes = emptySet(),
            codeBoundToHardwareId = null,
            currentHardwareId = hardwareId
        )

        assertEquals(LicenseCodeValidator.CodeVerdict.Invalid, verdict)
    }

    @Test
    fun `box code failing checksum is invalid`() {
        // 12 символов, но сумма не кратна 7.
        val verdict = validator.validate(
            cleanCode = "ABCDEFGHIJKM",
            remoteConfig = null,
            usedMasterCodes = emptySet(),
            codeBoundToHardwareId = null,
            currentHardwareId = hardwareId
        )

        assertTrue("Код без контрольной суммы невалиден", verdict is LicenseCodeValidator.CodeVerdict.Invalid)
    }
}
