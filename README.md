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
- Активация по одноразовому скретч-коду: atomic redeem, PostgreSQL source of
  truth, DB-backed Bearer token и `/v1/license/validate`. Paddle/HELEKET
  renewal backend реализован, но UI остаётся отключён до live credentials.

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
- PostgreSQL 15+ для server license/billing и server integration tests
- Gradle 8.7 (используется wrapper — отдельная установка не нужна)

## Сборка

```bash
bash ./gradlew :app:testDevDebugUnitTest              # Android JVM tests
bash ./gradlew :app:jacocoDevDebugCoverageVerification # tests + JaCoCo gate
bash ./gradlew phase3StaticAnalysis                    # Detekt app + server
bash ./gradlew :app:lintDevDebug                       # Android Lint
bash ./gradlew :app:assembleDevDebug                   # dev debug APK
bash ./gradlew :app:assembleDevDebugAndroidTest        # compile device tests
bash ./gradlew :server:test                            # requires test PostgreSQL
```

Coverage и quality tasks описаны в
[`docs/TEST_QUALITY.md`](docs/TEST_QUALITY.md). APK:
`app/build/outputs/apk/dev/debug/app-dev-debug.apk`.

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
- `server/` — JVM API: auth/authz, PostgreSQL licensing/billing, persistent
  license rate limits, Paddle/HELEKET adapters, AI router, provider fallback,
  circuit breaker и metrics. См. `docs/SERVER_AI_LAYER.md` и
  `docs/LICENSE_BILLING.md`.

## Конфигурация

- Android хранит только Bearer-токен доступа JARVIS в
  `EncryptedSharedPreferences` (Android Keystore). Ключи Groq/Gemini/OpenRouter
  на устройство не передаются.
- PostgreSQL URL/password, HMAC pepper, admin tokens, plan catalog и provider
  secrets задаются environment variables; пример — `server/.env.example`.
  `.env` и другие секреты Git игнорирует.
- License migrations автоматически применяются с checksum и PostgreSQL
  advisory lock. Для запуска server tests задайте `JARVIS_TEST_DATABASE_*`.
- `local.properties` (путь к Android SDK) в Git не хранится — создаётся локально.
- Локальная LLM-модель не входит в APK; порядок установки описан в
  `docs/LOCAL_AI.md`.

## Production deployment и TLS

> **Production deployment без TLS/reverse proxy запрещён, поскольку authentication Bearer tokens передаются по сети.**

Поддерживаемая topology:

```text
Internet -> Caddy HTTPS :443 -> private jarvis-server HTTP :8080
                              -> private PostgreSQL :5432
```

Только Caddy публикует ports 80/443; application и database ports не
публикуются. Caddy обеспечивает TLS 1.2/1.3, ACME certificate/renewal и
redirect-only port 80. Production startup требует HTTPS `PUBLIC_BASE_URL`,
explicit trusted proxy CIDR и подтверждение TLS termination; иначе сервер
завершается с configuration error. Development HTTP остаётся доступен только на
`127.0.0.1`.

Полная инструкция по DNS, certificates, firewall/security groups, trusted proxy
headers, Docker Compose, health checks и smoke verification:
[`docs/PRODUCTION_DEPLOYMENT.md`](docs/PRODUCTION_DEPLOYMENT.md).

## CI

GitHub Actions (`.github/workflows/build.yml`): при каждом push собирает dev
APK/androidTest APK, прогоняет JVM-тесты, JaCoCo coverage gate, Detekt и lint
(`warningsAsErrors` + reviewed baseline), проверяет server с PostgreSQL,
валидирует production Compose/Caddy TLS configuration и публикует quality
reports в workflow artifacts. Отдельный job с KVM и Android API 34 запускает
обычный instrumentation suite и формирует Android coverage report; тесты,
которым нужны физическое устройство, Bluetooth или реальная модель, остаются
явно gated.

Инструментальные тесты (`androidTest`, включая encrypted license storage,
DataStore, Compose UI, Room и scheduler) требуют реального target/emulator:

```bash
bash ./gradlew :app:connectedDevDebugAndroidTest
bash ./gradlew :app:createDevDebugCoverageReport
```

## Repository policy

- [CHANGELOG.md](CHANGELOG.md) — только фактические изменения и releases.
- [SECURITY.md](SECURITY.md) — private vulnerability disclosure process.
- [LICENSE](LICENSE) — license пока не выбрана; требуется решение владельца.
- [docs/DEPENDENCY_UPDATE_PLAN.md](docs/DEPENDENCY_UPDATE_PLAN.md) — reviewed
  dependency migration plan.
