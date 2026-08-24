# Room schema compatibility policy

## Supported upgrades

The first release-candidate schema with verifiable export history is **v5** (`app/schemas/.../5.json`). In-place upgrades are supported from v5 to future versions only when every migration and exported schema is committed and covered by `MigrationTestHelper`.

## Legacy versions 1–4: explicit breaking change

The repository contains no authentic v1–v4 schema exports, migration SQL, released database fixtures or usable Git history. Reconstructing these schemas by inference would risk corrupting messages, memories, preferences and automations. Therefore v1–v4 upgrades are **officially unsupported**.

The application does not use a broad `fallbackToDestructiveMigration`. Instead, before Room opens:

1. the SQLite `user_version` is read;
2. versions 1–4 are recognized explicitly;
3. the database and WAL/SHM/journal sidecars are atomically moved to an app-private backup named `jarvis_database.unsupported-vN-<timestamp>.bak`;
4. archive metadata is recorded in app-private preferences;
5. a clean, validated v5 database is created.

This is a breaking clean-install behavior for active app data, but the legacy file is retained for support/manual recovery rather than deleted. If any archive move fails, startup fails closed and attempts to roll back already moved files.

## Consequences

- Legacy messages/memories/settings are not imported automatically.
- Existing legacy data remains in private app storage until uninstall/app-data clear or support recovery.
- Uninstalling the application removes both current and archived databases.
- Backups are not exported or uploaded.
- Versions newer than the app schema are not archived/downgraded; Room refuses them.

## Test evidence

`LegacyDatabasePolicyInstrumentedTest` creates real SQLite v1, v2, v3 and v4 files, inserts sentinel data, archives each file, verifies the sentinel survives in the backup, then opens a clean Room v5 database and validates that the legacy table is absent.

`JarvisDatabaseMigrationTest` validates the authentic v5 schema. When v6 is introduced, a real 5→6 migration and full supported chain must be added; destructive fallback must not be expanded.
