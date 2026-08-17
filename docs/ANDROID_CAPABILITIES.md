# JARVIS v0.2 — что реально может Android

Документ фиксирует **фактические** возможности инструментов на современном
Android. Правило проекта:

> Не оставляй функцию выглядеть рабочей, если Android API не позволяет её выполнить.

Каждый инструмент возвращает структурированный `ToolExecutionResult` со статусом:

| Статус | Смысл | Состояние устройства изменилось? |
|---|---|---|
| `SUCCESS` | действие выполнено | да (если `actionRequiresUser = false`) |
| `PERMISSION_REQUIRED` | не хватает runtime-разрешения | **нет** |
| `USER_ACTION_REQUIRED` | Android требует действия пользователя в системном UI | **нет** |
| `UNSUPPORTED` | возможность недоступна на этом устройстве / API-level | **нет** |
| `FAILURE` / `TIMEOUT` | техническая ошибка | **нет** |
| `REQUIRES_USER_CONFIRMATION` | нужно подтверждение перед выполнением | ещё нет |

---

## Android Capability Layer (`JarvisCapability`)

Отдельный слой возможностей устройства — `agent/capability/JarvisCapability.kt`.
Группа (домен) → набор листовых проверок (`DeviceCapability`), которые реально
выполняет `DeviceCapabilityRegistry` (API-level, hardware, permission model):

| Группа | Листовые возможности |
|---|---|
| `device.bluetooth` | чтение состояния, переключение (только до Android 12), системный экран |
| `device.wifi` | чтение состояния, панель Wi-Fi (переключение — `USER_ACTION_REQUIRED`) |
| `device.brightness` | чтение, запись (`WRITE_SETTINGS`), настройки экрана |
| `device.screenshot` | AccessibilityService (API 30+), MediaProjection (согласие) |
| `device.apps` | запуск установленных приложений |
| `communication.sms` | прямая отправка (`SEND_SMS`), композер |
| `communication.call` | прямой вызов (`CALL_PHONE`), номеронабиратель |
| `media` | управление воспроизведением |
| `accessibility` | служба специальных возможностей JARVIS |
| `location` | определение местоположения (`LocationProvider`) |

Каждый инструмент домена реализует `CapabilityAwareTool` и объявляет:
`capabilityContract` (листовые гейты для preflight) и `capability` (группа слоя).

Статус группы — агрегация листьев по принципу «лучший доступный путь»:
`Available`, если есть хотя бы один рабочий путь; иначе — самый действенный
блокер: `PERMISSION_REQUIRED` (разрешения объединяются) →
`USER_ACTION_REQUIRED` → `UNSUPPORTED`. Снимок всего слоя:
`DeviceCapabilityRegistry.snapshotByGroup()`.

Слой расширяемый: новый домен = объект-группа + листовые проверки в
`DeviceCapability` и `DeviceCapabilityRegistry`.

---

## Bluetooth (`device.bluetooth`)

| Возможность | Статус | Причина |
|---|---|---|
| Чтение состояния адаптера | ✅ работает | нужен `BLUETOOTH_CONNECT` на API 31+ |
| Список сопряжённых устройств | ✅ работает | `BLUETOOTH_CONNECT` |
| Открыть системный экран Bluetooth | ✅ работает | `Settings.ACTION_BLUETOOTH_SETTINGS` |
| **Программно включить/выключить** | ❌ **невозможно** | `BluetoothAdapter.enable()` deprecated с API 33 и не работает для сторонних приложений на Android 13+ |

Запрос «включи Bluetooth» → `USER_ACTION_REQUIRED` + открытие системного экрана.

Пример диалога (честный UX, без имитации переключения):

```
Пользователь: Джарвис, включи Bluetooth
JARVIS:       Bluetooth сейчас выключен. Открываю настройки Bluetooth — переключите его там, сэр.
```

JARVIS никогда не возвращает `SUCCESS` для переключения, если оно не выполнено:
`BluetoothAdapter.enable()` запрещён сторонним приложениям на Android 13+.
Accessibility-клики по шторке быстрых настроек как способ обхода **удалены**:
это обход системной модели безопасности, а не легальная capability.

## Wi-Fi (`device.wifi`)

| Возможность | Статус | Причина |
|---|---|---|
| Чтение состояния, SSID, уровня сигнала | ✅ работает | SSID требует разрешения на локацию |
| Открыть системную панель Wi-Fi | ✅ работает | `Settings.Panel.ACTION_WIFI` |
| **Программно включить/выключить** | ❌ **невозможно** | `WifiManager.setWifiEnabled()` возвращает `false` для `targetSdk >= 29` (Android 10+) |

