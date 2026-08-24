package com.jarvis.server.persistence

import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/**
 * Session-level PostgreSQL advisory lock enforcing ADR-0001's one-server limit.
 *
 * The dedicated connection remains open for the process lifetime. PostgreSQL
 * releases the lock automatically if the process/connection dies. Failure to
 * connect or acquire is fail-closed: a second production instance must not run
 * with process-local circuit/metrics state.
 */
class PostgresSingleInstanceGuard private constructor(
    private val connection: Connection,
    private val lockId: Long
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
                statement.setLong(1, lockId)
                statement.execute()
            }
        } finally {
            connection.close()
        }
    }

    companion object {
        const val DEFAULT_LOCK_ID: Long = 733_611_043L

        fun acquire(
            dataSource: DataSource,
            lockId: Long = DEFAULT_LOCK_ID
        ): PostgresSingleInstanceGuard {
            val connection = dataSource.connection
            try {
                val acquired = connection.prepareStatement(
                    "SELECT pg_try_advisory_lock(?)"
                ).use { statement ->
                    statement.setLong(1, lockId)
                    statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
                }
                check(acquired) {
                    "Single-instance topology violation: another JARVIS application instance is active"
                }
                return PostgresSingleInstanceGuard(connection, lockId)
            } catch (failure: Throwable) {
                connection.close()
                throw failure
            }
        }
    }
}
