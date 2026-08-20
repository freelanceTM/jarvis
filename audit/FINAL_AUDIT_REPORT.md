# Финальный QA / code / security audit — JARVIS

Дата: 2026-08-20
Исходный revision: `9196b97` (`main`, shallow checkout)
Объём: Android-приложение + JVM API server, около 25.5k строк production-кода.

## 1. Repository overview

Монорепозиторий на Kotlin/Gradle 8.7, JDK 17:

- `:app`: Android 10+ (`minSdk 29`, `targetSdk 34`), Compose, Hilt, Room,
  DataStore, OkHttp, kotlinx.serialization/coroutines, MediaPipe LLM.
- `:server`: JVM 17, встроенный `HttpServer`, OkHttp, kotlinx.serialization,
  in-memory auth/rate/health/usage state.
- Android flow:
  `UI/Voice -> SendPromptUseCase -> AgentPipeline -> ExecutionDecisionEngine`
  -> device tools / on-device Gemma / workflow memory / cloud / cognitive agent.
- Server flow:
  `HTTP -> authentication -> authorization -> rate limiter -> AiRouter`
  -> `ProviderManager -> Groq/Gemini/OpenRouter -> usage + metrics`.
- Persistence: Room (`messages`, memories, facts, preferences, procedures,
  automations) and encrypted SharedPreferences for access/license data.
- External boundaries: JARVIS API, AI providers, Open-Meteo,
  DuckDuckGo/Wikipedia, Android ContentResolver, filesystem/model file,
  accessibility, telephony/SMS, Bluetooth, microphone, TTS/STT.
- Entry points: `MainActivity`, `JarvisVoiceService`, `SystemEventReceiver`,
  `JarvisAccessibilityService`, server `MainKt`.
- Server endpoints: `GET /v1/health`, `POST /v1/ai/execute`,
  `GET /v1/admin/metrics`.

Полный source inventory и статический package graph находятся в:

- `audit/REPOSITORY_INVENTORY.md`
- `audit/DEPENDENCY_GRAPH.md`
- `audit/dependencies.txt`
- `audit/CHANGE_MANIFEST.md`

## 2. Что исследовано и протестировано

Изучены production/test Kotlin-файлы, manifest/resources, Room schema v5,
Gradle/version catalog, ProGuard, network security config, README/docs,
benchmark, `.env.example`, GitHub Actions и lint baseline.

Проверены:

- маршрутизация device/local/cloud/agent, privacy и ambiguity;
- parser tool-calls, malformed/random input, limits;
- confirmation queue, token replay/mutation/concurrency, timeout/cancellation;
- automation matching, malformed schedule/conditions, cooldown concurrency;
- local AI input/config boundaries;
- server auth/authz, config, body validation, error normalization;
- provider request/response DTO, status classification, retry/fallback/timeout;
- circuit breaker CLOSED/OPEN/HALF_OPEN и конкурентные probes;
- rate limiting minute/day/boundaries/concurrency;
- usage retention/concurrency и metrics contention;
- log secret redaction и CR/LF log injection;
- реальный локальный HTTP smoke flow;
- Android manifest, exported components, permissions, CA trust;
- сборка app APK и androidTest APK;
- static secret scan, TODO/FIXME, ignored/skipped tests, dependencies.

Не выполнялись destructive действия и запросы к реальным AI/production
системам.

## 3. Tests added / strengthened

Итоговый net test count: **+67 JVM tests**:

- Android/JVM: 384 -> **420** (+36 net).
- Server: 34 -> **65** (+31).

Новые test-файлы:

- `ToolCallParserTest` — структуры, aliases, malformed members, braces/quotes,
  multiple objects, 1 MB limit, 1000 random malformed strings.
- `ToolRegistryValidationTest` — blank/duplicate IDs and ambiguous aliases.
- `ToolExecutorBehaviorTest` — timeout, cancellation and rollback.
- `LocalAiModelsBoundaryTest` — numeric/non-finite generation config and locale.
- `ContactResolverNumberTest` — phone normalization/validation/injection shapes.
- `PrivacyClassifierTest` — credentials/payment/medical/document patterns and false positives.
- `BoundedResponseBodyTest` — known/unknown-length and UTF-8 byte limits.
- `WebSearchToolNetworkSafetyTest` — oversized upstream fail-fast behavior.
- `AccessTokenPolicyTest` — exact client/server token-contract boundaries.
- `AuthConfigRateLimitTest` — auth/config/rate boundaries and concurrency.
- `HttpProvidersTest` — Groq/OpenRouter/Gemini formats and all status classes.
- `ObservabilityUsageTest` — redaction/log forging, bounded concurrent usage,
  atomic metrics.
