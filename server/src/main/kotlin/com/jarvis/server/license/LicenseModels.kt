package com.jarvis.server.license

import java.time.Instant
import java.util.UUID

enum class LicenseStatus { ISSUED, ACTIVE, EXPIRED, REVOKED, DISABLED }
enum class LicenseBillingStatus { GRANTED, PENDING, PAID, PAST_DUE, CANCELED, REFUNDED }

data class BillingPlan(
    val id: String,
    val productId: String,
    val displayName: String,
    val durationDays: Int,
    val amountMinor: Long,
    val currency: String,
    val paddlePriceId: String? = null,
    val heleketCurrency: String? = null,
    val active: Boolean = true
)

data class IssueLicenseCommand(
    val planId: String,
    val accountExternalRef: String? = null,
    val startsAt: Instant? = null,
    val expiresAt: Instant? = null,
    val oneTime: Boolean = true,
    val metadataJson: String = "{}",
    val actorId: String,
    val requestId: String,
    val remoteAddress: String?
)

data class IssuedLicense(
    val licenseId: UUID,
    /** Returned exactly once; only its keyed hash is persisted. */
    val code: String,
    val status: LicenseStatus,
    val planId: String,
    val issuedAt: Instant,
    val expiresAt: Instant?
)

sealed interface RedeemOutcome {
    data class Success(
        val accountId: UUID,
        val licenseId: UUID,
        val accessToken: String,
        val planId: String,
        val productId: String,
        val startsAt: Instant,
        val expiresAt: Instant,
        val billingStatus: LicenseBillingStatus
    ) : RedeemOutcome

    data object InvalidOrUnknown : RedeemOutcome
    data object AlreadyRedeemed : RedeemOutcome
    data object Expired : RedeemOutcome
    data object RevokedOrDisabled : RedeemOutcome
    data object InvalidPlan : RedeemOutcome
    data object InvalidState : RedeemOutcome
}

enum class ValidationFailure {
    NO_LICENSE,
    EXPIRED,
    REVOKED_OR_DISABLED,
    WRONG_DEVICE,
    BILLING_INACTIVE,
    INVALID_PLAN,
    ACCOUNT_DISABLED,
    INVALID_STATE
}

sealed interface LicenseValidationOutcome {
    data class Valid(
        val licenseId: UUID,
        val planId: String,
        val productId: String,
        val startsAt: Instant,
        val expiresAt: Instant,
        val billingStatus: LicenseBillingStatus
    ) : LicenseValidationOutcome

    data class Invalid(val reason: ValidationFailure) : LicenseValidationOutcome
}

data class AuthenticatedAccount(
    val accountId: UUID,
    val externalRef: String?
)
