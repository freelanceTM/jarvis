# OMNIX Wearable-First Product Audit (baseline v0.2.0 → план v0.3)

**Дата:** 2026-08-31 · **Ревизия:** `main@8f4680e` (тег `v0.2.0`)
**Продуктовая рамка:** Voice-first AI wearable ecosystem — пользователь выполняет
повседневные цифровые задачи через Clip-наушники, не доставая телефон.
**Главный acceptance criterion:** «Может ли пользователь выполнить задачу голосом
через Clip-наушники, не доставая телефон, быстро, надёжно и предсказуемо?»
**Правила статусов:** PASS/FAIL/BLOCKED/NOT TESTED/PARTIAL; PASS = доказано на
реальном устройстве. Код ≠ доказательство работы. Неизмеренное = NOT MEASURED.

---

## 1. Экосистема: что изучено

### 1.1 Android (все компоненты найдены и проверены в коде)

| Компонент | Файл | Факт |
|---|---|---|
| Voice pipeline | `VoiceInteractionOrchestrator.kt` (1040 строк) | 9 режимов: STANDBY_WAKE_WORD → VERIFYING_KEYWORD → LISTENING_USER_QUERY → AI_THINKING → TTS_SPEAKING → CONTINUOUS_CONVERSATION; + AWAITING_CONFIRMATION, AWAITING_PRIVACY_CONSENT, LIVE_EAR_INTERPRETER |
| Wake word | `AlisaStyleWakeWordEngine.kt` + `WakeWordExtractor` | STANDBY-режим FGS-microphone, частичные результаты STT + извлечение wake-слова/запроса |
| Microphone service | `JarvisVoiceService.kt` (449) | foregroundServiceType=«microphone», START_STICKY + CR-11 guard, boot-restart (RECEIVE_BOOT_COMPLETED), PhoneStateListener/TelephonyCallback (звонки) |
| STT | `SpeechRecognizerManager.kt` (302) | системный `SpeechRecognizer`, FREE_FORM, partial results, языковой тег. **On-device НЕ форсируется** (`EXTRA_PREFER_OFFLINE`/`createOnDeviceSpeechRecognizer` отсутствуют) |
| Local AI | `agent/localai/` (MediaPipe LLM runtime) | `isReady()` = модель найдена локально; модель **не поставляется с APK** (user-installed) |
| Routing | `ExecutionDecisionEngine` | P1 DEVICE → P4 AGENT → P2 LOCAL → P3 CLOUD, privacy-гейты (закрытый пункт аудита) |
| Fast path | `FastCommandRouter` | route→Tool **без LLM** (закрытый пункт) |
| Agent core | `pipeline/`, `planner/`, `observation/` | Plan→Act→Observe→Re-plan (закрытый пункт) |
| Tool system | 39 JarvisTool | wifi, bt, brightness, volume, apps, sms, calls, screenshot, a11y (UiClick/UiType/ScreenReader), notifications, calendar, alarms, media, clipboard, share, translate, weather, web, location, flashlight, dnd, battery, time, device-info, network, contacts, telegram, **memory.remember/recall/forget**, **ear.briefing**, heart-rate, steps, wear-os, sleep, create-automation, vision |
| TTS | `TextToSpeechManager.kt` (344) | QUEUE_FLUSH по умолчанию, буфер+re-init, обработка «Russian TTS not available» |
| Bluetooth audio | `BluetoothAudioRouter.kt` (364) | SCO / A2DP / BLE_HEADSET / wired / USB, `setCommunicationDevice`, `routeAudioToEarbud()`, **headset-only mode** (`isHeadsetOnlyMode` в оркестраторе: без наушников работа блокируется) |
| Accessibility | `AccessibilityPrivacyPolicy` | банк/пароль/2FA = BLOCK (закрытый пункт) |
| Permissions | манифест | RECORD_AUDIO, PHONE, SMS, CONTACTS, LOCATION, BLUETOOTH(+ADMIN), WRITE_SETTINGS, SCHEDULE_EXACT_ALARM, FGS×3, POST_NOTIFICATIONS, BOOT |
| Battery mgmt | `BatteryOptimizationHelper` + Settings UI | запрос исключения из Doze |

### 1.2 Server (Ktor, всё найдено)

| Область | Факт |
|---|---|
| API | `/v1/health`, `/v1/license/redeem`, `/v1/license/validate`, `/v1/ai/execute`, `/v1/billing/checkout`, `/v1/billing/webhooks/{heleket,paddle}`, `/v1/admin/licenses/{issue,revoke}`, `/v1/admin/metrics{,/prometheus}` |
| Auth | device token + entitlement checks (`Auth.kt`, `EntitlementChecker`) |
| License | scratch-code → token, JDBC-репозиторий, crypto |
| AI router | provider selection (health+priority+circuit breaker), privacy-политика: **в production запрещён глобальный PRIVATE/SENSITIVE без per-request consent** (ServerConfig:199) |
| Billing | checkout + verified webhooks + reconciliation worker |
| Observability | metrics + Prometheus endpoint |
| Memory/search | серверная память НЕ является основным хранилищем: факты — локально в Room (см. §17) |

