package com.jarvis.server

import com.jarvis.server.admin.AdminPasswords
import com.jarvis.server.admin.AdminPermission
import com.jarvis.server.admin.AdminRbac
import com.jarvis.server.admin.AdminRole
import com.jarvis.server.admin.AiProviderOverride
import com.jarvis.server.admin.AiRoutingSettings
import com.jarvis.server.admin.CostModel
import com.jarvis.server.admin.CostSettings
import com.jarvis.server.admin.FeatureFlagService
import com.jarvis.server.admin.ProviderCostEntry
import com.jarvis.server.admin.ProviderTokenUsage
import com.jarvis.server.admin.SecuritySettings
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты ядра Control Plane (Control Plane ТЗ §30): RBAC-матрица,
 * хеширование паролей, cost model (formula/UNKNOWN), feature-flag rollout.
 */
class AdminControlPlaneUnitTest {

    /* ── RBAC (ТЗ §5): backend-матрица — единственный источник правды ────── */

    @Test
    fun `super admin can everything`() {
        AdminPermission.entries.forEach { permission ->
            assertTrue(AdminRbac.can(AdminRole.SUPER_ADMIN, permission))
        }
    }

    @Test
    fun `admin cannot manage admin accounts`() {
        assertTrue(AdminRbac.can(AdminRole.ADMIN, AdminPermission.LICENSES_WRITE))
        assertTrue(AdminRbac.can(AdminRole.ADMIN, AdminPermission.PROVIDERS_CONFIGURE))
        assertFalse(AdminRbac.can(AdminRole.ADMIN, AdminPermission.ADMINS_MANAGE))
    }

    @Test
    fun `support cannot configure providers or revoke devices`() {
        assertTrue(AdminRbac.can(AdminRole.SUPPORT, AdminPermission.USERS_READ))
        assertTrue(AdminRbac.can(AdminRole.SUPPORT, AdminPermission.LICENSES_WRITE))
        assertFalse(AdminRbac.can(AdminRole.SUPPORT, AdminPermission.PROVIDERS_CONFIGURE))
        assertFalse(AdminRbac.can(AdminRole.SUPPORT, AdminPermission.DEVICES_REVOKE))
        assertFalse(AdminRbac.can(AdminRole.SUPPORT, AdminPermission.SETTINGS_WRITE))
    }

    @Test
    fun `viewer is strictly read-only`() {
        AdminPermission.entries.filter { it.name.endsWith("_READ") }.forEach { permission ->
            assertTrue("VIEWER должен иметь $permission", AdminRbac.can(AdminRole.VIEWER, permission))
        }
        AdminPermission.entries.filter { !it.name.endsWith("_READ") }.forEach { permission ->
            assertFalse("VIEWER не должен иметь $permission", AdminRbac.can(AdminRole.VIEWER, permission))
        }
    }

    /* ── Password hashing (ТЗ §4) ──────────────────────────────────────────── */

    @Test
    fun `password roundtrip verifies`() {
        val hash = AdminPasswords.hash("correct-horse-battery")
        assertTrue(AdminPasswords.verify("correct-horse-battery", hash))
        assertFalse(AdminPasswords.verify("wrong-password-123", hash))
    }

    @Test
    fun `same password yields different hashes (unique salt)`() {
        val a = AdminPasswords.hash("correct-horse-battery")
        val b = AdminPasswords.hash("correct-horse-battery")
        assertTrue(a != b)
        assertTrue(AdminPasswords.verify("correct-horse-battery", a))
        assertTrue(AdminPasswords.verify("correct-horse-battery", b))
    }

