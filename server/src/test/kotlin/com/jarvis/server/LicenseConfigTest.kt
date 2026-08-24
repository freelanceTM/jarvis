package com.jarvis.server

import com.jarvis.server.config.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseConfigTest {
    private fun baseEnv() = mutableMapOf(
        "DATABASE_URL" to "jdbc:postgresql://db.internal:5432/jarvis",
        "DATABASE_USER" to "jarvis",
        "DATABASE_PASSWORD" to "database-secret",
        "LICENSE_CODE_PEPPER" to "p".repeat(64),
        "BILLING_PLANS" to "earclip-monthly|jarvis-earclip|Monthly|30|1400|USD|pri_1234567890|USDT",
        "PADDLE_ENVIRONMENT" to "sandbox",
        "PADDLE_API_KEY" to "paddle-key",
        "PADDLE_WEBHOOK_SECRET" to "paddle-webhook",
        "PUBLIC_BASE_URL" to "https://api.example.com",
        "HELEKET_MERCHANT_ID" to "merchant",
        "HELEKET_API_KEY" to "heleket-key"
    )

    @Test
    fun `persistent license billing configuration parses explicit plan catalog`() {
        val config = ServerConfig.fromEnv(baseEnv()::get).licenseSubsystem
        assertNotNull(config)
        val subsystem = config!!
        assertEquals(1, subsystem.plans.size)
        assertEquals(30, subsystem.plans.single().durationDays)
        assertEquals(1_400L, subsystem.plans.single().amountMinor)
        assertEquals("https://sandbox-api.paddle.com", subsystem.paddle.apiBaseUrl)
        assertTrue(subsystem.paddle.configured)
        assertTrue(subsystem.heleket.configured)
    }

    @Test
    fun `partial database or license secrets fail fast`() {
        val missingPepper = baseEnv().apply { remove("LICENSE_CODE_PEPPER") }
        assertFails { ServerConfig.fromEnv(missingPepper::get) }
        val missingPassword = baseEnv().apply { remove("DATABASE_PASSWORD") }
        assertFails { ServerConfig.fromEnv(missingPassword::get) }
        val weakPepper = baseEnv().apply { put("LICENSE_CODE_PEPPER", "weak") }
        assertFails { ServerConfig.fromEnv(weakPepper::get) }
    }

    @Test
    fun `duplicate or malformed plans fail fast`() {
        val duplicate = baseEnv().apply {
            this["BILLING_PLANS"] = this["BILLING_PLANS"] + ";" + this["BILLING_PLANS"]
        }
        assertFails { ServerConfig.fromEnv(duplicate::get) }
        val malformed = baseEnv().apply { this["BILLING_PLANS"] = "attacker|plan" }
        assertFails { ServerConfig.fromEnv(malformed::get) }
    }

    @Test
    fun `billing providers stay disabled without credentials`() {
        val env = baseEnv().apply {
            remove("PADDLE_API_KEY")
            remove("PADDLE_WEBHOOK_SECRET")
            remove("HELEKET_MERCHANT_ID")
            remove("HELEKET_API_KEY")
            remove("PUBLIC_BASE_URL")
        }
        val subsystem = ServerConfig.fromEnv(env::get).licenseSubsystem!!
        assertFalse(subsystem.paddle.configured)
        assertFalse(subsystem.heleket.configured)
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue("Expected configuration failure", failed)
    }
}