### 1.3 Headphones / Clip hardware

```text
HARDWARE IMPLEMENTATION NOT AVAILABLE IN REPOSITORY
```

Отсутствует: протокол/SDK/firmware Clip-наушников, OTA, телеметрия батареи,
кнопка/жесты, dedicated activation, кастомный микрофонный тракт, pairing-механика
устройства. В репо — только **стандартный Android-стек Bluetooth-аудио**
(BluetoothAudioRouter) и три софтверные «ушные» фичи: headset-only mode,
Ear Briefing, Live Ear Interpreter. Специфичный для «Clip» код не найден
(grep по clip/earbud/BLE-GATT/media-button — совпадения только ClipboardTool,
переводчик, аудио-типы).

**Прямое следствие для §12:** дифференциатор Clip-железа в софте сейчас = 0.
Каждая из найденных «ушных» фич работает и с обычными Bluetooth-наушниками.

---

## 2. Реальный продукт-флоу по этапам

| Этап | Implemented | Used | Tested | Measured | Failure handling |
|---|---|---|---|---|---|
| Wake | ✅ код | FGS-mic standby | unit+instrumented (API-34 CI) | **NOT MEASURED** | VERIFYING_KEYWORD, возврат в STANDBY |
| BT transport | ✅ стандартный стек | routeAudioToEarbud при старте/интерпретаторе | instrumentation device-types check | **NOT MEASURED** | checkHeadsetConnection, headset-only блокировка |
| STT | ✅ системный | partial+final | instrumented | **NOT MEASURED** | ошибки → STANDBY, CR-01 guard дубликатов |
| Understanding | ✅ fast router + parser; ⚠️ контекст | FastCommandRouter без LLM | JVM-тесты | **NOT MEASURED** | fallback в decision engine |
| Routing | ✅ | decision engine | JVM 31/31 invariants | **NOT MEASURED** | BLOCKED OFFLINE, честные ошибки |
| Execution (tools) | ✅ 39 tools | по интентам | JVM + instrumented | **NOT MEASURED** | SUCCESS/PERMISSION_REQUIRED/USER_ACTION_REQUIRED/UNSUPPORTED — без false-success |
| Local AI | ✅ runtime | если модель установлена | JVM-контракт | **NOT MEASURED** | FAILED без эскалации в cloud (гейт) |
| Cloud AI | ✅ клиент+сервер | по приватности/недоступности local | серверные интеграционные (CI) | **NOT MEASURED** | CLOUD_AI FAILED, таймауты, CB |
| Verification | ✅ result summary | tool results → голос | JVM | **NOT MEASURED** | наблюдаемое поведение, epoch-discard |
| TTS | ✅ | QUEUE_FLUSH | instrumented | **NOT MEASURED** | Error → STANDBY; Russian fallback |
| Output BT | ✅ | тот же маршрут | — | **NOT MEASURED** | — |

**Вывод §2:** полный контур реализован в коде и покрыт тестами уровня JVM/
instrumented, но **ни один этап не измерен и не доказан на реальном устройстве**.

## 3. Clip-наушники как основной интерфейс

| Блок | Статус | Факт |
|---|---|---|
| Микрофон наушников/качество | **HARDWARE NOT IN REPO** | используется стандартный маршрут SCO/BLE телефона |
| Wake word | PARTIAL | софтверный, работает через телефонный mic-тракт; на устройстве в фоне — BLOCKED |
| Push-to-talk / кнопка | **ОТСУТСТВУЕТ** | MEDIA_BUTTON / KEYCODE_HEADSETHOOK не обрабатываются нигде |
| Жесты | **ОТСУТСТВУЕТ** | — |
| Audio focus | **ОТСУТСТВУЕТ** | requestAudioFocus не вызывается → конфликт с музыкой/звонками не управляется |
| Pairing/reconnect | PARTIAL | системный BT; router слушает audioState (reconnect-логика приложения — только маршрутизация) |
| Батарея наушников | **ОТСУТСТВУЕТ** | телеметрии нет |
| OTA / device identity | **ОТСУТСТВУЕТ** | — |
| TTS-маршрут в наушники | ✅ код | routeAudioToEarbud + setCommunicationDevice |
| Phone locked / screen off | PARTIAL | FGS-microphone + WAKE_LOCK + boot-restart есть в коде; **поведение на устройстве BLOCKED** (вендорские ограничения Doze/FGS-mic — самая рискованная зона Android) |

