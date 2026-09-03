# Changelog

All notable repository changes are recorded here. The project did not contain a
release changelog or authoritative release tags in the provided workspace, so
entries are grouped under **Unreleased** rather than inventing published
versions or dates.

The format is based on Keep a Changelog, but semantic-version release history
must be added only when the repository owner creates an actual release.

## [Unreleased]

### Added

- Контракт Fallback-бюджета (server): худший случай платных вызовов на одну
  команду задокументирован и закреплён — maxProviderAttempts=2 ×
  (1+maxRetriesPerProvider=0) = 2 ≤ 3 (spec «max 2–3 attempts»); 429 не
  ретраится у того же провайдера (fallback к следующему), retry только для
  TIMEOUT/CONNECTION/SERVER_ERROR, AUTH/NOT_CONFIGURED permanent; клиент
  повторов не делает (не умножает платные вызовы). Новые тесты: конечность
  комбинированных retry×fallback (2×2), дефолт = ровно 2 платных попытки
  (третий провайдер не трогается); 5 FALLBACK-инвариантов.
  См. docs/CLOUD_ROUTER.md «Fallback и бюджет попыток».
- Smart Cloud Router (server): выбор лучшего провайдера по измерениям, а не
  random/статический приоритет. `ProviderPerformanceTracker` хранит на каждого
  провайдера latency (EMA успешных вызовов), errors (success rate), 429
  (RATE_LIMITED счётчик и доля); availability — circuit breaker
  (`ProviderHealthTracker`), cost — конфигурируемые цены USD/1М токенов из
  admin settings (секция cost, читаются на каждый отбор без рестарта).
  `SmartProviderSelectionPolicy`: score = 0.35·latency + 0.35·reliability +
  0.15·(1−429share) + 0.15·cost, min-max нормализация, нейтраль при
  недостатке данных, tie-break и cold start — статический приоритет
  (поведение до порога 5 измерений не меняется). `ProviderManager` —
  единственная точка записи исходов. Тесты: 9 сценариев Smart Router;
  6 SMART-ROUTER-инвариантов. См. docs/CLOUD_ROUTER.md.
- Метрики Local-first ExecutionRouter (`ExecutionRouterMetrics`):
  total_requests / tool_requests / local_requests (+agent/direct) /
  cloud_requests / failed_local / cloud_escalations и проценты
  Local Execution % / Cloud Execution %; целевой ориентир первой версии
  60–70%+ — метрика, не жёсткое правило (на роутинг не влияет). Сводка в лог
  каждые 25 запросов; эскалация считается только когда локальная полоса была
  реально опрошена (skip по requiresWeb — не эскалация). 7 JVM-тестов
  подсчёта в ExecutionDecisionEngineTest.
- Local-first перевод (`LocalLlmTranslationProvider`): короткие реплики
  живого переводчика обрабатываются on-device Gemma (полоса LOCAL AI
  ExecutionRouter), длинные документы (>500 символов) и «модель не готова»
  честно уступают облаку (движок провайдеров уже сортирует offline-first).
  Приватный бонус: PRIVATE/SENSITIVE тексты, заблокированные облачным
  провайдером (C-02), локально переводятся без сети. ExecutionRouter-маппинг
  (LOCAL TOOL / LOCAL AI / CLOUD) задокументирован в docs/LOCAL_AI.md §0;
  тесты локального провайдера и каскада local→cloud; инварианты.
- Криптографическая привязка OMNIX Clip (server V008 + Android core/clip):
  идентичность = пара ключей EC P-256 (не имя/MAC). Производство регистрирует
  публичный ключ (`/v1/admin/clips/provision`; serial, public key, owner,
  license/account, status в clip_devices). Подключение: server-challenge
  (single-use, TTL 120s) → Clip подписывает каноническое сообщение
  (JARVIS-CLIP-ATTEST-v1) → сервер проверяет подпись зарегистрированным
  ключом → VALID + первая привязка владельца; revoked/чужой аккаунт/чужой
  ключ/replay — отказ. Android: ClipAttestationProtocol/ClipIdentityVerifier
  (fail-closed ECDSA), EncryptedClipTrustStore (ключ закрепляется только из
  серверных ответов), ClipAttestationManager (онлайн-сервер / офлайн-локально
  со свежестью), ClipTransport — честный контракт для firmware (фейковой
  реализации нет: TransportUnavailable, никогда не фейковый VALID). Тесты:
  интеграция на реальном Postgres (6 сценариев), JVM-тесты Android-стека;
  8 CLIP-инвариантов. См. docs/OMNIX_CLIP_BINDING.md.
