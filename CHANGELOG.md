# Changelog

All notable repository changes are recorded here. The project did not contain a
release changelog or authoritative release tags in the provided workspace, so
entries are grouped under **Unreleased** rather than inventing published
versions or dates.

The format is based on Keep a Changelog, but semantic-version release history
must be added only when the repository owner creates an actual release.

## [Unreleased]

### Added

- Admin Panel: аудит control plane против MVP-дерева (docs/CONTROL_PLANE.md
  §12) — панель уже покрывает Dashboard/Users(+devices,subscription)/Devices/
  Licenses/AI(providers,health,usage)/Requests(cloud)/System в одном JVM без
  BI/CRM/K8s. Закрыт единственный пробел дерева: список лицензий без фильтра
  active/expired — `GET /v1/admin/licenses?status=` (bind-param SQL, неизвестный
  статус = 400, не тихий «показать всё»), UI-вкладки ALL/ISSUED/ACTIVE/EXPIRED/
  REVOKED/DISABLED. 2 теста (surface), 6 ADMIN-инвариантов (137 всего).
- Agent Core: принцип «не использовать агента там, где достаточно Tool»
  (docs/AGENT_CORE.md). Одиночный tool_call из ответа облачной модели больше
  не запускает cognitive loop — идёт прямым путём команды устройства
  (privacy gate → policy → честный итог, `CLOUD_PLAN_SINGLE_TOOL`); агент
  (Plan→Act→Observe→Verify→Replan, MAX_REPLANS=2, бюджет 8 c) остаётся для
  многошаговых планов. Закрыта дыра policy: fail-closed гейт внешнего
  раскрытия (`mayDiscloseExternally`) теперь применяется и к plans из ответа
  модели — раньше он был только у детерминированных планов (LLM предлагает —
  policy решает). 3 теста (одно- vs многошаговый cloud-план, privacy-гейт),
  8 AGENT-CORE-инвариантов (131 всего).
- Memory: три уровня (Conversation / Session / Long-term) + retrieval-стадия
  перед LLM — «Query → Memory retrieval → Relevant memories only → AI»
  (docs/MEMORY.md). Главный фикс: `buildPromptMemoryContext()` существовал,
  но не вызывался ни разу — long-term память доходила до модели только через
  явный RecallMemoryTool (лишний round-trip). Теперь `SendPromptUseCase`
  кладёт top-3 релевантных воспоминаний (≤800 символов) в новый
  `ExecutionRequest.memoryContext`; cloud executor добавляет блок к
  systemPrompt, локальный промпт-билдер — офлайн-модели (recall без сети).
  Память включена в privacy-классификацию: приватный факт из памяти не уходит
  в облако под безобидным запросом. 7 тестов, 7 MEMORY-инвариантов (123 всего).
- Ear Mode: аудит и фиксы прерываний непрерывного переводчика
  (Clip → Bluetooth → SCO → STT → перевод → TTS → Clip), docs/EAR_MODE.md.
  Audio focus: `TextToSpeechManager` держит `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`
  (USAGE_ASSISTANT) на время речи, LOSS → stop — музыка/нотификации больше не
  играют поверх перевода (раньше `requestAudioFocus` не вызывался нигде).
  Disconnect/reconnect: `routeAudioToEarbud` без гарнитуры больше не ставит
  `MODE_IN_COMMUNICATION`+SCO (было — на каждую фразу), выбор устройства
  BT-first (SCO/BLE важнее проводных), ACL-коннект считается наушником только
  при реальном аудиовыходе (часы/машина больше не перехватывают роутинг).
  Phone call: `resumeAfterPhoneCall` восстанавливает `LIVE_EAR_INTERPRETER`,
  экран переводчика ставит/снимает паузу через `orchestrator.currentMode`.
  Microphone: экран переводчика останавливает wake-word AudioRecord на время
  слушания и возвращает его сервису (конфликт захватчиков с Android 10).
  Speaker: выход из Ear Mode/пауза/закрытие экрана возвращают аудиорежим к
  дефолту (`restoreDefaultRouting`). 12 EAR-MODE-инвариантов (116 всего),
  тест политики focus.
- Battery: idle-выгрузка тяжёлой модели («return idle»). `MediaPipeModelManager`
  больше не держит модель (~529 МБ) резидентной навсегда после первого
  инференса (раньше unload был только по memory pressure): новый
  `IdleUnloadScheduler` выгружает её после 5 минут неактивности
  (`modelIdleUnloadMs`); каждый запрос через `runtimeOrNull()` продлевает окно
  (`noteUsed()`), следующий после паузы — лениво перезагружает (~1–3 c). Окно
  больше худшего tool-таймаута (≤4 c) — выгрузка не может закрыть движок
  посреди генерации; `close()` отменяет таймер. Тесты на виртуальном времени
  (runTest). Фазовый аудит батареи (Idle/Wake/Listening/Local
  inference/Bluetooth/TTS/Background): docs/BATTERY.md; 6 BATTERY-инвариантов.
- Voice Latency (`VoiceLatencyMetrics`): сегменты голосового пайплайна
  Wake→STT→Router→AI→Tool→TTS с P50/P95/P99 (кольцевой буфер 256/серия,
  monotonic clock) и обязательным разрезом LOCAL/CLOUD для AI-фазы — видно
  реальную разницу локального и облачного ответа. Точки: оркестратор
  (wake/STT/TTS), execution engine (STT→Router через новый
  `ExecutionRequest.originTimestampMs`, dispatch полосы, длительность AI и
  Tool). На роутинг не влияет. 8 тестов (перцентили, разрез, кольцо,
  интеграция по полосам); 5 VOICE-LATENCY-инвариантов. См. docs/LOCAL_AI.md.
- Cost Control (server): Request → Cost estimation → Budget policy →
  Provider. Класс сложности запроса (SIMPLE/MEDIUM/HARD) детерминирован по
  форме (длина промпта/истории, requiresWeb — `RequestCostEstimator`, без
  LLM); класс через `ProviderRequirements.costClass` переключает веса
  Smart-политики отбора: SIMPLE — цена доминирует (0.5, «cheapest
  acceptable») + урезанный бюджет ответа (≤256 токенов), HARD — качество
  доминирует (надёжность 0.5, цена исключена, «best quality»), MEDIUM —
  прежний баланс. Полная картина «Simple → Local → $0»: короткие команды
  остаются на устройстве (полосы LOCAL TOOL/AI), серверная классификация
  защищает дошедшие запросы. Тесты: классификатор + переключение победителя
  классом при одной и той же раскладке метрик (8 сценариев); 6
  COST-CONTROL-инвариантов. См. docs/CLOUD_ROUTER.md.
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