Запрос «включи Wi-Fi» → `USER_ACTION_REQUIRED` + открытие системной панели,
сначала сообщается текущее состояние:

```
Пользователь: Джарвис, включи Wi-Fi
JARVIS:       Wi-Fi сейчас выключен. Открываю панель Wi-Fi — переключите его там, сэр.
```

## Яркость (`device.brightness`)

| Возможность | Статус |
|---|---|
| Чтение текущей яркости | ✅ работает |
| Изменение яркости | ⚠️ только при выданном `WRITE_SETTINGS` |

Алгоритм изменения яркости (`SetBrightnessTool`):

```
Запрос
  ↓
canWrite()
  ↓
YES → реально изменить яркость (SCREEN_BRIGHTNESS, авторежим отключается)
  ↓
NO  → USER_ACTION_REQUIRED + открыть ACTION_MANAGE_WRITE_SETTINGS
```

Поддерживаются абсолютные и относительные команды:
«увеличь яркость до 80%» → `percent=80` (абсолютное),
«увеличь яркость на 20» → `delta=20` (относительное от текущей),
«ярче/темнее» → `delta=±10`, «на максимум/минимум» → 100/0.

Старое значение яркости **и режим автояркости** сохраняются в `rollbackData`
и восстанавливаются при откате (`rollback()`), если действие шло в цепочке
сценария. Проверка идёт через `Settings.System.canWrite(context)` — если
разрешения нет, возвращается `USER_ACTION_REQUIRED` и открывается
`ACTION_MANAGE_WRITE_SETTINGS`. Разрешение объявлено в манифесте — без этого
`canWrite` не вернёт `true` никогда.

## Скриншот (`device.screenshot`)

| API-level | Статус |
|---|---|
| Android 11+ (API 30+) с включённой службой JARVIS | ✅ `GLOBAL_ACTION_TAKE_SCREENSHOT` |
| Android 11+ без включённой службы | ⚠️ `USER_ACTION_REQUIRED` + экран спец. возможностей |
| Android 10 (minSdk 29) | ❌ `UNSUPPORTED` — системного API для этого нет |
| Окно с `FLAG_SECURE` | ❌ `FAILURE` (система отклоняет), не тихий успех |

MediaProjection объявлен в capability-реестре как требующий явного согласия
пользователя. В v0.2 он **не выдаётся за рабочий**: для consent-диалога нужен
Activity-хост и foreground-сервис типа `mediaProjection`, что вынесено за рамки
текущего milestone. Разрешение `FOREGROUND_SERVICE_MEDIA_PROJECTION` добавлено
в манифест заранее.

## SMS (`communication.sms`)

DangerLevel: **HIGH**, подтверждение обязательно.

| Ситуация | Результат |
|---|---|
| `SEND_SMS` выдано, номер найден | ✅ SMS реально отправлено |
| `SEND_SMS` не выдано | `PERMISSION_REQUIRED` — **сообщение не отправлено** |
| Контакт не найден | `FAILURE (CONTACT_NOT_FOUND)` |
| Нет телефонии | `UNSUPPORTED` |

Раньше при отсутствии разрешения открывался SMS-композер и возвращался
`SUCCESS` («SMS отправлено»), хотя сообщение отправлено не было. Путь
«композер = отправка» **полностью удалён из кода**: единственная отправка —
через `SmsManager` при выданном `SEND_SMS`.

**Confirmation Gate** (общий для SMS и звонков):

```
Пользователь:  Отправь маме: «Я буду через 20 минут»
JARVIS:        Отправить маме сообщение «Я буду через 20 минут»? Подтвердите, сэр.
               (REQUIRES_USER_CONFIRMATION — выполнение ещё не началось)
Пользователь:  Да.
JARVIS:        (resolveContact → permission check → отправка) → SUCCESS
Пользователь:  Нет. / таймаут 30 c
JARVIS:        Операция отменена, сэр. — ничего не отправлено
```

Подтверждение обрабатывается голосом («Да»/«Нет»/«подтверждаю»/«отмена»…)
и в текстовом чате: карточка с кнопками «Подтвердить»/«Отмена» над полем ввода,
плюс текстовые ответы «да/нет» тоже работают. Распознавание — единая утилита
`ConfirmationIntent` (общая для голосового флоу и чата). В голосовом флоу —
таймаут ожидания. Только после «Да» выполняется цепочка
`resolveContact() → проверка разрешения → SEND_SMS/ACTION_CALL → SUCCESS`.
Ни один из шагов не может вернуть `SUCCESS` раньше реального действия.

