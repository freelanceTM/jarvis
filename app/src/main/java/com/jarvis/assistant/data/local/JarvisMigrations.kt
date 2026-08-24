package com.jarvis.assistant.data.local

import androidx.room.migration.Migration

/**
 * Supported Room migrations from the first release-candidate schema onward.
 *
 * Only v5 has an authentic exported schema in this repository. Schemas v1-v4
 * cannot be reconstructed safely without Git history/released artifacts, so
 * guessed migrations are forbidden. [LegacyDatabasePolicy] implements the
 * declared breaking-change strategy: archive an unsupported legacy DB inside
 * app-private storage, record the event, then create a clean v5 database.
 *
 * For every future schema change:
 * 1. update entities and increment [JarvisDatabase] version;
 * 2. add `MIGRATION_X_Y` below;
 * 3. export and commit both schema JSON files;
 * 4. add a MigrationTestHelper step and full-chain test;
 * 5. never add a broad destructive fallback.
 */
object JarvisMigrations {
    /** Earliest schema from which in-place upgrades are supported. */
    const val MIN_SUPPORTED_VERSION = 5

    /** Future supported migrations in ascending order. */
    val ALL: Array<Migration> = emptyArray()
}
