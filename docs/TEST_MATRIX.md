# Тестовая матрица финального тестирования (v0.3)

**Ревизия:** `main@…` · Статусы строк:
**AUTO** — уже покрыто JVM-тестом (файл указан, CI гоняет при каждом PR);
**PG** — Postgres-integration тест (локально нужен Postgres, гоняет в CI);
**DEVICE** — требует реального устройства/Clip (шаг описан, запуск:
`device-validation/0*.sh` + ручные пункты); **GAP** — честная дыра
(осознанно отложена, не прикрыта тестом).

Итог на дату: **~300 автотестов** (app 82 файла, server 37 файлов) +
146 архитектурных инвариантов (`scripts/verify-architectural-invariants.sh`,
hard-run в build.yml) + device-validation 01–05.

---

## 1. Voice (голосовые команды: 51 тест роутера + сценарии + движок)

**AUTO-база:** `FastCommandRouterTest` (51 @Test — каждая семья команд),
`CognitivePlannerTest` (14), `ExecutionDecisionEngineTest` (38),
`MediaIntentParserTest`, `InterpreterPresetTest`, `WorkingMemoryTest`,
`VoiceLatencyMetricsTest` (8).

| # | Команда / сценарий | Ожидаемый маршрут | Статус / якорь |
|---|---|---|---|
| 1 | «Джарвис» → верификация | STANDBY → VERIFYING_KEYWORD → LISTENING | DEVICE (03-voice-loop) + `VoiceInteractionOrchestrator` |
| 2 | «Открой Telegram» | DEVICE_TOOL conf .95, без агента | AUTO `FastCommandRouterTest` |
| 3 | «Открой YouTube и найди UFC» | AGENT: open→click→type→verify(4 шага) | AUTO `CognitivePlannerTest` |
| 4 | «Включи фонарик» / «выключи» | device.flashlight on/off | AUTO |
| 5 | «Громкость выше/ниже/50%/mute» | device.volume | AUTO |
| 6 | «Яркость 30%» | device.brightness | AUTO |
| 7 | «Какая батарея» | system.battery | AUTO |
| 8 | «Сколько время» / дата | system.time | AUTO |
| 9 | «Включи музыку / дальше / стоп / тише» | media.control (нормализация намерения) | AUTO `MediaIntentParserTest` |
| 10 | «Погода в Ашхабаде» / «погода» (гео) | intelligence.weather (+/- город) | AUTO (кейс hardcoded-города закрыт) |
| 11 | «Что на экране» / «прочитай экран» | accessibility.screen_reader (+placeholder в историю) | AUTO `ToolExecutorBehaviorTest` |
| 12 | «Нажми Отправить» / «прокрути вниз» | accessibility.ui_click/type_text | AUTO |
| 13 | «Сделай скриншот» | device.screenshot | AUTO |
| 14 | «Не беспокоить» вкл/выкл | device.dnd | AUTO |
| 15 | «Проложи маршрут до X» | location.navigation | AUTO |
| 16 | «Позвони маме» (confirm) | communication.call → AWAITING_CONFIRMATION | AUTO `ToolExecutorConfirmationQueueTest` |
| 17 | «Напиши СМС X» (confirm) | communication.sms (forced confirm S-3) | AUTO |
| 18 | «Найди контакт X» | communication.contacts | AUTO |
| 19 | «Запусти Chrome / включи вайфай / блютуз» | device.open_app / wifi / bluetooth | AUTO |
| 20 | «Что за телефон» | system.device_info | AUTO |
| 21 | «Переведи на английский …» | intelligence.translate (Ear Mode — мгновенный перевод) | AUTO `InterpreterPresetTest` |
| 22 | «Запомни: ключ у консьержа» | memory.remember | AUTO |
| 23 | «Что ты помнишь про X» | memory.recall | AUTO |
| 24 | «Забудь про BMW» | memory.forget (FastCommandRouter §1) | AUTO |
| 25 | «Когда подключатся наушники — включи музыку» | productivity.create_automation | AUTO |
| 26 | «Брифинг» | productivity.ear_briefing | AUTO |
| 27 | «Я ухожу / я дома / ко сну / доброе утро / на встрече / еду / экономь / статус телефона / презентация / отмени всё» | Сценарии 1–10 → планы 2–4 шага | AUTO `CognitivePlannerTest`+`ScenarioMatcher` |
| 28 | «Найди лучший X и сравни» | Cloud AI → (LLM plan) AGENT многошаговый | AUTO `ExecutionDecisionEngineTest` |
| 29 | «Расскажи анекдот» (свободный) | Local AI (если модель есть) → Cloud | AUTO `LocalAiRoutingIntegrationTest` |
| 30 | «Открой» (без объекта) | CLARIFICATION, ни один executor не вызван | AUTO |
| 31 | «Стоп / хватит / отмена» | handleCancel → STANDBY | DEVICE + AUTO |
| 32 | Пайплайн Wake→STT→Router→AI→Tool→TTS P50/P95/P99, LOCAL vs CLOUD | метрики, цель Simple→local | DEVICE замер + AUTO `VoiceLatencyMetricsTest` |
| 33 | Эхо/ложный wake, CR-01 guard | FinalResult вне режимов игнор | AUTO |

