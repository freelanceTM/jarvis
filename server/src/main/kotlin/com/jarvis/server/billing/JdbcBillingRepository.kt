package com.jarvis.server.billing

import com.jarvis.server.license.BillingPlan
import java.sql.Connection
import java.sql.ResultSet
import com.jarvis.server.persistence.getInstant
import com.jarvis.server.persistence.setInstant
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

class JdbcBillingRepository(private val dataSource: DataSource) {
    companion object {
        const val DEFAULT_STALE_SCAN_LIMIT: Int = 50
    }

    fun createOrGetOrder(
        accountId: UUID,
        plan: BillingPlan,
        provider: BillingProviderId,
        idempotencyKey: String,
        now: Instant
    ): BillingOrder? = transaction { connection ->
        // Serializes distinct idempotency keys for the same product/provider.
        // Without this lock two concurrent requests could both reach an external
        // payment API before the partial unique index becomes visible.
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
            it.setString(1, "checkout:$accountId:${plan.id}:${provider.name}")
            it.execute()
        }
        findOrderByIdempotency(connection, accountId, idempotencyKey)?.let { return@transaction it }
        findOpenOrder(connection, accountId, plan.id, provider)?.let { return@transaction it }
        val licenseId = activeLicenseId(connection, accountId, now) ?: return@transaction null
        val id = UUID.randomUUID()
        val inserted = connection.prepareStatement(
            """
            INSERT INTO billing_orders(
                id, account_id, license_id, plan_id, provider, status,
                amount_minor, currency, idempotency_key, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, 'CREATED', ?, ?, ?, ?, ?)
            ON CONFLICT (account_id, idempotency_key) DO NOTHING
            """.trimIndent()
        ).use {
            it.setObject(1, id)
            it.setObject(2, accountId)
            it.setObject(3, licenseId)
            it.setString(4, plan.id)
            it.setString(5, provider.name)
            it.setLong(6, plan.amountMinor)
            it.setString(7, plan.currency)
            it.setString(8, idempotencyKey)
            it.setInstant(9, now)
            it.setInstant(10, now)
            it.executeUpdate() == 1
        }
        if (inserted) findOrderById(connection, id)
        else findOrderByIdempotency(connection, accountId, idempotencyKey)
    }

    fun claimCheckoutCreation(orderId: UUID, now: Instant): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "UPDATE billing_orders SET status = 'PROCESSING', updated_at = ? WHERE id = ? AND status = 'CREATED'"
        ).use {
            it.setInstant(1, now)
            it.setObject(2, orderId)
            it.executeUpdate() == 1
        }
    }

    fun markCheckoutCreated(
        orderId: UUID,
        providerOrderId: String,
        checkoutUrl: String,
        providerSubscriptionId: String?,
        now: Instant
    ): BillingOrder = transaction { connection ->
        val changed = connection.prepareStatement(
            """
            UPDATE billing_orders SET status = 'PENDING', provider_order_id = ?,
                provider_subscription_id = ?, checkout_url = ?, updated_at = ?
            WHERE id = ? AND status IN ('PROCESSING', 'PENDING')
            """.trimIndent()
        ).use {
            it.setString(1, providerOrderId)
            it.setString(2, providerSubscriptionId)
            it.setString(3, checkoutUrl.take(2048))
            it.setInstant(4, now)
            it.setObject(5, orderId)
            it.executeUpdate()
        }
        check(changed == 1) { "Billing order cannot transition to PENDING" }
        checkNotNull(findOrderById(connection, orderId))
    }

    fun markCheckoutFailed(orderId: UUID, now: Instant) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE billing_orders SET status = 'FAILED', updated_at = ? WHERE id = ? AND status IN ('CREATED', 'PROCESSING')"
            ).use {
                it.setInstant(1, now)
                it.setObject(2, orderId)
                it.executeUpdate()
            }
        }
    }

    fun markReconciliationRequired(orderId: UUID, now: Instant): BillingOrder? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE billing_orders SET status = 'RECONCILIATION_REQUIRED', updated_at = ?
                WHERE id = ? AND status = 'PROCESSING'
                RETURNING *
                """.trimIndent()
            ).use {
                it.setInstant(1, now)
                it.setObject(2, orderId)
                it.executeQuery().use { result -> if (result.next()) result.toOrder() else null }
            }
        }

    fun applyEvent(event: VerifiedBillingEvent, now: Instant): BillingEventApplyResult = transaction { connection ->
        val eventId = UUID.randomUUID()
        val inserted = connection.prepareStatement(
            """
            INSERT INTO billing_events(
                id, provider, provider_event_id, event_type, payload_hash,
                signature_verified, processing_status, occurred_at, received_at
            ) VALUES (?, ?, ?, ?, ?, TRUE, 'RECEIVED', ?, ?)
            ON CONFLICT (provider, provider_event_id) DO NOTHING
            """.trimIndent()
        ).use {
            it.setObject(1, eventId)
            it.setString(2, event.provider.name)
            it.setString(3, event.providerEventId.take(192))
            it.setString(4, event.eventType.take(96))
            it.setBytes(5, event.payloadHash)
            it.setInstant(6, event.occurredAt)
            it.setInstant(7, now)
            it.executeUpdate() == 1
        }
        if (!inserted) return@transaction BillingEventApplyResult.DUPLICATE

        val order = findOrderForEvent(connection, event)
        if (order == null) {
            completeEvent(connection, eventId, "IGNORED", null, null, "UNKNOWN_ORDER", now)
            return@transaction BillingEventApplyResult.UNKNOWN_ORDER
        }
        connection.prepareStatement(
            "UPDATE billing_events SET order_id = ?, account_id = ? WHERE id = ?"
        ).use {
            it.setObject(1, order.id)
            it.setObject(2, order.accountId)
            it.setObject(3, eventId)
            it.executeUpdate()
        }
        if ((event.localOrderId != null && event.localOrderId != order.id) ||
            (event.providerOrderId != null && order.providerOrderId != null &&
                event.providerOrderId != order.providerOrderId) ||
            (event.providerSubscriptionId != null && order.providerSubscriptionId != null &&
                event.providerSubscriptionId != order.providerSubscriptionId) ||
            (event.expectedAmountMinor != null && event.expectedAmountMinor != order.amountMinor) ||
            (event.expectedCurrency != null && !event.expectedCurrency.equals(order.currency, ignoreCase = true))
        ) {
            completeEvent(
                connection, eventId, "FAILED", order.id, order.accountId,
                "ORDER_REFERENCE_AMOUNT_OR_CURRENCY_MISMATCH", now
            )
            return@transaction BillingEventApplyResult.INVALID_STATE
        }
        connection.prepareStatement(
            """
            UPDATE billing_orders SET
                provider_order_id = COALESCE(provider_order_id, ?),
                provider_subscription_id = COALESCE(provider_subscription_id, ?),
                updated_at = ?
            WHERE id = ?
            """.trimIndent()
        ).use {
            it.setString(1, event.providerOrderId)
            it.setString(2, event.providerSubscriptionId)
            it.setInstant(3, now)
            it.setObject(4, order.id)
            it.executeUpdate()
        }

        val result = when (event.kind) {
            BillingEventKind.PAID -> applyPaid(connection, order, event, now)
            BillingEventKind.PAYMENT_FAILED -> {
                if (order.status !in setOf(BillingOrderStatus.PAID, BillingOrderStatus.REFUNDED)) {
                    updateOrderStatus(connection, order.id, "FAILED", now)
                }
                BillingEventApplyResult.PROCESSED
            }
            BillingEventKind.CANCELED -> {
                if (order.status != BillingOrderStatus.PAID) updateOrderStatus(connection, order.id, "CANCELED", now)
                order.licenseId?.let { updateLicenseBilling(connection, it, "CANCELED", now) }
                BillingEventApplyResult.PROCESSED
            }
            BillingEventKind.REFUNDED -> applyRefund(connection, order, now)
            BillingEventKind.IGNORED -> BillingEventApplyResult.PROCESSED
        }
        completeEvent(
            connection, eventId,
            if (event.kind == BillingEventKind.IGNORED) "IGNORED" else "PROCESSED",
            order.id, order.accountId, null, now
        )
        result
    }

    fun findOrder(orderId: UUID): BillingOrder? = dataSource.connection.use { findOrderById(it, orderId) }

    /**
     * P1-3: заказы в RECONCILIATION_REQUIRED, не обновлявшиеся с [staleBefore].
     *
     * ТОЛЬКО чтение: воркер видимости (ReconciliationWorker) не меняет
     * состояние заказа — решение PAID/FAILED принимает человек или
     * доверенный webhook провайдера. Уникальные индексы и advisory-lock
     * продолжают защищать целостность; воркер лишь делает backlog заметным
     * в метриках и логах (см. docs/RUNBOOK.md §5).
     */
    fun findStaleReconciliationOrders(
        staleBefore: Instant,
        limit: Int = DEFAULT_STALE_SCAN_LIMIT
    ): List<StaleReconciliationOrder> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT id, provider, provider_order_id, updated_at
            FROM billing_orders
            WHERE status = 'RECONCILIATION_REQUIRED' AND updated_at < ?
            ORDER BY updated_at ASC
            LIMIT ?
            """.trimIndent()
        ).use {
            it.setInstant(1, staleBefore)
            it.setInt(2, limit)
            it.executeQuery().use { result ->
                val orders = mutableListOf<StaleReconciliationOrder>()
                while (result.next()) {
                    // updated_at объявлен NOT NULL в схеме; null здесь возможен
                    // только при рассинхронизации схемы — такой заказ пропускаем,
                    // а не роняем воркер видимости.
                    val updatedAt = result.getInstant("updated_at") ?: continue
                    orders += StaleReconciliationOrder(
                        orderId = result.getObject("id", UUID::class.java),
                        provider = BillingProviderId.valueOf(result.getString("provider")),
                        providerOrderId = result.getString("provider_order_id"),
                        updatedAt = updatedAt
                    )
                }
                orders
            }
        }
    }

    private fun applyPaid(
        connection: Connection,
        order: BillingOrder,
        event: VerifiedBillingEvent,
        now: Instant
    ): BillingEventApplyResult {
        // Different Paddle event types may describe the same transaction. The
        // order transition, not only event ID, is the fulfillment idempotency guard.
        if (order.status == BillingOrderStatus.PAID) return BillingEventApplyResult.PROCESSED
        if (order.status == BillingOrderStatus.REFUNDED) return BillingEventApplyResult.INVALID_STATE
        val licenseId = order.licenseId ?: return BillingEventApplyResult.INVALID_STATE

        val planDuration = connection.prepareStatement(
            "SELECT duration_days FROM billing_plans WHERE id = ? AND active = TRUE"
        ).use {
            it.setString(1, order.planId)
            it.executeQuery().use { result -> if (result.next()) result.getInt(1) else null }
        } ?: return BillingEventApplyResult.INVALID_STATE

        val license = connection.prepareStatement(
            "SELECT status, expires_at FROM licenses WHERE id = ? AND account_id = ? FOR UPDATE"
        ).use {
            it.setObject(1, licenseId)
            it.setObject(2, order.accountId)
            it.executeQuery().use { result ->
                if (!result.next()) null else result.getString("status") to result.getInstant("expires_at")
            }
        } ?: return BillingEventApplyResult.INVALID_STATE
        if (license.first in setOf("REVOKED", "DISABLED")) return BillingEventApplyResult.INVALID_STATE

        val base = license.second?.takeIf { it.isAfter(now) } ?: now
        val newExpiry = base.plus(planDuration.toLong(), ChronoUnit.DAYS)
        connection.prepareStatement(
            """
            UPDATE licenses SET status = 'ACTIVE', billing_status = 'PAID', expires_at = ?,
                updated_at = ?, version = version + 1 WHERE id = ?
            """.trimIndent()
        ).use {
            it.setInstant(1, newExpiry)
            it.setInstant(2, now)
            it.setObject(3, licenseId)
            it.executeUpdate()
        }
        connection.prepareStatement(
            """
            UPDATE billing_orders SET status = 'PAID', paid_at = ?, updated_at = ?,
                provider_subscription_id = COALESCE(?, provider_subscription_id)
            WHERE id = ?
            """.trimIndent()
        ).use {
            it.setInstant(1, now)
            it.setInstant(2, now)
            it.setString(3, event.providerSubscriptionId)
            it.setObject(4, order.id)
            it.executeUpdate()
        }
        return BillingEventApplyResult.PROCESSED
    }

    private fun applyRefund(connection: Connection, order: BillingOrder, now: Instant): BillingEventApplyResult {
        updateOrderStatus(connection, order.id, "REFUNDED", now)
        val licenseId = order.licenseId ?: return BillingEventApplyResult.INVALID_STATE
        connection.prepareStatement(
            """
            UPDATE licenses SET status = 'REVOKED', billing_status = 'REFUNDED',
                revoked_at = ?, revoked_reason = 'billing_refund', updated_at = ?, version = version + 1
            WHERE id = ? AND account_id = ?
            """.trimIndent()
        ).use {
            it.setInstant(1, now)
            it.setInstant(2, now)
            it.setObject(3, licenseId)
            it.setObject(4, order.accountId)
            it.executeUpdate()
        }
        connection.prepareStatement(
            "UPDATE api_tokens SET status = 'REVOKED', revoked_at = ? WHERE account_id = ? AND status = 'ACTIVE'"
        ).use {
            it.setInstant(1, now)
            it.setObject(2, order.accountId)
            it.executeUpdate()
        }
        return BillingEventApplyResult.PROCESSED
    }

    private fun updateLicenseBilling(connection: Connection, licenseId: UUID, state: String, now: Instant) {
        connection.prepareStatement(
            "UPDATE licenses SET billing_status = ?, updated_at = ?, version = version + 1 WHERE id = ? AND status = 'ACTIVE'"
        ).use {
            it.setString(1, state)
            it.setInstant(2, now)
            it.setObject(3, licenseId)
            it.executeUpdate()
        }
    }

    private fun updateOrderStatus(connection: Connection, id: UUID, status: String, now: Instant) {
        connection.prepareStatement("UPDATE billing_orders SET status = ?, updated_at = ? WHERE id = ?").use {
            it.setString(1, status)
            it.setInstant(2, now)
            it.setObject(3, id)
            it.executeUpdate()
        }
    }

    private fun completeEvent(
        connection: Connection,
        eventId: UUID,
        status: String,
        orderId: UUID?,
        accountId: UUID?,
        failureCode: String?,
        now: Instant
    ) {
        connection.prepareStatement(
            """
            UPDATE billing_events SET processing_status = ?, order_id = COALESCE(?, order_id),
                account_id = COALESCE(?, account_id), failure_code = ?, processed_at = ?
            WHERE id = ?
            """.trimIndent()
        ).use {
            it.setString(1, status)
            it.setObject(2, orderId)
            it.setObject(3, accountId)
            it.setString(4, failureCode)
            it.setInstant(5, now)
            it.setObject(6, eventId)
            it.executeUpdate()
        }
    }

    private fun findOrderForEvent(
        connection: Connection,
        event: VerifiedBillingEvent
    ): BillingOrder? {
        val (column, value) = when {
            event.localOrderId != null -> "id" to event.localOrderId
            event.providerOrderId != null -> "provider_order_id" to event.providerOrderId
            event.providerSubscriptionId != null ->
                "provider_subscription_id" to event.providerSubscriptionId
            else -> return null
        }
        // Column comes exclusively from the closed constant set above.
        return connection.prepareStatement(
            "SELECT * FROM billing_orders WHERE provider = ? AND $column = ? " +
                "ORDER BY created_at DESC LIMIT 1 FOR UPDATE"
        ).use {
            it.setString(1, event.provider.name)
            it.setObject(2, value)
            it.executeQuery().use { result -> if (result.next()) result.toOrder() else null }
        }
    }

    private fun activeLicenseId(connection: Connection, accountId: UUID, now: Instant): UUID? =
        connection.prepareStatement(
            """
            SELECT id FROM licenses
            WHERE account_id = ? AND status IN ('ACTIVE', 'EXPIRED') AND starts_at <= ?
              AND billing_status IN ('GRANTED', 'PAID', 'CANCELED', 'PAST_DUE')
            ORDER BY expires_at DESC NULLS LAST LIMIT 1 FOR UPDATE
            """.trimIndent()
        ).use {
            it.setObject(1, accountId)
            it.setInstant(2, now)
            it.executeQuery().use { result -> if (result.next()) result.getObject(1, UUID::class.java) else null }
        }

    private fun findOrderById(connection: Connection, id: UUID): BillingOrder? =
        connection.prepareStatement("SELECT * FROM billing_orders WHERE id = ?").use {
            it.setObject(1, id)
            it.executeQuery().use { result -> if (result.next()) result.toOrder() else null }
        }

    private fun findOrderByIdempotency(
        connection: Connection,
        accountId: UUID,
        idempotencyKey: String
    ): BillingOrder? = connection.prepareStatement(
        "SELECT * FROM billing_orders WHERE account_id = ? AND idempotency_key = ?"
    ).use {
        it.setObject(1, accountId)
        it.setString(2, idempotencyKey)
        it.executeQuery().use { result -> if (result.next()) result.toOrder() else null }
    }

    private fun findOpenOrder(
        connection: Connection,
        accountId: UUID,
        planId: String,
        provider: BillingProviderId
    ): BillingOrder? = connection.prepareStatement(
        """
        SELECT * FROM billing_orders
        WHERE account_id = ? AND plan_id = ? AND provider = ?
          AND status IN ('CREATED', 'PROCESSING', 'RECONCILIATION_REQUIRED', 'PENDING')
        ORDER BY created_at DESC LIMIT 1
        """.trimIndent()
    ).use {
        it.setObject(1, accountId)
        it.setString(2, planId)
        it.setString(3, provider.name)
        it.executeQuery().use { result -> if (result.next()) result.toOrder() else null }
    }

    private fun ResultSet.toOrder() = BillingOrder(
        id = getObject("id", UUID::class.java),
        accountId = getObject("account_id", UUID::class.java),
        licenseId = getObject("license_id", UUID::class.java),
        planId = getString("plan_id"),
        provider = BillingProviderId.valueOf(getString("provider")),
        status = BillingOrderStatus.valueOf(getString("status")),
        amountMinor = getLong("amount_minor"),
        currency = getString("currency"),
        idempotencyKey = getString("idempotency_key"),
        providerOrderId = getString("provider_order_id"),
        providerSubscriptionId = getString("provider_subscription_id"),
        checkoutUrl = getString("checkout_url"),
        paidAt = getInstant("paid_at")
    )

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val value = block(connection)
            connection.commit()
            value
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }
}
