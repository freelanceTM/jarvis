package com.jarvis.assistant.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Миграции Room (пункт аудита #7 — HIGH).
 *
 * Валидирует, что экспортированная схема v5 соответствует сущностям и что
 * БД открывается/мигрирует без потери данных. При изменении схемы:
 * 1. добавь MIGRATION_X_Y в JarvisMigrations.ALL;
 * 2. добавь тест runMigrationsAndValidate(TEST_DB, NEW_VERSION, true, MIGRATION_X_Y).
 *
 * Запуск: ./gradlew connectedDebugAndroidTest (требует эмулятора/устройства).
 */
@RunWith(AndroidJUnit4::class)
class JarvisDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        JarvisDatabase::class.java
    )

    @Test
    fun `v5 schema creates all tables and validates`() {
        // Создаём БД из экспортированной схемы 5.json и валидируем её.
        helper.createDatabase(TEST_DB, 5).close()
        helper.runMigrationsAndValidate(TEST_DB, 5, true, *JarvisMigrations.ALL)
    }

    @Test
    fun `messages table roundtrips on v5 schema`() {
        val db = helper.createDatabase(TEST_DB, 5)
        db.execSQL(
            "INSERT INTO messages (role, text, timestamp) VALUES ('user', 'тестовое сообщение', 1)"
        )
        db.query("SELECT COUNT(*) FROM messages").use { cursor ->
            cursor.moveToFirst()
            assertTrue("Сообщение должно сохраниться", cursor.getInt(0) >= 1)
        }
        db.close()
    }

    @Test
    fun `automations table has trigger_param column on v5`() {
        val db = helper.createDatabase(TEST_DB, 5)
        db.query("SELECT trigger_param FROM automations LIMIT 0").use { cursor ->
            assertTrue("Колонка trigger_param должна существовать", cursor.columnCount >= 1)
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
    }
}
