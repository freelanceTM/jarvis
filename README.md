# JARVIS — голосовой AI-ассистент для Android

Персональный голосовой AI-ассистент для Android (minSdk 29 / targetSdk 34).
Проект реализует голосовое взаимодействие, агентную систему с набором
инструментов (tools), «режим наушников» (Ear Mode) с непрерывным переводом
речи, память на основе Room с лексико-семантическим поиском и лицензирование
по скретч-кодам.

> Текущая версия: **0.2.0** (ветка `main`).

## Возможности

- Голосовое взаимодействие: wake-word, STT (системный распознаватель), TTS.
- Агентное ядро: планирование, роутинг задач, выбор инструментов, память.
- Инструменты: звонки, SMS, контакты, погода, web-поиск, перевод, будильники,
  календарь, ярлыки, Bluetooth/Wi-Fi (в рамках ограничений Android),
  скриншоты, автоматизации, зачитывание экрана и UI-клики (accessibility).
- Ear Mode: непрерывный перевод речи (RU/EN/TK/TR/DE/ZH/AR) с выводом
  в наушники через Bluetooth SCO.
- Активация по одноразовому скретч-коду (fail-closed через сервер лицензий;
  до реализации `/v1/license/validate` активация честно недоступна).

Честное описание того, что реально работает на Android (и что невозможно
программно) — в [docs/ANDROID_CAPABILITIES.md](docs/ANDROID_CAPABILITIES.md).

## Семантический поиск — честная формулировка

Поиск по памяти и Tool Discovery используют **лексико-семантический матчинг**
(`SemanticTextMatcher`): ручные векторы из словаря корней, синонимов,
хеш-отпечатков слов и n-грамм. Это НЕ neural embeddings — нейросетевые
embedding-модели в проект не включены (`LocalEmbeddingProvider.isReady() == false`,
`RemoteEmbeddingProvider` не настроен). Настоящие embeddings — будущий слой
(контракт `EmbeddingProvider` уже зафиксирован), см. docs/ANDROID_CAPABILITIES.md.

## Требования

- JDK 17
- Android SDK: compileSdk 34, build-tools 34.x
- Gradle 8.7 (используется wrapper — отдельная установка не нужна)

## Сборка

```bash
./gradlew :app:testDebugUnitTest # Android JVM unit-тесты
./gradlew :server:test           # unit/integration-тесты API
./gradlew :app:lintDebug         # Android Lint
./gradlew :app:assembleDebug     # debug APK
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Структура

- `app/src/main/java/com/jarvis/assistant/`
  - `agent/` — агентное ядро: планировщик, роутер, память, инструменты
  - `ai/` — клиент JARVIS API (без ключей AI-провайдеров на устройстве)
  - `data/` — Room, DataStore, сетевые DTO
  - `domain/` — модели и use cases
  - `presentation/` — Compose UI (chat, настройки, активация, перевод)
  - `voice/` — STT/TTS, wake-word, Bluetooth-аудио, фоновый сервис
  - `core/` — лицензирование, безопасное хранение ключа, сеть, константы
- `app/src/test/` — JVM unit-тесты; `app/src/androidTest/` — тесты на устройстве.
- `server/` — JVM API: auth/authz, rate limit, AI router, provider fallback,
  circuit breaker, usage/metrics; подробности в `docs/SERVER_AI_LAYER.md`.

## Конфигурация

- Android хранит только Bearer-токен доступа JARVIS в
  `EncryptedSharedPreferences` (Android Keystore). Ключи Groq/Gemini/OpenRouter
  на устройство не передаются.
- Серверные токены и ключи провайдеров задаются environment variables; пример —
  `server/.env.example`. `.env` и другие секреты Git игнорирует.
- `local.properties` (путь к Android SDK) в Git не хранится — создаётся локально.
- Локальная LLM-модель не входит в APK; порядок установки описан в
  `docs/LOCAL_AI.md`.

## CI

GitHub Actions (`.github/workflows/build.yml`): при каждом push собирает
debug APK, прогоняет unit-тесты и lint (`warningsAsErrors` + baseline —
новые warnings блокируют сборку), артефакт выкладывается в workflow artifacts.
Инструментальные тесты (`androidTest`, включая миграции Room) — локально:
`./gradlew connectedDebugAndroidTest`.
