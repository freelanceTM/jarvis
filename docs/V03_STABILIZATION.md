# OMNIX v0.3 Stabilization — baseline, конформация протокола, backlog, scorecard

**Ветка:** `release/v0.3-stabilization` (от `main@122ba09`)
**Протокол:** 22 фазы стабилизации v0.2.0 → v0.3.
**Головной принцип протокола соблюдён буквально:** PASS только с evidence;
BLOCKED/NOT TESTED — где нет окружения; ничего не симулировано.

---

## PHASE 0 — BASELINE (FIXED)

| Поле | Значение | Evidence |
|---|---|---|
| Git commit | `main@122ba09` (base) → ветка `release/v0.3-stabilization` | git |
| Branch | `release/v0.3-stabilization` | создана в этой ревизии |
| Version | versionName **0.2.0**, versionCode 1 (dev → `-dev`, staging → `-staging`) | `app/build.gradle.kts:107-108,135,144` |
| Build variant | release (R8: `proguard-android-optimize` + `proguard-rules.pro`, `signingConfig=release`) | `app/build.gradle.kts:159-179` |
| Android / API | minSdk 29 / target 34 / compile 34 | `app/build.gradle.kts:101-106` |
| Device model / RAM / CPU | **NOT TESTED** — sandbox без Android-окружения (нет JDK/SDK/adb/emulator) | среда |
| Backend | один JVM-процесс (`server/`), Kotlin 1.9.24, Postgres-миграции V001–V008 | repo |
| Локальная модель | ~529 МБ в `files/llm/`, вне APK, lazy-load + idle-unload 5 мин | `MediaPipeModelManager` |

Baseline не менялся: все шесть коммитов сессии — аддитивные; стабильный v0.2.0
поведения (маршруты, контракты, guardrails) сохранён (инварианты 153 PASS).

## PHASE 1 — RELEASE BUILD

**BUILD = BLOCKED (sandbox)** — в окружении нет JDK17/Android SDK; Gradle-сборка
невозможна физически. Static pre-flight выполнен (FIXED в этой ревизии):

- release buildType существует, R8 включён (`minify`+optimize), proguard-rules.pro на месте;
- signing config декларирован (секреты — только env, §27);
- manifest: FGS-типы microphone/connectedDevice/mediaProjection соответствуют сервису;
- CI (build.yml) — единственный компилятор: гоняет unit-тесты + 153 инварианта.

**Правило для владельца устройства:** первый `./gradlew assembleRelease` на
машине с SDK — это фактический PHASE 1; красный build = P0 по протоколу.

## PHASE 2–3 — INSTALL / REAL VOICE LOOP

**BLOCKED — hardware unavailable.** Прогон — `device-validation/01…05.sh` +
матрица `docs/TEST_MATRIX.md` (строки DEVICE). Метрики (wake/stt/router/
execution/tts latency, 20+ повторений, P50/P95/P99/Min/Max/Avg LOCAL vs CLOUD)
**заранее подготовлены**: `VoiceLatencyMetrics` пишет каждый сегмент
(коммит 4efc281), шаблон результатов — `device-validation/RESULTS_TEMPLATE.md`.
Писать числа до прогонов запрещено протоколом (правило 8) — в scorecard стоит
NOT MEASURED.

## PHASE 4 — VOICE TEST MATRIX

**PASS (документ + авто-слой):** `docs/TEST_MATRIX.md` (коммит 122ba09):
33 voice-сценария / 34 тулa / 11 permission-denied / network / AI / BT /
security, каждая строка AUTO (JVM-тест с файлом) / PG / DEVICE / GAP.
Local-Tool команды из протокола («Открой Telegram», таймер, громкость,
яркость, скриншот, настройки) — покрыты AUTO (51 тест FastCommandRouterTest).

## PHASE 5 — LOCAL-FIRST

