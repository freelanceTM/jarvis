package com.jarvis.server.clip

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC-хранилище идентичности Clip (V008). Только SQL; криптография — в
 * [ClipAttestationService].
 */
class JdbcClipDeviceRepository(private val dataSource: DataSource) {

    fun provision(
        clipSerial: String,
        publicKey: ByteArray,
        now: Instant
    ): ClipProvisionOutcome = transaction { connection ->
        val existing = findBySerial(connection, clipSerial)
        if (existing != null) return@transaction ClipProvisionOutcome.AlreadyExists
        val id = UUID.randomUUID()
        connection.prepareStatement(
            """
            INSERT INTO clip_devices(id, clip_serial, public_key, status, created_at, updated_at)
            VALUES (?, ?, ?, 'PROVISIONED', ?, ?)
            """.trimIndent()
        ).use {
            it.setObject(1, id)
            it.setString(2, clipSerial)
            it.setBytes(3, publicKey)
            it.setInstant(4, now)
            it.setInstant(5, now)
            it.executeUpdate()
        }
        ClipProvisionOutcome.Created(
            ClipDevice(
                id = id,
                clipSerial = clipSerial,
                publicKey = publicKey,
                ownerAccountId = null,
                status = ClipDeviceStatus.PROVISIONED,
                boundAt = null,
                lastVerifiedAt = null
            )
        )
    }

    fun findBySerial(clipSerial: String): ClipDevice? = transaction { connection ->
        findBySerial(connection, clipSerial)
    }

    fun issueChallenge(
        clipSerial: String,
        nonce: ByteArray,
        issuedAtMs: Long,
        expiresAt: Instant,
        now: Instant
    ): ClipChallenge = transaction { connection ->
        val id = UUID.randomUUID()
        connection.prepareStatement(
            """
            INSERT INTO clip_attest_challenges(id, clip_serial, nonce, issued_at_ms, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use {
            it.setObject(1, id)
            it.setString(2, clipSerial)
            it.setBytes(3, nonce)
            it.setLong(4, issuedAtMs)
            it.setInstant(5, expiresAt)
            it.setInstant(6, now)
            it.executeUpdate()
        }
        ClipChallenge(id, clipSerial, nonce, issuedAtMs, expiresAt)
    }

    /**
     * Атомарное одноразовое потребление challenge: помечает used_at только если
     * он ещё не использован и не истёк. Возвращает строку challenge или null
     * (replay/истёк/не существует).
     */
    fun consumeChallenge(challengeId: UUID, now: Instant): ClipChallenge? = transaction { connection ->
        val row = connection.prepareStatement(
            """
            SELECT clip_serial, nonce, issued_at_ms FROM clip_attest_challenges
            WHERE id = ? AND used_at IS NULL AND expires_at > ?
            FOR UPDATE
            """.trimIndent()
        ).use {
            it.setObject(1, challengeId)
            it.setInstant(2, now)
            it.executeQuery().use { result ->
                if (!result.next()) return@transaction null
                ClipChallenge(
                    challengeId = challengeId,
                    clipSerial = result.getString("clip_serial"),
                    nonce = result.getBytes("nonce"),
                    issuedAtMs = result.getLong("issued_at_ms"),
                    expiresAt = now
                )
            }
        } ?: return@transaction null
        connection.prepareStatement(
            "UPDATE clip_attest_challenges SET used_at = ? WHERE id = ? AND used_at IS NULL"
        ).use {
            it.setInstant(1, now)
            it.setObject(2, challengeId)
            it.executeUpdate()
        }
        row
    }

    /** Первая привязка владельца: только если владелец ещё не назначен. */
    fun bindOwner(clipSerial: String, accountId: UUID, now: Instant): Boolean = transaction { connection ->
        connection.prepareStatement(
            """
            UPDATE clip_devices SET owner_account_id = ?, bound_at = ?, updated_at = ?, status = 'ACTIVE'
            WHERE clip_serial = ? AND owner_account_id IS NULL AND status <> 'REVOKED'
            """.trimIndent()
        ).use {
            it.setObject(1, accountId)
            it.setInstant(2, now)
            it.setInstant(3, now)
            it.setString(4, clipSerial)
            it.executeUpdate() > 0
        }
    }

    fun markVerified(clipSerial: String, now: Instant) = transaction { connection ->
        connection.prepareStatement(
            "UPDATE clip_devices SET last_verified_at = ?, updated_at = ?, status = 'ACTIVE' WHERE clip_serial = ? AND status <> 'REVOKED'"
        ).use {
            it.setInstant(1, now)
            it.setInstant(2, now)
            it.setString(3, clipSerial)
            it.executeUpdate()
        }
    }

    fun setStatus(clipSerial: String, status: ClipDeviceStatus, now: Instant): Boolean = transaction { connection ->
        connection.prepareStatement(
            "UPDATE clip_devices SET status = ?, updated_at = ? WHERE clip_serial = ?"
        ).use {
            it.setString(1, status.name)
            it.setInstant(2, now)
            it.setString(3, clipSerial)
            it.executeUpdate() > 0
        }
    }

    private fun findBySerial(connection: Connection, clipSerial: String): ClipDevice? =
        connection.prepareStatement(
            """
            SELECT id, clip_serial, public_key, owner_account_id, status, bound_at, last_verified_at
            FROM clip_devices WHERE clip_serial = ?
            """.trimIndent()
        ).use {
            it.setString(1, clipSerial)
            it.executeQuery().use { result ->
                if (!result.next()) return@use null
                ClipDevice(
                    id = result.getObject("id", UUID::class.java),
                    clipSerial = result.getString("clip_serial"),
                    publicKey = result.getBytes("public_key"),
                    ownerAccountId = result.getObject("owner_account_id", UUID::class.java),
                    status = ClipDeviceStatus.valueOf(result.getString("status")),
                    boundAt = result.getInstant("bound_at"),
                    lastVerifiedAt = result.getInstant("last_verified_at")
                )
            }
        }

    private fun <T> transaction(block: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
}
