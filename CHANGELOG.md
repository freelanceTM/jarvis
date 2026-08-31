# Changelog

All notable repository changes are recorded here. The project did not contain a
release changelog or authoritative release tags in the provided workspace, so
entries are grouped under **Unreleased** rather than inventing published
versions or dates.

The format is based on Keep a Changelog, but semantic-version release history
must be added only when the repository owner creates an actual release.

## [Unreleased]

### Added

- `docs/OMNIX_V03_PLAN_VERIFICATION.md`: item-by-item verification of the
  proposed OMNIX v0.3 plan against actual code — what already exists
  (ExecutionDecisionEngine as the ExecutionRouter, FastCommandRouter no-AI
  paths, server provider selection), the confirmed gap table
  (key pools, neural embeddings, device identity, admin plane, local LLM
  measurements) with причина/влияние/исправление, and the adjusted phase order.

- `docs/AUDIT_2026_08_29.md`: final independent audit report — item-by-item
  verification of the 2026-08-29 review against code, Before/After comparison,
  explicit deferred/declined items with rationale, and the next-step plan.

- Release signing pipeline: env/keystore.properties-driven `signingConfig`,
  manual "Release JARVIS (signed)" workflow (AAB+APK, `apksigner verify`),
  `JARVIS_REQUIRE_SIGNED_RELEASE` fail-fast, R8 release smoke on every PR,
  `docs/RELEASE.md`.
- Accessibility privacy boundary: per-package policy
  (`AccessibilityPrivacyPolicy` + `AccessibilityPrivacyStore`), lock-screen/system
  packages never accessible, password fields never read or typed,
  honest `SCREEN_BLOCKED_BY_PRIVACY_POLICY` /
  `APP_BLOCKED_BY_PRIVACY_POLICY` / `PASSWORD_FIELD_USER_INPUT_REQUIRED`
  results, package-only audit logging, 16 JVM policy tests.
- Prometheus metrics export: `GET /v1/admin/metrics/prometheus`
  (Bearer + VIEW_ADMIN, `text/plain; version=0.0.4`), stable `jarvis_*`
  metric names, endpoint and format tests.
- Operational runbook `docs/RUNBOOK.md` (metrics map, alert table,
  provider/rate-limit/401/usage/Postgres/rollback playbooks, backup RPO/RTO,
  reconciliation procedure) plus `deploy/prometheus/{prometheus,alerts}.yml`.
- `ReconciliationWorker`: read-only visibility for orders stuck in
  `RECONCILIATION_REQUIRED` (aging metric + warn logs, no state guessing),
  `JdbcBillingRepository.findStaleReconciliationOrders`, lifecycle wiring.
- Provider contract tests over recorded fixtures
  (`server/src/test/resources/provider-contracts/`) for Groq/OpenRouter/Gemini:
  success parsing, 429/401 classification, honest schema-drift failures.

### Changed

- `ToolExecutionResult.failure(...)` gained optional structured `data`.
### Added

- JaCoCo coverage configuration for Android JVM tests and the server JVM module.
- `phase3Coverage`, `phase3StaticAnalysis`, and `phase3Quality` Gradle entry
  points, plus XML/HTML/CSV coverage reports and coverage verification.
- Reviewed JaCoCo CI floors: 24% lines / 20% branches for Android JVM tests and
  80% lines / 35% branches for the full PostgreSQL-backed server suite.
- Detekt static analysis with a reviewed high-signal rule configuration and
  HTML/XML/SARIF reports.
- Behavioral tests for activation ViewModel, settings ViewModel, repositories,
  JARVIS API network handling, and Android system/device tools.
- Android instrumentation tests for encrypted `LicenseManagerImpl`, DataStore
  persistence, and Compose component semantics.
- Repository security disclosure process in `SECURITY.md`.
- Project-license decision placeholder in `LICENSE`; no license was selected on
  behalf of the owner.
