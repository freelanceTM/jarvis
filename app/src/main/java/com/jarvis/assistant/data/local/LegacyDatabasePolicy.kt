package com.jarvis.assistant.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.jarvis.assistant.core.constants.AppConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Explicit breaking-change policy for unrecoverable Room schemas v1-v4.
 *
 * Authentic exported schemas/migrations do not exist, so guessing SQL would
 * risk corrupting user data. We archive the complete legacy SQLite file set in
 * internal app storage and create a clean v5 database. The archive is retained
 * for support/manual recovery and the reset is recorded in preferences.
 */
object LegacyDatabasePolicy {
    val UNSUPPORTED_VERSIONS: Set<Int> = setOf(1, 2, 3, 4)

    private const val PREFS = "jarvis_database_policy"
    private const val KEY_PREVIOUS_VERSION = "legacy_previous_version"
    private const val KEY_BACKUP_NAME = "legacy_backup_name"
    private const val KEY_ARCHIVED_AT = "legacy_archived_at"

    data class ArchiveResult(
        val previousVersion: Int,
        val backupFile: File,
        val archivedAt: Long
    )

    /** Serialized because the database factory can be requested concurrently. */
    @Synchronized
    fun archiveUnsupportedDatabaseIfPresent(
        context: Context,
        databaseName: String = AppConstants.DATABASE_NAME,
        now: Long = System.currentTimeMillis()
    ): ArchiveResult? {
        val database = context.getDatabasePath(databaseName)
        if (!database.exists()) return null

        val version = SQLiteDatabase.openDatabase(
            database.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { sqlite ->
            val currentVersion = sqlite.version
            if (currentVersion in UNSUPPORTED_VERSIONS) {
                // Make the main file independently recoverable before touching
                // WAL/SHM sidecars. A crash during later cleanup therefore does
                // not discard committed WAL rows.
                sqlite.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                    if (cursor.moveToFirst() && cursor.getInt(0) != 0) {
                        throw IllegalStateException("Unable to checkpoint unsupported Room database v$currentVersion")
                    }
                }
            }
            currentVersion
        }
        if (version !in UNSUPPORTED_VERSIONS) return null

        val backup = File(database.parentFile, "$databaseName.unsupported-v$version-$now.bak")
        check(!backup.exists()) { "Legacy archive already exists: ${backup.name}" }
        val suffixes = listOf("", "-wal", "-shm", "-journal")
        val files = suffixes
            .map { suffix -> File(database.absolutePath + suffix) to File(backup.absolutePath + suffix) }
            .filter { (source, _) -> source.exists() }
        val copied = mutableListOf<File>()
        try {
            for ((source, target) in files) {
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                FileOutputStream(target, true).channel.use { it.force(true) }
                copied += target
            }
            val archivedVersion = SQLiteDatabase.openDatabase(
                backup.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { it.version }
            check(archivedVersion == version) { "Legacy archive version verification failed" }
        } catch (failure: Throwable) {
            copied.asReversed().forEach { runCatching { Files.deleteIfExists(it.toPath()) } }
            throw IllegalStateException("Unable to archive unsupported Room database v$version", failure)
        }

        // Main DB is deleted last. If the process dies during cleanup, the
        // checkpointed original still opens and the copy can safely be retried.
        try {
            files.asReversed().forEach { (source, _) ->
                if (source != database) Files.deleteIfExists(source.toPath())
            }
            check(Files.deleteIfExists(database.toPath())) { "Legacy database disappeared during archive" }
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "Archive is complete but unsupported Room database cleanup failed",
                failure
            )
        }

        val recorded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PREVIOUS_VERSION, version)
            .putString(KEY_BACKUP_NAME, backup.name)
            .putLong(KEY_ARCHIVED_AT, now)
            .commit()
        check(recorded) { "Legacy archive metadata commit failed" }

        return ArchiveResult(version, backup, now)
    }

    fun lastArchive(context: Context): ArchiveResult? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getInt(KEY_PREVIOUS_VERSION, 0)
        val name = prefs.getString(KEY_BACKUP_NAME, null) ?: return null
        val archivedAt = prefs.getLong(KEY_ARCHIVED_AT, 0)
        if (version !in UNSUPPORTED_VERSIONS || archivedAt <= 0) return null
        return ArchiveResult(
            version,
            File(context.getDatabasePath(AppConstants.DATABASE_NAME).parentFile, name),
            archivedAt
        )
    }
}

object JarvisDatabaseFactory {
    fun create(
        context: Context,
        databaseName: String = AppConstants.DATABASE_NAME
    ): JarvisDatabase {
        LegacyDatabasePolicy.archiveUnsupportedDatabaseIfPresent(context, databaseName)
        return Room.databaseBuilder(
            context,
            JarvisDatabase::class.java,
            databaseName
        )
            .addMigrations(*JarvisMigrations.ALL)
            .build()
    }
}
