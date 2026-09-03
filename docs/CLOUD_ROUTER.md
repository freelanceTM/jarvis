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

## Fallback и бюджет попыток (не бесконечные retries)

Ваша цепочка — существующее поведение `ProviderManager`:

```
Groq ──429 (RATE_LIMITED, не ретраится у того же)──▶  Gemini ──timeout──▶  ...
```

| Параметр | Дефолт | Смысл |
|---|---|---|
| `maxProviderAttempts` | **2** | максимум провайдеров на ОДИН запрос (fallback-цепочка обрезается) |
| `maxRetriesPerProvider` | **0** | повторов у одного провайдера нет; включать только осознанно |
| retry только для | TIMEOUT/CONNECTION/SERVER_ERROR | `isRetryable`; 429 у того же провайдера НЕ повторяется, AUTH/NOT_CONFIGURED — permanent (вывод из ротации) |
| backoff | пропускается, если deadline истечёт | CR-06 |

**Худший случай платных вызовов на одну команду пользователя:**

```
maxProviderAttempts × (1 + maxRetriesPerProvider) = 2 × 1 = 2   (дефолт ≤ 3 ✓)
```

Временной бюджет проверяется на старте (`provider timeout budget exceeds
request deadline` warning в Main.kt) и перед каждой попыткой (CR-06 deadline
guard). Клиент повторов НЕ делает («локальный повтор снят: сервер уже делает
controlled retry») — платные попытки не умножаются между слоями.

Тесты-доказательства: `fallback chain respects max provider attempts`,
`rate limited provider is not retried but falls back`,
`invalid api key disables provider without pointless retries`,
`combined retry and fallback attempts are finite and capped` (2×2=4 при
осознанно включённых retry — верхняя граница, не бесконечность),
`shipped defaults cap one user command at two paid attempts`.

## Тесты и инварианты

`SmartProviderSelectionPolicyTest` (JVM): cold start = статический порядок,
порог MIN_SAMPLES, быстрейший обгоняет приоритет, 429-штраф, дешёвый побеждает
при равном качестве, сбоивший — последний, circuit-OPEN исключён, cold-start
эквивалентен legacy-политике; интеграция с менеджером (429 и латентность
записываются). Инварианты `SMART-ROUTER:*` в
`scripts/verify-architectural-invariants.sh`.
