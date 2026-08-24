package com.jarvis.server

import com.jarvis.server.billing.BillingEventApplyResult
import com.jarvis.server.billing.BillingEventKind
import com.jarvis.server.billing.BillingOrderStatus
import com.jarvis.server.billing.BillingProvider
import com.jarvis.server.billing.BillingProviderId
import com.jarvis.server.billing.BillingService
import com.jarvis.server.billing.CreateCheckoutOutcome
import com.jarvis.server.billing.JdbcBillingRepository
import com.jarvis.server.billing.ProviderCheckout
import com.jarvis.server.billing.VerifiedBillingEvent
import com.jarvis.server.license.BillingPlan
import com.jarvis.server.license.IssueLicenseCommand
import com.jarvis.server.license.JdbcLicenseRepository
import com.jarvis.server.license.LicenseCrypto
import com.jarvis.server.license.LicenseService
import com.jarvis.server.license.LicenseValidationOutcome
import com.jarvis.server.license.RedeemOutcome
import com.jarvis.server.provider.ProviderFailureKind
import com.jarvis.server.provider.TransportException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class BillingPersistenceIntegrationTest : PostgresTestSupport() {
    private val now = Instant.parse("2026-08-20T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var licenseService: LicenseService
    private lateinit var licenseRepository: JdbcLicenseRepository
    private lateinit var billingRepository: JdbcBillingRepository
    private lateinit var billingService: BillingService
    private lateinit var accountId: UUID
    private lateinit var accessToken: String
    private val providerCalls = AtomicInteger()

    private val plan = BillingPlan(
        "earclip-monthly", "jarvis-earclip", "Monthly", 30, 1_400, "USD",
        paddlePriceId = "pri_1234567890", heleketCurrency = "USDT"
    )

    private inner class FakeProvider(private val delayMs: Long = 0) : BillingProvider {
        override val id = BillingProviderId.PADDLE
        override fun isConfigured() = true
        override suspend fun createCheckout(
            order: com.jarvis.server.billing.BillingOrder,
            plan: BillingPlan
        ): ProviderCheckout {
            providerCalls.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            return ProviderCheckout(
                providerOrderId = "txn_${order.id.toString().replace("-", "")}",
                checkoutUrl = "https://checkout.paddle.com/pay/${order.id}"
            )
        }
    }

    private inner class AmbiguousProvider : BillingProvider {
        override val id = BillingProviderId.PADDLE
        override fun isConfigured() = true
        override suspend fun createCheckout(
            order: com.jarvis.server.billing.BillingOrder,
            plan: BillingPlan
        ): ProviderCheckout {
            providerCalls.incrementAndGet()
            throw TransportException(ProviderFailureKind.TIMEOUT, "response lost")
        }
    }

    private fun setupServices(provider: BillingProvider = FakeProvider()) {
        val crypto = LicenseCrypto("test-license-pepper-32-bytes-minimum-value")
        licenseRepository = JdbcLicenseRepository(dataSource, crypto)
        licenseService = LicenseService(licenseRepository, crypto, clock)
        licenseService.upsertPlan(plan)
        val issued = licenseService.issue(
            IssueLicenseCommand(
                planId = plan.id,
                actorId = "admin",
                requestId = "issue-request",
                remoteAddress = "127.0.0.1"
            )
        )
        val redeemed = licenseService.redeem(
            issued.code, "device-abcdefgh", "redeem-request", "127.0.0.1"
        ) as RedeemOutcome.Success
        accountId = redeemed.accountId
        accessToken = redeemed.accessToken
        billingRepository = JdbcBillingRepository(dataSource)
        billingService = BillingService(
            billingRepository, licenseRepository, listOf(provider), clock
        )
    }

    @Test
    fun `checkout idempotency returns one order and one provider call`() = runBlocking {
        setupServices()
        val first = billingService.createCheckout(accountId, plan.id, BillingProviderId.PADDLE, "idem-12345678")
        val second = billingService.createCheckout(accountId, plan.id, BillingProviderId.PADDLE, "idem-12345678")

        assertTrue(first is CreateCheckoutOutcome.Success)
        assertTrue(second is CreateCheckoutOutcome.Success)
        assertEquals(
            (first as CreateCheckoutOutcome.Success).order.id,
            (second as CreateCheckoutOutcome.Success).order.id
        )
        assertEquals(1, providerCalls.get())
    }

    @Test
    fun `concurrent checkout creation claims provider call once`() {
        setupServices(FakeProvider(delayMs = 100))
        val pool = Executors.newFixedThreadPool(12)
        try {
            val results = pool.invokeAll(
                List(24) {
                    Callable {
                        runBlocking {
                            billingService.createCheckout(
                                accountId, plan.id, BillingProviderId.PADDLE, "idem-concurrent-1"
                            )
                        }
                    }
                }
            ).map { it.get() }
            assertEquals(1, providerCalls.get())
            assertTrue(results.any { it is CreateCheckoutOutcome.Success })
            assertTrue(results.all { it is CreateCheckoutOutcome.Success || it is CreateCheckoutOutcome.InProgress })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `ambiguous provider timeout blocks duplicate checkout and delayed webhook reconciles`() = runBlocking {
        setupServices(AmbiguousProvider())
        val before = licenseService.validate(accountId, "device-abcdefgh") as LicenseValidationOutcome.Valid

        val first = billingService.createCheckout(
            accountId, plan.id, BillingProviderId.PADDLE, "idem-timeout-first"
        )
        val second = billingService.createCheckout(
            accountId, plan.id, BillingProviderId.PADDLE, "idem-timeout-second"
        )

        assertTrue(first is CreateCheckoutOutcome.ReconciliationRequired)
        assertTrue(second is CreateCheckoutOutcome.ReconciliationRequired)
        assertEquals(1, providerCalls.get())
        val orderId = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT id FROM billing_orders WHERE account_id = ? AND status = 'RECONCILIATION_REQUIRED'"
            ).use {
                it.setObject(1, accountId)
                it.executeQuery().use { result -> assertTrue(result.next()); result.getObject(1, UUID::class.java) }
            }
        }
        val delayed = event("txn_recovered_1234567890", "evt-recovered", BillingEventKind.PAID)
            .copy(localOrderId = orderId)
        assertEquals(BillingEventApplyResult.PROCESSED, billingService.applyVerifiedEvent(delayed))

        val renewed = licenseService.validate(accountId, "device-abcdefgh") as LicenseValidationOutcome.Valid
        assertEquals(before.expiresAt.plusSeconds(30L * 86_400), renewed.expiresAt)
        val reconciled = billingRepository.findOrder(orderId)!!
        assertEquals(BillingOrderStatus.PAID, reconciled.status)
        assertEquals("txn_recovered_1234567890", reconciled.providerOrderId)
    }

    @Test
    fun `paid event renews once across duplicate and alternate event ids`() = runBlocking {
        setupServices()
        val checkout = billingService.createCheckout(
            accountId, plan.id, BillingProviderId.PADDLE, "idem-paid-123"
        ) as CreateCheckoutOutcome.Success
        val initial = licenseService.validate(accountId, "device-abcdefgh") as LicenseValidationOutcome.Valid
        val baseEvent = event(checkout.order.providerOrderId!!, "evt-paid-1", BillingEventKind.PAID)

        assertEquals(BillingEventApplyResult.PROCESSED, billingService.applyVerifiedEvent(baseEvent))
        assertEquals(BillingEventApplyResult.DUPLICATE, billingService.applyVerifiedEvent(baseEvent))
        assertEquals(
            BillingEventApplyResult.PROCESSED,
            billingService.applyVerifiedEvent(baseEvent.copy(providerEventId = "evt-paid-completed-2"))
        )

        val renewed = licenseService.validate(accountId, "device-abcdefgh") as LicenseValidationOutcome.Valid
        assertEquals(initial.expiresAt.plusSeconds(30L * 86_400), renewed.expiresAt)
        assertEquals(BillingOrderStatus.PAID, billingRepository.findOrder(checkout.order.id)?.status)
    }

    @Test
    fun `amount mismatch never grants renewal`() = runBlocking {
        setupServices()
        val checkout = billingService.createCheckout(
            accountId, plan.id, BillingProviderId.PADDLE, "idem-mismatch"
        ) as CreateCheckoutOutcome.Success
        val before = licenseService.validate(accountId, "device-abcdefgh") as LicenseValidationOutcome.Valid

        val result = billingService.applyVerifiedEvent(
            event(checkout.order.providerOrderId!!, "evt-mismatch", BillingEventKind.PAID)
                .copy(expectedAmountMinor = 1)
        )

        assertEquals(BillingEventApplyResult.INVALID_STATE, result)
        val after = licenseService.validate(accountId, "device-abcdefgh") as LicenseValidationOutcome.Valid
        assertEquals(before.expiresAt, after.expiresAt)
    }

    @Test
    fun `conflicting signed order references never grant renewal`() = runBlocking {
        setupServices()
        val checkout = billingService.createCheckout(
            accountId, plan.id, BillingProviderId.PADDLE, "idem-reference-mismatch"
        ) as CreateCheckoutOutcome.Success
        val before = licenseService.validate(accountId, "device-abcdefgh") as LicenseValidationOutcome.Valid

        val result = billingService.applyVerifiedEvent(
            event("txn_conflicting_1234567890", "evt-reference-mismatch", BillingEventKind.PAID)
                .copy(localOrderId = checkout.order.id)
        )

        assertEquals(BillingEventApplyResult.INVALID_STATE, result)
        val after = licenseService.validate(accountId, "device-abcdefgh") as LicenseValidationOutcome.Valid
        assertEquals(before.expiresAt, after.expiresAt)
        assertEquals(BillingOrderStatus.PENDING, billingRepository.findOrder(checkout.order.id)?.status)
    }

    @Test
    fun `expired account can authenticate purchase and reactivate through paid renewal`() = runBlocking {
        setupServices()
        val future = now.plusSeconds(31L * 86_400)
        val futureClock = Clock.fixed(future, ZoneOffset.UTC)
        val futureLicenseService = LicenseService(licenseRepository, LicenseCrypto("test-license-pepper-32-bytes-minimum-value"), futureClock)
        val expired = futureLicenseService.validate(accountId, "device-abcdefgh")
        assertTrue(expired is LicenseValidationOutcome.Invalid)
        assertNotNull(futureLicenseService.authenticateAccessToken(accessToken))

        val futureBilling = BillingService(
            billingRepository, licenseRepository, listOf(FakeProvider()), futureClock
        )
        val checkout = futureBilling.createCheckout(
            accountId, plan.id, BillingProviderId.PADDLE, "idem-expired-renewal"
        ) as CreateCheckoutOutcome.Success
        futureBilling.applyVerifiedEvent(
            event(checkout.order.providerOrderId!!, "evt-expired-paid", BillingEventKind.PAID)
                .copy(occurredAt = future)
        )

        val valid = futureLicenseService.validate(accountId, "device-abcdefgh")
            as LicenseValidationOutcome.Valid
        assertEquals(future.plusSeconds(30L * 86_400), valid.expiresAt)
    }

    @Test
    fun `failed payment preserves paid period but refund revokes entitlement and token`() = runBlocking {
        setupServices()
        val failedCheckout = billingService.createCheckout(
            accountId, plan.id, BillingProviderId.PADDLE, "idem-failed"
        ) as CreateCheckoutOutcome.Success
        billingService.applyVerifiedEvent(
            event(failedCheckout.order.providerOrderId!!, "evt-failed", BillingEventKind.PAYMENT_FAILED)
        )
        assertTrue(licenseService.validate(accountId, "device-abcdefgh") is LicenseValidationOutcome.Valid)

        val refundCheckout = billingService.createCheckout(
            accountId, plan.id, BillingProviderId.PADDLE, "idem-refund"
        ) as CreateCheckoutOutcome.Success
        billingService.applyVerifiedEvent(
            event(refundCheckout.order.providerOrderId!!, "evt-refund-paid", BillingEventKind.PAID)
        )
        billingService.applyVerifiedEvent(
            event(refundCheckout.order.providerOrderId!!, "evt-refund", BillingEventKind.REFUNDED)
        )
        assertTrue(licenseService.validate(accountId, "device-abcdefgh") is LicenseValidationOutcome.Invalid)
        assertEquals(null, licenseService.authenticateAccessToken(accessToken))
    }

    private fun event(
        providerOrderId: String,
        eventId: String,
        kind: BillingEventKind
    ) = VerifiedBillingEvent(
        provider = BillingProviderId.PADDLE,
        providerEventId = eventId,
        providerOrderId = providerOrderId,
        eventType = kind.name,
        kind = kind,
        payloadHash = ByteArray(32) { 7 },
        occurredAt = now,
        expectedAmountMinor = 1_400,
        expectedCurrency = "USD"
    )
}
