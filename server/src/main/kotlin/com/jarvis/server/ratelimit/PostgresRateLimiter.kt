package com.jarvis.server.ratelimit

import com.jarvis.server.config.RateLimitConfig
import com.jarvis.server.persistence.getInstant
import com.jarvis.server.persistence.setInstant
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

/** Multi-instance-safe limiter for license, redemption and billing endpoints. */
class PostgresRateLimiter(
    private val dataSource: DataSource,
    private val scope: String,
    private val config: RateLimitConfig,
    private val clock: () -> Long = System::currentTimeMillis
) : RateLimiter {
    companion object {
        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 86_400_000L
    }

    init {
        require(scope.matches(Regex("[a-z0-9_-]{1,32}"))) { "invalid rate-limit scope" }
    }

    override fun check(clientId: String): RateLimitDecision {
        val keyHash = MessageDigest.getInstance("SHA-256")
            .digest(clientId.toByteArray(StandardCharsets.UTF_8))
        val lockKey = "$scope:${keyHash.joinToString("") { "%02x".format(it) }}"
        val nowMs = clock()
        val now = Instant.ofEpochMilli(nowMs)
        val minuteStart = Instant.ofEpochMilli(nowMs - MINUTE_MS)
        val dayStart = Instant.ofEpochMilli(nowMs - DAY_MS)

        return transaction { connection ->
            connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
                it.setString(1, lockKey)
                it.execute()
            }
            // Indexed global cleanup bounds storage even for one-shot attacker keys.
            connection.prepareStatement("DELETE FROM license_rate_limit_events WHERE occurred_at < ?").use {
                it.setInstant(1, dayStart)
                it.executeUpdate()
            }
            val stats = connection.prepareStatement(
                """
                SELECT
                    count(*) FILTER (WHERE occurred_at >= ?) AS minute_count,
                    min(occurred_at) FILTER (WHERE occurred_at >= ?) AS minute_oldest,
                    count(*) AS day_count,
                    min(occurred_at) AS day_oldest
                FROM license_rate_limit_events
                WHERE scope = ? AND key_hash = ? AND occurred_at >= ?
                """.trimIndent()
            ).use {
                it.setInstant(1, minuteStart)
                it.setInstant(2, minuteStart)
                it.setString(3, scope)
                it.setBytes(4, keyHash)
                it.setInstant(5, dayStart)
                it.executeQuery().use { result ->
                    result.next()
                    Stats(
                        minuteCount = result.getInt("minute_count"),
                        minuteOldest = result.getInstant("minute_oldest"),
                        dayCount = result.getInt("day_count"),
                        dayOldest = result.getInstant("day_oldest")
                    )
                }
            }
            if (stats.minuteCount >= config.perMinute) {
                val oldest = stats.minuteOldest?.toEpochMilli() ?: nowMs
                return@transaction RateLimitDecision.Limited(
                    retryAfter(oldest + MINUTE_MS - nowMs), "per_minute"
                )
            }
            if (stats.dayCount >= config.perDay) {
                val oldest = stats.dayOldest?.toEpochMilli() ?: nowMs
                return@transaction RateLimitDecision.Limited(
                    retryAfter(oldest + DAY_MS - nowMs), "per_day"
                )
            }
            connection.prepareStatement(
                "INSERT INTO license_rate_limit_events(scope, key_hash, occurred_at) VALUES (?, ?, ?)"
            ).use {
                it.setString(1, scope)
                it.setBytes(2, keyHash)
                it.setInstant(3, now)
                it.executeUpdate()
            }
            RateLimitDecision.Allowed
        }
    }

    override fun reset(clientId: String) {
        val keyHash = MessageDigest.getInstance("SHA-256")
            .digest(clientId.toByteArray(StandardCharsets.UTF_8))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "DELETE FROM license_rate_limit_events WHERE scope = ? AND key_hash = ?"
            ).use {
                it.setString(1, scope)
                it.setBytes(2, keyHash)
                it.executeUpdate()
            }
        }
    }

    private fun retryAfter(remainingMs: Long): Long =
        ((remainingMs.coerceAtLeast(1) + 999) / 1_000).coerceAtLeast(1)

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

    private data class Stats(
        val minuteCount: Int,
        val minuteOldest: Instant?,
        val dayCount: Int,
        val dayOldest: Instant?
    )
}
