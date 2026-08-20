# JARVIS API — Server AI Layer (Этап 3)

Облачная AI-оркестрация вынесена на сервер. Android больше не знает, какой
провайдер выполняет запрос, и не хранит их ключи.

```
JARVIS Android
      ↓  Bearer <access token>
POST /v1/ai/execute
      ↓
Authentication → Authorization → Rate Limit
      ↓
AI Router          (валидация, privacy, нормализация ошибок, usage)
      ↓
Provider Manager   (отбор, timeout, retry, fallback, health, circuit breaker)
      ↓
 ┌────────────┬──────────────┬──────────────┐
 Groq         Gemini         OpenRouter
```

---

## 1. Технологический выбор

| Компонент | Выбор | Почему |
|---|---|---|
| Язык | Kotlin | тот же, что Android — общие модели, одна команда, одни тесты |
| Сборка | Gradle-модуль `:server` в монорепо | Android-сборка независима; `:app` не зависит от `:server` |
| HTTP | `com.sun.net.httpserver` (JDK) | нужен один POST-эндпоинт; Ktor/Spring притащили бы десятки зависимостей ради того же. Вся логика в `JarvisApiHandler`, переезд на Ktor — замена ~40 строк |
| HTTP-клиент | OkHttp | уже используется в проекте |
| Сериализация | kotlinx.serialization | уже используется |
| Persistence | in-memory | серверной БД в проекте нет; ТЗ (п. 31) запрещает разворачивать большую persistence ради этого этапа |

---

## 2. API

### `POST /v1/ai/execute`

```json
{
  "text": "Объясни квантовую запутанность",
  "source": "VOICE",
  "privacyLevel": "NORMAL",
  "requiresWeb": false,
  "requestId": "optional-client-correlation-id",
  "systemContext": "optional: описание доступных на устройстве инструментов"
}
```

Ответ:

```json
{
  "success": true,
  "text": "Квантовая запутанность — это...",
  "executionType": "CLOUD_AI",
  "requestId": "8d8f..."
}
```

**Провайдер клиенту не раскрывается** — это server-side telemetry (п. 3 ТЗ).

Про `systemContext`: сервер не знает, какие инструменты есть на конкретном
телефоне, поэтому Tool Discovery остаётся на клиенте и приходит как контекст.
Сервер **дополняет** им свой базовый system prompt, а не заменяет; длина
валидируется наравне с `text`. Это не выбор провайдера и не выбор модели.

### `GET /v1/health` — публичный. `GET /v1/admin/metrics` — только `ADMIN`.

### Коды ошибок

```
INVALID_REQUEST · UNAUTHORIZED · FORBIDDEN · RATE_LIMITED
PRIVACY_POLICY_VIOLATION · PROVIDER_UNAVAILABLE · PROVIDER_TIMEOUT
PROVIDER_ERROR · ALL_PROVIDERS_UNAVAILABLE · PAYLOAD_TOO_LARGE · INTERNAL_ERROR
```

Наружу уходит только код и заранее заданный безопасный текст. Ни stack trace,
ни `e.message`, ни статусы провайдеров, ни ключи клиент не видит.

---

## 3. Authentication vs Authorization

Намеренно разделены (п. 7 ТЗ):

- **Authentication** (`TokenAuthenticator`) — «кто это». Bearer-токен;
  хранится и сравнивается как SHA-256, сравнение константное по времени.
  Токены приходят из env `JARVIS_CLIENT_TOKENS`, в коде их нет.
- **Authorization** (`TierAuthorizer`) — «что можно». Тарифы
  `FREE / PRO / ADMIN / INTERNAL` → права `EXECUTE_AI`, `VIEW_ADMIN`.

Добавление биллинга сведётся к правке таблицы прав — API и роутер не меняются.

На Android токен лежит в `EncryptedSharedPreferences` (Android Keystore) —
переиспользован существующий `SecurityManager`.

---

## 4. Rate limiting

Скользящее окно на две шкалы (в минуту и в сутки), с привязкой к `clientId`.
При превышении — `429` + заголовок `Retry-After`.

