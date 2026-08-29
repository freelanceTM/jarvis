package com.jarvis.server.license

import java.time.Clock
import java.time.Duration
import java.util.UUID

class LicenseService(
    private val repository: JdbcLicenseRepository,
    private val crypto: LicenseCrypto,
    private val clock: Clock = Clock.systemUTC()
) {
    fun upsertPlan(plan: BillingPlan) {
        require(plan.id.matches(Regex("[a-z0-9][a-z0-9_-]{1,63}"))) { "invalid plan id" }
        require(plan.productId.matches(Regex("[a-z0-9][a-z0-9_-]{1,63}"))) { "invalid product id" }
        require(plan.displayName.length in 1..128) { "invalid display name" }
        require(plan.durationDays in 1..3650) { "invalid plan duration" }
        require(plan.amountMinor >= 0) { "invalid plan amount" }
        require(plan.currency.matches(Regex("[A-Z]{3}"))) { "invalid plan currency" }
        repository.upsertPlan(plan, clock.instant())
    }

    fun issue(command: IssueLicenseCommand): IssuedLicense {
        require(command.actorId.length in 1..128) { "actor is required" }
        require(command.requestId.length in 1..64) { "requestId is required" }
        require(command.oneTime) { "Only one-time activation codes are supported" }
        if (command.startsAt != null && command.expiresAt != null) {
            require(command.expiresAt.isAfter(command.startsAt)) { "expiresAt must be after startsAt" }
            require(Duration.between(command.startsAt, command.expiresAt).toDays() <= 3650) {
                "license duration exceeds 3650 days"
            }
        }

        val now = clock.instant()
        repeat(5) {
            val code = crypto.generateLicenseCode()
            val id = UUID.randomUUID()
            try {
                repository.insertIssuedLicense(
                    id = id,
                    codeHash = crypto.licenseCodeHash(code),
                    codeHint = crypto.codeHint(code),
                    command = command,
                    now = now
                )
                return IssuedLicense(
                    licenseId = id,
                    code = code,
                    status = LicenseStatus.ISSUED,
                    planId = command.planId,
                    issuedAt = now,
                    expiresAt = command.expiresAt
                )
            } catch (_: DuplicateLicenseCodeException) {
                // Cryptographically improbable; retry without leaking either code.
            }
        }
        error("Could not generate a unique license code")
    }

    fun redeem(
        rawCode: String,
        deviceId: String,
        requestId: String,
        remoteAddress: String?
    ): RedeemOutcome {
        val canonical = crypto.normalizeLicenseCode(rawCode)
            ?: return RedeemOutcome.InvalidOrUnknown
        return repository.redeem(canonical, deviceId, requestId, remoteAddress, clock.instant())
    }

    fun validate(accountId: UUID, deviceId: String): LicenseValidationOutcome =
        repository.validate(accountId, deviceId, clock.instant())

    fun authenticateAccessToken(token: String): AuthenticatedAccount? =
        repository.authenticateAccessToken(token, clock.instant())

    fun hasActiveEntitlement(accountId: UUID): Boolean =
        repository.hasActiveEntitlement(accountId, clock.instant())

    fun revoke(
        licenseId: UUID,
        reason: String,
        actorId: String,
        requestId: String,
        remoteAddress: String?
    ): Boolean = repository.revoke(
        licenseId, reason, actorId, requestId, remoteAddress, clock.instant()
    )
}
