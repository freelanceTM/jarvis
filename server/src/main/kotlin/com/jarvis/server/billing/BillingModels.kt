package com.jarvis.server.billing

import java.time.Instant
import java.util.UUID

enum class BillingProviderId { PADDLE, HELEKET, LOCAL_TMT }
enum class BillingOrderStatus {
    CREATED,
    PROCESSING,
    RECONCILIATION_REQUIRED,
    PENDING,
    PAID,
    FAILED,
    CANCELED,
    REFUNDED,
    EXPIRED
}

data class BillingOrder(
    val id: UUID,
    val accountId: UUID,
    val licenseId: UUID?,
    val planId: String,
    val provider: BillingProviderId,
    val status: BillingOrderStatus,
    val amountMinor: Long,
    val currency: String,
    val idempotencyKey: String,
    val providerOrderId: String?,
    val providerSubscriptionId: String?,
    val checkoutUrl: String?,
    val paidAt: Instant?
)

data class ProviderCheckout(
    val providerOrderId: String,
    val checkoutUrl: String,
    val providerSubscriptionId: String? = null
)

interface BillingProvider {
    val id: BillingProviderId
    fun isConfigured(): Boolean
    suspend fun createCheckout(order: BillingOrder, plan: com.jarvis.server.license.BillingPlan): ProviderCheckout
}

sealed interface CreateCheckoutOutcome {
    data class Success(val order: BillingOrder) : CreateCheckoutOutcome
    data object UnknownPlan : CreateCheckoutOutcome
    data object ProviderUnavailable : CreateCheckoutOutcome
    data object NoActiveLicense : CreateCheckoutOutcome
    data object InvalidRequest : CreateCheckoutOutcome
    data object ProviderFailure : CreateCheckoutOutcome
    data class InProgress(val order: BillingOrder) : CreateCheckoutOutcome
    data class ReconciliationRequired(val order: BillingOrder) : CreateCheckoutOutcome
}

/** A provider call may have succeeded remotely even though no response reached us. */
class BillingProviderException(
    val ambiguous: Boolean,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

enum class BillingEventKind { PAID, PAYMENT_FAILED, CANCELED, REFUNDED, IGNORED }

data class VerifiedBillingEvent(
    val provider: BillingProviderId,
    val providerEventId: String,
    val providerOrderId: String?,
    val eventType: String,
    val kind: BillingEventKind,
    val payloadHash: ByteArray,
    val occurredAt: Instant?,
    val providerSubscriptionId: String? = null,
    val localOrderId: UUID? = null,
    val expectedAmountMinor: Long? = null,
    val expectedCurrency: String? = null
)

enum class BillingEventApplyResult { PROCESSED, DUPLICATE, UNKNOWN_ORDER, INVALID_STATE }