- `ProviderResilienceTest` — manager timeout and HALF_OPEN probe concurrency.

Существенно усилены существующие tests для routing, privacy, benchmark,
license fail-closed, automation, confirmation token binding и UTF-8 body limits.
Instrumented test methods переименованы, чтобы D8 мог собрать test APK.

## 4. Bugs found and fixed

### B01 — Critical — license/payment bypass

- **Component:** Android license/subscription.
- **Reproduction:** при недоступном license endpoint локальный checksum принимал
  генерируемые коды; remote config отдавал master-коды клиенту; кнопка «50 TMT»
  локально добавляла 30 дней без оплаты; reset стирал one-time state.
- **Root cause:** клиент считался источником доверия для лицензии и billing.
- **Fix:** удалены checksum, client-side master-code list, local subscription
  extension и reset API. Все коды валидирует сервер; outage — `ServiceUnavailable`
  и fail-closed. Продление UI отключено до server-side billing.
- **Regression:** rewritten `LicenseCodeValidatorTest`.
- **Remaining:** `/v1/license/validate` ещё не реализован, поэтому активация
  сейчас честно недоступна, а не обходится локально.

### B02 — High — confirmation token не был привязан к операции

- **Component:** `ToolExecutor`.
- **Reproduction:** поставить SMS в очередь, взять token, создать другой
  `ToolCall` с тем же `callId`, но новым recipient/message/tool — выполнялась
  изменённая операция. Concurrent replay мог пройти более одного раза.
- **Root cause:** проверялись только `callId + token`; составные queue operations
  не были атомарными.
- **Fix:** exact immutable `ToolCall` equality, synchronized enqueue/consume/
  remove/clear, одноразовое атомарное потребление.
- **Regression:** mutation и 64-way replay tests.

### B03 — High — опасные false-positive device routes

- **Component:** `MediaIntentParser`, `FastCommandRouter`, `ScenarioMatcher`.
- **Reproduction:** «Когда следующий матч?» переключал трек; «найди в интернете
  диагноз» открывал Wi-Fi; `еду` совпадало внутри «следующий» и запускало driving
  agent; «финансовая отчётность» запускала диагностику телефона.
- **Root cause:** unrestricted substring matching.
- **Fix:** standalone/media context rules, explicit connectivity intent,
  specific scenario phrases, corrected driving/sleep/power-saving matching.
- **Regression:** parser/router/scenario tests and 100-case benchmark.

### B04 — High — benchmark позволял известные опасные регрессии

- **Component:** benchmark regression gate.
- **Reproduction:** suite был green при 73% accuracy, 4 device false positives и
  13 false-local.
- **Root cause:** thresholds равнялись плохому baseline.
- **Fix:** complexity policy for 1B model, clarification flow, empty validation;
  thresholds increased to 99%, zero device false positives and zero false-local.
- **Result:** **73% -> 99%**, DEVICE/CLOUD/WEB/LOCAL/PRIVACY/AMBIGUOUS 100%.

### B05 — High — circuit breaker probe storm / stuck HALF_OPEN

- **Component:** server provider health/selection.
- **Reproduction:** после cooldown concurrent requests одновременно проходили
  HALF_OPEN; selection меняла state даже для provider за пределом attempts.
- **Root cause:** side effect в `isAvailable`, no atomic probe reservation.
- **Fix:** side-effect-free eligibility + atomic `tryAcquire`; sequential
  half-open successes; only real attempts counted.
- **Regression:** 200 concurrent acquisitions and blocked concurrent requests.

### B06 — High — ProviderManager не гарантировал timeout

- **Component:** server provider orchestration.
- **Reproduction:** custom/future provider suspends indefinitely despite manager
  timeout responsibility.
- **Root cause:** only OkHttp implementation had timeout.
- **Fix:** manager-level `withTimeout` from provider config; cancellation remains
  structured; fallback occurs after timeout.

### B07 — High — log injection and ineffective secret sanitizer

- **Component:** server structured logger.
- **Reproduction:** requestId with CR/LF forged a second log line; Bearer/key in a
  field was not automatically redacted.
- **Root cause:** `LogSanitizer` existed but logger did not invoke it.
- **Fix:** central secret + control-character sanitization for message/fields.

### B08 — High — provider response memory DoS

