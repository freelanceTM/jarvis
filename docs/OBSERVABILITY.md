# Observability: единый request ID через весь путь запроса

**Ревизия:** `main` · Один идентификатор на пользовательский запрос:

```
Voice → Router → Tool → AI → Server → Provider
```

Формат: **`omx_01J…`** — префикс `omx_` + ULID (26 символов Crockford
Base32: 48 бит ms-timestamp + 80 бит случайности). Свойства: лексикографическая
сортировка = хронологическая; уникальность без координации; 30 символов —
укладывается в серверный лимит 64. Генератор — `core/request/RequestIds.kt`
(часы/рандом инжектируются — тесты детерминированы).

## Где живёт id на каждом этапе

| Этап | Кто пишет/несёт | Где посмотреть |
|---|---|---|
| **Voice** | `VoiceInteractionOrchestrator.processUserQuery` генерирует id в момент финального STT и логирует `voice query accepted \| requestId=…` | logcat |
| **Router** | id в `ExecutionRequest.requestId` (генерируется один раз; `copy()` сохраняет); `ExecutionDecisionEngine.logRequest/logRoute` пишут его в КАЖДУЮ строку маршрута | logcat |
| **Tool** | тот же id в `ExecutionResult.metadata["request_id"]` (device tool / local / cloud / direct) | метаданные результата |
| **AI (клиент)** | `JarvisApiClient.execute(requestId=…)` — раньше здесь рождался ОТДЕЛЬНЫЙ UUID (разрыв корреляции); теперь принимает агентский id, логирует `api request/response \| requestId=…` | logcat |
| **Server** | `JarvisApiHandler` принимает клиентский id (≤64, не пустой), логирует им все события, пишет в `ai_usage_records.request_id` (**UNIQUE с client_id** — заодно идемпотентность ретраев), возвращает эхом в ответе и ошибках | `ai_usage_records`, `GET /v1/admin/logs?component=CLOUD` (колонка `requestId`) |
| **Provider** | строка `ai_usage_records` несёт пару (request_id, provider, latency, success, error_code) — «открыть один запрос и увидеть, какой провайдер его выполнил» | админка → Requests → cloud |

## Как открыть «один запрос»

1. В logcat: `requestId=omx_01J…` → grep — все строки клиента одной реплики
   (voice accept, route=…, api request/response).
2. В админке: Requests (logs, CLOUD) → колонка requestId → строка
   `(client_id, request_id)` уникальна: попытки провайдера, латентность,
   success/error_code, стоимость (`/usage/cost`).

## Правила

- Id генерируется **на клиенте** (сервер — не источник истины для
  корреляции; сервер генерирует свой только если клиентский отсутствует/битый).
- Согласованность: один id на весь голосовой turn; копии `ExecutionRequest`
  и все downstream-контракты (`AIRepository` → `AIClient` → `JarvisApiClient`)
  сохраняют его; legacy-вызовы (переводчик) получают свежий omx-id в
  `JarvisApiClient` — тоже коррелируемые, просто без агентного контекста.
- Тексты запросов по-прежнему НЕ логируются нигде — id коррелирует
  метаданные, не контент (пункт 20 ТЗ, §28).
- Честные «-»: pre-parse ошибки сервера (rate-limit до парсинга, TLS-гейт)
  физически не знают клиентского id — там остаётся `-`; это документировано,
  а не скрыто.
