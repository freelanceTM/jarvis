# NEXT SESSION — push Stage 1-6 changes to GitHub

## Context
Previous session ("arena/01a0372a-jarvis") produced a full set of changes for
Stages 1–6 (C-02 privacy consent, H-02/H-03/H-04/H-05/H-06 fixes,
M-01/M-02/M-10 cleanup, test gaps) but could not push because that session was
closed after its PR was merged and sandbox TLS was blocked.

This workspace contains everything needed. Three possible scenarios, tried
in order until one works:

### Scenario A: branch `arena/01a0372a-jarvis` exists and has commit 3bdf041
```
git branch
git log arena/01a0372a-jarvis --oneline -3
```
If you see commit `3bdf041` ("Этапы 1-6..."), create a new branch from it and
push:
```
git checkout -b fix/stage1-6-production 3bdf041
git push -u origin fix/stage1-6-production
gh pr create --title "Этапы 1-6: privacy consent, H/M fixes, test gaps" \
  --base main \
  --body "Включает C-02, H-02..H-06, M-01/M-02/M-10, тестовые пробелы, инвариант-скрипт в CI."
```

### Scenario B: commit 3bdf041 doesn't exist but stage1-6.patch is present
Apply the patch from repo root (it is 582KB, git format-patch style):
```
git checkout -b fix/stage1-6-production origin/main
git apply --3way stage1-6.patch
# If --3way fails (no base), use:
# patch -p1 < stage1-6.patch
git add -A
git commit -m "Этапы 1-6: C-02 privacy consent, H-02/H-03/H-04/H-05/H-06 fixes, M-01/M-02/M-10 cleanup, test gaps"
git push -u origin fix/stage1-6-production
gh pr create ... (как выше)
```

### Scenario C: no patch, no commit, but files are on disk
All modified/new files are still in the working tree. If `git status` shows
86 modified/new files, just:
```
git checkout -b fix/stage1-6-production origin/main
git add -A
git commit -m "Этапы 1-6: ..."
git push -u origin fix/stage1-6-production
gh pr create ...
```

## After push
1. Wait for CI (`./gradlew :app:compileDevDebugKotlin :server:compileKotlin`
   and tests).
2. Fix any compile/test failures (expected: 0-5 small import/signature issues).
3. `bash scripts/verify-architectural-invariants.sh` must pass.
4. Report the PR URL back to the user.

## Important notes
- Files under `archive/` are documentation only (OrchestratorEngine,
  ChatUiStateMachine). They must not be compiled by Gradle. If Gradle picks
  them up, add an exclude in build.gradle.kts — but they're plain Kotlin
  with no Android deps so they should be harmless even if compiled.
- `server/src/main/.../http/JarvisApiHandler.kt` imports
  `kotlin.text.Charsets` (already in file).
- WorkManager dependency is in `gradle/libs.versions.toml`
  (`androidx-work-runtime-ktx`).
- `scripts/verify-architectural-invariants.sh` is added as a CI step in
  `.github/workflows/build.yml`.
- The file `jarvis-stage1-6.zip` in repo root is a redundant backup archive
  (4.1MB) — do NOT commit it. Delete it before pushing.
- The file `stage1-6.patch` in repo root is 582KB — do NOT commit it either;
  delete before pushing.
- After successful push, DELETE `jarvis-stage1-6.zip` and `stage1-6.patch`
  from the branch (they shouldn't be in PR).

## Files added/modified (86 total)
See `git diff --stat origin/main` or the patch for full list. Key dirs:
- app/src/main/java/.../agent/automation/scheduler/AutomationReconcileWorker.kt (new)
- app/src/main/java/.../voice/wakeword/WakeWordExtractor.kt (new)
- server/src/main/.../usage/AsyncUsageTracker.kt (new)
- server/src/main/.../auth/EntitlementChecker.kt (new)
- scripts/verify-architectural-invariants.sh (new, +x)
- archive/ (new directory with preserved unused infrastructure)
- app/src/test/.../ (many new tests for privacy, wakeword, etc.)
- server/src/test/.../ (many new tests for usage, auth, lifecycle, etc.)
- Many existing files modified for privacy consent dedup, dead-code removal,
  size-check UTF-8 fix, etc.