- **Component:** server `OkHttpTransport`.
- **Reproduction:** upstream could return an unbounded body read by `.string()`.
- **Fix:** content-length and streamed hard limit of 1 MiB.

### B09 — High — Android trusted user-installed CAs in release

- **Component:** `network_security_config.xml`.
- **Impact:** a user CA could MITM the JARVIS Bearer token.
- **Fix:** release trust anchors now use system CAs only; lint baseline entry
  `AcceptsUserCertificates` removed.

### B10 — High — automation race and false success

- **Component:** `PersonalAutomationEngine` / `RuleEvaluator`.
- **Reproduction:** duplicate broadcasts could both pass stale cooldown; failed,
  permission-blocked or confirmation-blocked action still recorded trigger and
  spoke success; malformed condition executed fail-open.
- **Fix:** serialized event critical section, volatile initialization,
  record/announce only complete success, cancellation propagation, malformed
  conditions/schedules fail closed.

### B11 — High — BroadcastReceiver async work was unreliable / wrong events

- **Component:** `SystemEventReceiver`.
- **Reproduction:** coroutine launched after `onReceive` without `goAsync` could
  be killed; any Bluetooth ACL device counted as headphones; Wi-Fi adapter
  enabled counted as connected.
- **Fix:** `goAsync().finish()`, audio-class filter, active Wi-Fi network check,
  corrected broadcast action.

### B12 — High — contact ambiguity could call/SMS wrong person

- **Component:** `ContactResolver`, Call/SMS.
- **Reproduction:** partial `LIKE` selected first matching contact silently.
- **Fix:** exact-name priority, distinct-number collection, explicit
  `AMBIGUOUS_CONTACT`, normalized phone regex, no send/call until clarified.

### B13 — High — UI automation contract was broken and under-protected

- **Component:** Fast router / accessibility tools.
- **Reproduction:** router emitted `target`, tool required `target_text`; scroll
  emitted action that tool ignored; typed secret appeared in log/result.
- **Fix:** aligned schema, implemented scroll, confirmation required for typing,
  text-bound confirmation preview, no typed payload in log/result, 10k limit.

### B14 — High — androidTest APK could not be dexed

- **Component:** 13 instrumented tests.
- **Reproduction:** D8 rejected Kotlin method SimpleNames containing spaces.
- **Fix:** Java-compatible method names. `assembleDebugAndroidTest` now passes.

### B15 — Medium — ToolCall parser fragile/unbounded

- **Reproduction:** first `{` + last `}` glued objects; one malformed array item
  discarded valid calls; huge output allocated/parsing unbounded.
- **Fix:** quote-aware balanced scanner, per-item validation, 1M char limit.
- **Regression:** deterministic malformed cases + 1000-input fuzz loop.

### B16 — Medium — usage retention was O(n), racy and pathological

- **Component:** server in-memory usage.
- **Reproduction:** `ConcurrentLinkedQueue.size` scans 10k records per request;
  negative capacity could loop forever; concurrent trimming produced wrong size.
- **Fix:** validated capacity, synchronized O(1) counter/trim, safe negative limit.

### B17 — Medium — config accepted unsafe resource/credential values

- **Component:** server config.
- **Fix:** validation for port, timeouts, breaker, rate, generation, body <=10 MiB,
  token >=32 chars, client ID, duplicate tokens; updated `.env.example`.

### B18 — Medium — cancellation could become success/failure and skip rollback

- **Component:** ToolExecutor/DecisionEngine/Automation.
- **Fix:** propagate `CancellationException`; rollback executed in
  `NonCancellable` context for completed workflow steps.

### B19 — Medium — accessibility traversal/log memory/privacy

- **Fix:** iterative traversal, max 2000 nodes/20k chars, typed text never logged,
  removed unused quick-settings bypass helper and unused neural TTS client.

### B20 — Medium — AlarmClock tool missing permission and accepted overflow ranges

- **Fix:** `SET_ALARM` manifest permission, type/hour/timer bounds and safe label.

### B21 — Medium — SMS could create unbounded paid multipart messages

- **Fix:** hard limit 1600 characters plus existing explicit confirmation.

### B22 — Medium — duplicate tool IDs/aliases silently overrode registry

- **Fix:** fail-fast validation; unused raw registry execution bypass removed.

### B23 — Low — HTTP/rate/body boundary defects