## 2. Tools (каждый инструмент — 34 tool)

**AUTO-база:** `ToolExecutorBehaviorTest` (privacy gate → preflight → execute
→ verify → честный статус), `ToolExecutorConfirmationQueueTest` (CR-04
callId-токены), `ToolDiscoveryEngineTest`, `CapabilityContractTest`,
`JarvisCapabilityTest`, реестр: `ToolRegistry`/`AppResolverTest`.

| Семья (tool_id) | AUTO | DEVICE-чек |
|---|---|---|
| device.flashlight / volume / dnd / brightness / wifi / bluetooth / screenshot / open_app | роутер+executor | каждая команда реально меняет состояние; «blocked_by_android» честен |
| system.battery / time / network_status / device_info | executor | значения совпадают с системными |
| communication.call / sms (confirm, forced) / contacts / share / telegram | CR-04+S-3 тесты | звонок/SMS уходят только после «да» |
| accessibility.screen_reader / ui_click / type_text | Lockdown-тесты (placeholder в историю) | при выключенном сервисе — честный отказ |
| intelligence.web_search / weather / translate / vision | Cost Control (HARD), C-02 | реальный ответ, таймаут ≤4 c |
| location.navigation | роутер | открывается карты |
| media.control | MediaIntentParser | каждая ветка play/pause/next/prev/stop |
| memory.remember / recall / forget | `MemoryRetrievalInjectionTest`, `WorkingMemoryTest` | recall возвращает записанное |
| productivity.alarm_timer / calendar / clipboard / create_automation / ear_briefing | executor+automation tests | будильник ставится, брифинг звучит |

## 3. Permissions (каждое разрешение в denied-состоянии)

**AUTO-база:** `FakeCapabilityRegistry.create()` выдаёт НИЧЕГО по умолчанию —
preflight-тесты падают при утечке; `ToolExecutorBehaviorTest`.

| Permission denied | Ожидаемое поведение | Статус |
|---|---|---|
| RECORD_AUDIO | `JarvisVoiceService.start/safeStartForeground` fail-closed: сервис не поднимается, не остаётся «created без foreground» | AUTO (контракт) + DEVICE тумблер |
| READ_PHONE_STATE | пауза на звонок отключена, лог «call-state pause disabled», сервис жив | AUTO контракт (`registerTelephonyListener`) |
| BLUETOOTH_CONNECT (API 31+) | N-05: имя устройства/connectedDevices → пусто/строк-заглушка, БЕЗ SecurityException-краша | AUTO контракт + DEVICE |
| ACCESSIBILITY (сервис выключен) | screen-тулы недоступны — preflight, честный отказ; фейк-успех запрещён (bbcbcf1) | AUTO |
| SEND_SMS / CALL_PHONE / READ_CONTACTS | preflightCapability→permissions→policy блокирует ДО execute; итог — объяснение, не ошибка | AUTO |
| ACCESS_NOTIFICATION_POLICY | DND → честный blocked_by_android | AUTO контракт |
| WRITE_SETTINGS | brightness → blocked_by_android | AUTO контракт |
| SCHEDULE_EXACT_ALARM | alarm_timer → fallback/честный отказ | AUTO контракт |
| POST_NOTIFICATIONS | FGS жив, уведомление молча (Android 13) | DEVICE |
| ACCESS_FINE/COARSE_LOCATION | weather без гео → «скажите город» | AUTO (empty-args ветка) |
| Battery optimization | helper предлагает исключение; без него wake-word деградирует — NOT MEASURED | DEVICE |

## 4. Network (Online / Slow / Offline / Reconnect)

| Сценарий | Ожидаемое | Статус |
|---|---|---|
| Online | E2E 200, requestId эхо | PG `ApiIntegrationTest` (41) + DEVICE |
| Slow (GPRS/3G) | клиент callTimeout 30 c > сервер 28 c → 504 PROVIDER_TIMEOUT с телом (не голый SocketTimeout) | контракт AUTO (`CALL_TIMEOUT_SECONDS` ↔ `SERVER_REQUEST_DEADLINE_MS`), замер DEVICE |
| Offline | OFFLINE_MESSAGE с перечнем офлайн-команд; local-полоса работает; `requiresWeb` → локальный слой skip, не фейк | AUTO `ExecutionDecisionEngineTest` + DEVICE airplane |
| Reconnect | NetworkMonitor возвращает online → cloud-полоса восстановится без перезапуска; незавершённые вызовы отменены (CR-05) | DEVICE + AUTO `OkHttpTransportCancellationTest` |
| TLS-гейт / proxy | PROVIDER_TIMEOUT/secure transport, requestId=`-` (pre-parse честно) | PG `DeploymentSecurityTest` |