**Ответ на ключевой вопрос §3** («может ли пользователь сказать "JARVIS, …" с
заблокированным телефоном»): по коду — да, FGS+boot+wake реализованы; по фактам —
**NOT PROVEN (BLOCKED)**: это ровно тот сценарий, который вендоры ограничивают
агрессивнее всего, и который нельзя подтвердить без физического устройства.

## 4. Real device validation (Phone + Clip + production-like build)

```text
BLOCKED — нет ни телефона, ни Clip-наушников в среде исполнения аудита.
Готовый протокол: device-validation/ (PR #46) — 5 adb-скриптов + матрица.
Минимальный сценарий Pair→Launch→Activate→Say→Execute→Hear ×N повторов
включён в kit; для Clip-наушников после появления железа добавляются
§3-пункты (кнопка, микрофонный тракт, батарея, reconnect).
Все метрики: NOT MEASURED.
```

## 5. Главный KPI: Hands-Free Task Success Rate

```text
Hands-Free Task Success Rate: NOT MEASURED
```

Тестовый набор определён (§6), исполнение — на устройстве (§4). Не подставляю
вымышленных значений.

## 6. Task Matrix

Классификация маршрутов — из кода (decision engine + isOffline-флаги: 30 из 39
инструментов локальные). Колонки результата — к заполнению на устройстве.

| Task | Voice Only | Local | Cloud | Tool (id) | Result Verified | Success | P50 | P95 |
|---|---|---|---|---|---|---|---|---|
| Позвони X | да | ✅ | — | call | код-level | NOT MEASURED | N/M | N/M |
| Напиши X (SMS) | да | ✅ (подтверждение) | — | sms | | | | |
| Ответь на сообщение | да | ⚠️ partial | возможно | sms/telegram | | | | |
| Какая погода? | да | — | ✅ | weather | | | | |
| Который час? | да | ✅ | — | get_time | | | | |
| Найди информацию | да | — | ✅ | web_search | | | | |
| Громкость 50% | да | ✅ | — | set_volume | | | | |
| Включи фонарик | да | ✅ | — | flashlight | | | | |
| Открой приложение | да | ✅ | — | open_app | | | | |
| Сделай скриншот | да | ✅ (A11y A30+) | — | screenshot | | | | |
| Поставь таймер | да | ✅ | — | alarm_timer | | | | |
| Создай напоминание | да | ✅ | — | alarm/automation | | | | |
| Создай событие | да | ✅ | — | calendar | | | | |
| Будильник | да | ✅ | — | alarm_timer | | | | |
| Включи музыку | да | ✅ | — | media_control | | | | |
| Следующий трек / пауза | да | ✅ | — | media_control | | | | |
| Объясни / сравни / напиши | да | если LLM установлен | ✅ | local/cloud AI | | | | |
| Переведи | да | ✅ (live ear) | частично | translate | | | | |

**Главный вопрос §6:** сколько задач реально выполняется без доставания
телефона — **NOT MEASURED**. Потенциал по коду: ~30 локальных команд + cloud —
но это потенциал, не факт.

## 7. Latency Budget

Стадии: Wake + BT + STT + Intent + Routing + AI + Tool + Verify + TTS + BT-out.

```text
TOTAL LATENCY (local command):  NOT MEASURED
TOTAL LATENCY (cloud request):  NOT MEASURED
P50 / P95 / P99:                NOT MEASURED
```

Инструментальная база: logcat-маркеры (wake/STT/route/tool/TTS) верифицированы
в коде; **duration-маркеров нет** (гэп DV-05) → точные sub-stage замеры требуют
OMNIX_TRACE-инструментации (6–8 точек) либо logcat-epoch с интерполяцией
(±шум между логами). Замер — только на устройстве (§4).

## 8. Voice UX

| Вопрос | Вердикт | Основание (код) |
|---|---|---|
| Слишком длинные ответы? | PARTIAL | device-tools: короткое «summary, сэр.»; **cloud-ответы — полной длины LLM**, ограничений/тримминга для голоса нет |
| Подтреждения нужны? | ✅ | AWAITING_CONFIRMATION: да/нет голосом, таймаут, очередь (ToolExecutor pending confirmation) |
| Может ли перебить JARVIS? | ⚠️ **ОГРАНИЧЕНО** | во время TTS_SPEAKING FinalResult игнорируется (CR-01) → **голое «Стоп» во время ответа НЕ работает**; перебивание — только через wake-слово (медленнее) или по коду не гарантировано. **Это ключевой UX-гэп wearable** |
| «Нет»/«Отмена»/«Стоп» | ✅ в слушающих режимах | «стоп/хватит/отмена/джарвис стоп/выйти» (orchestrator:401) + ConfirmationIntent |
| Follow-up без wake word | ⚠️ PARTIAL | CONTINUOUS_CONVERSATION: окно 8 с (FOLLOW_UP_WINDOW_MS) после каждого ответа — **работает**; но |
| «Какая погода?» → «А завтра?» | ❌ **FAKE COMPLETE** | `ConversationContext`/`ReferenceResolver`/`WorkingMemory.context` существуют, но **не вызываются из голосового пути** (grep: WorkingMemory используют только decision/observation engines). Разрешение анафоры («он», «а завтра», «его») в голосе не подключено |

