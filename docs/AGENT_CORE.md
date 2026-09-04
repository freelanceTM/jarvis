# Agent Core: цикл Understand → Plan → Act → Observe → Verify → Re-plan

**Ревизия:** `main` · Код-ревью агентского ядра. Главный принцип: **не
использовать агента там, где достаточно Tool** (одиночная команда «Открой
Telegram» не должна платить за cognitive loop), и наоборот — многошаговая
задача («найди лучший X и сравни») не должна съедаться одиночным
инструментом.

## Куда уходит запрос (лестница решений, ExecutionDecisionEngine.decide)

```
FastCommandRouter (<10 мс, rule-based)          ← P1: Tool, не Agent
   «открой telegram» → device.open_app (conf .95)
   «привет» → готовая реплика
   Unknown ↓
CognitivePlanner.planForGoal(text, null)        ← P2 (детерминированный AGENT)
   Сценарий 0: «открой X и найди Y» → 4 шага
     open_app → ui_click(поиск) → type_text → screen_reader(verify)
   Сценарии 1–10 («я ухожу», «режим сна», …) → 2–4 шага
   plan == null ↓
Local AI (Gemma / WorkflowExecutor, офлайн)     ← P3
   Uncertain ↓
Cloud AI                                        ← P4
   → если в ответе модели tool_calls:
       ровно 1 шаг  → ПРЯМОЙ tool-путь (P1-семантика, НЕ агент)  ← фикс
       ≥ 2 шагов    → CognitivePlanner план → AGENT (cognitive loop)
```

Все детерминированные сценарии планировщика многошаговы (проверено: все
`ScenarioPlanBuilders` строят 2–4 шага; одиночный «открой Telegram» до
планировщика не доходит — его забирает FastCommandRouter на P1).

## Цикл (AgentCognitiveLoop) и его guardrails

| Стадия цикла | Где в коде | Защита |
|---|---|---|
| Understand | анафора `WorkingMemory.resolveContextualQuery` **до** движка; `clarificationPrompt` («открой» без объекта → вопрос, ни один executor не вызывается) | тест `underspecified commands request clarification before any executor` |
| Plan | `CognitivePlanner.planForGoal` (сценарии) / `toolCallParser.parse` (LLM tool_calls) | — |
| Act | `ToolExecutor.execute` (privacy gate → preflight → execute) | policy: подтверждения, forced rules |
| Observe | `AgentObservationEngine.shouldExecuteStep` (pre) + `observe` (post) | hint-контракт: PERMISSION/AWAIT_USER_ACTION/ABORT не репланят |
| Verify | `verifyOnScreen`: `verifyScreenContains` шага → чтение экрана → цель подтверждена | «Открыл YouTube» ≠ успех, пока текст не на экране |
| Re-plan | `CognitivePlanner.replan` (альтернатива/пропуск), только если `observation.isReplanWorthwhile` | MAX_REPLANS=2, MAX_TOTAL_STEPS=12, LOOP_BUDGET_MS=8000 (wall-clock на весь цикл), per-tool 4 c |

Бюджеты AR-03 не менять без device-измерений: 8 с подобраны до ANR-порога.

## Что закрыто этой ревизией

1. **Один tool_call из ответа модели = Tool, не Agent.** Раньше ЛЮБОЙ план из
   tool_calls облачной модели (включая одношаговые) запускал cognitive loop
   (`withTimeout(8s)`, observations, replan-машина) ради одного вызова.
   Теперь одношаговый план идёт тем же прямым путём, что и команды
   FastCommandRouter: privacy gate → policy → честный итог
   (`DecisionReason.CLOUD_PLAN_SINGLE_TOOL`).
2. **Policy-гейт на cloud-плане.** Fail-closed проверка
   `mayDiscloseExternally` была только у детерминированного AGENT-плана;
   tool_calls из ответа модели исполнялись без неё. Теперь тот же гейт:
   не-NORMAL приватность + внешние инструменты → блокировка всего плана
   (LLM предлагает — policy решает).

## Не менять

- Порядок P2 (AGENT) до P3 (LOCAL AI) — сохранённое поведение AgentPipeline:
  многошаговый запрос не должен «съедаться» одиночным офлайн-сценарием.
- `RecallMemoryTool`-через-LLM для явных «что ты помнишь» — retrieval-блок
  (docs/MEMORY.md) покрывает пассивный контекст, тул — явные вопросы.
