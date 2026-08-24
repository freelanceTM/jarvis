package com.jarvis.server.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

/** PostgreSQL connection settings. Secrets are supplied only through environment variables. */
data class DatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int = 8,
    val connectionTimeoutMs: Long = 5_000
) {
    init {
        require(jdbcUrl.startsWith("jdbc:postgresql://")) { "DATABASE_URL must be a PostgreSQL JDBC URL" }
        require(user.isNotBlank()) { "DATABASE_USER must not be blank" }
        require(password.isNotEmpty()) { "DATABASE_PASSWORD must not be empty" }
        require(maxPoolSize in 1..64) { "database maxPoolSize must be in 1..64" }
        require(connectionTimeoutMs in 250..60_000) { "database connection timeout out of range" }
    }
}

object DatabaseFactory {
    fun create(config: DatabaseConfig): HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.user
            password = config.password
            maximumPoolSize = config.maxPoolSize
            minimumIdle = 1
            connectionTimeout = config.connectionTimeoutMs
            validationTimeout = minOf(2_000, config.connectionTimeoutMs)
            isAutoCommit = true
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            poolName = "jarvis-postgres"
            addDataSourceProperty("ApplicationName", "jarvis-server")
            addDataSourceProperty("tcpKeepAlive", "true")
        }
    )
}

/**
 * Minimal deterministic migration runner.
 *
 * Migrations are immutable classpath resources with SHA-256 checksums. A
 * PostgreSQL advisory lock serializes startup across multiple instances.
 */
class DatabaseMigrator(private val dataSource: DataSource) {
    private data class Migration(val version: Int, val description: String, val resource: String)

    private val migrations = listOf(
        Migration(1, "license core", "/db/migration/V001__license_core.sql"),
        Migration(2, "billing orders and events", "/db/migration/V002__billing_orders_events.sql"),
        Migration(3, "persistent license rate limits", "/db/migration/V003__persistent_license_rate_limits.sql"),
        Migration(4, "billing reconciliation guard", "/db/migration/V004__billing_reconciliation_guard.sql"),
        Migration(5, "shared AI usage", "/db/migration/V005__shared_ai_usage.sql")
    )

    fun migrate() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SELECT pg_advisory_lock(733611042)")
            }
            try {
                ensureMigrationTable(connection)
                migrations.forEach { applyMigration(connection, it) }
            } finally {
                connection.createStatement().use { it.execute("SELECT pg_advisory_unlock(733611042)") }
            }
        }
    }

    fun appliedVersions(): List<Int> = dataSource.connection.use { connection ->
        ensureMigrationTable(connection)
        connection.prepareStatement("SELECT version FROM schema_migrations ORDER BY version").use { statement ->
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.getInt(1)) }
            }
        }
    }

    private fun ensureMigrationTable(connection: Connection) {
        connection.createStatement().use {
            it.execute(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version INTEGER PRIMARY KEY,
                    description VARCHAR(200) NOT NULL,
                    checksum CHAR(64) NOT NULL,
                    installed_at TIMESTAMPTZ NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private fun applyMigration(connection: Connection, migration: Migration) {
        val sql = javaClass.getResourceAsStream(migration.resource)?.use {
            String(it.readBytes(), StandardCharsets.UTF_8)
        } ?: error("Missing migration resource ${migration.resource}")
        val checksum = sha256(sql)

        val existing = connection.prepareStatement(
            "SELECT checksum FROM schema_migrations WHERE version = ?"
        ).use { statement ->
            statement.setInt(1, migration.version)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
        if (existing != null) {
            check(MessageDigest.isEqual(existing.toByteArray(), checksum.toByteArray())) {
                "Migration V${migration.version} checksum mismatch"
            }
            return
        }

        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            sql.split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach { statementSql ->
                    connection.createStatement().use { it.execute(statementSql) }
                }
            connection.prepareStatement(
                "INSERT INTO schema_migrations(version, description, checksum, installed_at) VALUES (?, ?, ?, ?)"
            ).use { statement ->
                statement.setInt(1, migration.version)
                statement.setString(2, migration.description)
                statement.setString(3, checksum)
                statement.setInstant(4, Instant.now())
                statement.executeUpdate()
            }
            connection.commit()
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
