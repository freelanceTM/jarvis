package com.jarvis.server.admin

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Событие аудита (таблица admin_audit_log). */
data class AdminAuditEvent(
    val id: UUID,
    val occurredAt: Instant,
    val actor: String,
    val action: String,
    val entityType: String,
    val entityId: String?,
    val oldValue: String,
    val newValue: String,
    val remoteAddress: String?,
    val sessionId: UUID?,
    val requestId: String?
)

/** Фильтр чтения аудита. */
data class AdminAuditQuery(
    val action: String? = null,
    val actor: String? = null,
    val entityType: String? = null,
    val limit: Int = 50,
    val offset: Long = 0
)

/**
 * APPEND-ONLY аудит административных изменений (Control Plane ТЗ §19).
 *
 * Класс намеренно НЕ предоставляет UPDATE/DELETE — immutable by design.
 * Записи создаются одним методом [append]; чтение — [find] (линейно по времени).
 * Редактирование через UI/API невозможно.
 */
class AdminAuditLog(private val dataSource: DataSource) {

    fun append(
        actor: String,
        action: String,
        entityType: String,
        entityId: String?,
        oldValue: String,
        newValue: String,
        remoteAddress: String?,
        sessionId: UUID?,
        requestId: String?,
        now: Instant = Instant.now()
    ) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO admin_audit_log " +
                    "(id, occurred_at, actor, action, entity_type, entity_id, old_value, new_value, remote_address, session_id, request_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?::text, ?::jsonb, ?::jsonb, ?, ?, ?)"
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setTimestamp(2, Timestamp.from(now))
                ps.setString(3, actor.take(128))
                ps.setString(4, action.take(64))
                ps.setString(5, entityType.take(32))
                ps.setString(6, entityId?.take(128))
                ps.setString(7, sanitizeJson(oldValue))
                ps.setString(8, sanitizeJson(newValue))
                ps.setString(9, remoteAddress?.take(64))
                ps.setObject(10, sessionId)
                ps.setString(11, requestId?.take(64))
                ps.executeUpdate()
            }
        }
    }

    fun find(query: AdminAuditQuery): List<AdminAuditEvent> {
        val where = mutableListOf("1=1")
        val params = mutableListOf<String>()
        query.action?.let { where += "action = ?"; params += it }
        query.actor?.let { where += "actor = ?"; params += it }
        query.entityType?.let { where += "entity_type = ?"; params += it }
        val sql = "SELECT id, occurred_at, actor, action, entity_type, entity_id, " +
            "old_value::text AS old_value, new_value::text AS new_value, remote_address, session_id, request_id " +
            "FROM admin_audit_log WHERE ${where.joinToString(" AND ")} " +
            "ORDER BY occurred_at DESC LIMIT ${query.limit.coerceIn(1, 200)} OFFSET ${query.offset.coerceAtLeast(0)}"
        return dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                params.forEachIndexed { i, value -> ps.setString(i + 1, value) }
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<AdminAuditEvent>()
                    while (rs.next()) {
                        out += AdminAuditEvent(
                            id = rs.getObject("id", UUID::class.java),
                            occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                            actor = rs.getString("actor"),
                            action = rs.getString("action"),
                            entityType = rs.getString("entity_type"),
                            entityId = rs.getString("entity_id"),
                            oldValue = rs.getString("old_value") ?: "{}",
                            newValue = rs.getString("new_value") ?: "{}",
                            remoteAddress = rs.getString("remote_address"),
                            sessionId = rs.getObject("session_id", UUID::class.java),
                            requestId = rs.getString("request_id")
                        )
                    }
                    out
                }
            }
        }
    }

    /** Минимальная защита от битых JSON в old/new value. */
    private fun sanitizeJson(raw: String): String =
        if (raw.isBlank()) "{}" else raw
}