## 9. Voice Response Policy

**Фактически:** де-факто политика есть ТОЛЬКО для tools (isSuccess →
`"${summary}, сэр."`, blocked → summary, error → «Не удалось выполнить: …») —
это правильная короткая форма. **Для cloud-ответов политики нет** — LLM-текст
озвучивается как есть (chatbot-риск: длинные монологи в ухо).

```text
VOICE RESPONSE POLICY: PARTIAL (tools ✅ / cloud ❌)
```

Предлагаемая минимальная архитектура (без rewrite): `VoiceResponsePolicy` —
pure-функция на границе orchestrator→TTS: (1) для action-ответов — уже
существующий summary-путь; (2) для cloud — 1–2 предложения: первое предложение
или шаблонный префикс + опция «подробно?»; (3) кап по символам для TTS
(конфигурируемый); (4) обязательные короткие коды ошибок из строк ресурсов
(уже частично есть: `operaciya_otmenena_sir`, `ne_ponyal_skazhite_da_ili_net`).
Оценка: ~100–150 строк + тесты, без изменения пайплайна. Включать **после**
доказательства core loop (правило 18).

## 10. Local-first

| Класс | Что входит (по коду) |
|---|---|
| **LOCAL CANDIDATES** | 30 из 39 инструментов `isOffline=true`: громкость, фонарик, яркость, apps, sms, calls, таймеры/будильники, календарь, media, clipboard, wifi/bt-состояние, время, батарея, контакты, screenshots, a11y-автоматизации, memory.remember/recall/forget, перевод, briefing, sleep, automations |
| **CLOUD REQUIRED** | web_search, погода, knowledge-вопросы (explain/compare/write) — при неустановленной local-модели всё reasoning уходит в cloud |
| **HYBRID** | reasoning-команды: local LLM (если готов) → cloud fallback; SMS/Telegram «напиши текст…» (текст может требовать LLM) |

Ненужные cloud-вызовы: архитектурно перекрыты (FastCommandRouter без LLM +
privacy-гейты + запрет глобального PRIVATE-роутинга на сервере). Фактическая
доля локальных/облачных вызовов: **NOT MEASURED** (нужны счётчики route_local/
route_cloud — DV-05).

## 11. Capability Matrix (device control)

| Capability | Android API | Permission | Классификация | Accessibility | Works (по докам/коду) |
|---|---|---|---|---|---|
| Wi-Fi toggle | панель | — | **USER_ACTION** (A10+: setWifiEnabled=false) | нет | чтение ✅, переключение = системная панель |
| Bluetooth toggle | 33+ запрет | BLUETOOTH_CONNECT | **USER_ACTION** (A12+) | нет | чтение ✅, toggle ❌ A13+ |
| Volume | AudioManager | — | **DIRECT** | нет | ✅ |
| Brightness | Settings.System | WRITE_SETTINGS | **PERMISSION** | нет | ✅ после спец-разрешения |
| Apps | launch intent | — | **DIRECT** | нет | ✅ |
| SMS | SmsManager | SEND_SMS | **PERMISSION**+confirm | нет | ✅ |
| Calls | ACTION_CALL | CALL_PHONE | **PERMISSION**+confirm | нет | ✅ |
| Screenshot | A11y(A30+)/MediaProjection | — | **ACCESSIBILITY** (+согласие projection) | да | ✅ A30+ |
| Calendar | provider | WRITE_CALENDAR | **PERMISSION** | нет | ✅ |
| Alarm | AlarmClock intent / exact | SCHEDULE_EXACT_ALARM | **DIRECT**/PERMISSION(A31+) | нет | ✅ |
| Flashlight | CameraManager | — (camera) | **DIRECT** | нет | ✅ |
| DND | NotificationManager | ACCESS_NOTIFICATION_POLICY | **PERMISSION** | нет | ✅ |
| Notifications read | NotificationListener | — | **PERMISSION** (системный грант) | нет | ✅ |
| Clipboard | ClipboardManager | — | **OEM DEPENDENT** (A10+: только своё приложение в фоне) | нет | ⚠️ |
| Screen-read/UI automation | AccessibilityService | — | **ACCESSIBILITY** | да | ✅ с privacy-гвардой |
| Location | FusedLocation | FINE/COARSE | **PERMISSION** | нет | ✅ |
| Heart-rate/Steps | — | — | **OEM DEPENDENT** (источник — wearable; прямого доступа к сенсорам телефона в коде нет) | нет | ⚠️ требует устройство-источник |