- UTF-8 byte limit now uses bytes rather than UTF-16 chars.
- known health endpoint wrong method returns 405, not 404.
- `Retry-After` rounds up rather than under-reporting by one second.
- body decoding explicitly uses UTF-8.
- invalid local AI numeric values/non-finite floats fail fast; formatting is
  locale independent.

### B24 — High — client/server trusted a false `NORMAL` privacy label

- **Reproduction:** a request such as `мой пароль от банка: 4821-secret` with
  the default `NORMAL` label reached Cloud AI; its literal text was also eligible
  for Android debug logging before routing.
- **Root cause:** privacy was entirely caller-supplied; server only checked the
  enum sent by the client.
- **Fix:** conservative Unicode-normalized classifiers now run independently on
  Android and server. Explicit/detected levels can only be strengthened. The
  effective level controls log redaction, local quality routing and cloud gate.
  General questions such as «как сменить пароль» remain `NORMAL`.
- **Regression:** classifier positive/negative/boundary tests plus full HTTP
  integration proving a falsely labelled credential never reaches a provider.

### B25 — High — `WorkingMemory` concurrent map corruption

- **Reproduction:** 12 concurrent dialog/observation workers caused an assertion
  failure through `LinkedHashMap` races/iteration while mutation.
- **Root cause:** access-order `LinkedHashMap` and compound context updates were
  unsynchronized although observation runs on `Dispatchers.IO`.
- **Fix:** all cache and compound context operations are serialized on the
  singleton monitor; volatile immutable context snapshots remain cheap to read.
- **Regression:** 24,000 concurrent put/get/context updates, exception-free and
  bounded to 128 entries.

### B26 — High — Android network responses were unbounded and leaked connections

- **Components:** JARVIS API, web search, weather, license validator.
- **Reproduction:** `.string()` allocated an arbitrary known or chunked/gzipped
  upstream body; WebSearch responses were not closed on all paths. Synchronous
  calls could also exceed their tool-level coroutine timeout.
- **Fix:** streamed decompressed-byte limits (64 KiB–1 MiB), `use` on every
  response, 3.5–4 s per-request search/weather call timeouts, 35 s API total
  call timeout, and explicit oversized-response errors.
- **Regression:** known length, unknown length, multibyte UTF-8 and 512 KiB
  upstream tests.

### B27 — Medium — Android accepted tokens rejected by the server

- **Reproduction:** Android considered a 10-character value configured while the
  server requires at least 32; weak values were sent repeatedly and settings
  still reported success.
- **Fix:** shared semantics of 32–256 non-whitespace/control characters, UI
  validation feedback, local request rejection, server max/character bounds.
- **Regression:** 31/32/256/257 and whitespace/control tests on both sides.

## 5. Security findings

### Fixed

- Critical local license/payment bypass.
- Confirmation mutation/replay/race.
- User-CA token MITM exposure.
- Log forging and secret leakage in server logs.
- Weak/duplicate static API tokens.
- Upstream response memory DoS.
- Wrong-recipient contact ambiguity and unbounded SMS segments.
- Sensitive accessibility text logging.
- Automatic Android + server credential/payment/medical privacy gates.
- Client/server access-token length and character-policy mismatch.
- Bounded Android response reads and deterministic network call deadlines.
- No hardcoded real secret found; matches are fake redaction-test values only.

### Remaining

1. **High functional/security dependency:** real license endpoint and server-side
   issuance/one-time/billing persistence do not exist. Client is fail-closed.
2. **Medium detection limit:** automatic privacy classification is deliberately
   conservative and pattern-based; novel/obfuscated secrets may still require an
   explicit PRIVATE/SENSITIVE label from the caller.
3. **Medium:** server is plain HTTP and requires correctly configured TLS reverse
   proxy; Bearer tokens are unsafe without it.
4. **Medium:** server auth/rate/circuit/usage are in-memory and per-instance;
   horizontal deployment needs shared state.
5. **Medium:** accessibility screen reader remains intrinsically powerful; it
   needs device-level/manual privacy testing across banking/password screens.
6. **Medium supply chain:** CI actions use version tags rather than immutable
   commit SHAs; dependency/SBOM/CVE scanning is absent.
7. Only the shallow checked-out revision was scanned; full Git history was not
   available for historical secret scanning.

## 6. Performance findings

- Fixed O(n) usage trimming and unbounded provider body.
- Concurrent stress tests: 100 rate calls, 1000 usage writes, 2000 metric updates,
  200 HALF_OPEN acquisitions.
