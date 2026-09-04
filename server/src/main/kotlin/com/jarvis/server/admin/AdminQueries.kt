package com.jarvis.server.admin

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * READ MODELS Control Plane поверх СУЩЕСТВУЮЩИХ таблиц:
 *
 * - «Пользователь»  = accounts (+ licenses, api_tokens, billing_orders);
 * - «Устройство»    = api_tokens + лицензионный device binding
 *   (licenses.redeemed_device_hash) — отдельной таблицы devices в системе
 *   нет и создавать параллельную запрещено ТЗ §29;
 * - «Usage»         = ai_usage_records (cloud). Локальные execution-ы
 *   выполняются на устройстве и сервером не наблюдаются — честно
 *   помечается NOT COLLECTED (ТЗ §15/§31).
 *
 * Все запросы возвращают ТОЛЬКО операционные метаданные — приватного
 * контента (текстов запросов/ответов) в этих таблицах нет по дизайну.
 */
class AdminQueries(private val dataSource: DataSource) {

    data class DashboardSnapshot(
        val accountsTotal: Long,
        val licensesActive: Long,
        val licensesIssued: Long,
        val tokensActive: Long,
        val requestsToday: Long,
        val errorsToday: Long,
        val tokensToday: Long,
        val ordersPending: Long
    )

