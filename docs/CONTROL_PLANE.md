# OMNIX Control Plane — операционный центр (admin API + UI)

**Дата:** 2026-08-31 · **Базовая ревизия:** `main@3b22bcc`
Принцип (ТЗ §1): Control Plane встроен в **существующий** backend — тот же JVM-процесс,
та же БД, тот же деплой. Параллельной системы, вторых таблиц и второго billing нет.

## 1. Architecture gap (что было → что сделано)

```text
Existing Architecture
    Собственный HTTP-слой (com.sun.net.httpserver) + handlers pipeline
    Postgres + Hikari + immutable migrator (V001–V005)
    Auth: static env-токены (ADMIN tier) + license-токены; TierAuthorizer
    Сущности: accounts, billing_plans, licenses, api_tokens, license_audit_log,
              billing_orders/events, ai_usage_records (метаданные, без контента)
    Observability: Metrics (provider success/failure/latency), Prometheus
    Rate limiting: PostgresRateLimiter (persistent)
Admin Requirements (ТЗ)
    admin auth/session/RBAC · users/devices/licenses · AI routing runtime-config
    providers health · usage/cost · logs/audit · settings/feature flags · UI
Existing Capabilities (переиспользовано, не дублировано)
    Логи/usage/health-данные, rate limiter, audit-модель, мигратор, auth-хелперы
Missing Capabilities (добавлено V006 + admin-пакет)
    admin_accounts/admin_sessions/admin_audit_log/admin_settings/feature_flags
    AdminAuthService (login/brute-force/session/revocation)
    AdminRbac (SUPER_ADMIN/ADMIN/SUPPORT/VIEWER × 17 permissions)
    AdminHttpHandler (29 маршрутов) + AdminUiHandler (server-rendered UI)
    AdminSettingsService (validate→persist→audit→apply), FeatureFlagService, CostModel
Required Changes (минимальный шов в существующий код)
    DefaultProviderSelectionPolicy: +1 параметр ProviderRuntimeOverrides
      (runtime priority/enabled поверх startup-конфига)
    Main.buildResources: сборка admin-подсистемы + bootstrap + extension chain
    Риск: низкий (существующие маршруты/поведения не изменены; все новые пути
    под /v1/admin/*, лицензионный handler обрабатывает issue/revoke как раньше)
```

## 2. Аутентификация и сессии

- **Login:** `POST /v1/admin/auth/login` (username/password, PBKDF2-HmacSHA256,
  210k iterations; пароль ≥ 12 символов).
- **Сессии:** bearer-токен (raw выдаётся один раз), в БД — только SHA-256;
  TTL 30 мин со sliding renewal (продление после половины TTL);
  logout/revocation — `POST /v1/admin/auth/logout`.
- **Brute-force:** персистентный `PostgresRateLimiter` scope `admin_login`
  (5 попыток в минуту И в сутки на username+IP; успех сбрасывает счётчик).
- **2FA:** точка расширения зафиксирована (second-factor шаг перед выдачей
  Success; миграция схемы не потребуется).
- **Legacy-совместимость:** static env-токены (tier ADMIN) продолжают работать
  как SUPER_ADMIN — существующие `/v1/admin/licenses/*` не сломаны.

Bootstrap: при пустой `admin_accounts` и заданных
`JARVIS_ADMIN_BOOTSTRAP_USERNAME/PASSWORD` (≥12 симв., иначе fail-fast)
создаётся единственный SUPER_ADMIN; событие — в audit.

## 3. RBAC

Матрица роль→permissions — `AdminRbac.kt`, проверка на backend на **каждом**
запросе (`requirePermission`). Frontend-authorization не признаётся.

| Permission | SUPER | ADMIN | SUPPORT | VIEWER |
|---|---|---|---|---|
| *_READ (dashboard/users/devices/licenses/subscriptions/providers/usage/logs/audit/settings/features) | ✅ | ✅ | ✅ | ✅ |
| LICENSES_WRITE (disable/enable/extend/change-plan) | ✅ | ✅ | ✅ | — |
| DEVICES_REVOKE | ✅ | ✅ | — | — |
| PROVIDERS_CONFIGURE / SETTINGS_WRITE / FEATURES_WRITE | ✅ | ✅ | — | — |
| ADMINS_MANAGE | ✅ | — | — | — |

## 4. API (существующий convention `/v1/admin/*` сохранён)

