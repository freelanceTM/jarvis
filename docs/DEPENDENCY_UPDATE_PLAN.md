# Dependency update audit — Phase 3

Date: 2026-08-24

## Scope and method

The Phase 3 starting Android lint baseline contained 85 entries, of which 54
were `GradleDependency` and 3 were `AndroidGradlePluginVersion`. Because lint
reports one dependency finding for each analyzed flavor, those 54 entries
represented 18 unique dependency messages, not 54 independent libraries and
not code coverage.

After adding explicitly pinned test-quality dependencies, the first reviewed
Phase 3 baseline contained 100 entries. A later lint metadata refresh reported 88
entries because four dependency identities were no longer emitted. CI then
confirmed that `GradleDependency` and `AndroidGradlePluginVersion` depend on the
runner's external "latest available" metadata and checkout path, so identical
source produced different baseline matches across environments.

The environment-dependent version-recency IDs `GradleDependency`,
`AndroidGradlePluginVersion`, and `OldTargetApi` are now excluded from Android
lint only. Dependency responsibility remains covered by Dependabot, pull-request
dependency review, strict locks, SHA-256 verification metadata, and blocking
Trivy HIGH/CRITICAL scans. Compile/target SDK recency remains an explicit part of
the coordinated AGP/Kotlin/KSP/Compose migration below. Deterministic Android
lint rules remain `warningsAsErrors`.

The final lint baseline therefore contains only the 28 existing Android
source/resource findings. It was signature-compared with both prior baselines:
all 28 findings are identical and no new production lint issue was baselined.

Updates were evaluated against the current coupled toolchain:

- Gradle 8.7;
- Android Gradle Plugin 8.5.2;
- Kotlin 1.9.24;
- KSP 1.9.24-1.0.20;
- Compose compiler 1.5.14;
- compile/target SDK 34;
- Java 17.

Dependency locks and SHA-256 verification metadata remain mandatory. No dynamic
versions are allowed.

## Existing security floors retained

The project already constrains several transitive dependencies for documented
security reasons. These constraints were not relaxed:

| Dependency | Enforced version | Reason recorded in build |
|---|---:|---|
| Netty | 4.1.137.Final | 2025/2026 codec and TLS CVE floor |
| protobuf-java | 3.25.5 | CVE-2024-7254 tooling path |
| protobuf-javalite | 4.28.2 | CVE-2024-7254 MediaPipe path |
| Commons IO | 2.14.0 | CVE-2024-47554 tooling path |
| Guava Android | 33.7.1-android | Remove older Guava CVEs |
| PostgreSQL JDBC | 42.7.13 | Current reviewed server floor |

Final vulnerability policy remains the blocking Trivy HIGH/CRITICAL scan in
`.github/workflows/security.yml`; version recency alone is not a vulnerability
finding.

## Updates performed now

These are bounded patch/minor updates within the existing Kotlin/AGP generation:

| Dependency family | Before | After | Assessment |
|---|---:|---:|---|
| AndroidX Lifecycle | 2.8.4 | 2.8.7 | Patch line; no API migration required |
| Activity Compose | 1.9.1 | 1.9.3 | Patch line; compatible with current Compose setup |
| DataStore Preferences | 1.1.1 | 1.1.7 | Patch line; persistence API unchanged |
| AndroidX Test JUnit | 1.1.5 | 1.2.1 | Stable compatible test line |
| AndroidX Test Core | 1.5.0 | 1.6.1 | Stable compatible test line |
| AndroidX Test Runner | 1.5.2 | 1.6.2 | Stable compatible test line |

Test-only additions:

- MockK 1.13.12 for behavioral tests of final Kotlin/Android collaborators;
  the newer 1.14.x line was rejected because it resolved Kotlin 2.2/coroutines
  1.10 metadata incompatible with the project's Kotlin 1.9 compiler;
- OkHttp MockWebServer at the existing OkHttp 4.12.0 version;
- kotlinx-coroutines-test at the existing coroutines 1.8.1 version;
- Compose UI test artifacts from the existing Compose BOM;
- Detekt 1.23.8;
- JaCoCo 0.8.11.