    fun dashboard(now: Instant = Instant.now()): DashboardSnapshot = dataSource.connection.use { c ->
        fun scalar(sql: String, bind: (java.sql.PreparedStatement) -> Unit = {}): Long =
            c.prepareStatement(sql).use { ps ->
                bind(ps)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        val midnight = Timestamp.from(now.minusSeconds(now.epochSecond % 86_400))
        DashboardSnapshot(
            accountsTotal = scalar("SELECT count(*) FROM accounts"),
            licensesActive = scalar("SELECT count(*) FROM licenses WHERE status = 'ACTIVE'"),
            licensesIssued = scalar("SELECT count(*) FROM licenses"),
            tokensActive = scalar("SELECT count(*) FROM api_tokens WHERE status = 'ACTIVE'"),
            requestsToday = scalar("SELECT count(*) FROM ai_usage_records WHERE occurred_at >= ?") {
                it.setTimestamp(1, midnight)
            },
            errorsToday = scalar("SELECT count(*) FROM ai_usage_records WHERE occurred_at >= ? AND NOT success") {
                it.setTimestamp(1, midnight)
            },
            tokensToday = scalar("SELECT coalesce(sum(total_tokens), 0) FROM ai_usage_records WHERE occurred_at >= ?") {
                it.setTimestamp(1, midnight)
            },
            ordersPending = scalar(
                "SELECT count(*) FROM billing_orders WHERE status IN ('CREATED','PROCESSING','RECONCILIATION_REQUIRED','PENDING')"
            )
        )
    }

    /* ── Users ────────────────────────────────────────────────────────────── */

    data class UserRow(
        val accountId: UUID,
        val externalRef: String?,
        val status: String,
        val createdAt: Instant,
        val licenses: Long,
        val activeLicenses: Long,
        val lastActiveAt: Instant?
    )

    fun users(query: String?, limit: Int, offset: Long): List<UserRow> {
        val sql = buildString {
            append(
                "SELECT a.id, a.external_ref, a.status, a.created_at, " +
                    "(SELECT count(*) FROM licenses l WHERE l.account_id = a.id) AS licenses_total, " +
                    "(SELECT count(*) FROM licenses l WHERE l.account_id = a.id AND l.status = 'ACTIVE') AS licenses_active, " +
                    "(SELECT max(x.occurred_at) FROM ai_usage_records x WHERE x.client_id = a.id::text) AS last_usage_at " +
                    "FROM accounts a "
            )
            if (!query.isNullOrBlank()) append("WHERE a.external_ref ILIKE ? ")
            append("ORDER BY a.created_at DESC LIMIT ? OFFSET ?")
        }
        return dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                var idx = 1
                if (!query.isNullOrBlank()) ps.setString(idx++, "%${query.take(64)}%")
                ps.setInt(idx++, limit.coerceIn(1, 200))
                ps.setLong(idx, offset.coerceAtLeast(0))
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<UserRow>()
                    while (rs.next()) out += rs.toUserRow()
                    out
                }
            }
        }
    }

    data class UserDetail(
        val account: UserRow,
        val licenses: List<LicenseRow>,
        val devices: List<DeviceRow>,
        val usageSummary: UsageSummary
    )

    fun userDetail(accountId: UUID): UserDetail? {
        val account = dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT a.id, a.external_ref, a.status, a.created_at, " +
                    "(SELECT count(*) FROM licenses l WHERE l.account_id = a.id) AS licenses_total, " +
                    "(SELECT count(*) FROM licenses l WHERE l.account_id = a.id AND l.status = 'ACTIVE') AS licenses_active, " +
                    "(SELECT max(x.occurred_at) FROM ai_usage_records x WHERE x.client_id = a.id::text) AS last_usage_at " +
                    "FROM accounts a WHERE a.id = ?"
            ).use { ps ->
                ps.setObject(1, accountId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toUserRow() else null }
            }
        } ?: return null
        return UserDetail(
            account = account,
            licenses = licensesByAccount(accountId),
            devices = devicesByAccount(accountId),
            usageSummary = usageSummary(clientId = accountId.toString(), days = 30)
        )
    }

    /* ── Devices (= api_tokens + license device binding) ──────────────────── */

    data class DeviceRow(
        val tokenId: UUID,
        val accountId: UUID,
        val status: String,
        val issuedAt: Instant,
        val lastUsedAt: Instant?,
        val expiresAt: Instant?,
        /** Префикс device-hash лицензии (маска, не полный хеш). */
        val deviceBinding: String?
    )

    fun devices(limit: Int, offset: Long): List<DeviceRow> = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT t.id, t.account_id, t.status, t.issued_at, t.last_used_at, t.expires_at, " +
                "l.redeemed_device_hash IS NOT NULL AS bound " +
                "FROM api_tokens t LEFT JOIN licenses l ON l.account_id = t.account_id " +
                "ORDER BY t.issued_at DESC LIMIT ? OFFSET ?"
        ).use { ps ->
            ps.setInt(1, limit.coerceIn(1, 200))
            ps.setLong(2, offset.coerceAtLeast(0))
            ps.executeQuery().use { rs ->
                val out = mutableListOf<DeviceRow>()
                while (rs.next()) {
                    out += DeviceRow(
                        tokenId = rs.getObject("id", UUID::class.java),
                        accountId = rs.getObject("account_id", UUID::class.java),
                        status = rs.getString("status"),
                        issuedAt = rs.getTimestamp("issued_at").toInstant(),
                        lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant(),
                        expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                        deviceBinding = if (rs.getBoolean("bound")) "BOUND" else null
                    )
                }
                out
            }
        }
    }

    fun device(tokenId: UUID): DeviceRow? = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT t.id, t.account_id, t.status, t.issued_at, t.last_used_at, t.expires_at, " +
                "l.redeemed_device_hash IS NOT NULL AS bound " +
                "FROM api_tokens t LEFT JOIN licenses l ON l.account_id = t.account_id WHERE t.id = ?"
        ).use { ps ->
            ps.setObject(1, tokenId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    DeviceRow(
                        tokenId = rs.getObject("id", UUID::class.java),
                        accountId = rs.getObject("account_id", UUID::class.java),
                        status = rs.getString("status"),
                        issuedAt = rs.getTimestamp("issued_at").toInstant(),
                        lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant(),
                        expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                        deviceBinding = if (rs.getBoolean("bound")) "BOUND" else null
                    )
                }
            }
        }
    }

    fun devicesByAccount(accountId: UUID): List<DeviceRow> = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT t.id, t.account_id, t.status, t.issued_at, t.last_used_at, t.expires_at, " +
                "NULL::boolean AS bound FROM api_tokens t WHERE t.account_id = ? ORDER BY t.issued_at DESC"
        ).use { ps ->
            ps.setObject(1, accountId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<DeviceRow>()
                while (rs.next()) {
                    out += DeviceRow(
                        tokenId = rs.getObject("id", UUID::class.java),
                        accountId = rs.getObject("account_id", UUID::class.java),
                        status = rs.getString("status"),
                        issuedAt = rs.getTimestamp("issued_at").toInstant(),
                        lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant(),
                        expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                        deviceBinding = null
                    )
                }
                out
            }
        }
    }

    /** Отзыв device-токена (idempotent). Возвращает true, если строка изменилась. */
    fun revokeDeviceToken(tokenId: UUID, now: Instant): Boolean = dataSource.connection.use { c ->
        c.prepareStatement("UPDATE api_tokens SET status = 'REVOKED', revoked_at = ? WHERE id = ? AND status = 'ACTIVE'")
            .use { ps ->
                ps.setTimestamp(1, Timestamp.from(now))
                ps.setObject(2, tokenId)
                ps.executeUpdate() > 0
            }
    }

    /* ── Licenses ─────────────────────────────────────────────────────────── */

    data class LicenseRow(
        val id: UUID,
        val status: String,
        val billingStatus: String,
        val codeHint: String,
        val planId: String,
        val accountId: UUID?,
        val issuedAt: Instant,
        val startsAt: Instant?,
        val expiresAt: Instant?,
        val redeemedAt: Instant?
    )

    fun licenses(limit: Int, offset: Long): List<LicenseRow> = licenses(limit, offset, null)

    /**
     * ADMIN (MVP-дерево Licenses: active/expired): список с опциональным
     * фильтром по статусу. null = все (прежнее поведение). Валидация значения —
     * на handler'е (400 на неизвестный статус), здесь статус попадает в SQL
     * только как bind-параметр.
     */
    fun licenses(limit: Int, offset: Long, status: com.jarvis.server.license.LicenseStatus?): List<LicenseRow> {
        if (status == null) {
            return queryLicenses("ORDER BY issued_at DESC LIMIT ? OFFSET ?") { ps ->
                ps.setInt(1, limit.coerceIn(1, 200))
                ps.setLong(2, offset.coerceAtLeast(0))
            }
        }
        return queryLicenses("WHERE status = ? ORDER BY issued_at DESC LIMIT ? OFFSET ?") { ps ->
            ps.setString(1, status.name)
            ps.setInt(2, limit.coerceIn(1, 200))
            ps.setLong(3, offset.coerceAtLeast(0))
        }
    }

    fun licensesByAccount(accountId: UUID): List<LicenseRow> =
        queryLicenses("WHERE account_id = ? ORDER BY issued_at DESC") { ps ->
            ps.setObject(1, accountId)
        }

    fun license(id: UUID): LicenseRow? =
        queryLicenses("WHERE id = ?") { ps -> ps.setObject(1, id) }.firstOrNull()

    private fun queryLicenses(suffix: String, bind: (java.sql.PreparedStatement) -> Unit): List<LicenseRow> {
        val sql = "SELECT id, status, billing_status, code_hint, plan_id, account_id, issued_at, starts_at, expires_at, redeemed_at " +
            "FROM licenses $suffix"
        return dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                bind(ps)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<LicenseRow>()
                    while (rs.next()) out += rs.toLicenseRow()
                    out
                }
            }
        }
    }

    /** Продление: expires_at += days от максимума(сейчас, текущий expires_at). */
    fun extendLicense(id: UUID, days: Int, now: Instant): Boolean = dataSource.connection.use { c ->
        c.prepareStatement(
            "UPDATE licenses SET expires_at = COALESCE(expires_at, now()) + make_interval(days => ?), updated_at = ?, " +
                "version = version + 1 WHERE id = ? AND status IN ('ACTIVE','ISSUED','DISABLED')"
        ).use { ps ->
            ps.setInt(1, days)
            ps.setTimestamp(2, Timestamp.from(now))
            ps.setObject(3, id)
            ps.executeUpdate() > 0
        }
    }

    fun suspendLicense(id: UUID, now: Instant): Boolean =
        updateLicenseStatus(id, from = "ACTIVE", to = "DISABLED", now = now)

    fun resumeLicense(id: UUID, now: Instant): Boolean =
        updateLicenseStatus(id, from = "DISABLED", to = "ACTIVE", now = now)

    fun changeLicensePlan(id: UUID, planId: String, now: Instant): Boolean = dataSource.connection.use { c ->
        c.prepareStatement(
            "UPDATE licenses SET plan_id = ?, updated_at = ?, version = version + 1 " +
                "WHERE id = ? AND status IN ('ISSUED','ACTIVE','DISABLED') AND EXISTS " +
                "(SELECT 1 FROM billing_plans p WHERE p.id = ? AND p.active)"
        ).use { ps ->
            ps.setString(1, planId.take(64))
            ps.setTimestamp(2, Timestamp.from(now))
            ps.setObject(3, id)
            ps.setString(4, planId.take(64))
            ps.executeUpdate() > 0
        }
    }

    private fun updateLicenseStatus(id: UUID, from: String, to: String, now: Instant): Boolean =
        dataSource.connection.use { c ->
            c.prepareStatement(
                "UPDATE licenses SET status = ?, updated_at = ?, version = version + 1 WHERE id = ? AND status = ?"
            ).use { ps ->
                ps.setString(1, to)
                ps.setTimestamp(2, Timestamp.from(now))
                ps.setObject(3, id)
                ps.setString(4, from)
                ps.executeUpdate() > 0
            }
        }

    /* ── Subscriptions (billing orders поверх существующей системы) ───────── */

    data class OrderRow(
        val id: UUID,
        val accountId: UUID,
        val planId: String,
        val provider: String,
        val status: String,
        val amountMinor: Long,
        val currency: String,
        val createdAt: Instant,
        val paidAt: Instant?
    )

    fun orders(limit: Int, offset: Long): List<OrderRow> = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT id, account_id, plan_id, provider, status, amount_minor, currency, created_at, paid_at " +
                "FROM billing_orders ORDER BY created_at DESC LIMIT ? OFFSET ?"
        ).use { ps ->
            ps.setInt(1, limit.coerceIn(1, 200))
            ps.setLong(2, offset.coerceAtLeast(0))
            ps.executeQuery().use { rs ->
                val out = mutableListOf<OrderRow>()
                while (rs.next()) {
                    out += OrderRow(
                        id = rs.getObject("id", UUID::class.java),
                        accountId = rs.getObject("account_id", UUID::class.java),
                        planId = rs.getString("plan_id"),
                        provider = rs.getString("provider"),
                        status = rs.getString("status"),
                        amountMinor = rs.getLong("amount_minor"),
                        currency = rs.getString("currency"),
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                        paidAt = rs.getTimestamp("paid_at")?.toInstant()
                    )
                }
                out
            }
        }
    }

    /* ── Usage / cost / logs ──────────────────────────────────────────────── */

    data class UsageSummary(
        val periodDays: Int,
        val requests: Long,
        val errors: Long,
        val inputTokens: Long,
        val outputTokens: Long,
        /** Локальные выполнения серверу не видны (выполняются на устройстве). */
        val localExecutions: Long? = null,
        val byProvider: List<ProviderTokenUsage>
    )

    fun usageSummary(clientId: String?, days: Int): UsageSummary {
        val period = days.coerceIn(1, 90)
        val since = Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(period.toLong())))
        val where = if (clientId.isNullOrBlank()) "occurred_at >= ?" else "occurred_at >= ? AND client_id = ?"
        return dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT provider, count(*) AS requests, " +
                    "count(*) FILTER (WHERE NOT success) AS errors, " +
                    "sum(input_tokens) AS input_tokens, sum(output_tokens) AS output_tokens " +
                    "FROM ai_usage_records WHERE $where GROUP BY provider"
            ).use { ps ->
                ps.setTimestamp(1, since)
                if (!clientId.isNullOrBlank()) ps.setString(2, clientId.take(128))
                ps.executeQuery().use { rs ->
                    val providers = mutableListOf<ProviderTokenUsage>()
                    var requests = 0L
                    var errors = 0L
                    while (rs.next()) {
                        val row = ProviderTokenUsage(
                            provider = rs.getString("provider") ?: "UNKNOWN",
                            requests = rs.getLong("requests"),
                            errors = rs.getLong("errors"),
                            inputTokens = rs.getLong("input_tokens").let { if (rs.wasNull()) null else it },
                            outputTokens = rs.getLong("output_tokens").let { if (rs.wasNull()) null else it }
                        )
                        providers += row
                        requests += row.requests
                        errors += row.errors
                    }
                    UsageSummary(
                        periodDays = period,
                        requests = requests,
                        errors = errors,
                        inputTokens = providers.sumOf { it.inputTokens ?: 0 },
                        outputTokens = providers.sumOf { it.outputTokens ?: 0 },
                        byProvider = providers
                    )
                }
            }
        }
    }

    data class LogRow(
        val occurredAt: Instant,
        val component: String,
        val type: String,
        val actor: String,
        val result: String,
        val latencyMs: Long?,
        /** OBSERVABILITY: сквозной request id (клиентский `omx_…` или серверный). */
        val requestId: String? = null
    )

    /**
     * Операционный журнал (ТЗ §18): union метаданных cloud-вызовов
     * (ai_usage_records), админ-действий (admin_audit_log) и лицензионных
     * событий (license_audit_log). Приватный контент не выбирается.
     */
    fun logs(component: String?, limit: Int, offset: Long): List<LogRow> {
        val componentFilter = component?.take(32)
        val rows = mutableListOf<LogRow>()
        fun collect(sql: String, bind: (java.sql.PreparedStatement) -> Unit) {
            dataSource.connection.use { c ->
                c.prepareStatement(sql).use { ps ->
                    bind(ps)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) rows += rs.toLogRow()
                    }
                }
            }
        }
        val wantAll = componentFilter == null || componentFilter == "ALL"
        if (wantAll || componentFilter == "CLOUD") {
            collect(
                "SELECT occurred_at, 'CLOUD' AS component, coalesce(provider, 'UNKNOWN') AS type, " +
                    "client_id AS actor, CASE WHEN success THEN 'SUCCESS' ELSE 'FAILURE: ' || coalesce(error_code, 'ERROR') END AS result, " +
                    "latency_ms, request_id FROM ai_usage_records ORDER BY occurred_at DESC LIMIT ? OFFSET ?"
            ) { ps ->
                ps.setInt(1, limit.coerceIn(1, 200))
                ps.setLong(2, offset.coerceAtLeast(0))
            }
        }
        if (wantAll || componentFilter == "ADMIN") {
            collect(
                "SELECT occurred_at, 'ADMIN' AS component, action AS type, actor, entity_type AS result, " +
                    "NULL::bigint AS latency_ms, request_id FROM admin_audit_log ORDER BY occurred_at DESC LIMIT ? OFFSET ?"
            ) { ps ->
                ps.setInt(1, limit.coerceIn(1, 200))
                ps.setLong(2, offset.coerceAtLeast(0))
            }
        }
        if (wantAll || componentFilter == "LICENSE") {
            collect(
                "SELECT occurred_at, 'LICENSE' AS component, action AS type, actor_type || ':' || coalesce(actor_id, '-') AS actor, " +
                    "entity_type AS result, NULL::bigint AS latency_ms, request_id FROM license_audit_log " +
                    "ORDER BY occurred_at DESC LIMIT ? OFFSET ?"
            ) { ps ->
                ps.setInt(1, limit.coerceIn(1, 200))
                ps.setLong(2, offset.coerceAtLeast(0))
            }
        }
        return rows.sortedByDescending { it.occurredAt }.take(limit.coerceIn(1, 200))
    }

    /* ── мапперы ──────────────────────────────────────────────────────────── */

    private fun ResultSet.toUserRow() = UserRow(
        accountId = getObject("id", UUID::class.java),
        externalRef = getString("external_ref"),
        status = getString("status"),
        createdAt = getTimestamp("created_at").toInstant(),
        licenses = getLong("licenses_total"),
        activeLicenses = getLong("licenses_active"),
        lastActiveAt = getTimestamp("last_usage_at")?.toInstant()
    )

    private fun ResultSet.toLicenseRow() = LicenseRow(
        id = getObject("id", UUID::class.java),
        status = getString("status"),
        billingStatus = getString("billing_status"),
        codeHint = getString("code_hint"),
        planId = getString("plan_id"),
        accountId = getObject("account_id", UUID::class.java)?.let { it as UUID },
        issuedAt = getTimestamp("issued_at").toInstant(),
        startsAt = getTimestamp("starts_at")?.toInstant(),
        expiresAt = getTimestamp("expires_at")?.toInstant(),
        redeemedAt = getTimestamp("redeemed_at")?.toInstant()
    )

    private fun ResultSet.toLogRow() = LogRow(
        occurredAt = getTimestamp("occurred_at").toInstant(),
        component = getString("component"),
        type = getString("type"),
        actor = getString("actor")?.take(64) ?: "-",
        result = getString("result") ?: "-",
        latencyMs = getLong("latency_ms").let { if (wasNull()) null else it },
        requestId = getString("request_id")
    )
}