## Звонки (`communication.call`)

DangerLevel: **MEDIUM**, подтверждение обязательно.

| Ситуация | Результат |
|---|---|
| `CALL_PHONE` выдано, номер найден | ✅ `ACTION_CALL`, вызов начинается |
| `CALL_PHONE` не выдано | `USER_ACTION_REQUIRED`: открыт `ACTION_DIAL`, но **звонок не совершён** |
| Имя контакта не разрешилось в номер | `FAILURE (CONTACT_NOT_FOUND)` — раньше в `tel:` уходил текст «маме» |

## Погода (`intelligence.weather`)

Никакого зашитого города (бывший hardcode «Ashgabat» полностью отсутствует).
Локация — **параметр**, оба пути из ТЗ:

```
«Какая погода?»           → LocationProvider (GPS/NETWORK last known)
                                ↓
                            WeatherProvider (Open-Meteo, forecast по координатам)
                                ↓
                            Weather
«Какая погода в Берлине?» → Geocoder (open-meteo geocoding)
                                ↓
                            WeatherProvider
                                ↓
                            Weather
```

Команда «погода» маршрутизируется офлайн-роутером (< 10 мс, без LLM):
город после «в/во» извлекается и передаётся параметром `location`;
без города — пустые аргументы, тул сам берёт текущее местоположение.

Нет разрешения на локацию → `PERMISSION_REQUIRED` + предложение назвать город.
Нет координат → `FAILURE (LOCATION_UNAVAILABLE)`. Фейковая погода не выдаётся.

## Архитектура агента (AgentPipeline)

Единый конвейер JARVIS — `agent/pipeline/AgentPipeline.kt`:

```
                JARVIS
                   │
          ┌────────▼────────┐
          │ FastCommandRouter│  TIER 0: локальный NLU (<10 мс, офлайн)
          └────────┬────────┘
                   │
          ┌────────▼────────┐
          │ Agent Cognitive │  TIER 1: PLAN → EXECUTE → OBSERVE →
          │      Loop       │         VERIFY → REPLAN
          └────────┬────────┘
                   │
             PLAN / REPLAN     (CognitivePlanner: сценарии + LLM-планы)
                   │
          ┌────────▼────────┐
          │ Tool Discovery  │  (ToolRegistry.discoverRelevantTools)
          └────────┬────────┘
                   │
          ┌────────▼────────┐
          │  Tool Executor  │
          └────────┬────────┘
                   │
          ┌────────▼────────┐
          │   Observation    │  (AgentObservationEngine)
          └────────┬────────┘
                   │
              VERIFY           (проверка результата по экрану)
                   │
              SUCCESS
```

`SendPromptUseCase` — тонкая обвязка (анафора, память, сохранение сообщений);
вся агентская логика — в pipeline.

Дерево TOOLS v0.2 (честное состояние):

```
TOOLS
├── Device        WiFi ✓ Bluetooth ✓ Brightness ✓ Screenshot ✓ Volume ✓ Flashlight ✓ Apps ✓
├── Communication Call ✓ SMS ✓ Contacts ✓ Telegram ✓ (открытие+share; отправка — пользователем)
├── Intelligence  Web Search ✓ Weather ✓ Translation ✓ Memory ✓ Vision ⚠ (UNSUPPORTED — нет модели)
├── Automation    Time ✓ (TIME_SCHEDULE) App Events ✓ Scheduled Tasks ✓ Location ⚠ (геозоны — будущее)
└── Health        Wear OS ⚠ Heart Rate ⚠ Steps ⚠ Sleep ⚠ Activity ⚠ (UNSUPPORTED — нет источников данных)
```

`⚠` — честные заглушки-контракты: возвращают UNSUPPORTED с причиной вместо
выдуманных данных (как EmbeddingProvider).

## Agent Cognitive Loop: PLAN → EXECUTE → OBSERVE → VERIFY → DONE/REPLAN

Состояние агента при выполнении многошаговых планов:

```
PLAN
 ↓
EXECUTE (инструмент выполнен)
 ↓
OBSERVE (структурированное наблюдение: success/stateChanged/error/nextActionHint)
 ↓
VERIFY  (если шаг требует проверки: чтение экрана + поиск ожидаемого текста)
 ↓
SUCCESS?
 ├── YES → DONE (следующий шаг / завершение плана)
 └── NO  → REPLAN (до MAX_REPLANS=2, бюджет шагов MAX_TOTAL_STEPS=12)
```

Ключевое отличие от «голосового чат-бота»: инструмент может вернуть SUCCESS,
но цель считается достигнутой только после VERIFY. Шаг плана может нести
`verifyScreenContains` — тогда после выполнения агент читает экран
(`accessibility.screen_reader`) и проверяет наличие ожидаемого текста.

Пример — «Открой YouTube и найди UFC» (сценарий планировщика):

```
1. device.open_app(youtube)
2. accessibility.ui_click("поиск")      — найти поле поиска
3. accessibility.type_text("UFC")       — ввести запрос (ACTION_SET_TEXT)
4. accessibility.screen_reader          — VERIFY: «UFC» на экране?
                                          └─ нет → REPLAN (честно: цель не подтверждена)
```

`accessibility.type_text` — инструмент ввода текста через Accessibility
(ACTION_SET_TEXT в сфокусированное/первое редактируемое поле). Если служба
специальных возможностей выключена — `USER_ACTION_REQUIRED`; поля на экране
нет — `FAILURE (NO_EDITABLE_FIELD)`.

## AutomationEngine (Event → RuleMatcher → List<AutomationRule> → execute all)

Архитектура v0.2 вместо «одно событие → одно правило»:

```
Event
 ↓
RuleMatcher
 ↓
List<AutomationRule>   (все подходящие, отсортированы по приоритету)
 ↓
execute all matching rules
```

Одно событие матчит ВСЕ правила: подключение наушников может одновременно
выставить громкость, прочитать уведомления и открыть приложение. Правила
выполняются по приоритету (больший — раньше), сбой одного не останавливает
остальные, кулдаун защищает от «дребезга» событий.

**Расписание (TIME_SCHEDULE)**: правило с triggerParam "HH:MM" срабатывает
в точное время. Пример 07:00 — утреннее расписание выполняет 4 действия:
открыть календарь, сказать погоду, прочитать расписание, сообщить важные
задачи (дефолтное правило `default_morning_schedule`). Создать расписание
можно через `productivity.create_automation` с trigger_type=TIME_SCHEDULE
и trigger_param="07:00".

## Разрешение приложений (`device.open_app`)

Каскад «фраза → приложение» (чистая логика `AppMatchCascade`, без Android):

```
exact match      → точное имя пакета / точное название приложения
normalized match → «компактные» формы («You Tube» → «youtube»)
alias match      → разговорные алиасы + ласкательные префиксы («ютубчик»)
fuzzy match      → нечёткое сходство (Левенштейн/подстрока, порог 0.72)
semantic match   → лексико-семантическое сходство (SemanticTextMatcher)
```

Примеры: «ютуб», «ютюб», «youtube», «You Tube», «ютубчик» →
`com.google.android.youtube`.

ОБЯЗАТЕЛЬНАЯ проверка установленности: каскад работает только по списку
реально установленных приложений; известный, но не установленный пакет
возвращает `NotInstalled`, а не открывается.

## Медиа-интенты (`media.control`)

Явная модель намерений вместо «фраза → произвольное действие» (раньше
«включи музыку» могло превратиться в NEXT):

| Интент | Пример фразы | Действие |
|---|---|---|
| `PLAY` | «включи музыку» | `KEYCODE_MEDIA_PLAY` |
| `PAUSE` | «поставь на паузу» | `KEYCODE_MEDIA_PAUSE` |
| `RESUME` | «продолжи музыку» | `KEYCODE_MEDIA_PLAY` (продолжение, не перезапуск) |
| `NEXT` | «следующий трек» | `KEYCODE_MEDIA_NEXT` |
| `PREVIOUS` | «предыдущий трек» | `KEYCODE_MEDIA_PREVIOUS` |
| `STOP` | «выключи музыку» | `KEYCODE_MEDIA_STOP` |
| `TOGGLE` | «плей/пауза» | `KEYCODE_MEDIA_PLAY_PAUSE` |
| `VOLUME_UP` | «сделай музыку громче» | `device.volume` (up) |
| `VOLUME_DOWN` | «сделай музыку тише» | `device.volume` (down) |