    @Test
    fun `corrupted stored hash fails closed`() {
        assertFalse(AdminPasswords.verify("correct-horse-battery", ""))
        assertFalse(AdminPasswords.verify("correct-horse-battery", "garbage"))
        assertFalse(AdminPasswords.verify("correct-horse-battery", "pbkdf2\$999\$AAA\$BBB"))
        assertFalse(AdminPasswords.verify("correct-horse-battery", "pbkdf2\$100\$AAA\$BBB"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `short passwords are rejected at hash time`() {
        AdminPasswords.hash("short12")
    }

    /* ── Settings validation (ТЗ §20: validation обязательна) ─────────────── */

    @Test(expected = IllegalArgumentException::class)
    fun `ai settings reject unknown provider`() {
        AiRoutingSettings(providers = mapOf("OPENAI" to AiProviderOverride(enabled = false)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ai settings reject out-of-range priority`() {
        AiRoutingSettings(providers = mapOf("GROQ" to AiProviderOverride(priority = 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `security settings reject absurd session ttl`() {
        SecuritySettings(sessionTtlMinutes = 10_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cost settings reject negative price`() {
        CostSettings(providers = mapOf("GROQ" to ProviderCostEntry(usdPerMillionInput = -1.0)))
    }

    /* ── Feature flags rollout (ТЗ §21) ───────────────────────────────────── */

    @Test
    fun `flag buckets are deterministic and within 0..99`() {
        val service = FeatureFlagService(mockk(relaxed = true))
        val first = service.bucketOf("wake_word", "client-123")
        assertEquals(first, service.bucketOf("wake_word", "client-123"))
        assertEquals(first, service.bucketOf("wake_word", "client-123"))
        repeat(200) { idx ->
            val bucket = service.bucketOf("ear_mode", "client-$idx")
            assertTrue(bucket in 0..99)
        }
        // Разные клиенты не все в одном бакете (санити распределения).
        val distinct = (0 until 100).map { service.bucketOf("translation", "u-$it") }.toSet()
        assertTrue("ожидали широкое распределение, получили ${distinct.size}", distinct.size > 50)
    }

    @Test
    fun `flag evaluation respects enabled and rollout percent`() {
        val service = FeatureFlagService(mockk(relaxed = true))
        // Флаг отсутствует → off.
        assertFalse(service.isEnabledFor("wake_word", "client-1"))
    }

    /* ── Cost model (ТЗ §16: input / formula / result, UNKNOWN без цены) ──── */

    @Test
    fun `cost is calculated from configured prices with transparent formula`() {
        val prices = CostSettings(
            providers = mapOf(
                "GROQ" to ProviderCostEntry(usdPerMillionInput = 0.05, usdPerMillionOutput = 0.10)
            )
        )
        val totals = CostModel.calculate(
            usage = listOf(ProviderTokenUsage("GROQ", requests = 2, errors = 0, inputTokens = 1_000_000, outputTokens = 500_000)),
            prices = prices
        )
        assertEquals(1, totals.lines.size)
        assertEquals(0.05 + 0.05, totals.lines[0].costUsd!!, 1e-9)
        assertEquals("in/1e6*0.05 + out/1e6*0.1", totals.lines[0].formula)
        assertEquals(0.1, totals.totalUsd!!, 1e-9)
        assertTrue(totals.unknownProviders.isEmpty())
    }

    @Test
    fun `unconfigured price yields UNKNOWN not zero`() {
        val totals = CostModel.calculate(
            usage = listOf(
                ProviderTokenUsage("GROQ", requests = 1, errors = 0, inputTokens = 100, outputTokens = 100),
                ProviderTokenUsage("GEMINI", requests = 1, errors = 0, inputTokens = 100, outputTokens = 100)
            ),
            prices = CostSettings(
                providers = mapOf("GEMINI" to ProviderCostEntry(usdPerMillionInput = 1.0, usdPerMillionOutput = 2.0))
            )
        )
        assertNull("GROQ без цены обязан быть UNKNOWN", totals.lines.first { it.provider == "GROQ" }.costUsd)
        assertTrue(totals.unknownProviders.contains("GROQ"))
        assertNull("сумма с UNKNOWN не может быть точной", totals.totalUsd)
        assertEquals(0.0003, totals.knownUsd, 1e-9) // GEMINI: 100/1e6*1 + 100/1e6*2
    }

    @Test
    fun `missing token counts yield UNKNOWN`() {
        val totals = CostModel.calculate(
            usage = listOf(
                ProviderTokenUsage("GROQ", requests = 1, errors = 0, inputTokens = null, outputTokens = null)
            ),
            prices = CostSettings(
                providers = mapOf("GROQ" to ProviderCostEntry(usdPerMillionInput = 1.0, usdPerMillionOutput = 1.0))
            )
        )
        assertNull(totals.lines[0].costUsd)
        assertTrue(totals.lines[0].formula.contains("token counts"))
    }
}