Источники: docs/ANDROID_CAPABILITIES.md (честные таблицы по каждой capability),
манифест, код инструментов. Все строки «✅» = код+документация; на устройстве —
BLOCKED (§4). Важное правило уже зашито: USER_ACTION_REQUIRED ≠ SUCCESS.

## 12. Hardware Differentiator

| Возможность | Статус |
|---|---|
| Dedicated activation (не wake word) | **Not implemented** |
| Кнопка/жесты наушников | **Not implemented** (MEDIA_BUTTON не обрабатывается) |
| Оптимизированный микрофон/beamforming | **Not implemented** (HARDWARE NOT IN REPO) |
| Always-available interaction | Partially (FGS+boot в коде; на устройстве не доказано) |
| Custom audio routing | Partially (стандартный SCO/BLE router, headset-only mode) |
| Hardware telemetry / battery наушников | **Not implemented** |
| Low-latency interaction | Partially (epoch-discard, flush-TTS — софтверно; измерений нет) |
| Custom wake mechanism | **Not implemented** (софтверный wake) |
| Дополнительные сенсоры Clip | **Not implemented** |
| Ear Briefing при надевании | **Partially/Fake-complete**: движок есть (15-сек брифинг «JARVIS Earclip»), но автотриггер «при надевании» в коде не найден — вызывается только голосом (EarBriefingTool) |
| Live Ear Interpreter | **Implemented (код)** — full-duplex перевод «в ухо», CR-22 last-wins; уникальная софтверная фича, но работает с любыми наушниками |

```text
CRITICAL PRODUCT GAP
```

**Прямой ответ §12:** сейчас НЕТ ни одной программно-доказанной причины покупать
именно Clip-наушники вместо обычных Bluetooth-наушников + это приложение.
Все реализованные фичи работают со стандартным BT-стеком. Дифференциатор должен
быть создан: (a) hardware-фичи (кнопка/сенсор, телеметрия, dedicated activation)
+ их Android-интеграция, либо (b) честный рефокус v0.3 на «работает с любыми
наушниками» до появления железа.

## 13. Reliability (13 сценариев)

| Сценарий | Expected | Actual (код) | Recovery | User feedback |
|---|---|---|---|---|
| BT disconnect | пауза+восстановление | audioState-наблюдение; headset-only блокирует работу без наушников | повторная маршрутизация при reconnect | BLOCKED (не проверено) |
| Network failure | честная ошибка | BLOCKED OFFLINE-маркер, без false-success | retry-политики | ✅ код / BLOCKED runtime |
| STT failure | ошибка+повтор | ошибки → STANDBY | рестарт слушания | частично (строки ресурсов) |
| TTS failure | fallback | TtsState.Error → STANDBY; Russian-недоступность обрабатывается | re-init + pending buffer | ✅ |
| Local AI failure | без эскалации | FAILED без escalation (гейт приватности) | cloud только по политике | ✅ |
| Cloud AI failure | таймаут/ошибка | CLOUD_AI FAILED, CB на сервере | fallback/error | ✅ |
| Tool failure | понятная ошибка | «Не удалось выполнить: …» | — | ✅ |
| Permission failure | USER_ACTION_REQUIRED | отдельный статус, ≠ SUCCESS | подсказка | ✅ |
| Phone locked | wake продолжает | FGS-mic + WAKE_LOCK; **вендор-риск** | boot-restart | **BLOCKED** |
| App killed | START_STICKY | CR-11 restart-guard | system restart | **BLOCKED** |
| Android kills FGS | auto-restart | STICKY+FGS | — | **BLOCKED** |
| Headphones battery low | предупреждение | **NOT IMPLEMENTED** (нет телеметрии) | — | ❌ |
| Headset disconnect during response | довыполнить+держать результат | epoch-discard не теряет «старые» ответы; довоспроизведение при reconnect — не доказано | — | **BLOCKED** |

## 14. Interruption Model

| Событие | Детект | Действие | Статус |
|---|---|---|---|
| «Стоп» в слушающих режимах | stop-word list | handleCancel() | ✅ код |
| «Стоп» во время ответа (TTS_SPEAKING) | CR-01 игнорирует FinalResult | **НЕ прерывает** | **❌ гэп** |
| Новый wake-запрос во время ответа | wake word | QUEUE_FLUSH TTS + новый запрос | ⚠️ код да, runtime BLOCKED |
| Входящий звонок | PhoneStateListener/TelephonyCallback в сервисе | обработка в сервисе; поведение TTS при звонке — не проверено | PARTIAL/BLOCKED |
| Media стартует | — | **audio focus не используется** | **❌ не реализовано** |
| Notification arrives | FGS notification | не влияет на речь | ✅ (независимо) |
| BT reconnect | audioState | повторная маршрутизация | PARTIAL |

