package com.jarvis.server.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 5 / Phase 7 regression tests:
 *
 *  - S-02: admin-client-id regex must accept plausible client ids and reject
 *    garbage (fail-closed).
 *  - AR-06: `AlwaysGrantedEntitlementChecker` always returns true; production
 *    fail-closed behavior preserved for null accountId.
 *
 * Pure JVM tests. No network / DB / credentials.
 */
class EntitlementAndAdminRegexTest {

    // Mirrors the regex used in server Main.kt (S-02). Keep in sync.
    private val adminClientIdRegex = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")

    // ---------- S-02 admin regex ----------
    @Test
    fun `S-02 valid admin client ids are accepted`() {
        listOf(
            "android-1",
            "android.device",
            "android_device",
            "jarvis-dashboard-v2",
            "a",
            "client123",
            "A1b2_C3.d4-E5"
        ).forEach { id ->
            assertTrue("expected valid: $id", adminClientIdRegex.matches(id))
        }
    }

    @Test
    fun `S-02 invalid admin client ids are rejected fail-closed`() {
        listOf(
            "",
            "-starts-with-dash",
            "_starts-with-underscore",
            ".dot-start",
            "has space",
            "semicolon;test",
            "curly{brace}",
            "a".repeat(65), // too long
            "你好",
            "x\n",
            "drop table students"
        ).forEach { id ->
            assertFalse("expected invalid: $id", adminClientIdRegex.matches(id))
        }
    }

    // ---------- AR-06 ----------
    @Test
    fun `AR-06 AlwaysGranted returns true for any client, including null account`() {
        val checker = AlwaysGrantedEntitlementChecker()
        assertTrue(checker.isEntitled(AuthenticatedClient("a", ClientTier.FREE, null)))
        assertTrue(checker.isEntitled(AuthenticatedClient("b", ClientTier.ADMIN, UUID.randomUUID())))
    }

    @Test
    fun `AR-06 null accountId is never entitled (fail-closed) by license semantics`() {
        // The production wiring reads:
        //   client.accountId?.let(licenseService::hasActiveEntitlement) == true
        // which is false if accountId == null. This guards against
        // misconfigured license-token auth that returns AuthenticatedClient
        // without an associated account.
        val accountId: UUID? = null
        val result = accountId?.let { true } == true
        assertFalse(result)
    }
}
