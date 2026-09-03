package com.jarvis.server.license

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import com.jarvis.server.persistence.getInstant
import com.jarvis.server.persistence.setInstant
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

class DuplicateLicenseCodeException(cause: SQLException) : RuntimeException(cause)
class UnknownPlanException(planId: String) : RuntimeException("Unknown or inactive plan: $planId")

/** PostgreSQL-backed license source of truth. */
class JdbcLicenseRepository(
    private val dataSource: DataSource,
    private val crypto: LicenseCrypto
) {
    fun upsertPlan(plan: BillingPlan, now: Instant = Instant.now()) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO billing_plans(
                    id, product_id, display_name, duration_days, amount_minor, currency,
                    paddle_price_id, heleket_currency, active, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    product_id = EXCLUDED.product_id,
                    display_name = EXCLUDED.display_name,
                    duration_days = EXCLUDED.duration_days,
                    amount_minor = EXCLUDED.amount_minor,
                    currency = EXCLUDED.currency,
                    paddle_price_id = EXCLUDED.paddle_price_id,
                    heleket_currency = EXCLUDED.heleket_currency,
                    active = EXCLUDED.active,
                    updated_at = EXCLUDED.updated_at
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, plan.id)
                statement.setString(2, plan.productId)
                statement.setString(3, plan.displayName)
                statement.setInt(4, plan.durationDays)
                statement.setLong(5, plan.amountMinor)
                statement.setString(6, plan.currency)
                statement.setString(7, plan.paddlePriceId)
                statement.setString(8, plan.heleketCurrency)
                statement.setBoolean(9, plan.active)
                statement.setInstant(10, now)
                statement.setInstant(11, now)
                statement.executeUpdate()
            }
        }
    }

    fun findPlan(planId: String, onlyActive: Boolean = true): BillingPlan? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT id, product_id, display_name, duration_days, amount_minor, currency,
                   paddle_price_id, heleket_currency, active
            FROM billing_plans
            WHERE id = ? ${if (onlyActive) "AND active = TRUE" else ""}
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, planId)
            statement.executeQuery().use { result -> if (result.next()) result.toPlan() else null }
        }
    }

    fun insertIssuedLicense(
        id: UUID,
        codeHash: ByteArray,
        codeHint: String,
        command: IssueLicenseCommand,
        now: Instant
    ) {
        transaction { connection ->
            val plan = findPlan(connection, command.planId) ?: throw UnknownPlanException(command.planId)
            val accountId = command.accountExternalRef?.let { findOrCreateAccount(connection, it, now) }
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO licenses(
                        id, code_hash, code_hint, status, billing_status, issued_at,
                        starts_at, expires_at, product_id, plan_id, account_id, one_time,
                        metadata, created_at, updated_at
                    ) VALUES (?, ?, ?, 'ISSUED', 'GRANTED', ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setBytes(2, codeHash)
                    statement.setString(3, codeHint)
                    statement.setInstant(4, now)
                    statement.setInstant(5, command.startsAt)
                    statement.setInstant(6, command.expiresAt)
                    statement.setString(7, plan.productId)
                    statement.setString(8, plan.id)
                    statement.setObject(9, accountId)
                    statement.setBoolean(10, command.oneTime)
                    statement.setString(11, command.metadataJson)
                    statement.setInstant(12, now)
                    statement.setInstant(13, now)
                    statement.executeUpdate()
                }
            } catch (e: SQLException) {
                if (e.sqlState == "23505") throw DuplicateLicenseCodeException(e)
                throw e
            }
            audit(
                connection, "ADMIN", command.actorId, "LICENSE_ISSUED", "LICENSE", id,
                command.requestId, command.remoteAddress,
                """{"plan_id":"${jsonEscape(plan.id)}","code_hint":"${jsonEscape(codeHint)}"}""",
                now
            )
        }
    }

    fun redeem(
        canonicalCode: String,
        deviceId: String,
        requestId: String,
        remoteAddress: String?,
        now: Instant
    ): RedeemOutcome {
        val codeHash = crypto.licenseCodeHash(canonicalCode)
        val deviceHash = runCatching { crypto.deviceHash(deviceId) }.getOrElse {
            return RedeemOutcome.InvalidOrUnknown
        }

        return transaction { connection ->
            val row = connection.prepareStatement(
                """
                SELECT l.id, l.status, l.billing_status, l.starts_at, l.expires_at,
                       l.account_id, l.redeemed_at, l.revoked_at, l.plan_id,
                       p.product_id, p.duration_days, p.active AS plan_active,
                       a.status AS account_status
                FROM licenses l
                JOIN billing_plans p ON p.id = l.plan_id
                LEFT JOIN accounts a ON a.id = l.account_id
                WHERE l.code_hash = ?
                FOR UPDATE OF l
                """.trimIndent()
            ).use { statement ->
                statement.setBytes(1, codeHash)
                statement.executeQuery().use { result -> if (result.next()) RedemptionRow.from(result) else null }
            } ?: return@transaction RedeemOutcome.InvalidOrUnknown

            when (row.status) {
                LicenseStatus.REVOKED, LicenseStatus.DISABLED ->
                    return@transaction RedeemOutcome.RevokedOrDisabled
                LicenseStatus.EXPIRED -> return@transaction RedeemOutcome.Expired
                LicenseStatus.ACTIVE -> return@transaction RedeemOutcome.AlreadyRedeemed
                LicenseStatus.ISSUED -> Unit
            }
            if (row.redeemedAt != null) return@transaction RedeemOutcome.AlreadyRedeemed
            if (!row.planActive || row.durationDays !in 1..3650) return@transaction RedeemOutcome.InvalidPlan
            if (row.accountStatus != null && row.accountStatus != "ACTIVE") {
                return@transaction RedeemOutcome.RevokedOrDisabled
            }
            if (row.expiresAt != null && !row.expiresAt.isAfter(now)) {
                markExpired(connection, row.id, now)
                return@transaction RedeemOutcome.Expired
            }

            val accountId = row.accountId ?: createAccount(connection, now)
            val startsAt = row.startsAt ?: now
            val expiresAt = row.expiresAt ?: startsAt.plus(row.durationDays.toLong(), ChronoUnit.DAYS)
            if (!expiresAt.isAfter(now)) {
                markExpired(connection, row.id, now)
                return@transaction RedeemOutcome.Expired
            }

            val updated = connection.prepareStatement(
                """
                UPDATE licenses SET
                    status = 'ACTIVE', account_id = ?, starts_at = ?, expires_at = ?,
                    redeemed_at = ?, redeemed_device_hash = ?, updated_at = ?, version = version + 1
                WHERE id = ? AND status = 'ISSUED' AND redeemed_at IS NULL
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, accountId)
                statement.setInstant(2, startsAt)
                statement.setInstant(3, expiresAt)
                statement.setInstant(4, now)
                statement.setBytes(5, deviceHash)
                statement.setInstant(6, now)
                statement.setObject(7, row.id)
                statement.executeUpdate()
            }
            if (updated != 1) return@transaction RedeemOutcome.AlreadyRedeemed

            val accessToken = crypto.generateAccessToken()
            // Authentication survives entitlement expiry so the account can validate
            // status and purchase a renewal. AI access is gated separately.
            // V007: токен сразу привязывается к устройству, с которого прошёл redeem.
            insertApiToken(connection, accountId, accessToken, now, null, deviceHash)
            audit(
                connection, "DEVICE", null, "LICENSE_REDEEMED", "LICENSE", row.id,
                requestId, remoteAddress, "{}", now
            )
            RedeemOutcome.Success(
                accountId = accountId,
                licenseId = row.id,
                accessToken = accessToken,
                planId = row.planId,
                productId = row.productId,
                startsAt = startsAt,
                expiresAt = expiresAt,
                billingStatus = row.billingStatus
            )
        }
    }

    /**
     * Аутентификация jrv_-токена.
     *
     * @param deviceId идентификатор устройства. non-null — enforcement-путь
     *        (AI-исполнение): строка токена ОБЯЗАНА быть привязана к
     *        устройству (V007) и хеш обязан совпасть; legacy-токен без
     *        привязки отвергается (fail-closed, само-залечивается через
     *        /v1/license/validate → [bindTokenDevice]).
     *        null — legacy-путь (validate/checkout): проверка устройства не
     *        выполняется здесь (validate сверяет device_id с лицензией сам).
     */
    fun authenticateAccessToken(
        token: String,
        deviceId: String? = null,
        now: Instant = Instant.now()
    ): AuthenticatedAccount? {
        val tokenHash = crypto.accessTokenHash(token)
        val expectedDeviceHash = deviceId?.let { id ->
            runCatching { crypto.deviceHash(id) }.getOrElse { return null }
        }
        return transaction { connection ->
            connection.prepareStatement(
                """
                SELECT t.id, t.account_id, a.external_ref, t.device_hash
                FROM api_tokens t
                JOIN accounts a ON a.id = t.account_id
                WHERE t.token_hash = ? AND t.status = 'ACTIVE' AND a.status = 'ACTIVE'
                  AND (t.expires_at IS NULL OR t.expires_at > ?)
                """.trimIndent()
            ).use { statement ->
                statement.setBytes(1, tokenHash)
                statement.setInstant(2, now)
                statement.executeQuery().use { result ->
                    if (!result.next()) return@transaction null
                    val rowDeviceHash = result.getBytes("device_hash")
                    if (expectedDeviceHash != null &&
                        (rowDeviceHash == null ||
                            !crypto.constantTimeEquals(rowDeviceHash, expectedDeviceHash))
                    ) {
                        return@transaction null
                    }
                    val tokenId = result.getObject("id", UUID::class.java)
                    val account = AuthenticatedAccount(
                        accountId = result.getObject("account_id", UUID::class.java),
                        externalRef = result.getString("external_ref")
                    )
                    connection.prepareStatement("UPDATE api_tokens SET last_used_at = ? WHERE id = ?").use {
                        it.setInstant(1, now)
                        it.setObject(2, tokenId)
                        it.executeUpdate()
                    }
                    account
                }
            }
        }
    }

    fun validate(
        accountId: UUID,
        deviceId: String,
        now: Instant = Instant.now()
    ): LicenseValidationOutcome {
        val deviceHash = runCatching { crypto.deviceHash(deviceId) }.getOrElse {
            return LicenseValidationOutcome.Invalid(ValidationFailure.WRONG_DEVICE)
        }
        return transaction { connection ->
            val accountActive = connection.prepareStatement("SELECT status FROM accounts WHERE id = ?").use {
                it.setObject(1, accountId)
                it.executeQuery().use { result -> result.next() && result.getString(1) == "ACTIVE" }
            }
            if (!accountActive) return@transaction LicenseValidationOutcome.Invalid(ValidationFailure.ACCOUNT_DISABLED)

            val rows = connection.prepareStatement(
                """
                SELECT l.id, l.status, l.billing_status, l.starts_at, l.expires_at,
                       l.redeemed_device_hash, l.plan_id, l.product_id, p.active AS plan_active
                FROM licenses l
                JOIN billing_plans p ON p.id = l.plan_id
                WHERE l.account_id = ?
                ORDER BY l.expires_at DESC NULLS LAST, l.issued_at DESC
                FOR UPDATE OF l
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, accountId)
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(ValidationRow.from(result)) }
                }
            }
            if (rows.isEmpty()) return@transaction LicenseValidationOutcome.Invalid(ValidationFailure.NO_LICENSE)

            var fallback = ValidationFailure.NO_LICENSE
            for (row in rows) {
                when (row.status) {
                    LicenseStatus.REVOKED, LicenseStatus.DISABLED -> {
                        fallback = ValidationFailure.REVOKED_OR_DISABLED
                        continue
                    }
                    LicenseStatus.EXPIRED -> {
                        fallback = ValidationFailure.EXPIRED
                        continue
                    }
                    LicenseStatus.ISSUED -> {
                        fallback = ValidationFailure.INVALID_STATE
                        continue
                    }
                    LicenseStatus.ACTIVE -> Unit
                }
                if (!row.planActive) {
                    fallback = ValidationFailure.INVALID_PLAN
                    continue
                }
                if (row.deviceHash == null || !crypto.constantTimeEquals(row.deviceHash, deviceHash)) {
                    fallback = ValidationFailure.WRONG_DEVICE
                    continue
                }
                val startsAt = row.startsAt
                val expiresAt = row.expiresAt
                if (startsAt == null || expiresAt == null) {
                    fallback = ValidationFailure.INVALID_STATE
                    continue
                }
                if (now.isBefore(startsAt)) {
                    fallback = ValidationFailure.INVALID_STATE
                    continue
                }
                if (!expiresAt.isAfter(now)) {
                    markExpired(connection, row.id, now)
                    fallback = ValidationFailure.EXPIRED
                    continue
                }
                if (row.billingStatus !in setOf(
                        LicenseBillingStatus.GRANTED,
                        LicenseBillingStatus.PAID,
                        LicenseBillingStatus.CANCELED
                    )
                ) {
                    fallback = ValidationFailure.BILLING_INACTIVE
                    continue
                }
                return@transaction LicenseValidationOutcome.Valid(
                    licenseId = row.id,
                    planId = row.planId,
                    productId = row.productId,
                    startsAt = startsAt,
                    expiresAt = expiresAt,
                    billingStatus = row.billingStatus
                )
            }
            LicenseValidationOutcome.Invalid(fallback)
        }
    }

    fun hasActiveEntitlement(accountId: UUID, now: Instant = Instant.now()): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT EXISTS(
                    SELECT 1 FROM licenses l
                    JOIN billing_plans p ON p.id = l.plan_id
                    JOIN accounts a ON a.id = l.account_id
                    WHERE l.account_id = ? AND l.status = 'ACTIVE' AND a.status = 'ACTIVE'
                      AND p.active = TRUE AND l.starts_at <= ? AND l.expires_at > ?
                      AND l.billing_status IN ('GRANTED', 'PAID', 'CANCELED')
                )
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, accountId)
                statement.setInstant(2, now)
                statement.setInstant(3, now)
                statement.executeQuery().use { it.next(); it.getBoolean(1) }
            }
        }

    fun revoke(
        licenseId: UUID,
        reason: String,
        actorId: String,
        requestId: String,
        remoteAddress: String?,
        now: Instant = Instant.now()
    ): Boolean = transaction { connection ->
        val changed = connection.prepareStatement(
            """
            UPDATE licenses SET status = 'REVOKED', revoked_at = ?, revoked_reason = ?,
                   updated_at = ?, version = version + 1
            WHERE id = ? AND status NOT IN ('REVOKED', 'DISABLED')
            """.trimIndent()
        ).use { statement ->
            statement.setInstant(1, now)
            statement.setString(2, reason.take(256))
            statement.setInstant(3, now)
            statement.setObject(4, licenseId)
            statement.executeUpdate() == 1
        }
        if (changed) {
            connection.prepareStatement(
                """
                UPDATE api_tokens SET status = 'REVOKED', revoked_at = ?
                WHERE account_id = (SELECT account_id FROM licenses WHERE id = ?)
                  AND status = 'ACTIVE'
                """.trimIndent()
            ).use {
                it.setInstant(1, now)
                it.setObject(2, licenseId)
                it.executeUpdate()
            }
            audit(
                connection, "ADMIN", actorId, "LICENSE_REVOKED", "LICENSE", licenseId,
                requestId, remoteAddress, "{}", now
            )
        }
        changed
    }

    private fun findPlan(connection: Connection, planId: String): BillingPlan? =
        connection.prepareStatement(
            """
            SELECT id, product_id, display_name, duration_days, amount_minor, currency,
                   paddle_price_id, heleket_currency, active
            FROM billing_plans WHERE id = ? AND active = TRUE
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, planId)
            statement.executeQuery().use { result -> if (result.next()) result.toPlan() else null }
        }

    private fun findOrCreateAccount(connection: Connection, externalRef: String, now: Instant): UUID {
        require(externalRef.length in 1..128 && externalRef.none(Char::isISOControl)) {
            "accountExternalRef is malformed"
        }
        connection.prepareStatement("SELECT id FROM accounts WHERE external_ref = ?").use { statement ->
            statement.setString(1, externalRef)
            statement.executeQuery().use { if (it.next()) return it.getObject(1, UUID::class.java) }
        }
        val id = UUID.randomUUID()
        val inserted = connection.prepareStatement(
            """
            INSERT INTO accounts(id, external_ref, status, created_at, updated_at)
            VALUES (?, ?, 'ACTIVE', ?, ?)
            ON CONFLICT (external_ref) DO NOTHING
            """.trimIndent()
        ).use {
            it.setObject(1, id)
            it.setString(2, externalRef)
            it.setInstant(3, now)
            it.setInstant(4, now)
            it.executeUpdate() == 1
        }
        if (inserted) return id
        return connection.prepareStatement("SELECT id FROM accounts WHERE external_ref = ?").use {
            it.setString(1, externalRef)
            it.executeQuery().use { result -> check(result.next()); result.getObject(1, UUID::class.java) }
        }
    }

    private fun createAccount(connection: Connection, now: Instant): UUID {
        val id = UUID.randomUUID()
        connection.prepareStatement(
            "INSERT INTO accounts(id, external_ref, status, created_at, updated_at) VALUES (?, NULL, 'ACTIVE', ?, ?)"
        ).use {
            it.setObject(1, id)
            it.setInstant(2, now)
            it.setInstant(3, now)
            it.executeUpdate()
        }
        return id
    }

    private fun insertApiToken(
        connection: Connection,
        accountId: UUID,
        token: String,
        issuedAt: Instant,
        expiresAt: Instant?,
        deviceHash: ByteArray?
    ) {
        connection.prepareStatement(
            """
            INSERT INTO api_tokens(id, account_id, token_hash, status, issued_at, expires_at, created_at, device_hash)
            VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
            """.trimIndent()
        ).use {
            it.setObject(1, UUID.randomUUID())
            it.setObject(2, accountId)
            it.setBytes(3, crypto.accessTokenHash(token))
            it.setInstant(4, issuedAt)
            it.setInstant(5, expiresAt)
            it.setInstant(6, issuedAt)
            it.setBytes(7, deviceHash)
            it.executeUpdate()
        }
    }

    /**
     * Само-залечивание legacy-токенов (до V007): после успешного validate —
     * когда device_id уже сверен с redeemed_device_hash лицензии — токен
     * привязывается к устройству. Привязка одноразовая (device_hash IS NULL):
     * перебиндить украденный токен на другое устройство нельзя.
     */
    fun bindTokenDevice(token: String, deviceId: String): Boolean {
        val tokenHash = runCatching { crypto.accessTokenHash(token) }.getOrElse { return false }
        val deviceHash = runCatching { crypto.deviceHash(deviceId) }.getOrElse { return false }
        return transaction { connection ->
            connection.prepareStatement(
                """
                UPDATE api_tokens SET device_hash = ?
                WHERE token_hash = ? AND status = 'ACTIVE' AND device_hash IS NULL
                """.trimIndent()
            ).use {
                it.setBytes(1, deviceHash)
                it.setBytes(2, tokenHash)
                it.executeUpdate() > 0
            }
        }
    }

    private fun markExpired(connection: Connection, licenseId: UUID, now: Instant) {
        connection.prepareStatement(
            "UPDATE licenses SET status = 'EXPIRED', updated_at = ?, version = version + 1 WHERE id = ? AND status IN ('ACTIVE', 'ISSUED')"
        ).use {
            it.setInstant(1, now)
            it.setObject(2, licenseId)
            it.executeUpdate()
        }
    }

    private fun audit(
        connection: Connection,
        actorType: String,
        actorId: String?,
        action: String,
        entityType: String,
        entityId: UUID?,
        requestId: String?,
        remoteAddress: String?,
        metadataJson: String,
        now: Instant
    ) {
        connection.prepareStatement(
            """
            INSERT INTO license_audit_log(
                id, occurred_at, actor_type, actor_id, action, entity_type,
                entity_id, request_id, remote_address, metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            """.trimIndent()
        ).use {
            it.setObject(1, UUID.randomUUID())
            it.setInstant(2, now)
            it.setString(3, actorType)
            it.setString(4, actorId)
            it.setString(5, action)
            it.setString(6, entityType)
            it.setObject(7, entityId)
            it.setString(8, requestId)
            it.setString(9, remoteAddress?.take(64))
            it.setString(10, metadataJson)
            it.executeUpdate()
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }

    private fun ResultSet.toPlan() = BillingPlan(
        id = getString("id"),
        productId = getString("product_id"),
        displayName = getString("display_name"),
        durationDays = getInt("duration_days"),
        amountMinor = getLong("amount_minor"),
        currency = getString("currency"),
        paddlePriceId = getString("paddle_price_id"),
        heleketCurrency = getString("heleket_currency"),
        active = getBoolean("active")
    )

    private data class RedemptionRow(
        val id: UUID,
        val status: LicenseStatus,
        val billingStatus: LicenseBillingStatus,
        val startsAt: Instant?,
        val expiresAt: Instant?,
        val accountId: UUID?,
        val redeemedAt: Instant?,
        val planId: String,
        val productId: String,
        val durationDays: Int,
        val planActive: Boolean,
        val accountStatus: String?
    ) {
        companion object {
            fun from(result: ResultSet) = RedemptionRow(
                id = result.getObject("id", UUID::class.java),
                status = LicenseStatus.valueOf(result.getString("status")),
                billingStatus = LicenseBillingStatus.valueOf(result.getString("billing_status")),
                startsAt = result.getInstant("starts_at"),
                expiresAt = result.getInstant("expires_at"),
                accountId = result.getObject("account_id", UUID::class.java),
                redeemedAt = result.getInstant("redeemed_at"),
                planId = result.getString("plan_id"),
                productId = result.getString("product_id"),
                durationDays = result.getInt("duration_days"),
                planActive = result.getBoolean("plan_active"),
                accountStatus = result.getString("account_status")
            )
        }
    }

    private data class ValidationRow(
        val id: UUID,
        val status: LicenseStatus,
        val billingStatus: LicenseBillingStatus,
        val startsAt: Instant?,
        val expiresAt: Instant?,
        val deviceHash: ByteArray?,
        val planId: String,
        val productId: String,
        val planActive: Boolean
    ) {
        companion object {
            fun from(result: ResultSet) = ValidationRow(
                id = result.getObject("id", UUID::class.java),
                status = LicenseStatus.valueOf(result.getString("status")),
                billingStatus = LicenseBillingStatus.valueOf(result.getString("billing_status")),
                startsAt = result.getInstant("starts_at"),
                expiresAt = result.getInstant("expires_at"),
                deviceHash = result.getBytes("redeemed_device_hash"),
                planId = result.getString("plan_id"),
                productId = result.getString("product_id"),
                planActive = result.getBoolean("plan_active")
            )
        }
    }

    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