**Вердикт §14:** модель прерываний — самое слабое UX-место wearable-опытa:
отмена во время говорения и аудио-фокус отсутствуют. Это MUST для v0.3.

## 15. Power / Battery

```text
Phone battery (idle):              NOT MEASURED
Phone battery (wake-word active):  NOT MEASURED  [риск: FGS-mic всегда активен]
Phone battery (voice session):     NOT MEASURED
Phone battery (Local AI):          NOT MEASURED  [MediaPipe LLM — тяжёлый]
Phone battery (Cloud AI):          NOT MEASURED
Phone battery (BT connected):      NOT MEASURED
Earbud battery:                    NOT AVAILABLE (HARDWARE NOT IN REPO)
```

Код учитывает питание: BatteryOptimizationHelper (Doze-исключение), WAKE_LOCK.
Но wake word always-on + local LLM — два главных потребителя, оба не измерены.

## 16. Security / Privacy

**Какие данные покидают устройство:**
1. **Голос → системный STT**: `SpeechRecognizer` НЕ переведён в on-device режим
   (`EXTRA_PREFER_OFFLINE` отсутствует) → **текст распознавания может уходить
   вендору STT (Google)**. Это недооценённый приватный канал — wearable
   записывает весь день. MUST: on-device/приватный STT-вариант + явная политика.
2. **Cloud AI**: только по приватности-гейтам (PrivacyClassifier, per-request
   consent в production, PRIVATE/SENSITIVE не уходит без согласия — гейт
   ServerConfig:199) — архитектура сильная (закрытые пункты аудита).
3. **Экран**: Accessibility Privacy Guard (банк/пароли/2FA = BLOCK, закрытый
   пункт) — случайная утечка контента экрана в cloud перекрыта политикой.
4. **Логи**: санитизация секретов (закрытый пункт gitleaks/audit).
5. **Сервер**: не развёрнут публично (DV-03) — фактическая поверхность атаки сейчас нулевая, после деплоя — по RUNBOOK.

```text
Содержимое экрана/разговора в cloud СЛУЧАЙНО: перекрыто гейтами (код-уровень);
runtime-доказательство — BLOCKED (§4). STT-канал вендора — НЕ перекрыт.
```

## 17. Memory

| Требование | Статус | Факт |
|---|---|---|
| remember («Запомни, мою машину зовут Tesla») | ✅ код | `memory.remember` (isOffline, key→value) → JarvisMemoryManager → **Room (FactDao/FactEntity), локально** |
| retrieve («Какая машина у меня?») | ✅ код | `memory.recall` + word-overlap semantic matching (EmbeddingProvider неактивен — гэп B-4) |
| forget | ✅ код | `memory.forget` + ForgetResult |
| voice-вызов | ⚠️ | вызов идёт через LLM/agent (нужен tool-call) или fast router — на устройстве не доказан (BLOCKED) |
| долгосрочность/гovernance | ✅ код | processTurnGovernance, buildPromptMemoryContext — память включается в промпт |

Flow-пример из ТЗ реализуем кодом, но end-to-end на устройстве — BLOCKED.

## 18. Proactive AI

```text
REACTIVE VOICE LOOP STABILITY: NOT PROVEN (BLOCKED) → proactive остаётся заморожен.
```

Инвентарь proactive-кода (не активировать до стабилизации reactive): Ear
Briefing (PARTIAL — автотриггера нет), reminders/alarms (alarm_timer),
automation engine + observation engine (условия battery/time/app → действия),
CreateAutomationTool. По правилу 18: не наращивать до доказательства core loop.

## 19. Архитектура v0.3

Текущая структура УЖЕ соответствует концепции из ТЗ (CLIP→Voice→Local→Router→
Tools/Local/Cloud→Verify→TTS→CLIP) — **rewrite не нужен** (правила 19–20).
Предлагаемые минимальные дополнения (в порядке приоритета):

```text
1. Barge-in layer: обработка stop-слов в TTS_SPEAKING (снять CR-01 ограничение
   для явных cancel-фраз) + AudioFocusRequest на время TTS/STT
2. VoiceResponsePolicy (§9): короткие cloud-ответы, кап длины
3. Wire ReferenceResolver в CONTINUOUS_CONVERSATION (контекст follow-up уже есть)
4. OMNIX_TRACE (6-8 duration-маркеров) + счётчики route_local/route_cloud
5. On-device STT режим (EXTRA_PREFER_OFFLINE/createOnDeviceSpeechRecognizer)
   как privacy-опция с деградацией качества
6. BT supervisor: reconnect-политика + headset telemetry hooks (когда появится железо)
```

Каждый пункт — маленький PR, не трогающий ядро оркестратора целиком.

