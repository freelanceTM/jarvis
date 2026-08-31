# OMNIX — верификация плана v0.3 по реальному коду

**Дата:** 2026-08-31
**Верифицировано против:** `main@59375b8` (после PR #40/#41/#42/#43)
**Метод:** каждый тезис плана развития (ExecutionRouter, Local Brain, Key Pool,
Device Identity, Memory, Admin) сверен с исходным кодом, а не с README.
Формат запрошенной таблицы: **подтверждено → причина → влияние → исправление**.

---

## 0. Главный вывод

Стратегия плана верна (не переписывать, local-first, гибридный перевод/память,
device identity с криптографией вместо MAC). Но **критическая развилка плана —
«PHASE 1: ввести ExecutionRouter» — уже реализована в коде**. Без этой сверки
план вёл бы к повторному строительству существующего и работающего ядра — ровно
того, что запрещают правила проекта.

Фактический gap-лист короче плана: **ключевые пулы провайдеров, neural
embeddings, device identity, admin control plane, измерения local LLM**.
Всё остальное — верификация, измерения и продуктовые решения.

---

## 1. Таблица A — что из плана УЖЕ есть в коде (не переписывать)

| Тезис плана | Факт в коде (evidence) | Вердикт |
|---|---|---|
| «Ввести слой ExecutionRouter — центральный мозг» | `agent/decision/ExecutionDecisionEngine.kt:48` — приоритеты P1 DEVICE_TOOL (FastCommandRouter, confidence ≥ порога) → P4 AGENT (CognitivePlanner) → P2 LOCAL_AI → P3 CLOUD_AI с privacy-гейтами, бюджетом и честными результатами; `ExecutionPorts.kt:32` — порт `tryHandle → LocalAiOutcome{Handled/Uncertain/Failed}` = контракт эскалации локальный→облако | **Уже реализовано.** PHASE 1 плана = верификация + тесты, не новый слой |
| «Открой Telegram — вообще без AI: STT → Fast Router → OPEN_APP» | `agent/fast/FastCommandRouter.kt:69,429` — алиасы «телеграм/тг/telegram/телегу» → `device.open_app`, DirectResponse-путь без LLM; 34 инструмента покрытия (volume, фонарик, таймер, звонок, скриншот, батарея, DND, навигация…) | **Уже реализовано** |
| «Второй уровень — Local AI с plan→tools» | `CompositeLocalAiExecutor` (`decision/LocalAiExecutorAdapter.kt:39`) = `WorkflowExecutor` (процедурные макросы, мгновенно) + `OnDeviceLocalAi` (MediaPipe Gemma, `libs.versions.toml:25` — `tasks-genai 0.10.35`); agent-путь `CognitivePlanner → AgentCognitiveLoop → ToolRegistry` | **Каркас реализован**; недостаёт измерений и численной confidence (см. Таблицу B) |
| «Приложение не должно знать, какой AI использовать: OMNIX API → Execution Router → Provider Manager» | Сервер: `router/AiRouter.kt` → `provider/ProviderManager.kt` → `DefaultProviderSelectionPolicy` (фильтр capability/health, сортировка HEALTHY→priority, `ProviderManager.kt:54–80`) + circuit breaker `CLOSED/OPEN/HALF_OPEN` + fallback + retry-бюджет + deadline 28s | **Уже реализовано**; в выборе провайдера пока нет cost/latency-слагаемых (см. B-2) |
| «Ear Mode с гибридным переводом» | `agent/translator/` — `LiveTranslatorEngine`, `TranslationLanguageDetector`, `TranslationProvider` с честными `Unsupported/NetworkRequired/ModelUnavailable`; Bluetooth SCO в voice/ | **Базовый flow реализован**; offline-модель перевода отсутствует (честно репортится) |
| «License: scratch code → atomic redeem → PostgreSQL → Bearer» | Подтверждено планом же; в коде: `license/`, `/v1/license/*`, DB-backed `jrv_` токены | **Уже реализовано** |
| «Сервер не переписывать, постепенно → Platform Server» | Согласны. Зоны auth/billing/rate/AI-роутинг/metrics уже отделены; расширение — аддитивные эндпоинты | **Согласны с планом** |

---

## 2. Таблица B — запрошенная таблица P0/P1: подтверждено → причина → влияние → исправление

| ID | Утверждение | Подтверждено? | Причина (evidence) | Влияние | Исправление | Приоритет |
|---|---|---|---|---|---|---|
| B-1 | «Local AI пока не полноценный Local Brain» | **Частично** | Инфраструктура есть (`OnDeviceLocalAi`, `LocalModelManager`, MediaPipe); модель ~529 МБ НЕ в APK by design (docs/LOCAL_AI.md, честная политика); neural embeddings не подключены (`LocalEmbeddingProvider.isReady()==false`) | Продукт в API-34 CI не может доказать качество локального слоя; часть пользователей останется без локального мозга | (1) измерения на устройстве: latency/RAM/battery/accuracy — скрипт `scripts/run-real-model-device-test.sh` уже есть, нужен прогон + фиксация результатов в docs/benchmark; (2) решить продуктово: управляемая доставка модели (on-demand download в первом запуске) vs user-installed | **P0 (измерения)** / P1 (доставка модели) |
| B-2 | «Key Pool со score вместо random» | **Да** | `ProviderConfig.apiKey` — ровно один ключ на провайдера (`ServerConfig.kt:13–34`); в `DefaultProviderSelectionPolicy` — только health+priority, cost/latency/429-история не участвуют в выборе | При росте нагрузки упрёмся в per-key rate limits провайдеров; сейчас fallback работает на уровне провайдеров, не ключей | НЕ random — согласны. Когда потребуется: `ProviderKeyPool` внутри `ProviderId` (несколько `apiKey` + per-key health/429-счётчики), выбор через существующий `ProviderSelectionPolicy` (аддитивно). До реального упора в лимиты — premature (правила проекта §19; per-client rate limiting уже в Postgres) | **P1 (при росте нагрузки)** |
| B-3 | «Device Identity: keypair вместо MAC/ID как proof-of-ownership» | **Да (как будущий дизайн)** | Кода привязки к MAC/ID сейчас НЕТ вообще — лицензия = scratch-code + DB-backed токен; единственный намёк на earbuds — план `earclip-monthly` в BILLING_PLANS | Если реализовывать наушники без identity — были бы дыры; сейчас дыр нет, есть отсутствие фичи | Согласны с криптосхемой плана: `device_id + public key (server) + private key (Android Keystore) + challenge-signature`; отдельная миграция `devices`, endpoint `/v1/devices/*`. Делать ПОСЛЕ существования железа | **P1 (при появлении устройства)** |
| B-4 | «Memory: Working+Personal+Semantic, vector DB не тащить сразу» | **Частично** | Working/Personal/Semantic-слои и DAO уже есть (`agent/memory/{semantic,procedural,context,manager}`); семантика — лексико-семантический матчинг, `EmbeddingProvider`-контракт зафиксирован (`semantic/EmbeddingProvider.kt:21`), реализации не активированы | Поиск по памяти хуже на перефразированных запросах | Активировать `EmbeddingProvider` (сначала on-device, например MediaPipe-embedder / small ONNX), сохранив fallback на лексический матчинг; server vector DB — НЕ сейчас (согласны с планом) | **P2** |
| B-5 | «Admin Panel как control plane» | **Да** | Админ-поверхность сейчас: `/v1/admin/metrics` + `/v1/admin/metrics/prometheus` (оба guarded). Нет users/devices/costs/flags/AI-requests | До 10–100 пользователей жить можно (SQL + Prometheus + RUNBOOK); дальше операционка станет ручной | Строить поэтапно по плану (PHASE 7): сначала read-only admin API (usage/costs/заказы), потом UI. НЕ сейчас | **P2–P3** |
| B-6 | «CI не доказывает Ear Mode на реальном наушнике» | **Да** | docs/TEST_QUALITY.md:77–78 — физические voice/Bluetooth-тесты явно gated; `run-real-model-device-test.sh` опционален | Регрессии SCO/фона/прерываний звонком ловятся только вручную | Hardware QA-чеклист (устройства × Android × earbuds × SCO/reconnect/calls/music) как регулярная ручная процедура + прогон gated-скриптов перед релизом | **P1 (процедура)** |
| B-7 | «Локальный confidence-порог для эскалации в облако» | **Частично** | Сейчас эскалация через дискретный контракт `LocalAiOutcome{Handled/Uncertain/Failed}` — без числа | Пороговая настройка качества/стоимости невозможна без телеметрии | После B-1 (измерения): добавить numeric confidence в `Handled`, порог в `ExecutionDecisionConfig`. Не раньше, чем появятся данные | **P2** |
| B-8 | «Не привязывать подписку к Bluetooth MAC» | Согласны, **N/A** | Такой привязки в коде нет | — | Зафиксировать как ADR-принцип до реализации устройств | **Документировать** |
| B-9 | «70% локально — не цель; цель min(стоимость, задержка) при приватности» | Согласны | Роутинг уже построен на приоритетах+confidence, не на квоте | — | Целевая метрика = доля маршрутов в метриках (добавить counter `route_local_total/route_cloud_total` — дёшево, можно вместе с B-7) | **P2 (метрика)** |

---

## 3. Расхождения с планом (важно)

1. **«PHASE 1: ExecutionRouter — P0» уже выполнен проектом.** Новый слой вводить
   не нужно: `ExecutionDecisionEngine` — это и есть запроектированный
   `ExecutionRouter` (та же диаграмма LOCAL/DEVICE/CLOUD). Работа по PHASE 1
   сужается до: тесты граничных случаев роутинга + B-1 измерения.
2. **«Local AI не является мозгом»** — инфраструктурно является (исполнимый бэкенд
   с портами и workflow-макросами); не хватает данных и доставки модели, а не
   архитектуры. Переписывание не требуется.
3. **Naming OMNIX Core/Local/Cloud/…** — продуктовое решение, не архитектурное;
   может быть зафиксировано в docs без движения кода (пакеты переименовывать
   сейчас — чистый риск без выгоды).

---

## 4. Согласованный порядок работ (скорректированный PHASE-план)

```text
PHASE 1 (уже сделано ядром)  → верификация роутинга + regression-тесты граничных случаев
PHASE 2  → B-1: измерения local LLM на устройстве; решение о доставке модели
PHASE 3  → B-2: key pool со score (только при упоре в лимиты провайдеров)
PHASE 4  → B-3: device identity (при появлении железа; ADR о криптосхеме — раньше)
PHASE 5  → Ear Mode hardware QA (B-6) + гибридный перевод (B-7 confidence)
PHASE 6  → B-4: активация EmbeddingProvider (memory-качество)
PHASE 7  → B-5: admin control plane (read-only API → UI)
```

## 5. Что НЕ делать (подтверждаем план)

iOS, вторая vector-БД на сервере, 20 провайдеров, собственная LLM, wearable OS,
сложный billing v2, 100+ инструментов, большая админка — всё это остаётся
за пределами текущего горизонта. Правила проекта (§19, §13 ТЗ) это прямо требуют.

---

*Документ — результат сверки плана v0.3 с кодом; дополняется по мере появления
новых тезисов. Все ссылки на файлы актуальны для `main@59375b8`.*