---

## 5. Выбор провайдера

`ProviderSelectionPolicy` = **priority + health + capabilities**:

1. отбрасываются выключенные и без ключа;
2. отбрасываются не удовлетворяющие требованиям (`requiresWeb`);
3. отбрасываются недоступные по health/circuit breaker;
4. сортировка: сначала `HEALTHY`, затем `DEGRADED`; внутри — по `priority`.

Клиент выбрать провайдера **не может** — поле `provider` в запросе
игнорируется (проверено тестом).

---

## 6. Health, retry, fallback

Классификация сбоев определяет поведение:

| Вид сбоя | Retry у того же | Fallback | Эффект на health |
|---|---|---|---|
| `TIMEOUT`, `CONNECTION`, `SERVER_ERROR` | да | да | счётчик сбоев |
| `RATE_LIMITED` | нет | да | счётчик сбоев |
| `AUTH`, `NOT_CONFIGURED` | нет | да | **выводится из ротации** |
| `BAD_REQUEST` | нет | да | счётчик сбоев |

Ключевое: неверный API-ключ не ретраится на каждом пользовательском
запросе — провайдер отключается до переконфигурации (п. 12 и 23 ТЗ).

Circuit breaker: `CLOSED → (N сбоев) → OPEN → (cooldown) → HALF_OPEN → CLOSED`.
In-memory, поскольку сервер пока single-instance (п. 24 ТЗ прямо это разрешает).

Fallback ограничен `MAX_PROVIDER_ATTEMPTS`, retry — `MAX_RETRIES_PER_PROVIDER`.
Бесконечных повторов нет.

---

## 7. Privacy

Сервер — **вторая линия защиты**. По умолчанию в облако выпускается только
`NORMAL`; `PRIVATE` и `SENSITIVE` отклоняются с `PRIVACY_POLICY_VIOLATION`
ещё до обращения к провайдеру. Поведение управляется
`ALLOW_PRIVATE_CLOUD` / `ALLOW_SENSITIVE_CLOUD`.

Это согласовано с Android: там PRIVATE идёт в локальную модель (Этап 2),
а в облако не уходит вовсе.

---

## 8. Usage tracking

Запись создаётся **на каждый** запрос — и успешный, и неуспешный:

```
requestId · clientId · provider · model · latencyMs
inputTokens · outputTokens · totalTokens · success · errorCode
promptChars · responseChars · timestamp
```

**Текст промпта и ответа не сохраняется** — только длина (п. 31 ТЗ).
Контракт `UsageRepository` минимален, замена in-memory на Postgres — один класс.

---

## 9. Логи

```
ts=... level=INFO  msg="ai request accepted" requestId=3de4... clientId=smoke-client source=VOICE privacyLevel=NORMAL promptSize="<6 chars>"
ts=... level=WARN  msg="provider failure" requestId=3de4... provider=GROQ failureKind=CONNECTION latencyMs=42 attempt=1
ts=... level=ERROR msg="ai request failed" requestId=3de4... errorCode=PROVIDER_UNAVAILABLE status=failure
```

`requestId` сквозной: Android → API → Router → Manager → Provider.
Промпт логируется как размер, не как содержимое. `LogSanitizer` дополнительно
маскирует секреты (`Bearer …`, `sk-…`, `gsk_…`, `AIza…`).

---

## 10. Запуск

```bash
cp server/.env.example server/.env     # .env в git не попадает
# заполнить JARVIS_CLIENT_TOKENS и хотя бы один *_API_KEY
set -a && . ./server/.env && set +a
./gradlew :server:run
```

Проверка:

```bash
curl http://localhost:8080/v1/health

curl -X POST http://localhost:8080/v1/ai/execute \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"text":"Привет","source":"CHAT","privacyLevel":"NORMAL","requiresWeb":false}'
```

Тесты: `./gradlew :server:test`

### Production

- **HTTPS обязателен.** Встроенный сервер слушает HTTP — TLS терминируется
  реверс-прокси (nginx/Caddy) или облачным балансировщиком. Без этого
  Bearer-токен уйдёт открытым текстом.
