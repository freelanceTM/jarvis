# Execution Decision Engine v0.2 (Этап 1)

Единый **слой принятия решения** поверх существующих механизмов выполнения.
Ничего из существующей архитектуры не переписано: движок только выбирает,
**что** выполнить, а **как** выполнять — знают уже существующие компоненты.

## Поток

```
Voice / Chat
     ↓
STT (SpeechRecognizerManager) — не изменён
     ↓
SendPromptUseCase → AgentPipeline
     ↓
ExecutionDecisionEngine
     ├── Device Tool → ToolExecutor → JarvisTool
     ├── Local AI    → WorkflowExecutor (процедурная память, офлайн)
     ├── Cloud AI    → AIRepository → UniversalAIClient
     └── Agent       → CognitivePlanner → AgentCognitiveLoop
     ↓
ExecutionResult → PromptExecutionResult → TTS / Chat UI
```

## Приоритеты

| # | Путь | Условие | ExecutionType |
|---|------|---------|---------------|
| 1 | Device Tool | `FastCommandRouter` уверен (confidence ≥ порога) | `DEVICE_TOOL` |
| 2 | Agent | `CognitivePlanner` построил многошаговый план | `AGENT` |
| 3 | Local AI | сработал офлайн-сценарий пользователя | `LOCAL_AI` |
| 4 | Cloud AI | всё остальное, если приватность и сеть позволяют | `CLOUD_AI` |

Порядок 2 → 3 повторяет прежнее поведение `AgentPipeline` (динамический план
проверялся до процедурной памяти) — обратная совместимость сохранена.

## Confidence

`FastCommandRouter` — детерминированный rule-based NLU и **не возвращает**
числовой уверенности. Второй механизм confidence не создавался: адаптер
`FastRouteConfidence` присваивает существующему `FastRouteResult` фиксированные
значения.

| FastRouteResult | confidence | CommandRoutingResult |
|---|---|---|
| `HandledLocally(toolCall != null)` | `0.95` | `DeviceCommand` |
| `HandledLocally(toolCall == null)` | `0.80` | `DirectResponse` |
| `ForwardToLlm` | `0.0` | `Unknown` |

Порог — `ExecutionDecisionConfig.deviceConfidenceThreshold = 0.75f`.

> **TODO:** порог требует калибровки на реальных логах. Текущее значение
> подобрано так, чтобы полностью сохранить существующее поведение приложения.

## Privacy

`PRIVATE` и `SENSITIVE` **никогда** не уходят в облако без
`ExecutionRequest.cloudExplicitlyAllowed = true`. Если локальный слой не
справился — возвращается честный `ExecutionResult.Error` с причиной
`CLOUD_BLOCKED_BY_PRIVACY`, а не тихая отправка в сеть.

Текст приватного запроса не логируется: `ExecutionRequest.loggableText`
возвращает `<redacted:N chars>`.

> **TODO:** автоматической классификации приватности пока нет — уровень задаёт
> вызывающий слой (по умолчанию `NORMAL`, поведение не изменилось).

## requiresWeb

Локальный слой — это процедурная память, а не нейросетевая модель:
`LocalAiExecutor.hasWebCapability == false`. При `requiresWeb = true` движок
**пропускает** локальный путь, чтобы тот не имитировал успех, и уходит в
Cloud/Agent. Новых web-интеграций на этом этапе не добавлено.

## Честная формулировка «Local AI»

Нейросетевой локальной LLM в проекте нет (см. `README.md` и
`docs/ANDROID_CAPABILITIES.md` — офлайн-модели и embeddings не подключены).
`ExecutionType.LOCAL_AI` означает **локальный офлайн-слой**:
`WorkflowExecutor` — сохранённые пользовательские сценарии, выполняемые
без сети. Когда появится настоящая on-device модель, достаточно будет добавить
её реализацию `LocalAiExecutor` — контракт менять не придётся.

## Error handling

`ExecutionDecisionEngine.execute()` не выбрасывает исключений. Любая
неожиданная ошибка логируется (`Log.e` с исключением) и превращается в
`ExecutionResult.Error` с обобщённым сообщением — stack trace пользователю
не показывается. Обычное «не могу выполнить» — это тоже `Error`, а не
исключение.

## Логи

Тег `DecisionEngine`:

```
request received | source=VOICE | privacy=NORMAL | requiresWeb=false | ... | text=включи фонарик
route=DEVICE_TOOL | reason=FAST_ROUTER_CONFIDENT | confidence=0.95
route=LOCAL_AI | reason=FAST_ROUTER_UNCERTAIN | confidence=0.0
route=CLOUD_AI | reason=LOCAL_AI_UNCERTAIN | confidence=0.0
route=BLOCKED  | reason=CLOUD_BLOCKED_BY_PRIVACY | privacy=PRIVATE
```

## Файлы

Создано:

- `agent/decision/ExecutionModels.kt` — `ExecutionRequest`, `ExecutionResult`,
  `ExecutionType`, `RequestSource`, `PrivacyLevel`, `DecisionReason`,
  `ExecutionDecisionConfig`
- `agent/decision/CommandRoutingResult.kt` — адаптер confidence над FastCommandRouter
- `agent/decision/ExecutionPorts.kt` — контракты `LocalAiExecutor`, `CloudAiExecutor`, `AgentExecutor`
- `agent/decision/ExecutionAdapters.kt` — адаптеры над существующими компонентами
- `agent/decision/ExecutionDecisionEngine.kt` — сам движок
- `app/src/test/.../agent/decision/ExecutionDecisionEngineTest.kt` — 16 unit-тестов

Изменено (минимально):

- `agent/pipeline/AgentPipeline.kt` — стал тонким адаптером к движку
  (публичный контракт `process()` сохранён)
- `domain/usecases/SendPromptUseCase.kt` — прокидывает `source` / `privacyLevel`
  (параметры со значениями по умолчанию — существующие вызовы не сломаны)
- `voice/orchestrator/VoiceInteractionOrchestrator.kt` — одна строка:
  `source = RequestSource.VOICE`
- `di/HiltModules.kt` — новый `ExecutionDecisionModule`

Не изменялись: `FastCommandRouter`, `ToolExecutor`, `JarvisTool`,
`AgentCognitiveLoop`, `CognitivePlanner`, `WorkflowExecutor`, `AIRepository`,
STT, TTS.