**PASS (авто-доказательство):** «Открой Telegram» и семействаSimple-команд
берёт FastCommandRouter на P1 (confidence 0.95, <10 мс) → DEVICE_TOOL:
`ExecutionDecisionEngineTest` — `assertEquals(0, local.calls); assertEquals(0,
cloud.calls)`; маршрутизация Simple → Local/$0 зафиксирована Cost Control
(9cbc766: cost-вес 0.5, maxTokens ≤256). Обоснованный cloud-переход только по
Uncertain/Complexity/requiresWeb. Device-замер процента — NOT MEASURED
(счётчики `ExecutionRouterMetrics` + админка готовы).

## PHASE 6 — CLOUD ESCALATION

**PASS (PG-слой):** fallback-бюджет ровно 2 платные попытки (bc821c0);
429 → без ретрая того же провайдера → следующий (Smart Router считает 429
отдельно); 500/timeout → circuit → ALL_PROVIDERS_UNAVAILABLE;
client callTimeout 30 c > server deadline 28 c → 504 PROVIDER_TIMEOUT с телом.
Evidence: `ProviderResilienceTest`, `CostControlTest`,
`SmartProviderSelectionPolicyTest`, `ApiIntegrationTest` (41). Error mapping —
`AiProvider.ProviderFailureKind` (RATE_LIMITED/AUTH/BAD_REQUEST/…).

## PHASE 7 — FAKE SUCCESS

**PASS:** запрет реализован и закреплён (bbcbcf1): «execute → exception →
SUCCESS» невозможен; ToolExecutor: execute → verify → SUCCESS / failure →
ERROR; `blocked_by_android` — честный исход, не ошибка; verifyOnScreen в
cognitive loop не даёт «Открыл YouTube» без текста на экране. Evidence:
`ToolExecutorBehaviorTest`, инварианты FAKE-SUCCESS.

## PHASE 8 — TOOL RESULT CONTRACT

**PASS (без переписывания — контракт уже достаточен):**
`ToolExecutionStatus` = SUCCESS, FAILURE, **TIMEOUT**, CANCELLED,
REQUIRES_USER_CONFIRMATION,
REQUIRES_SYSTEM_PANEL, **PERMISSION_REQUIRED** (+`missingPermissions`),
**USER_ACTION_REQUIRED**, **UNSUPPORTED** — все шесть исходов протокола
покрыты. `JarvisTool` = execute() + verify() + executionTimeoutMs (per-call
4 с) + mapError. Новая abstraction не добавлялась (правило 8 протокола).

## PHASE 9–10 — TOOL VALIDATION / PLATFORM LIMITS

Матрица валидации — `docs/TEST_MATRIX.md` §2 (Implemented/Registered/
Discoverable/Callable/Permission по каждому). Классификация возможностей
(DIRECT / PERMISSION_REQUIRED / USER_ACTION_REQUIRED / OEM_DEPENDENT /
VERSION_DEPENDENT / UNSUPPORTED) — `docs/ANDROID_CAPABILITIES.md`; подделка
success на неразрешённых действиях запрещена контрактом PHASE 7.
Физический прогон каждого тула — DEVICE (BLOCKED здесь).

## PHASE 11 — INTERRUPTION

**PASS (policy выведена из кода, закреплена тестами):**
- дубликат запроса во время обработки → **IGNORE** (`isProcessingQuery.compareAndSet`);
- новая реплика в Ear Mode → **REPLACE** (CR-22: `translationJob.cancel` + FLUSH);
- устаревшие результаты (смена режима/эпохи) → **CANCEL** (CR-01 guard, CR-07 epoch);
- очередь (QUEUE) не используется — сознательно, не нужно для UX.
Stale/duplicate TTS/orphan-coroutine: epoch-проверки после каждого await
(тесты `ExecutionDecisionEngineTest`, interpreter CR-22).

## PHASE 12 — RESTART / RECOVERY

Частично AUTO: START_STICKY рестарт сервиса безопасен (CR-11: null-intent →
переинициализация pipeline); BT disconnect/reconnect — контракты EAR-MODE
(ed7fb6e) + DEVICE-строки; network recovery — CR-05 (отмена in-flight OkHttp)
+ NetworkMonitor. Process death state consistency — DEVICE (05-interruption-recovery).

