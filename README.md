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
- Активация по одноразовому скретч-коду (30 дней бесплатно, далее подписка).

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
./gradlew assembleDebug          # собрать debug APK
./gradlew test                   # unit-тесты (JVM)
./gradlew lint                   # Android Lint
./gradlew build                  # полная сборка
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Структура

- `app/src/main/java/com/jarvis/assistant/`
  - `agent/` — агентное ядро: планировщик, роутер, память, инструменты
  - `ai/` — AI-клиенты (OpenAI / OpenRouter / Groq / Gemini через OkHttp)
  - `data/` — Room, DataStore, сетевые DTO
  - `domain/` — модели и use cases
  - `presentation/` — Compose UI (chat, настройки, активация, перевод)
  - `voice/` — STT/TTS, wake-word, Bluetooth-аудио, фоновый сервис
  - `core/` — лицензирование, безопасное хранение ключа, сеть, константы
- `app/src/test/` — unit-тесты

## Конфигурация

- Ключ API вводится пользователем в настройках приложения и хранится в
  `EncryptedSharedPreferences` (Android Keystore). В репозитории секретов нет.
- `local.properties` (путь к Android SDK) в Git не хранится — создаётся локально.
- Поддерживаемые провайдеры: OpenAI-compatible API (`sk-...`), Groq (`gsk_...`),
  OpenRouter (`sk-or-...`) и Google Gemini (`AIza...`).

## CI

GitHub Actions (`.github/workflows/build.yml`): при каждом push собирает
debug APK, прогоняет unit-тесты и lint (`warningsAsErrors` + baseline —
новые warnings блокируют сборку), артефакт выкладывается в workflow artifacts.
Инструментальные тесты (`androidTest`, включая миграции Room) — локально:
`./gradlew connectedDebugAndroidTest`.
