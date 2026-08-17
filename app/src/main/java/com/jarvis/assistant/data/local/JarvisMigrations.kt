package com.jarvis.assistant.data.local

import androidx.room.migration.Migration

/**
 * Все миграции Room-базы JARVIS (пункт аудита #7 — HIGH).
 *
 * ─── ПРОЦЕСС (обязателен для любого изменения схемы) ─────────────────────
 * 1. Измени сущность(и) в `entities/`.
 * 2. Подними `version` в [JarvisDatabase] (5 → 6).
 * 3. Добавь миграцию `MIGRATION_5_6` в массив [ALL] ниже.
 * 4. Пересобери проект: Room экспортирует новую схему в `app/schemas/`
 *    (ksp arg `room.schemaLocation`), файл 6.json появится рядом с 5.json.
 * 5. Добавь кейс в androidTest `JarvisDatabaseMigrationTest`
 *    (MigrationTestHelper: createDatabase(5) → runMigrationsAndValidate(6)).
 * 6. Закоммить и 5.json, и 6.json — схемы — часть репозитория.
 *
 * БЕЗ миграции Room бросит IllegalStateException при открытии базы
 * (fallbackToDestructiveMigration удалён): данные НЕ стираются молча.
 *
 * Примечание: схемы версий 1–4 не сохранялись (exportSchema был выключен),
 * поэтому переходы с них недоступны; для устройств с такими базами
 * потребуется восстановить историю схем из git или одноразовый перенос.
 */
object JarvisMigrations {

    /**
     * Все миграции в порядке возрастания версий.
     *
     * Пример будущей миграции 5 → 6:
     * ```
     * private val MIGRATION_5_6 = object : Migration(5, 6) {
     *     override fun migrate(db: SupportSQLiteDatabase) {
     *         db.execSQL("ALTER TABLE messages ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
     *     }
     * }
     * ```
     */
    val ALL: Array<Migration> = arrayOf(
        // MIGRATION_5_6 — добавить сюда при первом изменении схемы после v5.
    )
}