- Parser fuzz: 1000 random inputs up to 2000 chars; hard max 1M chars.
- Routing benchmark, 300 samples (simulated model/network):
  p50 476 ms, p90 607 ms, p95 610 ms, max 2277 ms.
- Latest debug APK: **140,940,756 bytes** (~134.4 MiB); test APK 870,595 bytes.
  MediaPipe native libs dominate; model (~529 MB) is external.
- WebSearch/Weather still use blocking synchronous OkHttp internally, but every
  call now has a deadline and every response has a decompressed-byte limit.
  Physical battery/RAM/CPU/thermal and actual provider latency were not measured.

## 7. Test/check results

| Check | Result |
|---|---|
| Android JVM tests | **420 passed, 0 failed, 0 skipped** |
| Server JVM unit/integration | **65 passed, 0 failed, 0 skipped** |
| Total executed JVM | **485 passed** |
| Instrumented sources | 13 tests compiled into APK; not executed |
| Benchmark | **99/100**, 0 device FP, 0 false-local |
| Android Lint debug | passed; 0 new findings, 82 baseline findings filtered |
| Debug APK | passed |
| androidTest APK | passed |
| Server build/distributions | passed |
| Real local HTTP smoke | 200/401/503/**403 auto-privacy**/400/405/413 as expected |
| Secret scan | no real key/private key found |
| Release compile + lintVital | passed |
| Full release APK packaging | not completed: `mergeExtDexRelease` exceeded the 2 GB sandbox memory limit |
| connectedDebugAndroidTest | not run: no emulator/device (`adb devices` empty) |

The first release packaging failure and several earlier Gradle daemon deaths were
resource failures in this 2 GB/no-swap sandbox, not test assertion failures.
Debug and test APK dex/package steps succeeded after isolated low-worker runs.

## 8. Test quality assessment

Strengths:

- Core routing, planner, local AI, memory matching and server orchestration now
  have meaningful state/error/concurrency assertions.
- No ignored/skipped JVM or instrumented tests found.
- Benchmark assertions are no longer a weak green baseline.

Weaknesses:

- No JaCoCo/Kover, detekt or ktlint configured; exact line/branch coverage unknown.
- Latest heuristic scan found 79/173 Android production Kotlin files without
  direct class-name references in tests. Many are UI/hardware/Android boundary classes.
- Existing `LicenseManagerTest` still tests duplicated arithmetic rather than
  constructing encrypted Android implementation.
- Some planner tests historically assert only non-null plans.
- UI/ViewModel/repository/DataStore/network-client and most device tools lack
  direct JVM/instrumented assertions.

## 9. Remaining functional risks

1. `onTimeSchedule()` has no actual scheduler/alarm caller; scheduled automation
   rules are not end-to-end operational.
2. Room schema history 1-4 is missing; upgrades from those versions cannot be
   validated and may fail to open.
3. Real device flows unverified: permissions, call/SMS, accessibility, Bluetooth
   SCO, microphone foreground service, wake locks, STT/TTS, OEM background rules.
4. Real MediaPipe model installation/inference and cancellation not run.
5. Real Groq/Gemini/OpenRouter formats were tested through fake transport, not
   live credentials/endpoints.
6. Arbitrary LLM multi-step planning remains incomplete: benchmark AGENT-010
   goes to cloud, yielding the single remaining routing miss.
7. The production API/license host is hardcoded; there is no environment-specific
   Android base URL or local integration flavor.
8. Release minification is disabled despite ProGuard rules; release artifact is
   large and easier to reverse engineer.
9. 82 lint baseline entries remain (mostly dependency updates, obsolete checks,
   unused resources); dependencies are materially behind 2026 versions.
10. No LICENSE, CHANGELOG, SECURITY.md or contribution/security disclosure policy.

## 10. Final assessment

**Server core:** good after fixes for a single-instance prototype; robust unit/
integration coverage of auth, validation, rate, provider failures, timeout,
fallback, health and logging. Not production-ready for multi-instance operation
without shared persistence/TLS deployment controls.

**Android core logic:** substantially improved. Routing safety moved from 73% to
99%, dangerous device false positives are zero in the benchmark, confirmation
and licensing fail closed, and debug/test packaging is healthy.

**Android product readiness:** still beta/prototype. Hardware/UI/network edges
are under-tested, the license backend and scheduled job are missing, and real
physical-device performance/reliability remains unknown.

Overall: **core logic B / security posture B- / Android E2E confidence C-**.
The repository is much safer and more testable, but green JVM tests must not be
interpreted as proof of production-ready voice/device behavior.
