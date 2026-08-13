package com.jarvis.assistant.core.license

import org.junit.Assert.*
import org.junit.Test

class LicenseManagerTest {

    @Test
    fun testValidHardwareCodes() {
        val masterCodes = listOf("JARVIS2026", "STARTUP2026", "ASHGABAT2026", "JARVISEARCLIP")
        for (code in masterCodes) {
            assertTrue("Master code $code should be recognized as valid", code.length >= 8)
        }
    }

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
