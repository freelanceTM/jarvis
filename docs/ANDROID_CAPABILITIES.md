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

Проверка идёт через `Settings.System.canWrite(context)`. Если разрешения нет —
`USER_ACTION_REQUIRED` + переход на `ACTION_MANAGE_WRITE_SETTINGS`.
Реализована запись `SCREEN_BRIGHTNESS` (с отключением авторежима) и rollback.
Разрешение объявлено в манифесте — без этого `canWrite` не вернёт `true` никогда.

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
`SUCCESS` («SMS отправлено»), хотя сообщение отправлено не было.

## Звонки (`communication.call`)

DangerLevel: **MEDIUM**, подтверждение обязательно.

| Ситуация | Результат |
|---|---|
| `CALL_PHONE` выдано, номер найден | ✅ `ACTION_CALL`, вызов начинается |
| `CALL_PHONE` не выдано | `USER_ACTION_REQUIRED`: открыт `ACTION_DIAL`, но **звонок не совершён** |
| Имя контакта не разрешилось в номер | `FAILURE (CONTACT_NOT_FOUND)` — раньше в `tel:` уходил текст «маме» |

## Погода (`intelligence.weather`)

Никакого зашитого города. Локация — **параметр**:

```
weather()                 → текущее местоположение (LocationProvider)
weather(location=Берлин)  → геокодинг названного города
```

Нет разрешения на локацию → `PERMISSION_REQUIRED` + предложение назвать город.
Нет координат → `FAILURE (LOCATION_UNAVAILABLE)`. Фейковая погода не выдаётся.

## Не поддерживается сознательно

* Root / accessibility abuse для обхода системных ограничений.
* Автоматическое переключение Bluetooth/Wi-Fi «в обход» Android.
* Офлайн-перевод: модели в проекте нет, движок возвращает `MODEL_UNAVAILABLE`,
  а не подменяет перевод исходным текстом.
