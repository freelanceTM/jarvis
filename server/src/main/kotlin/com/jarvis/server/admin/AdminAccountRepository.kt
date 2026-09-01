package com.jarvis.server.admin

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Строка admin-аккаунта (таблица admin_accounts, миграция V006). */
data class AdminAccount(
    val id: UUID,
    val username: String,
    val role: AdminRole,
    val status: String,
    val createdAt: Instant
)

/**
 * JDBC-доступ к admin-аккаунтам. Отдельный маппинг «пользователи системы»
 * (accounts) и «операторы Control Plane» (admin_accounts) сознательно:
 * это разные сущности с разными угрозами (лицензиат vs оператор).
 */
class AdminAccountRepository(private val dataSource: DataSource) {

    fun count(): Long = dataSource.connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT count(*) FROM admin_accounts").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    fun findByUsername(username: String): AdminAccount? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT id, username, role, status, created_at FROM admin_accounts WHERE username = ?")
            .use { ps ->
                ps.setString(1, username)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toAccount() else null }
            }
    }

    fun findById(id: UUID): AdminAccount? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT id, username, role, status, created_at FROM admin_accounts WHERE id = ?")
            .use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toAccount() else null }
            }
    }

    fun list(limit: Int, offset: Int): List<AdminAccount> = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT id, username, role, status, created_at FROM admin_accounts ORDER BY created_at DESC LIMIT ? OFFSET ?"
        ).use { ps ->
            ps.setInt(1, limit)
            ps.setInt(2, offset)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<AdminAccount>()
                while (rs.next()) out += rs.toAccount()
                out
            }
        }
    }

    fun create(username: String, passwordHash: String, role: AdminRole, now: Instant): AdminAccount {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO admin_accounts (id, username, password_hash, role, status, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)"
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, username)
                ps.setString(3, passwordHash)
                ps.setString(4, role.name)
                ps.setTimestamp(5, Timestamp.from(now))
                ps.setTimestamp(6, Timestamp.from(now))
                ps.executeUpdate()
            }
        }
        return AdminAccount(id, username, role, "ACTIVE", now)
    }

    fun setPasswordHash(accountId: UUID, passwordHash: String, now: Instant) {
        dataSource.connection.use { c ->
            c.prepareStatement("UPDATE admin_accounts SET password_hash = ?, updated_at = ? WHERE id = ?").use { ps ->
                ps.setString(1, passwordHash)
                ps.setTimestamp(2, Timestamp.from(now))
                ps.setObject(3, accountId)
                ps.executeUpdate()
            }
        }
    }

    fun setStatus(accountId: UUID, status: String, now: Instant) {
        dataSource.connection.use { c ->
            c.prepareStatement("UPDATE admin_accounts SET status = ?, updated_at = ? WHERE id = ?").use { ps ->
                ps.setString(1, status)
                ps.setTimestamp(2, Timestamp.from(now))
                ps.setObject(3, accountId)
                ps.executeUpdate()
            }
        }
    }

    fun passwordHashOf(accountId: UUID): String? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT password_hash FROM admin_accounts WHERE id = ? AND status = 'ACTIVE'").use { ps ->
            ps.setObject(1, accountId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    private fun java.sql.ResultSet.toAccount() = AdminAccount(
        id = getObject("id", UUID::class.java),
        username = getString("username"),
        role = AdminRole.valueOf(getString("role")),
        status = getString("status"),
        createdAt = getTimestamp("created_at").toInstant()
    )
}
