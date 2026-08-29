# Test quality and coverage

## Scope

The historical “files without a direct test reference” count is only a source
search heuristic. It is not statement, branch, line, or behavioral coverage and
must not be published as a percentage.

Phase 3 uses JaCoCo 0.8.11 for factual JVM coverage and Detekt 1.23.8 for Kotlin
static analysis.

## Commands

Android JVM coverage:

```bash
bash ./gradlew :app:jacocoDevDebugUnitTestReport
```

Outputs:

- `app/build/reports/jacoco/devDebug/jacoco.xml`;
- `app/build/reports/jacoco/devDebug/jacoco.csv`;
- `app/build/reports/jacoco/devDebug/html/index.html`.

Server JVM coverage (requires the configured PostgreSQL test service for the
full server suite):

```bash
bash ./gradlew :server:jacocoTestReport
```

Outputs:

- `server/build/reports/jacoco/test/jacocoTestReport.xml`;
- `server/build/reports/jacoco/test/jacocoTestReport.csv`;
- `server/build/reports/jacoco/test/html/index.html`.

Combined entry point:

```bash
bash ./gradlew phase3Coverage
```

Static analysis:

```bash
bash ./gradlew phase3StaticAnalysis
```

Full quality entry point:

```bash
bash ./gradlew phase3Quality
```

## Android instrumented coverage

Android instrumented coverage is configured for every debug flavor through
`enableAndroidTestCoverage`. It cannot be produced without a real adb target or
emulator.

With a connected target:

```bash
bash ./gradlew :app:createDevDebugCoverageReport
```

That task installs the devDebug app and test APK, runs instrumentation, collects
the device `.ec` data, and generates the AGP coverage report. Real model and
physical voice tests remain explicitly gated; ordinary device coverage must not
claim those paths when their prerequisites are absent.

The `android-instrumentation` job in `.github/workflows/build.yml` provisions a
KVM-backed API 34 emulator and runs this coverage task. It completed successfully
in GitHub Actions run `32783667894` and published the
`android-api34-instrumentation` artifact. The separately installed real model and
physical voice/Bluetooth tests remained gated.

The earlier local software-emulation attempt is still not valid execution
evidence: `/dev/kvm` was absent, boot required approximately 21 minutes, and
Android `system_server` crashed before APK installation. Physical-device coverage
remains a separate follow-up even though the ordinary API 34 emulator gate is now
green.

## Coverage policy

- Generated Android `R`, BuildConfig, Hilt/Dagger, Room implementation, manifest,
  and kotlinx serializer classes are excluded from the custom app JVM report.
- Production DTOs, entities, repositories, ViewModels, tools, and business logic
  are not excluded merely because they are difficult to test.
- No arbitrary aspirational percentage was selected before measurement. After
  reviewing the comparable baseline and full PostgreSQL-backed server run, CI
  floors are 24% line / 20% branch for Android JVM and 80% line / 35% branch
  for server JVM. They prevent a collapse while leaving improvements to
  package-specific behavioral work.
- The Phase 3 report records baseline and post-test metrics. Future changes
  should not reduce line or branch coverage without an explicit explanation.
- Security-critical packages should receive package-specific behavioral goals;
  a high global percentage is not a substitute for permission, privacy,
  persistence, cancellation, and error-path tests.

## Measured Android JVM coverage before and after Phase 3 tests

For a comparable baseline, the 443 retained pre-Phase-3 behavioral tests were
run against the same production classes, JaCoCo 0.8.11, and generated-code
exclusions as the final 472-test suite. The removed arithmetic-only
`LicenseManagerTest` covered no production class and therefore does not affect
this JVM baseline.

| Counter | Baseline covered/missed | Baseline | Final covered/missed | Final | Change |
|---|---:|---:|---:|---:|---:|
| Instructions | 28,298 / 74,157 | 27.62% | 32,467 / 69,988 | 31.69% | +4.07 pp |
| Branches | 1,691 / 5,993 | 22.01% | 1,810 / 5,874 | 23.56% | +1.55 pp |
| Lines | 3,338 / 10,292 | 24.49% | 3,946 / 9,684 | 28.95% | +4.46 pp |
| Methods | 676 / 2,143 | 23.98% | 866 / 1,953 | 30.72% | +6.74 pp |
| Classes | 230 / 764 | 23.14% | 305 / 689 | 30.68% | +7.54 pp |

The final report is the maintained metric. Coverage improved because behavioral
boundaries were executed; no production package was excluded simply to raise the
percentage.

Selected final class line coverage:

| Production class | Covered lines |
|---|---:|
| `ActivationViewModel` | 48/58 (82.76%) |
| `SettingsViewModel` | 95/98 (96.94%) |
| `JarvisApiClient` | 67/84 (79.76%) |
| `MessageRepositoryImpl` | 24/25 (96.00%) |
| `SettingsRepositoryImpl` | 43/43 (100.00%) |
| `GetBatteryTool` | 31/31 (100.00%) |
| `GetNetworkStatusTool` | 30/33 (90.91%) |
| `GetTimeTool` | 26/26 (100.00%) |
| `SetVolumeTool` | 44/52 (84.62%) |
| `FlashlightTool` | 37/38 (97.37%) |
| `OpenAppTool` | 57/101 (56.44%) |

## Measured server JVM coverage

The full ordinary 146-test server suite (excluding the explicitly opt-in live
provider smoke test), including 33 PostgreSQL-backed tests against an isolated
local PostgreSQL 17 database, measured:

| Counter | Covered | Missed | Coverage |
|---|---:|---:|---:|
| Instructions | 25,807 | 8,880 | 74.40% |
| Branches | 1,438 | 1,033 | 58.20% |
| Lines | 3,101 | 578 | 84.29% |
| Methods | 847 | 224 | 79.08% |
| Classes | 235 | 28 | 89.35% |

All 146 tests passed with no failures, errors, or skips. The maintained report is
`server/build/reports/jacoco/test/jacocoTestReport.xml`; CI provisions the same
PostgreSQL service and enforces the line and branch floors.

## Priority uncovered areas identified from the baseline

- all ViewModels and Compose presentation code: approximately 0% JVM line
  coverage;
- `LicenseManagerImpl`: 0/127 covered lines;
- `JarvisApiClient`: only constants loaded, request/error paths untested;
- `MessageRepositoryImpl` and `SettingsRepositoryImpl`: 0%;
- `SettingsDataStore`: 0% JVM coverage;
- most system/device/communication/productivity tools: 0%;
- MediaPipe, voice service, STT/TTS, Bluetooth, Room, and Android framework paths:
  device/instrumentation responsibilities rather than fake JVM evidence.

Phase 3 adds tests to these boundaries based on risk, not simply line count.
