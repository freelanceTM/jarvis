package com.jarvis.server.admin

import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/** Строка feature flag (таблица feature_flags). */
data class FeatureFlag(
    val key: String,
    val enabled: Boolean,
    val rolloutPercent: Int,
    val description: String,
    val updatedAt: Instant
)

/**
 * SERVER-DRIVEN feature flags (Control Plane ТЗ §21).
 *
 * Минимум — ON/OFF; сверх минимума — детерминированный percent rollout:
 * флаг вычисляется как h(key|clientId) % 100 < rolloutPercent, где h — SHA-256.
 * Детерминизм гарантирует, что один и тот же клиент стабильно попадает
 * в одну и ту же группу (без sticky-состояния).
 *
 * Feature flags ≠ Runtime configuration ≠ Secrets (ТЗ §22): флаги — только
 * тумблеры/rollout; конфигурация — в AdminSettings; секреты — только env.
 */
class FeatureFlagService(private val dataSource: DataSource) {

    /** Изначально известные флаги (создаются лениво при первом чтении/записи). */
    val knownKeys: List<String> = listOf(
        "wake_word", "agent_mode", "translation", "ear_mode",
        "health_integration", "proactive_ai", "ios"
    )

    private val cache = AtomicReference<Map<String, FeatureFlag>>(emptyMap())

    fun all(): Map<String, FeatureFlag> {
        refresh()
        return cache.get()
    }

    fun get(key: String): FeatureFlag? {
        refresh()
        return cache.get()[key]
    }

    /** Виден ли флаг данному клиенту с учётом rollout-процента. */
    fun isEnabledFor(key: String, clientId: String?): Boolean {
        val flag = get(key) ?: return false
        if (!flag.enabled) return false
        if (flag.rolloutPercent >= 100) return true
        if (flag.rolloutPercent <= 0 || clientId.isNullOrBlank()) return false
        return bucketOf(key, clientId) < flag.rolloutPercent
    }

    fun upsert(
        key: String,
        enabled: Boolean,
        rolloutPercent: Int,
        description: String,
        actor: String
    ): FeatureFlag {
        require(key in knownKeys) { "unknown feature flag: $key" }
        require(rolloutPercent in 0..100) { "rolloutPercent must be in 0..100" }
        val now = Instant.now()
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO feature_flags (key, enabled, rollout_percent, description, updated_by, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (key) DO UPDATE SET enabled = EXCLUDED.enabled, " +
                    "rollout_percent = EXCLUDED.rollout_percent, description = EXCLUDED.description, " +
                    "updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at"
            ).use { ps ->
                ps.setString(1, key)
                ps.setBoolean(2, enabled)
                ps.setInt(3, rolloutPercent)
                ps.setString(4, description.take(256))
                ps.setString(5, actor.take(128))
                ps.setTimestamp(6, Timestamp.from(now))
                ps.executeUpdate()
            }
        }
        refresh()
        return FeatureFlag(key, enabled, rolloutPercent, description.take(256), now)
    }

    /** h(key|clientId) % 100 — детерминированный bucket клиента. */
    fun bucketOf(key: String, clientId: String): Int {
        val bytes = AdminPasswords.sha256Hex("$key|$clientId")
        return ((bytes.substring(0, 8).toLong(16) % 100 + 100) % 100).toInt()
    }

    private fun refresh() {
        val rows = dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT key, enabled, rollout_percent, description, updated_at FROM feature_flags"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<FeatureFlag>()
                    while (rs.next()) {
                        out += FeatureFlag(
                            key = rs.getString("key"),
                            enabled = rs.getBoolean("enabled"),
                            rolloutPercent = rs.getInt("rollout_percent"),
                            description = rs.getString("description"),
                            updatedAt = rs.getTimestamp("updated_at").toInstant()
                        )
                    }
                    out
                }
            }
        }
        cache.set(rows.associateBy { it.key })
    }
}