- Device binding API-токенов (server V007): клиент — не источник истины.
  jrv_-токен привязывается к устройству при redeem (`api_tokens.device_hash`);
  AI-исполнение проверяет `X-Jarvis-Device` на КАЖДОМ запросе
  (`LicenseTokenAuthenticator.authenticate(header, deviceHeader)`): нет
  заголовка — отказ, чужое устройство — отказ, украденный токен бесполезен.
  Legacy-токены (до V007) на AI-пути отвергаются и само-залечиваются при
  первом успешном `/v1/license/validate` (клиент всегда проходит его до
  разблокировки UI); привязка одноразовая. entitlement по-прежнему
  перечитывается с сервера на каждом запросе (лицензия/план/биллинг/срок).
  Клиент: `AuthInterceptor` шлёт `X-Jarvis-Device` (тот же device id, что в
  redeem/validate). Тесты: биндинг + само-залечивание в
  LicenseApiIntegrationTest; 6 LICENSE-инвариантов. См. docs/LICENSE_BILLING.md.
- Accessibility Lockdown: экранный контент не покидает устройство — пайплайн
  Accessibility → capture UI → privacy filter → LLM вместо «полный экран →
  Cloud LLM». Слой 3: контентный санитайзер `ScreenTextSanitizer` (OTP 6–8
  цифр, короткие коды в код-контексте, картоподобные 13–19 цифр → «••••» на
  этапе capture; время/суммы/телефоны не трогает). Слой 4: маркер
  `containsScreenContent` через PlanExecutionSummary → ExecutionResult →
  PromptExecutionResult; в БД сообщений (SendPromptUseCase + bypass в
  ChatViewModel) пишется placeholder вместо текста экрана — история чата
  больше не несёт экральный контент в облачный запрос. `SENSITIVE_PACKAGE_HINTS`
  расширен реальными пакетами: Chase/Tinkoff/Sber/Privat24 (банки без «bank»),
  PayPal/Coinbase/Binance/Revolut/Venmo/CashApp/Alipay/Samsung Pay/GPay,
  Authy/FreeOTP/Aegis/Steam Guard. Документация — `docs/ACCESSIBILITY_PRIVACY.md`;
  5 A11Y-инвариантов; тесты реальных сценариев.
- Policy Engine безопасности действий (`agent/policy/`): LLM только предлагает
  действие (`ProposedAction` = toolId + arguments + origin), решение о риске и
  подтверждении принимает `ActionPolicyEngine` — категория по toolId, детектор
  денежных сумм (`MoneyAmountDetector`: «50 000», «50 тысяч», «$100», денежные
  глаголы), сопоставление доверенных контактов (`TrustedContactMatcher`),
  статический пол риска инструмента. Форсированные правила (нельзя отключить):
  деньги в исходящих сообщениях/платёжных инструментах, DELETE, accessibility-
  запись, AUTOMATION-происхождение для звонков/сообщений (S-3: триггер
  автоматизации не звонит/не пишет сам). Настраиваемые политики звонков и
  сообщений (ALWAYS/TRUSTED_ONLY/NEVER, MONEY_ONLY) + доверенные контакты
  (`ActionPolicySettings`, in-memory провайдер; UI/DataStore — следующий шаг).
  Интеграция: `ToolPermissionManager.preflight` (порядок capability →
  разрешения → политика), `ActionOrigin` протянут через `ToolExecutor.execute/
  executeAll`, `PersonalAutomationEngine` объявляет AUTOMATION. Документация —
  `docs/ACTION_POLICY.md`; 20+ JVM-тестов контрактов политики.
- Единый контракт Tool Registry 2.0 (`JarvisTool`): `requiredPermissions`
  (контрактный член; CapabilityAwareTool выводит его из capability-контракта,
  preflight блокирует plain-инструменты с невыданными разрешениями),
  `verify(arguments, draft)` (фаза Verification: ToolExecutor вызывает её после
  каждого успешного `execute()` внутри общего tool-таймаута; дефолт —
  pass-through) и `mapError(arguments, error)` (единый error mapping:
  SecurityException → PERMISSION_REQUIRED с объявленными разрешениями,
  ActivityNotFoundException → USER_ACTION_REQUIRED, остальное → FAILURE).
  Tier 1 (open_app, volume, brightness, alarm/timer, bluetooth, wi-fi) переведён
  на разделение фаз execute/verify; для bluetooth/wi-fi/open_app pass-through
  задокументирован (чтения самодостаточны / публичного API верификации нет).