## 5. AI (Local failure/timeout, Cloud, Provider 429/500)

| Сценарий | Ожидаемое | Статус / якорь |
|---|---|---|
| Local success | LOCAL_AIHandled, латентность в LOCAL-перцентили | AUTO `OnDeviceLocalAiTest` |
| Local failure (модель сломалась) | честный Error, НЕ эскалация в облако (приватность) | AUTO `CompositeLocalAiExecutor` контракт |
| Local not installed | Unsupported → облако; ~529MB файл вне APK | AUTO |
| Local idle >5 мин | unload → ленивый reload ~1–3 c | AUTO `IdleUnloadSchedulerTest` + DEVICE замер |
| Cloud success | CLOUD_AI + metadata.request_id | AUTO движок + PG |
| Cloud timeout | менеджер обрывает висящего провайдера → fallback | PG `ProviderResilienceTest` |
| Provider 429 | RATE_LIMITED: ретрая этого же провайдера бессмысленна → след. провайдер; 429 считается в PerformanceTracker (Smart Router вес 429) | PG `ProviderResilienceTest`+`SmartProviderSelectionPolicyTest` |
| Provider 500 | max 2 платные попытки (fallback budget bc821c0) → circuit → ALL_PROVIDERS_UNAVAILABLE | PG + AUTO `CostControlTest` (класс маршрута) |
| Rate limit клиента | 429 API-лимит fail-closed | PG `PostgresRateLimiterTest`/`AuthConfigRateLimitTest` |

## 6. Bluetooth (Clip / Ear Mode)

| Сценарий | Ожидаемое | Статус / якорь |
|---|---|---|
| Connected | earbud-роутинг, MODE_IN_COMMUNICATION только на сессию | AUTO-контракт (EAR-MODE инварианты) + DEVICE |
| Disconnected | speaker-фолбэк, guard «нет SCO-входа → MODE_NORMAL» (не долбить SCO) | AUTO (инвариант) + DEVICE |
| Reconnect | ACL-гейт по реальному аудиовыходу → авто-переезд следующей фразы | DEVICE, время reconnect NOT MEASURED |
| Wrong device (часы/машина) | ACL_CONNECTED ≠ наушник: гейт `checkHeadsetConnection()` | AUTO-контракт + DEVICE |
| Two devices | BT-first (SCO/BLE важнее wired); dual-bud поведение | NOT MEASURED |
| Phone call во время Ear Mode | пауза → восстановление режима; screen-путь resume | AUTO-контракт + DEVICE |
| SCO микрофон | слышимость через Clip-микрофон | DEVICE |

## 7. Security

| Сценарий | Ожидаемое | Статус / якорь |
|---|---|---|
| Invalid token | 401 fail-closed ДО парсинга тела; requestId в ошибке | PG `ApiIntegrationTest` |
| Expired license | статус EXPIRED → entitlement-гейт (JarvisApiHandler) → PAYMENT_REQUIRED; клиент-display cache не источник истины | PG `LicenseApiIntegrationTest` |
| Wrong device | V007: jrv_-токен сверяется с X-Jarvis-Device binding; unbound token denied on AI path | PG (`license token is device bound on AI enforcement path`) |
| Fake device (Clip) | V008 attestation: неизвестный Clip отклонён на challenge; подпись чужим ключом отклонена; второй аккаунт не attестит чужой Clip; revoked отклонён при валидной подписи | PG `ClipAttestationIntegrationTest` |
| Replay | повтор challenge отклонён (nonce/timestamp) | PG (`replayed challenge is rejected`) |
| Modified client | на Clip-пути — криптографическая attestation (подмена ключа ловится). **GAP:** для AI execute целостность APK сервером не проверяется (Play Integrity не подключён) — осознанно отложено; защита: токен+device binding+серверная переклассификация | честный GAP |

---

## Правила прогона

1. `./gradlew test` (app) + `./gradlew :server:test` — AUTO/PG.
2. `bash scripts/verify-architectural-invariants.sh` — 146 инвариантов.
3. `device-validation/01…05.sh` + ручные DEVICE-строки этой матрицы на Clip;
   результаты — в `device-validation/RESULTS_TEMPLATE.md`.
4. Строки **GAP** не закрывать «тихо»: либо тест, либо запись в «осознанно
   отложено» соответствующего docs/*.
