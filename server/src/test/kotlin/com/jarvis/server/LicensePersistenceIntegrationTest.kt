package com.jarvis.server

import com.jarvis.server.license.BillingPlan
import com.jarvis.server.license.IssueLicenseCommand
import com.jarvis.server.license.JdbcLicenseRepository
import com.jarvis.server.license.LicenseCrypto
import com.jarvis.server.license.LicenseService
import com.jarvis.server.license.LicenseValidationOutcome
import com.jarvis.server.license.RedeemOutcome
import com.jarvis.server.license.ValidationFailure
import com.jarvis.server.persistence.DatabaseMigrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class LicensePersistenceIntegrationTest : PostgresTestSupport() {
    private val now = Instant.parse("2026-08-20T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val crypto = LicenseCrypto("test-license-pepper-32-bytes-minimum-value")

    private fun service(): LicenseService {
        val repository = JdbcLicenseRepository(dataSource, crypto)
        return LicenseService(repository, crypto, clock).also {
            it.upsertPlan(
                BillingPlan(
                    id = "earclip-monthly",
                    productId = "jarvis-earclip",
                    displayName = "JARVIS Earclip Monthly",
                    durationDays = 30,
                    amountMinor = 1_400,
                    currency = "USD",
                    paddlePriceId = "pri_1234567890",
                    heleketCurrency = "USDT"
                )
            )
        }
    }

    private fun issue(service: LicenseService, expiresAt: Instant? = null) = service.issue(
        IssueLicenseCommand(
            planId = "earclip-monthly",
            expiresAt = expiresAt,
            actorId = "admin-test",
            requestId = UUID.randomUUID().toString(),
            remoteAddress = "127.0.0.1"
        )
    )

    @Test
    fun `clean database migrations are complete idempotent and constrained`() {
        val migrator = DatabaseMigrator(dataSource)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), migrator.appliedVersions())
        migrator.migrate()
        assertEquals(listOf(1, 2, 3, 4, 5, 6), migrator.appliedVersions())

        val tables = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema='public'"
            ).use { it.executeQuery().use { r -> buildSet { while (r.next()) add(r.getString(1)) } } }
        }
        assertTrue(tables.containsAll(setOf("licenses", "api_tokens", "billing_orders", "billing_events", "admin_accounts", "admin_sessions", "admin_audit_log", "admin_settings", "feature_flags")))
    }

    @Test
    fun `issuance stores only keyed hash and returns unpredictable unique codes`() {
        val service = service()
        val first = issue(service)
        val second = issue(service)

        assertNotEquals(first.code, second.code)
        assertTrue(first.code.matches(Regex("JRV(?:-[A-Z2-9]{5}){4}")))
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT code_hash, code_hint, metadata::text FROM licenses WHERE id = ?").use {
                it.setObject(1, first.licenseId)
                it.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(32, result.getBytes("code_hash").size)
                    assertEquals(first.code.takeLast(5), result.getString("code_hint"))
                    assertFalse(result.getString("metadata").contains(first.code))
                }
            }
        }
    }

    @Test
    fun `atomic redeem issues token and server validation owns expiration`() {
        val service = service()
        val issued = issue(service)
        val redeemed = service.redeem(issued.code, "device-abcdefgh", "req-1", "127.0.0.1")
            as RedeemOutcome.Success

        assertTrue(redeemed.accessToken.startsWith("jrv_"))
        assertEquals(now.plusSeconds(30L * 86_400), redeemed.expiresAt)
        assertNotNull(service.authenticateAccessToken(redeemed.accessToken))
        val validation = service.validate(redeemed.accountId, "device-abcdefgh")
        assertTrue(validation is LicenseValidationOutcome.Valid)
        assertEquals(redeemed.expiresAt, (validation as LicenseValidationOutcome.Valid).expiresAt)
    }

    @Test
    fun `unknown malformed and replayed one-time codes never redeem`() {
        val service = service()
        assertTrue(service.redeem("not-a-code", "device-abcdefgh", "r1", null) is RedeemOutcome.InvalidOrUnknown)
        assertTrue(
            service.redeem("JRV-AAAAA-AAAAA-AAAAA-AAAAA", "device-abcdefgh", "r2", null)
                is RedeemOutcome.InvalidOrUnknown
        )
        val issued = issue(service)
        assertTrue(service.redeem(issued.code, "device-abcdefgh", "r3", null) is RedeemOutcome.Success)
        assertTrue(service.redeem(issued.code, "device-abcdefgh", "r4", null) is RedeemOutcome.AlreadyRedeemed)
        assertTrue(service.redeem(issued.code, "other-device-xyz", "r5", null) is RedeemOutcome.AlreadyRedeemed)
    }

    @Test
    fun `concurrent redemption has exactly one winner`() {
        val service = service()
        val issued = issue(service)
        val pool = Executors.newFixedThreadPool(16)
        try {
            val outcomes = pool.invokeAll(
                List(64) { index ->
                    Callable {
                        service.redeem(
                            issued.code,
                            "device-${index.toString().padStart(8, '0')}",
                            "request-$index",
                            "127.0.0.1"
                        )
                    }
                }
            ).map { it.get() }
            assertEquals(1, outcomes.count { it is RedeemOutcome.Success })
            assertEquals(63, outcomes.count { it is RedeemOutcome.AlreadyRedeemed })

            val counts = dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT (SELECT count(*) FROM accounts), (SELECT count(*) FROM api_tokens)")
                        .use { it.next(); it.getLong(1) to it.getLong(2) }
                }
            }
            assertEquals(1L to 1L, counts)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `expired and revoked codes fail closed`() {
        val service = service()
        val expired = issue(service, expiresAt = now.minusSeconds(1))
        assertTrue(service.redeem(expired.code, "device-abcdefgh", "expired", null) is RedeemOutcome.Expired)

        val revoked = issue(service)
        assertTrue(service.revoke(revoked.licenseId, "fraud", "admin", "revoke", null))
        assertTrue(
            service.redeem(revoked.code, "device-abcdefgh", "revoked", null)
                is RedeemOutcome.RevokedOrDisabled
        )
    }

    @Test
    fun `server expiration invalidates entitlement while authentication remains available for renewal`() {
        val service = service()
        val issued = issue(service)
        val redeemed = service.redeem(issued.code, "device-abcdefgh", "redeem", null)
            as RedeemOutcome.Success
        val futureService = LicenseService(
            JdbcLicenseRepository(dataSource, crypto),
            crypto,
            Clock.fixed(now.plus(31, ChronoUnit.DAYS), ZoneOffset.UTC)
        )

        val result = futureService.validate(redeemed.accountId, "device-abcdefgh")

        assertEquals(ValidationFailure.EXPIRED, (result as LicenseValidationOutcome.Invalid).reason)
        assertNotNull(futureService.authenticateAccessToken(redeemed.accessToken))
    }

    @Test
    fun `wrong device cannot validate and revocation kills access token`() {
        val service = service()
        val issued = issue(service)
        val redeemed = service.redeem(issued.code, "device-abcdefgh", "redeem", null)
            as RedeemOutcome.Success

        val wrong = service.validate(redeemed.accountId, "device-ijklmnop")
        assertEquals(
            ValidationFailure.WRONG_DEVICE,
            (wrong as LicenseValidationOutcome.Invalid).reason
        )
        assertTrue(service.revoke(issued.licenseId, "chargeback", "admin", "revoke", null))
        assertEquals(null, service.authenticateAccessToken(redeemed.accessToken))
    }

    @Test
    fun `corrupt active database state fails closed`() {
        val service = service()
        val issued = issue(service)
        val redeemed = service.redeem(issued.code, "device-abcdefgh", "redeem", null)
            as RedeemOutcome.Success
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE licenses SET starts_at = NULL WHERE id = ?").use {
                it.setObject(1, redeemed.licenseId)
                it.executeUpdate()
            }
        }
        val result = service.validate(redeemed.accountId, "device-abcdefgh")
        assertEquals(
            ValidationFailure.INVALID_STATE,
            (result as LicenseValidationOutcome.Invalid).reason
        )
    }

    @Test(expected = com.jarvis.server.license.UnknownPlanException::class)
    fun `issuance rejects unknown plan`() {
        val repository = JdbcLicenseRepository(dataSource, crypto)
        LicenseService(repository, crypto, clock).issue(
            IssueLicenseCommand(
                planId = "attacker-controlled-plan",
                actorId = "admin",
                requestId = "request-unknown-plan",
                remoteAddress = null
            )
        )
    }
}
