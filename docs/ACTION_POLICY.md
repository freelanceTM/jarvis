# Политика безопасности действий (Action Policy)

**Ревизия:** `main` (после введения Policy Engine) · Компонент: `agent/policy/`

## Архитектура

LLM НЕ принимает критических решений. Модель только предлагает действие;
решение о риске и необходимости подтверждения принимает Policy Engine:

```
AI (LLM / FastCommandRouter)
 ↓
Proposed Action            (toolId + arguments + origin)   agent/policy/ActionPolicyModels.kt
 ↓
Policy Engine              ActionPolicyEngine.evaluate()
 ↓
Risk Level                 NONE → LOW → ELEVATED → HIGH → CRITICAL
 ↓
Confirmation?
 ├── NO  → Execute
 └── YES → Ask user (очередь подтверждений + одноразовые токены
            + привязка к каналу: голос ≠ чат)
```

Движок встраивается в существующий гейт `ToolPermissionManager.preflight()`
порядок: **capability → разрешения → политика → подтверждение** — бессмысленно
спрашивать подтверждение у невыполнимого действия.

## Что оценивает политика

| Вход | Источник | Манипуляция LLM |
|---|---|---|
| Категория действия | `toolId` (не аргументы!) | невозможна |
| Денежная сумма | содержимое аргументов (`MoneyAmountDetector`) | только текстом — детектор консервативен |
| Происхождение | `ActionOrigin` (USER_REQUEST / AUTOMATION) — выставляет код, не модель | невозможна |
| Статический пол риска | объявление автора инструмента (`ToolRisk`) | невозможна |
| Доверенные контакты | `ActionPolicySettings` (настройки пользователя) | невозможна |

## Форсированные правила (нельзя отключить настройками)

1. **Деньги в исходящем сообщении** — подтверждение всегда:
   «Отправь Ивану 50 000» → CRITICAL, prompt с суммой. Детектор понимает
   «50 000», «50000 рублей», «50 тысяч», «$100», «2.5 млн», денежные глаголы.
2. **PAYMENT-инструменты** (`payment.*`, `*transfer*`) — подтверждение всегда.
3. **DELETE-инструменты** (`memory.forget`, `*delete*`, `*wipe*`) — всегда
   («Действие необратимо»).
4. **ACCESSIBILITY-запись** (`accessibility.type_text`, `ui_click`) — всегда;
   чтение экрана (`screen_reader`) — разрешено без подтверждения.
5. **AUTOMATION + CALL/MESSAGE** — подтверждение всегда, даже при NEVER-политике
   и доверенном контакте: триггер-событие не имеет права звонить/писать
   самостоятельно (находка S-3 аудита: prompt injection через данные правила).

## Настраиваемые правила (`ActionPolicySettings`)

| Настройка | Значения | Дефолт |
|---|---|---|
| Звонки | ALWAYS / TRUSTED_ONLY / NEVER | ALWAYS |
| Сообщения | ALWAYS / MONEY_ONLY / NEVER | ALWAYS |
| Доверенные контакты | имена/номера (сравнение: последние 7 цифр телефона, регистронезависимое имя) | пусто |

Примеры:
- «Открой YouTube» → `APP_LAUNCH` → **execute**;
- «Позвони Ивану» → по политике звонков: ALWAYS → подтверждение,
  TRUSTED_ONLY → подтверждение только для чужих, NEVER → execute;
- «Отправь Ивану 50 000» → **обязательное подтверждение** при любой политике.

## Категории (`ActionCategory`)

| Категория | toolId | Решение по умолчанию |
|---|---|---|
| STATUS_READ | system.*, health.*, weather, vision | Allow |
| APP_LAUNCH | device.open_app | Allow |
| DEVICE_MUTATION | device.*, media.* | Allow |
| ACCESSIBILITY_READ | accessibility.screen_reader | Allow |
| ACCESSIBILITY_WRITE | accessibility.type_text, ui_click | **forced confirm** |
| CALL | communication.call | по политике звонков |
| MESSAGE | communication.sms, communication.telegram | по политике сообщений (+деньги форс) |
| PAYMENT | *pay*, *transfer* | **forced confirm** |
| DELETE | memory.forget, *delete*, *wipe*, *clear* | **forced confirm** |
| OTHER | memory.remember, intelligence.*, productivity.* | статический пол риска |

## Связь с очередью подтверждений

`PolicyDecision.RequireConfirmation` → `PreflightVerdict.ConfirmationRequired`
→ очередь `ToolExecutor` (одноразовые UUID-токены, владелец канала
VOICE/CHAT_UI, аудит bypass-вызовов). Между показом prompt и ответом
пользователя bypass повторяет preflight: политика переоценивается, разрешения
перепроверяются; форсированные правила при bypass легитимно consume
подтверждение, полученное от пользователя.

## Ограничения и следующий шаг

- Настройки пока in-memory (`DefaultActionPolicySettingsProvider`) с безопасными
  дефолтами; экран настроек + DataStore-персистенция — следующий шаг (контракт
  `ActionPolicySettingsProvider` уже готов).
- `PolicyDecision.Forbidden` сознательно не введён: в реестре нет запрещённых
  навсегда инструментов; когда появятся настоящие payment-операции, движок
  получит третью ветку без изменения архитектуры.