Правила разбора: специфичные паттерны («следующий трек») проверяются раньше
общих («включи»), RESUME — раньше PAUSE («продолжи после паузы» = RESUME),
а громкость распознаётся только в медиа-контексте («сделай громче» без слова
«музыка/трек» остаётся командой громкости устройства).

## Синхронный переводчик (`LiveTranslatorEngine`)

Конвейер v0.2:

```
Microphone
   ↓
Speech Recognition (STT, continuous full-duplex)
   ↓
Language Detection (AUTO-режим, эвристика по письменности)
   ↓
Translation Engine (LLM-провайдер, онлайн)
   ↓
TTS (speakQueued — не блокирует запись следующей фразы)
   ↓
Bluetooth Earbud (SCO)
```

**Быстрые режимы (пресеты):**

| Режим | source → target |
|---|---|
| `AUTO` | язык собеседника определяется автоматически → RU |
| `RU → TM` | русский → туркменский |
| `TM → RU` | туркменский → русский |
| `EN → RU` | английский → русский |
| `RU → EN` | русский → английский |

`AUTO`-режим: STT запускается без фиксированного языка, язык фразы
определяется детектором по письменности (кириллица → ru, латиница с маркерами
ň/ý/ž → tk, ğ/ı → tr, ß → de, иначе en, CJK → zh, арабская вязь → ar).
Бэкенд перевода в AUTO получает `sourceLang="auto"` и определяет язык сам.

Поддерживаемые языки: `ru, en, tk, tr, de, zh, ar` — список расширяемый.

**Честность офлайна:** офлайн-модели перевода в проекте НЕТ. Движок возвращает
`MODEL_UNAVAILABLE`, а не подменяет перевод исходным текстом; без сети —
`NETWORK_REQUIRED`. Онлайн-перевод работает только при подключении к интернету.

## Русская анафора (Context Resolver)

Разрешение местоимений строится на **типизированных сущностях** (Entity):

```
Entity
 ├── person        — человек/контакт
 ├── application   — приложение (Telegram, YouTube)
 ├── location      — место (Берлин, дом)
 ├── object        — предмет/файл/тема
 └── conversation  — диалог/канал (чат в Telegram)
```

Контекст диалога: `lastPerson`, `lastApp`, `lastLocation`, `lastMessage`,
`lastAction`, `lastContact`, `lastConversation`. Regex используется только
для **детекции** ссылки («ей/ему/там/это»); решение принимается по слотам,
а при отсутствии слота JARVIS честно задаёт уточняющий вопрос.

```
«Позвони маме.»  → lastContact = мама
«Напиши ей.»     → ей = маме  («напиши маме» — падеж через MorphologyResolver)
«Открой Telegram.» → lastApp = Telegram
«Напиши ему.»    → «напиши в Telegram» (приложение-канал, если контакт неизвестен)
```

**Морфология** делегируется отдельному NLP-модулю `MorphologyResolver`
(интерфейс + честная минимальная реализация `BasicMorphologyResolver`:
словарь семейных терминов + простое правило имён). Полноценная морфология
русского языка намеренно НЕ изобретается набором regex — это будущий
отдельный NLP-модуль.

## Семантический поиск — честная архитектура (НЕ embeddings)

`SemanticTextMatcher` (ранее назывался бы «VectorEmbeddingEngine») — это
**лексико-семантический матчер**, а НЕ neural embedding model. Внутри:
ручной словарь корней, проекция синонимов, хеш-отпечатки слов, би-/триграммы
и косинусное сходство. Он работает для Tool Discovery и грубого поиска по
памяти, но НЕ переносит смысл на незнакомые слова и не учитывает контекст.

Настоящие embeddings — отдельный будущий слой с честным контрактом:

```
EmbeddingProvider
 ├── LocalEmbeddingProvider   (ONNX/TFLite в APK — в v0.2 модель НЕ включена)
 └── RemoteEmbeddingProvider  (удалённый эндпоинт — в v0.2 НЕ настроен)
```

Оба провайдера в v0.2 возвращают `isReady() = false` и `embed() = null`
с объяснением причины — никаких «примерных» векторов, которые незаметно
исказили бы поиск по памяти.

## Не поддерживается сознательно

* Root / accessibility abuse для обхода системных ограничений.
* Автоматическое переключение Bluetooth/Wi-Fi «в обход» Android.
* Офлайн-перевод: модели в проекте нет, движок возвращает `MODEL_UNAVAILABLE`,
  а не подменяет перевод исходным текстом.