- Ключи — в Secret Manager, не в `.env`.
- CORS не настраивается: клиент нативный, браузерных запросов нет.

---

## 11. Что изменилось на Android

| Было (BYOK) | Стало (Этап 3) |
|---|---|
| Пользователь вводит `sk-…` / `gsk_…` / `AIza…` | Вводит токен доступа JARVIS |
| `UniversalAIClient` → Groq/Gemini/OpenRouter напрямую | `JarvisApiAiClient` → JARVIS API |
| Клиент выбирал модель (`TaskRouter`) | Модель выбирает сервер |
| Клиент делал web-поиск и ретраи | Это делает сервер |
| Ключи провайдеров в `EncryptedSharedPreferences` | Только токен доступа; legacy-ключ удаляется при старте |

`ExecutionDecisionEngine` **не изменён**. Cloud AI остался одним из четырёх
путей; поменялась только реализация за портом `CloudAiExecutor`.

Agent тоже ходит в облако через `CloudAi` → JARVIS API — отдельного пути
к провайдерам у него нет.

---

## 12. Файлы

Создано (`server/`): `api/Dto.kt`, `auth/Auth.kt`, `config/ServerConfig.kt`,
`http/JarvisApiHandler.kt`, `observability/Observability.kt`,
`provider/AiProvider.kt`, `provider/HttpProviders.kt`,
`provider/OkHttpTransport.kt`, `provider/ProviderHealth.kt`,
`provider/ProviderManager.kt`, `ratelimit/RateLimiter.kt`,
`router/AiRouter.kt`, `usage/UsageTracking.kt`, `Main.kt`,
тесты `FakeProvider.kt`, `ProviderManagerTest.kt`, `ApiIntegrationTest.kt`,
`build.gradle.kts`, `.env.example`.

Создано (Android): `data/remote/JarvisApiClient.kt`, `ai/JarvisApiAiClient.kt`.

Удалено (Android): `ai/GeminiDirectClient.kt` (UniversalAIClient),
`ai/AiKeyPolicy.kt` + тест — вместе с BYOK они больше не нужны.

Изменено (Android): `ai/AIClient.kt`, `core/security/SecurityManager.kt`,
`core/constants/AppConstants.kt`, `data/repository/AIRepositoryAndSettingsImpl.kt`,
`data/remote/interceptor/AuthInterceptor.kt`,
`domain/repository/DomainRepositories.kt`,
`agent/decision/ExecutionAdapters.kt`, `di/HiltModules.kt`,
`presentation/settings/*`, `presentation/main/MainViewModel.kt`, `strings.xml`.

---

## 13. Известные ограничения

1. **Rate limiter и circuit breaker — in-memory.** При нескольких инстансах
   лимиты станут per-instance. Нужен Redis — но это преждевременно для
   single-instance (п. 24 ТЗ).
2. **Usage — in-memory**, теряется при рестарте, ограничен 10 000 записей.
   Контракт готов к замене на БД.
3. **Токены статические** (из env). Ротация/отзыв без рестарта требуют БД.
4. **Нет streaming.** Архитектура его допускает (`/v1/ai/stream` добавляется
   рядом), но текущий Android-флоу его не требует (п. 28 ТЗ).
5. **`supportsWeb = false` у всех провайдеров.** Значит `requiresWeb=true`
   сейчас приведёт к `ALL_PROVIDERS_UNAVAILABLE`. Это честно: реального
   web-доступа ни у одного из подключённых провайдеров нет. Раньше поиск делал
   клиент через `WebSearchTool` — этот путь остался на Android как обычный
   инструмент агента.
6. **Реальные вызовы к Groq/Gemini/OpenRouter не проверялись** — нет ключей.
   Транспорт и парсинг покрыты тестами с fake-провайдерами; форматы DTO взяты
   из ранее работавшего `UniversalAIClient`.
7. **HTTPS не встроен** — обязателен реверс-прокси.
