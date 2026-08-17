package com.jarvis.assistant.core.license

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты LicenseManager (пункт аудита #1).
 *
 * ВАЖНО: тест `testValidHardwareCodes`, утверждавший, что мастер-коды
 * JARVIS2026/STARTUP2026/ASHGABAT2026/JARVISEARCLIP валидны, УДАЛЁН —
 * именно это поведение было уязвимостью. Мастер-коды теперь проверяются
 * только через удалённый конфиг (см. LicenseCodeValidatorTest).
 */
class LicenseManagerTest {

    @Test
    fun testFormatActivationCode() {
        val raw = "JARVIS2026ASHG"
        val formatted = raw.chunked(4).joinToString("-")
        assertEquals("JARV-IS20-26AS-HG", formatted)
    }

    @Test
    fun testRemainingDaysCalculation() {
        val now = System.currentTimeMillis()
        val expiry = now + (30L * 24 * 60 * 60 * 1000L)
        val remainingDays = ((expiry - now) / (24L * 60 * 60 * 1000L)).toInt()
        assertEquals(30, remainingDays)
    }
}