- Dependency update assessment and test-quality documentation.
- CI execution for the dev JVM suite, JaCoCo gates, Detekt, lint,
  PostgreSQL-backed server verification, and dev app/test APK compilation;
  quality reports and the dev app APK are published as workflow artifacts.
- A dedicated KVM-backed Android API 34 CI job that runs the ordinary
  instrumentation suite, generates Android coverage, and publishes test and
  coverage evidence. The corrected job completed successfully in hosted CI.

### Changed

- Replaced the arithmetic-only `LicenseManagerTest` with instrumentation that
  exercises the production encrypted Android implementation and fail-closed
  server behavior.
- Updated compatible patch/minor AndroidX lifecycle, activity, DataStore, and
  Android test dependencies without beginning the separate Kotlin/AGP/Compose
  major migration; strict dependency locks and verification metadata were
  regenerated for the reviewed graph.
- Excluded the legacy `kotlin-stdlib-common` metadata artifact from Android
  configurations so AGP lint artifact views remain compatible with strict
  locks; the Android/JVM implementation stays pinned to Kotlin stdlib 1.9.24.
- Kept the lint baseline deterministic at 28 reviewed Android findings. External
  dependency-recency IDs moved to Dependabot/dependency-review/lock/Trivy policy
  after CI proved their `latest available` metadata is environment-dependent;
  all 28 source/resource signatures remain unchanged.
- CI now runs explicit dev-flavor coverage verification, Detekt, deterministic
  lint, PostgreSQL-backed server tests, report publication, and dev app/test APK
  assembly instead of relying on a single generic debug path.
- Android instrumentation now packages the authentic Room v5 schema for
  `MigrationTestHelper`; the optional real-model test checks model presence
  before constructing the MediaPipe/Hilt runtime and skips cleanly when the
  separately distributed model is absent.
- Battery status tool now returns a structured failure when Android does not
  provide battery data or supplies an invalid status instead of fabricating a
  100% charge value.
- Removed unused constructor dependencies, parameters, and constants identified
  by static analysis; execution behavior is otherwise unchanged.
- Benchmark text formatting now uses `Locale.ROOT` for deterministic reports.

### Removed

- One-time handoff artifacts from the patch-import session: `stage1-6.patch`
  (content fully merged into the tree) and `NEXT_SESSION_README.md`.
- `archive/` prototype sources (`OrchestratorEngine`, `ChatUiStateMachine`);
  superseded implementations that were never compiled by Gradle and remain
  recoverable from Git history.
- `audit/` working reports and committed scanner evidence; Trivy/SBOM/gitleaks
  outputs are regenerated by CI on every push and published as workflow
  artifacts, so the checked-in copies were stale duplicates.
- Dead `BluetoothAudioManager` (superseded by `BluetoothAudioRouter`),
  unused `AutomationRuleDto`/`AutomationActionDto` DTOs (superseded by
  `AutomationEntity` plus explicit JSON), `ObserveNetworkStateUseCase`,
  the `ExecutionStep` typealias, the `JarvisGreenGlow` color, the unused
  server `ApiException`, and 39 verified-unused Kotlin imports.
- Eight unused legacy string resources (`status_*`, `btn_*`) together with
  their eight `UnusedResources` lint-baseline entries; the deterministic lint
  baseline shrinks from 28 to 20 reviewed findings.

### Existing unreleased work documented from the repository

- Android `dev`, `staging`, and `prod` flavors with strict production endpoint
  policy.
- AlarmManager-backed persisted automation scheduler and Room v5 legacy archive
  policy.
- MediaPipe local-model lifecycle and real-device test harness.
- Capability-driven AGENT-010 cloud fallback and regenerated benchmark data.
- R8/resource shrinking and narrow MediaPipe/JNI keep rules.
- PostgreSQL-backed server license, billing, rate-limit, and usage architecture.
- Supply-chain workflows, dependency locking/checksum verification, full-history
  secret-scan script, Trivy policy, and SBOM generation.

## Release history

**[OWNER ACTION REQUIRED]** Add entries here only for real tags/releases, with
release date, migration notes, supported Android/server versions, and links to
release artifacts.
