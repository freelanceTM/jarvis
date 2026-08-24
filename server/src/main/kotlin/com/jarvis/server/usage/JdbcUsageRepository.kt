package com.jarvis.server.usage

import com.jarvis.server.persistence.getInstant
import com.jarvis.server.persistence.setInstant
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

/**
 * PostgreSQL-backed, multi-instance-safe AI usage ledger.
 *
 * `(client_id, request_id)` makes retries/idempotent duplicate records harmless.
 * Retention cleanup is eventual and atomic with each insert transaction. A store
 * outage propagates to the request handler instead of silently losing accounting.
 */
class JdbcUsageRepository(
    private val dataSource: DataSource,
    private val retention: Duration = Duration.ofDays(30),
    private val clock: Clock = Clock.systemUTC()
) : UsageRepository {
    init {
        require(!retention.isNegative && !retention.isZero) { "usage retention must be positive" }
    }

    override suspend fun record(usage: AiUsageRecord) {
        transaction { connection ->
            connection.prepareStatement(
                """
                INSERT INTO ai_usage_records(
                    request_id, client_id, provider, model, latency_ms,
                    input_tokens, output_tokens, total_tokens, success, error_code,
                    prompt_chars, response_chars, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (client_id, request_id) DO NOTHING
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, usage.requestId)
                statement.setString(2, usage.clientId)
                statement.setString(3, usage.provider)
                statement.setString(4, usage.model)
                statement.setLong(5, usage.latencyMs)
                statement.setNullableLong(6, usage.inputTokens)
                statement.setNullableLong(7, usage.outputTokens)
                statement.setNullableLong(8, usage.totalTokens)
                statement.setBoolean(9, usage.success)
                statement.setString(10, usage.errorCode)
                statement.setInt(11, usage.promptChars)
                statement.setInt(12, usage.responseChars)
                statement.setInstant(13, usage.timestamp)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "DELETE FROM ai_usage_records WHERE occurred_at < ?"
            ).use { statement ->
                statement.setInstant(1, clock.instant().minus(retention))
                statement.executeUpdate()
            }
        }
    }

    override suspend fun recentFor(clientId: String, limit: Int): List<AiUsageRecord> {
        val boundedLimit = limit.coerceIn(0, 10_000)
        if (boundedLimit == 0) return emptyList()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT * FROM ai_usage_records
                WHERE client_id = ?
                ORDER BY occurred_at DESC, id DESC
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, clientId)
                statement.setInt(2, boundedLimit)
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.toUsageRecord()) }
                }.asReversed()
            }
        }
    }

    override suspend fun all(): List<AiUsageRecord> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT * FROM ai_usage_records ORDER BY occurred_at, id"
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.toUsageRecord()) }
            }
        }
    }

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

    private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
    }

    private fun ResultSet.nullableLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private fun ResultSet.toUsageRecord() = AiUsageRecord(
        requestId = getString("request_id"),
        clientId = getString("client_id"),
        provider = getString("provider"),
        model = getString("model"),
        latencyMs = getLong("latency_ms"),
        inputTokens = nullableLong("input_tokens"),
        outputTokens = nullableLong("output_tokens"),
        totalTokens = nullableLong("total_tokens"),
        success = getBoolean("success"),
        errorCode = getString("error_code"),
        promptChars = getInt("prompt_chars"),
        responseChars = getInt("response_chars"),
        timestamp = requireNotNull(getInstant("occurred_at"))
    )
}
