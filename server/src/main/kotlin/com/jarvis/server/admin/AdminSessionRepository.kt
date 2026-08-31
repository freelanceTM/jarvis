package com.jarvis.server.admin

import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/** Активная сессия оператора (таблица admin_sessions). */
data class AdminSession(
    val id: UUID,
    val accountId: UUID,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val remoteAddress: String?
)

/**
 * Хранилище admin-сессий. В БД лежит только SHA-256 токена; сырой токен
 * выдаётся один раз при [AdminAuthService.login] и живёт у клиента.
 *
 * Токены сравниваются по хешу — утечка дампа БД не даёт действующих сессий.
 */
class AdminSessionRepository(private val dataSource: DataSource) {

    private val random = SecureRandom()

    /** Создаёт сессию и возвращает сырой bearer-токен (не хранится). */
    fun create(accountId: UUID, ttl: java.time.Duration, remoteAddress: String?, now: Instant): String {
        val raw = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(random::nextBytes))
        val tokenHash = AdminPasswords.sha256Hex(raw)
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO admin_sessions (id, account_id, token_hash, created_at, expires_at, last_seen_at, remote_address) " +
                    "VALUES (?, ?, decode(?, 'hex'), ?, ?, ?, ?)"
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, accountId)
                ps.setString(3, tokenHash)
                ps.setTimestamp(4, Timestamp.from(now))
                ps.setTimestamp(5, Timestamp.from(now.plus(ttl)))
                ps.setTimestamp(6, Timestamp.from(now))
                ps.setString(7, remoteAddress?.take(64))
                ps.executeUpdate()
            }
        }
        return raw
    }

    /** Возвращает валидную (не истёкшую, не отозванную) сессию по сырому токену. */
    fun findValid(rawToken: String, now: Instant): AdminSession? = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT id, account_id, expires_at, revoked_at, remote_address FROM admin_sessions " +
                "WHERE token_hash = decode(?, 'hex') AND revoked_at IS NULL AND expires_at > ?"
        ).use { ps ->
            ps.setString(1, AdminPasswords.sha256Hex(rawToken))
            ps.setTimestamp(2, Timestamp.from(now))
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    AdminSession(
                        id = rs.getObject("id", UUID::class.java),
                        accountId = rs.getObject("account_id", UUID::class.java),
                        expiresAt = rs.getTimestamp("expires_at").toInstant(),
                        revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
                        remoteAddress = rs.getString("remote_address")
                    )
                }
            }
        }
    }

    /** Sliding renewal: продлевает сессию, если она прожила больше половины TTL. */
    fun renewIfDue(sessionId: UUID, ttl: java.time.Duration, now: Instant) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                "UPDATE admin_sessions SET expires_at = ?, last_seen_at = ? " +
                    "WHERE id = ? AND revoked_at IS NULL AND expires_at > ? " +
                    "AND ?::timestamptz > expires_at - (? * interval '1 millisecond')"
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(now.plus(ttl)))
                ps.setTimestamp(2, Timestamp.from(now))
                ps.setObject(3, sessionId)
                ps.setTimestamp(4, Timestamp.from(now))
                ps.setTimestamp(5, Timestamp.from(now))
                ps.setLong(6, ttl.toMillis() / 2)
                ps.executeUpdate()
            }
        }
    }

    fun revoke(sessionId: UUID, now: Instant) {
        dataSource.connection.use { c ->
            c.prepareStatement("UPDATE admin_sessions SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL").use { ps ->
                ps.setTimestamp(1, Timestamp.from(now))
                ps.setObject(2, sessionId)
                ps.executeUpdate()
            }
        }
    }

    fun revokeAllForAccount(accountId: UUID, now: Instant) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                "UPDATE admin_sessions SET revoked_at = ? WHERE account_id = ? AND revoked_at IS NULL"
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(now))
                ps.setObject(2, accountId)
                ps.executeUpdate()
            }
        }
    }

    /** Удаление истёкших/отозванных сессий старше горизонта (housekeeping). */
    fun purge(olderThan: java.time.Duration, now: Instant): Int {
        dataSource.connection.use { c ->
            c.prepareStatement("DELETE FROM admin_sessions WHERE expires_at < ? OR revoked_at < ?").use { ps ->
                val cutoff = Timestamp.from(now.minus(olderThan))
                ps.setTimestamp(1, cutoff)
                ps.setTimestamp(2, cutoff)
                return ps.executeUpdate()
            }
        }
    }
}
