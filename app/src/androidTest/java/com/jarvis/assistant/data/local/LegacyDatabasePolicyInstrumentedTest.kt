package com.jarvis.assistant.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LegacyDatabasePolicyInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val createdNames = mutableSetOf<String>()
    private val createdBackups = mutableSetOf<File>()

    @After
    fun cleanUp() {
        createdNames.forEach(context::deleteDatabase)
        createdBackups.forEach { backup ->
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                File(backup.absolutePath + suffix).delete()
            }
        }
    }

    @Test fun v1IsArchivedAndCleanV5IsCreated() = verifyUnsupportedVersion(1)
    @Test fun v2IsArchivedAndCleanV5IsCreated() = verifyUnsupportedVersion(2)
    @Test fun v3IsArchivedAndCleanV5IsCreated() = verifyUnsupportedVersion(3)
    @Test fun v4IsArchivedAndCleanV5IsCreated() = verifyUnsupportedVersion(4)

    @Test
    fun v5IsNeverArchived() {
        val name = "legacy-policy-v5.db"
        createdNames += name
        val database = context.getDatabasePath(name)
        database.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(database, null).use { it.version = 5 }

        val result = LegacyDatabasePolicy.archiveUnsupportedDatabaseIfPresent(context, name, now = 50L)

        assertEquals(null, result)
        assertTrue(database.exists())
    }

    private fun verifyUnsupportedVersion(version: Int) {
        val name = "legacy-policy-v$version.db"
        createdNames += name
        context.deleteDatabase(name)
        val database = context.getDatabasePath(name)
        database.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(database, null).use { legacy ->
            legacy.execSQL("CREATE TABLE legacy_sentinel(value TEXT NOT NULL)")
            legacy.execSQL("INSERT INTO legacy_sentinel(value) VALUES ('preserved-v$version')")
            legacy.version = version
        }

        val archived = LegacyDatabasePolicy.archiveUnsupportedDatabaseIfPresent(
            context,
            name,
            now = 1_000L + version
        )
        assertNotNull(archived)
        archived!!
        createdBackups += archived.backupFile
        assertEquals(version, archived.previousVersion)
        assertFalse(database.exists())
        assertTrue(archived.backupFile.exists())

        SQLiteDatabase.openDatabase(
            archived.backupFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { backup ->
            backup.query("legacy_sentinel", arrayOf("value"), null, null, null, null, null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("preserved-v$version", cursor.getString(0))
            }
        }

        val current = JarvisDatabaseFactory.create(context, name)
        try {
            current.openHelper.writableDatabase
            assertEquals(5, current.openHelper.readableDatabase.version)
            current.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages").use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
            val legacyTable = current.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='legacy_sentinel'"
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            assertEquals(0, legacyTable)
        } finally {
            current.close()
        }

        val recorded = LegacyDatabasePolicy.lastArchive(context)
        assertNotNull(recorded)
        assertEquals(version, recorded?.previousVersion)
        assertEquals(archived.backupFile.name, recorded?.backupFile?.name)
    }
}