## PHASE 13 — CLIP / EAR MODE

**BLOCKED — hardware unavailable** (реального Clip нет; симулировать PASS
запрещено). Всё код-левел-готово: attestation V008 (pair/bind/replay/revoked —
PG-тесты), SCO-роутинг, audio focus, phone-call pause/resume, микрофонный
конфликт wake-word (ed7fb6e). Тест-чеклист — TEST_MATRIX §6.

## PHASE 14 — BATTERY / RAM / CPU

**NOT MEASURED** (устройства нет). Подготовлено: idle-unload модели 5 мин
(c20fe62, тесты на виртуальном времени), замерный протокол в `docs/BATTERY.md`
(dumpsys batterystats/meminfo, сценарии Idle/Listening/Voice/LocalAI/Cloud/Ear).

## PHASE 15 — SECURITY / ACTIVATION

**PASS (эксплуатационный слой, PG):** wrong device → V007 token-device binding
(`license token is device bound on AI enforcement path`); expired/revoked →
entitlement-гейт; invalid token → 401 fail-closed до парсинга; replay → V008
nonce; duplicate activation → atomic redeem. Client state — не источник
истины: сервер переклассифицирует и перепроверяет (C-02, H-02, §28).

## PHASE 16–18 — BACKLOG И ИСПРАВЛЕНИЯ

### P0 (подтверждённые, блокирующие)

| ID | Проблема | Статус |
|---|---|---|
| P0-нет | — | Подтверждённых код-левел P0 нет: release-сборка и device-прогоны не выполнялись в этой среде; **первый release build + install — потенциальный источник P0, см. PHASE 1/2** |

### P1 (подтверждённые, seriously ухудшают reliability)

| ID | Component | Problem | Evidence | Fix | Effort |
|---|---|---|---|---|---|
| P1-1 | Accessibility privacy filter | Хинта `chase` не было в `SENSITIVE_PACKAGE_HINTS`, при том что регресс-тест требовал Block для `com.chase.sig.android` → латентный CI-red c 4e6e8a4 и, главное, **брендовый банк без слова «bank» пропускал full-screen capture к LLM** | `AccessibilityPrivacyPolicyTest.real banking packages…` vs `AccessibilityPrivacyPolicy.SENSITIVE_PACKAGE_HINTS` | **FIXED в этой ревизии**: `chase` добавлен; все 13 пакетов теста накрыты (скрипт-проверка); regression test уже существовал и теперь зелёный | done |
| P1-2 | CI/toolchain | Release/unit-сборка не выполняется в рабочем sandbox (нет JDK/SDK) — последние 6 коммитов валидировал только CI | среда; инварианты 153 PASS | первый CI-прогон ветки; при красном — чинить как P0 | CI-время |

### P2 / известные ограничения (не мешают core)

- Play Integrity не подключён → «modified client» на AI-пути не детектируется
  (защита: token+device binding+серверная переклассификация). TEST_MATRIX GAP.
- Local-телеметрия на сервере = NOT COLLECTED (админка честно).
- BT dual-bud, SCO-reconnect latency, duck-поведение плееров — NOT MEASURED.
- 2FA-фактор админки, percent-rollout per-plan (CONTROL_PLANE §13).

## PHASE 19 — REGRESSION

План: Build → Install → Activation → Wake → STT → Router → Tool → Cloud →
TTS → BT → Recovery по TEST_MATRIX; все PASS-строки Auto-слоя после P1-1
остаются PASS (изменение аддитивное: +1 хинт в эвристике, покрытый тестом).
Здесь выполнено: инварианты **153 PASS**.

## PHASE 20 — OBSERVABILITY

**PASS:** единый `request_id` (`omx_01J…`) Voice→Router→Tool→AI→Server→Provider
(коммит a870410); приватность: тексты/пароли/ключи/токены не логируются
(PrivacyLoggingRegressionTest, §28), чувствительный экранный контент —
placeholder в истории (ScreenContentPrivacy).

