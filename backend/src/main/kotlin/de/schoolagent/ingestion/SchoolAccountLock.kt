package de.schoolagent.ingestion

import de.schoolagent.common.DedupKeys
import de.schoolagent.connectors.school.SchoolConnectionException
import de.schoolagent.connectors.school.SchoolCredentials
import org.springframework.stereotype.Component
import javax.sql.DataSource

/** Session locks protect provider account context across processes without a long database transaction.
 * PostgreSQL releases ownership when the connection dies; no expiring lease can overlap a slow fetch.
 */
@Component
class SchoolAccountLock(private val dataSource: DataSource) {
    private val local = Array(128) { Any() }
    private val slots = java.util.concurrent.Semaphore(2)

    fun <T> withAccount(credentials: SchoolCredentials, work: () -> T): T {
        val hash = DedupKeys.sha256("${credentials.provider}|${credentials.url.trimEnd('/')}|${credentials.username.trim().lowercase()}|${credentials.institutionId.orEmpty()}")
        val key = hash.take(16).toULong(16).toLong()
        return synchronized(local[(key and 127).toInt()]) {
            if (!slots.tryAcquire()) throw SchoolConnectionException("School checks are busy; retry after the current sync finishes")
            try { dataSource.connection.use { connection ->
                fun lockCall(function: String): Boolean = connection.prepareStatement("SELECT $function(?)").use { statement ->
                    statement.queryTimeout = 5
                    statement.setLong(1, key)
                    statement.executeQuery().use { it.next(); it.getBoolean(1) }
                }
                if (!lockCall("pg_try_advisory_lock")) throw SchoolConnectionException(
                    "This school account is already being checked; retry after the current sync finishes")
                try { work() } finally {
                    // Never return a pooled session that still owns an account lock.
                    if (runCatching { lockCall("pg_advisory_unlock") }.getOrDefault(false).not())
                        connection.abort(java.util.concurrent.Executor { it.run() })
                }
            } } finally { slots.release() }
        }
    }
}