- Execute → verify → SUCCESS: read-back верификация результатов инструментов.
  Новый чистый модуль `agent/tools/verification/ExecutionVerification.kt`
  (правила решения + поллинг) и покрытие в инструментах: громкость
  (`SetVolumeTool`, `MediaControlTool` — read-back `getStreamVolume`),
  яркость (`SetBrightnessTool` — возврат `putInt` + read-back), DND
  (`DoNotDisturbTool` — applied + current interruption filter), фонарик
  (`FlashlightTool` — подтверждение через `CameraManager.TorchCallback` +
  выбор камеры по признаку вспышки вместо «первой в списке»), буфер обмена
  (`ClipboardTool` — read-back записи, которая с API 29 может молча
  игнорироваться в фоне), будильник (`AlarmTimerTool` — подтверждение через
  `AlarmManager.nextAlarmClockInfo`, иначе `ALARM_UNVERIFIED`).

### Changed

- Инструменты больше не сообщают «готово» без подтверждения системы:
  `DoNotDisturbTool` без policy-доступа возвращает `USER_ACTION_REQUIRED`
  (раньше — `SUCCESS` с `actionRequiresUser = true`); громкость «громче» на
  максимуме — `FAILURE VOLUME_AT_LIMIT` (раньше — «Громкость увеличена»);
  `media.control` формулирует play/pause/next как отправленную команду
  плееру, а не неподтверждаемый результат; таймер — «отправлен в приложение
  часов» (публичного API верификации таймера нет); rollback громкости
  подтверждается read-back'ом.
- `FastCommandRouter`: предзаготовленные реплики для tool-путей переведены
  в intent-формулировки («Включаю фонарик», «Ставлю музыку на паузу»,
  «Устанавливаю громкость на N%») — итог всегда озвучивается из реального
  `ToolExecutionResult`; `scripts/verify-architectural-invariants.sh` получил
  VERIFY-гварды против регрессии (запрет result-формулировок до выполнения).
- Инвариант H-04 актуализирован после удаления `ManualWakeWordTrigger`/
  `MainViewModel` frontend-rebuild'ом `0e9bf4b` (до фикса CI-шаг инвариантов
  падал на HEAD): проверка теперь требует, чтобы пайплайн запускался только
  через `JarvisVoiceService`, а presentation-слой не вызывал его напрямую.
- `docs/ANDROID_CAPABILITIES.md`: раздел «Верификация результата» с таблицей
  механизма подтверждения и честного отказа по каждому инструменту.

### Added

- OMNIX Control Plane (merged from `feat/control-plane`): admin HTTP API with
  RBAC and audit log, admin sessions/passwords, settings and feature flags,
  provider runtime overrides, cost model, operational UI, `rawQuery` plumbing
  through `HttpRequestContext`, and `V006__control_plane.sql` migration.
  Covered by 44 new tests (unit, surface, integration, UI).

### Changed

- CI: `anchore/sbom-action` bumped 0.24.0 -> 0.24.2 (SHA-pinned; pin verified
  against the upstream `v0.24.2` tag object).

### Fixed

- Restored executable bits on `scripts/*.sh` and `device-validation/*.sh`
  that were dropped by the control-plane branch (CI invokes them via `bash`,
  but the manual on-device kit relies on the exec bit).

### Deferred (dependency bumps rejected during the 2026-09-01 branch audit)

- `kotlin 1.9.24 -> 2.4.10`, `ksp -> 2.3.11`, `coroutines -> 1.11.0`,
  `room -> 2.8.4`, `androidx.test:runner -> 1.7.0`: all five Dependabot PRs
  omit the matching `gradle/verification-metadata.xml` entries, so the build
  fails dependency verification. The Kotlin/KSP jumps additionally conflict
  with the pinned Compose compiler `1.5.14` (Kotlin 1.9.x). These must be
  redone as coordinated upgrades (toolchain + Compose compiler + lockfiles +
  verification metadata) rather than merged as-is.



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
### Removed

- Documentation cleanup: removed agent-session and meta documentation
  (`docs/AUDIT_2026_08_29.md`, `docs/OMNIX_V03_PLAN_VERIFICATION.md`,
  `docs/BENCHMARK.md` + `docs/benchmark/`, `docs/DEPENDENCY_UPDATE_PLAN.md`,
  `docs/SUPPLY_CHAIN_SECURITY.md`, `docs/EXECUTION_DECISION_ENGINE.md`,
  `docs/SERVER_AI_LAYER.md`, `docs/TEST_QUALITY.md`,
  `docs/ROOM_SCHEMA_POLICY.md`, `docs/PHASE2_DEVICE_AND_PROVIDER_VALIDATION.md`,
  `docs/ANDROID_ENVIRONMENTS.md`, `docs/adr/`). Operational docs kept:
  RUNBOOK, RELEASE, PRODUCTION_DEPLOYMENT, LICENSE_BILLING,
  ANDROID_CAPABILITIES, LOCAL_AI. The single-instance decision (former
  ADR-0001) is now stated inline in the enforcing code
  (`DeploymentSecurityConfig`, `PostgresSingleInstanceGuard`) and asserted
  by `SharedStateArchitectureTest` against code, not prose.

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