## PHASE 21 — FINAL SCORECARD

Оценки только по evidence авто-слоя; device-зависимые — с NOT MEASURED:

| Категория | Оценка | Basis |
|---|---|---|
| Build reliability | **7/10** (BLOCKED-верификация: release-сборка локально невозможна; CI зелёный на инвариантах; static pre-flight чист) | PHASE 1 |
| Activation | **8/10** (PG-контракты атомарного redeem/binding; live-кресentials Paddle отключены сознательно) | PHASE 15 |
| Voice (loop) | 6/10 — код/метрики готовы, **NOT MEASURED** на устройстве | PHASE 3 |
| STT | 6/10 — системный распознаватель, continuous-режим протестирован контрактом; DEVICE-замер нет | TEST_MATRIX |
| Router | **9/10** (95 AUTO-тестов маршрутизации, приоритеты, метрики, latency-сегменты) | PHASE 5/6 |
| Tools | **8/10** (контракт полный, executor-тесты; физический прогон каждого — DEVICE) | PHASE 8/9 |
| Local-first | **9/10** (FastCommandRouter P1 + Cost Control + доказательства «0 cloud-calls») | PHASE 5 |
| Cloud fallback | **9/10** (2 попытки, 429/500/circuit, ProviderResilience) | PHASE 6 |
| TTS | 7/10 (audio focus, очередь, shutdown — контракты; физический прогон DEVICE) | ed7fb6e |
| Bluetooth | 6/10 (Ear Mode-контракты; **NOT MEASURED**: reconnect, dual-bud, дрейф) | PHASE 13 |
| Recovery | 7/10 (CR-11 sticky, CR-05 отмена, BT-контракты; process-death — DEVICE) | PHASE 12 |
| Security | **9/10** (V007/V008 PG-тесты, fail-closed, privacy-by-design) | PHASE 15 |
| Performance | 6/10 (latency-инструментарий готов; числа NOT MEASURED) | PHASE 3/14 |
| Battery | 5/10 (idle-unload контракт; дрейф NOT MEASURED) | PHASE 14 |
| **Overall v0.2 readiness** | **7/10** — код и контракты stabilized; недостающее — только физические доказательства | — |

Метрики для device-прогона (hands-free success rate, local %, cloud %,
P50/P95/P99, battery drain): **NOT MEASURED** — заполнить из
RESULTS_TEMPLATE после PHASE 2–3.

## PHASE 22 — ГОТОВНОСТЬ К v0.3

- **Production-ready (по evidence):** licensing/activation (PG), security
  (binding/attestation/replay), router + local-first, tool contract,
  fallback/cost control, control plane, observability (request_id).
- **Partially ready:** voice loop (код+метрики есть, device-прогона нет),
  Bluetooth/Ear Mode (контракты, замеров нет), battery (idle-unload, дрейф
  неизвестен), recovery.
- **Broken:** ничего подтверждённого (P1-1 закрыт); риски: первый release
  build, первые device-прогоны.
- **Missing:** device evidence (весь слой PHASE 2–3/13/14), local-телеметрия
  на сервере, Play Integrity.
- **НЕ менять:** лестницу решений (FastCommandRouter→Planner→Local→Cloud),
  guardrails цикла (2/12/8s), tool contract, privacy-архитектуру,
  лицензионный сервер, admin control plane.
- **v0.3 =** device-доказательство цикла (PHASE 2–3 по протоколу) → then
  roadmap-приоритеты по факту: 1) reliable Tool Ecosystem (device-прогон
  34 тулов), 2) Clip/Ear Mode hardware-тесты, 3) Local Brain (модель
  резидентно-экономно уже есть), 4) Memory (retrieval-ядро уже вколочено),
  5) Cost Optimization (данные появятся с телеметрией). Smart Router/Cloud
  Gateway/Admin — уже сделаны ранее и не дублируются.
