# Smart Cloud Router: выбор лучшего провайдера по измерениям

**Ревизия:** `main` · Сервер: `server/provider/` · Смежное: `docs/LICENSE_BILLING.md`, Control Plane (секция cost)

## Схема

```
AI Gateway (JarvisApiHandler)
      ↓
Provider Router (ProviderManager)
      ↓
┌──────────┬──────────┬────────────┐
Groq       Gemini     OpenRouter
```

Схема существовала и раньше; новым является ОТБОР: не статический приоритет
и не random, а **best provider** по измеренным показателям.

## Что хранится на каждого провайдера

| Показатель | Источник | Где |
|---|---|---|
| `latency` | EMA времени успешных вызовов (α=0.2), измеряется `ProviderManager` | `ProviderPerformanceTracker` (in-memory) |
| `errors` | successes/failures → success rate | там же |
| `429` | счётчик `RATE_LIMITED` и его доля | там же |
| `availability` | success rate + circuit breaker (`ProviderHealthTracker`: CLOSED/OPEN/HALF_OPEN) | `ProviderHealthTracker` |
| `cost` | конфигурируемые цены USD/1М токенов (admin settings, секция `cost` → `CostSettings`) | `AdminSettingsService.cost()` → `CostPriceSource` |

## Выбор

`SmartProviderSelectionPolicy` (реализация `ProviderSelectionPolicy`, точка
расширения, предусмотренная AR-02):

```
score = 0.35·latency + 0.35·reliability + 0.15·(1 − 429share) + 0.15·cost
```

- latency и cost — min-max нормализация среди кандидатов (быстрейший/дешевлейший = 1);
- компонент без достаточных данных (< `MIN_SAMPLES`=5 измерений или <2 кандидатов с данными) = нейтраль 0.5; **неизвестная цена — нейтраль, не ноль** (AR-02);
- tie-break — статический приоритет из конфига/overrides; детерминированно, без случайности;
- жёсткие фильтры как раньше: enabled/configured, capabilities (web/toolCalling), circuit breaker (`isAvailable`).

## Cold start

Пока ни у одного кандидата нет ≥5 измерений, порядок ровно прежний
(статический приоритет) — поведение сервера не меняется. Статистика in-memory
и обнуляется рестартом; на этот период роутер честно откатывается к
приоритетам. Ниже порога единичный замер роутинг не двигает.

## Интеграция

- `ProviderManager` — единственная точка, где проходят реальные исходы:
  success → `performance.recordSuccess(id, latency)`, failure →
  `performance.recordFailure(id, kind)`; snapshot — `performanceSnapshot()`.
- Цены: `Main` регистрирует `CostPriceSource` после создания
  `AdminSettingsService`; политика читает цены на КАЖДЫЙ отбор — обновления
  в admin подхватываются без рестарта. Формат конвертируется одной функцией
  (`costEstimates`: USD/1М → USD/1K).
- `DefaultProviderSelectionPolicy` сохранён (используется тестами и как
  семантический эталон cold start).

## Тесты и инварианты

`SmartProviderSelectionPolicyTest` (JVM): cold start = статический порядок,
порог MIN_SAMPLES, быстрейший обгоняет приоритет, 429-штраф, дешёвый побеждает
при равном качестве, сбоивший — последний, circuit-OPEN исключён, cold-start
эквивалентен legacy-политике; интеграция с менеджером (429 и латентность
записываются). Инварианты `SMART-ROUTER:*` в
`scripts/verify-architectural-invariants.sh`.