## 20. Product Gap

**Already works (code-level, device-unproven):** полный voice-контур (wake→STT→
route→execute→TTS), 39 инструментов, локальный-first routing с privacy-гейтами,
memory remember/recall/forget, подтверждения/очередь, follow-up окно 8с,
live ear interpreter, headset-only mode, boot/FGS/Doze-механика, сервер (license/
billing/ai-router/metrics), CI/тесты/invariants.

**Partially works:** follow-up контекст (окно есть, резолвер не подключён),
voice response policy (tools да, cloud нет), BT reconnect, briefing (без
автотриггера), on-device STT (не форсируется), health-инструменты (нет источника).

**Fake completeness:** ReferenceResolver/ConversationContext (существует, в голосе
не используется), Ear Briefing «при надевании» (триггера нет), «работает с
наушниками» (= со стандартными BT тоже).

**Missing:** Clip-интеграция целиком (HARDWARE NOT IN REPO), barge-in во время
TTS, audio focus, кнопка/жесты, телеметрия/батарея наушников, OTA, on-device STT
режим, duration-телеметрия.

**Critical (блокируют wearable-first):** 1) device validation (ничего не доказано
на телефоне); 2) отсутствие Clip-дифференциатора; 3) barge-in/отмена при ответе;
4) STT-приватный канал; 5) сервер не задеплоен (activation/cloud); 6) нет
латентной телеметрии.

## 21. Требования OMNIX v0.3

| Pri | Feature | Current state | Problem | Target state | Implementation | Dependencies | Risk | Priority |
|---|---|---|---|---|---|---|---|---|
| MUST | Device validation core loop | BLOCKED | ничего не доказано | ≥20 повторов, success-rate, latency | device-validation/ kit (готов) | телефон, подписанный APK | низкий | P0 |
| MUST | Подписанный release | fail-fast | нет артефакта | staging-APK | RELEASE.md §1–2 (владелец) | keystore | низкий | P0 |
| MUST | Сервер деплой | не развёрнут | activation/cloud недоступны | health=ok, E2E activation | PRODUCTION_DEPLOYMENT.md | VPS/домен | средний | P0 |
| MUST | Barge-in + cancel при TTS | CR-01 глушит | нельзя перебить ответ | «Стоп» работает всегда | точечный фикс в orchestrator + тесты | device-тест | средний (race-чувствительно) | P0 |
| MUST | Audio focus | отсутствует | конфликт с музыкой/звонками | focus для STT/TTS | AudioFocusRequest обвязка | device-тест | низкий | P0 |
| MUST | Приватный STT | системный cloud-STT | голос уходит вендору | on-device режим + политика | EXTRA_PREFER_OFFLINE + fallback | качество STT на ru | средний | P0 |
| MUST | OMNIX_TRACE телеметрия | duration-маркеров нет | latency неизмеряема | P50/P95 per stage | 6–8 Log-точек + счётчики | — | низкий | P0 |
| SHOULD | VoiceResponsePolicy | cloud-ответы длинные | chatbot-монологи | 1–2 фразы + «подробно?» | §9 pure-функция | — | низкий | P1 |
| SHOULD | Follow-up контекст | резолвер не подключён | «А завтра?» не работает | анафора в окне 8с | wire WorkingMemory в voice path | — | средний | P1 |
| SHOULD | Clip hardware protocol | отсутствует | нет дифференциатора | кнопка/телеметрия/activation | требует спецификацию железа | hardware team | высокий | P1 |
| SHOULD | Local LLM delivery + метрики | user-installed | неясная доля пользователей | on-demand download UX + замеры | DV-04 | модель-провайдер | средний | P1 |
| NICE | Embeddings для памяти | word-overlap | слабый recall | семантический поиск | активация EmbeddingProvider (B-4) | модель | низкий | P2 |
| NICE | Key pools, admin plane | — | масштабирование | B-2/B-5 | — | нагрузка | низкий | P2 |

## 22. Product Readiness Score

Оценки консервативные: учитывают только доказанное (код+CI+закрытые аудиты);
всё device-зависимое оценено по нижней границе.

| Category | Score |
|---|---|
| Clip hardware integration | **0/10** (NOT IN REPO) |
| Voice activation | 3/10 (код есть, фон/локскрин не доказаны) |
| STT | 3/10 (интегрирован; on-device/качество не доказаны) |
| Local Brain | 3/10 (контракт+JVM; модель не поставляется) |
| Tool execution | 4/10 (39 tools, JVM+instrumented) |
| Cloud AI | 3/10 (сервер-тесты CI; деплоя нет) |
| TTS | 4/10 (обвязка надёжная) |
| Bluetooth reliability | 2/10 (стандартный стек, reconnect слабый) |
| Voice UX | 2/10 (нет barge-in, контекст не подключён) |
| Hands-free task completion | **NOT MEASURED** → 0/10 доказанного |
| Latency | **NOT MEASURED** → 0/10 |
| Battery | **NOT MEASURED** → 0/10 |
| Privacy | 6/10 (сильные гейты; STT-канал открыт) |
| Security | 7/10 (закрытые аудиты; runtime BLOCKED) |
| Reliability | 2/10 (гвасты/эпохи в коде; runtime BLOCKED) |
| **Overall JARVIS readiness** | **≈2.5/10** |