```text
POST /v1/admin/auth/login | logout          GET /v1/admin/me
GET  /v1/admin/dashboard | health
GET  /v1/admin/users?page&q | users/{id}
GET  /v1/admin/devices?page | devices/{id}  POST devices/{id}/revoke
GET  /v1/admin/licenses?page | licenses/{id}
POST /v1/admin/licenses/{id}/disable|enable|extend|change-plan
GET  /v1/admin/subscriptions
GET  /v1/admin/providers                    POST /v1/admin/providers/{id}/configure
GET  /v1/admin/usage?days | usage/cost?days
GET  /v1/admin/logs?component | audit
GET|PUT /v1/admin/settings/{system|security|ai|limits|cost}
GET  /v1/admin/features                     PUT /v1/admin/features/{key}
GET  /v1/admin/ui/*                         (server-rendered operational UI)
```

Не тронуты: `/v1/admin/licenses/issue|revoke`, `/v1/admin/metrics*`,
`/v1/license/*`, `/v1/billing/*`, `/v1/ai/execute`, `/v1/health`.

## 5. Runtime-конфигурация AI (без выпуска APK)

`PUT /v1/admin/settings/ai` и `POST /v1/admin/providers/{id}/configure`:
полный цикл **Validate → Persist → Audit → Apply**. Apply — атомарная замена
`ProviderRuntimeOverrides`, которую `DefaultProviderSelectionPolicy` читает
на каждый выбор провайдера: `enabled`, `priority` — в рантайме;
`timeout/retry` — сознательно только через рестарт (входят в client-facing
deadline-бюджет CR-06; API отвечает `requiresRestart: true`).

## 6. Cost model (§16)

Цены — `settings/cost` (USD за 1М input/output токенов). Расчёт возвращает
`input / formula / result` для каждой строки. **Цена не сконфигурирована →
UNKNOWN** (и totalUsd = null), никогда не ноль и не выдумка.
KPI `local execution rate` — **NOT COLLECTED**: локальные выполнения происходят
на устройстве; клиентская телеметрия локальных запусков — отдельная задача v0.3.

## 7. Приватность (§28) — by design

- «Пользователь» = accounts, «устройство» = api_tokens + license device binding
  (отдельных параллельных сущностей нет — ТЗ §29).
- Логи/usage — только операционные метаданные (провайдер, latency, счётчики
  токенов/символов). Текстов запросов/ответов/голоса в БД нет и в админку не
  отдаются; поле `prompt_chars` наружу не экспонируется (`NOT EXPOSED`).
- API-ключи провайдеров никогда не возвращаются (`••••`).

## 8. Audit (§19)

`admin_audit_log` — append-only (в коде нет UPDATE/DELETE; endpoint только
GET). WHO/WHAT/WHEN/TARGET/OLD/NEW/IP/SESSION. Все мутации — лицензии,
устройства, settings, flags, providers, bootstrap, login — фиксируются.

## 9. Безопасность (§27)

Session-токены — SHA-256 в БД; сравнение секретов — constant-time;
RBAC backend-only; IDOR — id всегда в admin-namespace с permission-чеком;
UI-формы — CSRF-токен (double-submit) + HttpOnly SameSite=Strict cookie;
заголовки `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`,
`Cache-Control: no-store`; rate limit на login; секреты — только env.

## 10. UI (§24–26)

Server-rendered в существующем JVM (без Node в деплое — сознательное отклонение
от «Next.js по умолчанию»: single-instance, zero new infrastructure, Admin API
UI-agnostic). Dense dark-тема, self-contained (без CDN). Страницы: Login,
Dashboard, Users, User, Devices, Licenses, License (действия), Providers,
Usage/Cost, Logs, Audit, Settings, Flags. Desktop-first, мобильная сетка.

## 11. Тесты (§30)

- Unit: RBAC-матрица (4 роли × 17 permissions), пароли (roundtrip/salt/повреждения),
  settings-валидация (4 require-сценария), cost model (формула/UNKNOWN/токены),
  flag-бакеты (детерминизм/диапазон/распределение).
- Integration (postgres): login/brute-force/401/403-матрица, legacy-токен,
  logout-revocation, dashboard по seeded-данным, device revoke + audit,
  license lifecycle (disable/enable/extend/change-plan) + audit, изменение
  плана с несуществующим планом = 400 без audit, settings ai → overrides
  в рантайме + 400 на невалидное, flags + rollout, маскирование ключей,
  usage/cost из реальных `ai_usage_records`.
- UI integration: login-page, 303-редирект без сессии, HttpOnly+SameSite
  cookie, dashboard по реальным данным, неверный пароль.

## 12. Осталось (осознанно отложено)

- 2FA-фактор (схема готова, фактор не введён), admin account CRUD через UI/API
  (сейчас — bootstrap + БД), percent-rollout per-plan targeting (сейчас
  percent по clientId), экспорт/ротация логов, WebSocket-обновления дашборда.
- Local execution telemetry с устройств (чтобы KPI local share стал числом).
