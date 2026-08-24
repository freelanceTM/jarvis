package com.jarvis.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SharedStateArchitectureTest {
    @Test
    fun `production composition uses shared security and accounting state`() {
        val root = repositoryRoot()
        val main = Files.readString(root.resolve("server/src/main/kotlin/com/jarvis/server/Main.kt"))
        val compose = Files.readString(root.resolve("deploy/docker-compose.production.yml"))
        val adr = Files.readString(root.resolve("docs/adr/0001-single-application-instance.md"))

        assertTrue(main.contains("JdbcUsageRepository(dataSource)"))
        assertTrue(main.contains("PostgresRateLimiter(dataSource, \"ai_execute\""))
        assertTrue(main.contains("PostgresSingleInstanceGuard.acquire(dataSource)"))
        assertFalse(main.contains("InMemoryUsageRepository()"))
        assertFalse(main.contains("SlidingWindowRateLimiter(config.rateLimit)"))
        assertTrue(compose.contains("APPLICATION_REPLICA_COUNT: \"1\""))
        assertTrue(adr.contains("production supports exactly"))
    }

    @Test
    fun `shared state migration and retention schema remain registered`() {
        val root = repositoryRoot()
        val database = Files.readString(
            root.resolve("server/src/main/kotlin/com/jarvis/server/persistence/Database.kt")
        )
        val usageMigration = Files.readString(
            root.resolve("server/src/main/resources/db/migration/V005__shared_ai_usage.sql")
        )

        assertTrue(database.contains("V005__shared_ai_usage.sql"))
        assertTrue(usageMigration.contains("UNIQUE (client_id, request_id)"))
        assertTrue(usageMigration.contains("idx_ai_usage_cleanup"))
    }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(5) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: return@repeat
        }
        error("Repository root not found")
    }
}