## 23. Главный KPI Report

```text
Hands-Free Task Success Rate: NOT MEASURED
Local Task Share:             NOT MEASURED (код: 30/39 инструментов локальные)
Cloud Task Share:             NOT MEASURED
Average Latency:              NOT MEASURED
P50:                          NOT MEASURED
P95:                          NOT MEASURED
Failure Rate:                 NOT MEASURED
Battery Drain:                NOT MEASURED
```

Ни одно значение не подставлено — по правилам протокола. Источник будущих
цифр: device-validation kit + OMNIX_TRACE (P0 из §21).

## 24. Финальный вопрос

> **Может ли сегодняшний JARVIS реально заменить доставание телефона для значимой части повседневных задач?**

```text
NO
```

**WHY:**
1. **Ноль доказательств на устройстве**: весь контур существует только в коде и
   JVM/instrumented-тестах; по правилам протокола это не доказательство. Даже
   базовый «услышал→ответил» с заблокированного телефона не подтверждён.
2. **Clip-наушников в проекте нет** (HARDWARE NOT IN REPOSITORY): нет ни
   уникального интерфейса, ни телеметрии, ни кнопки — дифференциатор = 0.
3. **Ключевые wearable-UX механизмы отсутствуют**: нельзя перебить ответ («Стоп»
   при TTS игнорируется), нет audio focus, follow-up-контекст не подключён —
   «Диалог» из ТЗ пока полуфабрикат.
4. **Сервер не задеплоен** → activation и cloud-задачи недоступны конечному
   пользователю; подписанного APK тоже нет.
5. **Ни одной измеренной метрики** (latency/battery/success-rate) — продукт не
   может называться «быстрым и предсказуемым» без чисел.

При этом фундамент реален: архитектура точна, локальный контур богат (30
локальных инструментов), privacy-гейты образцовые. Это «собранный, но не
обкатанный двигатель» — дистанция до YES проходится roadmap'ом §25, а не rewrite'ом.

## 25. Финальный roadmap до OMNIX v0.3 (приоритет — качество core loop)

| # | Задача | Impact | Effort | Risk | Priority |
|---|---|---|---|---|---|
| 1 | Clip connectivity (пока: стандартный BT-надёжность + reconnect-политика; при железе: протокол/кнопка/телеметрия) | критичен для wearable | M (BT) / XL (железо) | средний/высокий | P0(BT)/P1(HW) |
| 2 | Voice activation в фоне/локскрин (доказать + чинить вендор-кейсы) | критичен | M | средний | P0 |
| 3 | STT reliability (+ on-device приватный режим) | критичен | S–M | средний | P0 |
| 4 | Local-first routing (сохранить; +счётчики route_local/cloud) | высокий | S | низкий | P0 |
| 5 | Tool reliability (device-прогоны 30 локальных, честные USER_ACTION) | критичен | M | средний | P0 |
| 6 | Result verification (телеметрия исходов + OMNIX_TRACE) | высокий | S | низкий | P0 |
| 7 | TTS (+VoiceResponsePolicy) | высокий | S | низкий | P1 |
| 8 | Interruption (barge-in «Стоп» при TTS + audio focus) | критичен | M | средний | P0 |
| 9 | Recovery (BT disconnect/reconnect, killed app, locked phone) | высокий | M | средний | P1 |
| 10 | Battery (замеры idle/wake/session/local-LLM; оптимизация wake) | высокий | M | низкий | P1 |
| 11 | Cloud fallback (деплой сервера, E2E activation, CB-поведение) | критичен | M | средний | P0 |
| 12 | Memory (wire follow-up контекст; embeddings позже) | высокий | S–M | средний | P1 |
| 13 | Translation (live ear — стабилизировать, замерить задержку) | высокий (уникальность) | S | низкий | P1 |
| 14 | Advanced agent (Plan→Act→Observe — уже есть; только стабилизация) | средний | — | низкий | P2 |
| 15 | Proactive (briefing автотриггер, suggestions) | средний | M | средний | **P3 — после доказательства reactive loop** |

**Критерий продвижения:** пункты 1–11 итерациями «реализуй → измерь на
устройстве → доказано (success-rate, P50/P95, battery) → следующий». Hands-free
KPI становится главным гейтом каждого релиза, начиная с первой device-сессии.
