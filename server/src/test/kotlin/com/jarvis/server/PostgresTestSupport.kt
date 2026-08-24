package com.jarvis.server

import com.zaxxer.hikari.HikariDataSource
import com.jarvis.server.persistence.DatabaseConfig
import com.jarvis.server.persistence.DatabaseFactory
import com.jarvis.server.persistence.DatabaseMigrator
import org.junit.After
import org.junit.Before

open class PostgresTestSupport {
    protected lateinit var dataSource: HikariDataSource

    @Before
    fun resetPostgres() {
        dataSource = DatabaseFactory.create(
            DatabaseConfig(
                jdbcUrl = System.getenv("JARVIS_TEST_DATABASE_URL")
                    ?: "jdbc:postgresql://127.0.0.1:15432/jarvis_test",
                user = System.getenv("JARVIS_TEST_DATABASE_USER") ?: "user",
                password = System.getenv("JARVIS_TEST_DATABASE_PASSWORD") ?: "unused",
                maxPoolSize = 16
            )
        )
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute("DROP SCHEMA IF EXISTS public CASCADE")
                it.execute("CREATE SCHEMA public")
            }
        }
        DatabaseMigrator(dataSource).migrate()
    }

    @After
    fun closePostgres() {
        dataSource.close()
    }
}