For non-Detekt Android configurations, the legacy `kotlin-stdlib-common`
metadata artifact is excluded while `kotlin-stdlib` remains pinned at 1.9.24.
The common artifact has no Android runtime variant and caused AGP lint artifact
views to disagree with strict dependency-lock state. The exclusion was retained
only after all flavor JVM tests, dev/prod lint, Android-test compilation, and
all three APK builds passed; Detekt keeps its own Kotlin 2.0.21 tool classpath.

Test dependencies do not enter the production APK.

CI-only tooling now includes ReactiveCircus Android Emulator Runner v2.38.0,
pinned to the immutable commit
`a421e43855164a8197daf9d8d40fe71c6996bb0d`. The annotated release tag was
verified against that peeled commit, its action uses Node 24, and it is limited
to provisioning the KVM-backed API 34 instrumentation job.

## Intentionally deferred updates

### AGP 8.5.2 → 9.x

Deferred as a dedicated build migration. It requires coordinated review of:

- Gradle 9.x;
- Kotlin 2.x built-in Kotlin changes;
- KSP2;
- AGP DSL and variant API changes;
- compile SDK update;
- R8 and native MediaPipe packaging;
- dependency lock and verification regeneration;
- CI runner memory and Java version.

Updating AGP alone would be an unsafe partial migration.

### Kotlin 1.9.24 → 2.x and Compose compiler migration

Deferred. Kotlin 2 requires coordinated changes to:

- Compose compiler plugin;
- KSP;
- serialization plugin;
- Detekt compatibility;
- Kotlin metadata consumed by AndroidX/coroutines/serialization;
- all unit, lint, R8, and instrumentation builds.

### Compose BOM 2024.06 → 2026.x

Deferred until Kotlin/Compose compiler migration. The jump spans many Material,
semantics, test, and compiler/runtime versions and needs UI regression testing on
a device.

### Room 2.6.1 → 2.8.x

Deferred because the project has a deliberate Room v5 migration/archive policy.
The Room update must be evaluated with:

- KSP/Kotlin migration;
- regenerated schema JSON;
- MigrationTestHelper on a real device;
- legacy v1–v4 archive tests;
- DAO and Flow behavior;
- minSdk/SQLite changes.

### AndroidX Lifecycle 2.8.x → 2.11.x, Core 1.13 → 1.19,
Navigation 2.7 → 2.9, Activity 1.9 → 1.13

Deferred as a compile SDK/Kotlin/Compose migration group. Updating only one of
these strongly coupled UI libraries would leave an unreviewed mixed-generation
stack.

### DataStore 1.1.7 → 1.2.x

Deferred beyond the safe patch update. The later line should be adopted with the
new AndroidX/Kotlin toolchain and the real persistence instrumentation suite.

### AndroidX Test 1.2/1.6 → JUnit 1.3 / Core/Runner 1.7

Deferred until compile SDK and instrumentation runner compatibility can be
validated on a real adb target. The current update removes the oldest test stack
without guessing device compatibility.

### OkHttp 4.12 → 5.x

Deferred as a network migration. It requires review of API changes, cancellation,
interceptors, MockWebServer, TLS behavior, bounded response handling, provider
clients, and release keep rules.

### kotlinx.coroutines 1.8 → 1.10 and serialization 1.6 → later lines

Deferred because later artifacts are coupled to newer Kotlin metadata. They must
move with Kotlin 2 rather than being forced into Kotlin 1.9.

### AndroidX Security Crypto

The catalog already uses stable 1.1.0. The lint baseline still contained an old
alpha06 message and is stale. `MasterKey` and `EncryptedSharedPreferences` are
now deprecated APIs; replacing them is a security-storage migration, not a
routine version bump. It requires an authenticated data/key migration and
real-device upgrade tests. No plaintext fallback was introduced.

## Required follow-up process

1. Run dependency review and Trivy after every lock change.
2. Review generated verification checksums against trusted repositories.
3. Update one coupled dependency group per pull request.
4. Run all three Android flavor tests, lint, Detekt, coverage, instrumentation
   compile, R8 release, server PostgreSQL tests, and container scan.
5. Never refresh the lint baseline wholesale to hide new dependency findings.
6. Record breaking changes and rollback instructions in `CHANGELOG.md`.
