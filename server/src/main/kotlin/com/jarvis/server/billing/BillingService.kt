package com.jarvis.server.billing

import com.jarvis.server.license.JdbcLicenseRepository
import com.jarvis.server.provider.TransportException
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.util.UUID

class BillingService(
    private val billingRepository: JdbcBillingRepository,
    private val licenseRepository: JdbcLicenseRepository,
    providers: Collection<BillingProvider>,
    private val clock: Clock = Clock.systemUTC()
) {
    private val providersById = providers.associateBy(BillingProvider::id)

    init {
        require(providersById.size == providers.size) { "Duplicate billing provider" }
    }

    suspend fun createCheckout(
        accountId: UUID,
        planId: String,
        providerId: BillingProviderId,
        idempotencyKey: String
    ): CreateCheckoutOutcome {
        if (!idempotencyKey.matches(Regex("[A-Za-z0-9_-]{8,128}"))) {
            return CreateCheckoutOutcome.InvalidRequest
        }
        val plan = licenseRepository.findPlan(planId) ?: return CreateCheckoutOutcome.UnknownPlan
        val provider = providersById[providerId]?.takeIf(BillingProvider::isConfigured)
            ?: return CreateCheckoutOutcome.ProviderUnavailable
        when (providerId) {
            BillingProviderId.PADDLE -> if (plan.paddlePriceId.isNullOrBlank()) {
                return CreateCheckoutOutcome.UnknownPlan
            }
            BillingProviderId.HELEKET -> if (plan.heleketCurrency.isNullOrBlank()) {
                return CreateCheckoutOutcome.UnknownPlan
            }
            BillingProviderId.LOCAL_TMT -> Unit
        }

        val now = clock.instant()
        val order = billingRepository.createOrGetOrder(
            accountId, plan, providerId, idempotencyKey, now
        ) ?: return CreateCheckoutOutcome.NoActiveLicense

        when (order.status) {
            BillingOrderStatus.PENDING, BillingOrderStatus.PAID ->
                return CreateCheckoutOutcome.Success(order)
            BillingOrderStatus.PROCESSING -> return CreateCheckoutOutcome.InProgress(order)
            BillingOrderStatus.RECONCILIATION_REQUIRED ->
                return CreateCheckoutOutcome.ReconciliationRequired(order)
            BillingOrderStatus.CREATED -> Unit
            BillingOrderStatus.FAILED,
            BillingOrderStatus.CANCELED,
            BillingOrderStatus.REFUNDED,
            BillingOrderStatus.EXPIRED -> return CreateCheckoutOutcome.InvalidRequest
        }

        if (!billingRepository.claimCheckoutCreation(order.id, now)) {
            return when (val current = billingRepository.findOrder(order.id)) {
                null -> CreateCheckoutOutcome.ProviderFailure
                else -> if (current.status in setOf(BillingOrderStatus.PENDING, BillingOrderStatus.PAID)) {
                    CreateCheckoutOutcome.Success(current)
                } else {
                    CreateCheckoutOutcome.InProgress(current)
                }
            }
        }

        return try {
            val checkout = provider.createCheckout(order.copy(status = BillingOrderStatus.PROCESSING), plan)
            CreateCheckoutOutcome.Success(
                billingRepository.markCheckoutCreated(
                    order.id,
                    checkout.providerOrderId,
                    checkout.checkoutUrl,
                    checkout.providerSubscriptionId,
                    clock.instant()
                )
            )
        } catch (cancelled: CancellationException) {
            billingRepository.markReconciliationRequired(order.id, clock.instant())
            throw cancelled
        } catch (failure: BillingProviderException) {
            if (failure.ambiguous) {
                CreateCheckoutOutcome.ReconciliationRequired(
                    reconciliationOrder(order, clock.instant())
                )
            } else {
                billingRepository.markCheckoutFailed(order.id, clock.instant())
                CreateCheckoutOutcome.ProviderFailure
            }
        } catch (_: TransportException) {
            CreateCheckoutOutcome.ReconciliationRequired(
                reconciliationOrder(order, clock.instant())
            )
        } catch (_: Exception) {
            // Once provider.createCheckout starts, an unknown exception may have
            // happened after remote creation. Prefer manual reconciliation over
            // a possible duplicate charge.
            CreateCheckoutOutcome.ReconciliationRequired(
                reconciliationOrder(order, clock.instant())
            )
        }
    }

    private fun reconciliationOrder(order: BillingOrder, now: java.time.Instant): BillingOrder =
        billingRepository.markReconciliationRequired(order.id, now)
            ?: billingRepository.findOrder(order.id)
            ?: order.copy(status = BillingOrderStatus.RECONCILIATION_REQUIRED)

    fun applyVerifiedEvent(event: VerifiedBillingEvent): BillingEventApplyResult =
        billingRepository.applyEvent(event, clock.instant())
}
