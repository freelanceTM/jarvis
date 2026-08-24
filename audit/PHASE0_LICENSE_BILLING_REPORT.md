# Фаза 0 — server-side licensing и billing

Дата: 2026-08-21

## 1. Итог

Серверная подсистема лицензирования реализована и стала PostgreSQL source of
truth. Android больше не принимает сохранённые preferences как достаточное
доказательство entitlement: при запуске приложения выполняется серверная
validation, а AI endpoint независимо проверяет entitlement перед провайдером.

Реализованы PostgreSQL storage/migrations, issuance, atomic one-time redeem,
dynamic Bearer auth, `/v1/license/validate`, revoke, server expiration,
config-driven plans, checkout orders, Paddle и HELEKET adapters, signature
verification, idempotent webhook processing, persistent multi-instance rate
limits и fail-closed reconciliation для неопределённых результатов checkout.
Даже новый client idempotency key не может создать второй provider checkout,
пока исходный open order не разрешён подписанным webhook/reconciliation.

Production renewal UI **не включён**. Для полного снятия deployment blocker
нужны реальные Paddle/HELEKET credentials и provider-dashboard E2E. Локальная
оплата по номеру телефона в Туркменистане также требует merchant contract и
закрытой документации банка/телекома; имитация такого provider не добавлялась.

## 2. Discovery до реализации

Найдено:

- server database, JDBC layer и migrations отсутствовали;
- billing provider, checkout, order, webhook и payment state отсутствовали;
- auth состоял только из статических Bearer tokens из env;
- rate limiter был in-memory;
- Android отправлял code на несуществующий `/v1/license/validate` без auth;
- Android самостоятельно вычислял expiry и хранил activation code/status;
- `MainActivity` открывал продукт по локальному `isActivatedAndValid()`;
- renewal-кнопка была отключена, реального billing UI не было;
- серверные endpoints были только AI/health/metrics;
- server DB models и migrations отсутствовали полностью;
- существующие license tests проверяли fail-closed client orchestration, но не
  server issuance/storage/redemption/billing.

Выбранная архитектура:

- PostgreSQL;
- bootstrap `activation code -> atomic redeem -> opaque jrv_ Bearer token`;
- config-driven plan catalog;
- Paddle Billing для international card/subscription checkout;
- HELEKET для crypto invoices;
- `LOCAL_TMT` fail-closed до получения официальной merchant integration.

## 2.1. Changed/added files

Phase 0 implementation and verification touched these source groups (generated
`build/` output is excluded):

- configuration/CI: `.github/workflows/build.yml`, `gradle/libs.versions.toml`,
  `server/build.gradle.kts`, `server/.env.example`;
- server composition/config/auth/API: `server/src/main/kotlin/com/jarvis/server/Main.kt`,
  `api/LicenseBillingDto.kt`, `auth/Auth.kt`, `config/LicenseConfig.kt`,
  `config/ServerConfig.kt`, `http/LicenseBillingHttpHandler.kt`;
- persistence/license: `persistence/Database.kt`, `persistence/JdbcTime.kt`, all
  files under `server/.../license/`, and `ratelimit/PostgresRateLimiter.kt`;
- billing: `billing/BillingModels.kt`, `BillingProviders.kt`, `BillingService.kt`,
  `JdbcBillingRepository.kt`, `WebhookVerification.kt`;
- SQL: all four files under `server/src/main/resources/db/migration/`;
- Android: `core/license/LicenseCodeValidator.kt`, `LicenseManager.kt`,
  `LicenseServerValidator.kt`, `presentation/MainActivity.kt`, activation and
  settings integration, `di/HiltModules.kt`, and related strings/tests;
- server tests: `ApiIntegrationTest.kt`, `BillingPersistenceIntegrationTest.kt`,
  `BillingProviderSecurityTest.kt`, `LicenseApiIntegrationTest.kt`,
  `LicenseConfigTest.kt`, `LicensePersistenceIntegrationTest.kt`,
  `PostgresRateLimiterTest.kt`, `PostgresTestSupport.kt`;
- TLS deployment/security: `deploy/`, `DeploymentSecurityConfig.kt`,
  `ProxyRequestSecurity.kt`, `DeploymentSecurityTest.kt`, Android
  `ApiTransportSecurityTest.kt`, and `docs/PRODUCTION_DEPLOYMENT.md`;
- documentation/audit: `docs/LICENSE_BILLING.md`, this report,
  `audit/TLS_DEPLOYMENT_REPORT.md`, `audit/FINAL_AUDIT_REPORT.md`,
  `audit/REPOSITORY_INVENTORY.md`,
  `audit/DEPENDENCY_GRAPH.md`, and `audit/generate_inventory.py`.

## 3. Migrations

Добавлены:

1. `V001__license_core.sql`
   - `accounts`;
   - `billing_plans`;
   - `licenses`;
   - `api_tokens`;
   - `license_audit_log`;
   - uniqueness/check constraints и indexes.
2. `V002__billing_orders_events.sql`
   - `billing_orders`;
   - `billing_events`;
   - account idempotency и unique provider event/order constraints.
3. `V003__persistent_license_rate_limits.sql`
   - PostgreSQL events для shared multi-instance rate limiting.
4. `V004__billing_reconciliation_guard.sql`
   - расширяет billing order status до `VARCHAR(32)`;
   - добавляет `RECONCILIATION_REQUIRED`;
   - partial unique index разрешает только один open order для комбинации
     account/plan/provider.

`DatabaseMigrator`:

- применяет migrations transactionally;
- сохраняет SHA-256 checksum;
- отказывается продолжать при изменённой применённой migration;
- использует PostgreSQL advisory lock для одновременного старта инстансов;
- повторный запуск idempotent.

Clean PostgreSQL 17 database migration и повторный idempotent запуск прошли до
версий `[1, 2, 3, 4]` в полном PostgreSQL integration suite.

## 4. Новые endpoints

| Endpoint | Auth | Семантика |
|---|---|---|
| `POST /v1/admin/licenses/issue` | ADMIN static Bearer | Выпуск одноразового code; code возвращается один раз |
| `POST /v1/admin/licenses/revoke` | ADMIN static Bearer | Revoke license и активных API tokens |
| `POST /v1/license/redeem` | code + remote-IP limiter | Atomic `ISSUED -> ACTIVE`, device bind, account/token issuance |
| `POST /v1/license/validate` | DB-backed `jrv_` Bearer | Account/device/license/plan/billing/expiration validation |
| `POST /v1/billing/checkout` | DB-backed `jrv_` Bearer | Idempotent Paddle/HELEKET checkout creation |
| `POST /v1/billing/webhooks/paddle` | Paddle HMAC signature | Transaction/subscription/refund lifecycle |
| `POST /v1/billing/webhooks/heleket` | HELEKET signature + IP | Crypto payment lifecycle |

Невалидные состояния не маскируются `HTTP 200 + valid:false`: используются
`401/402/403/404/409/410/429/503` и стабильные machine codes. Неопределённый
checkout возвращает `202` в том же `BillingCheckoutResponse` contract с
`order_id`, `status=PROCESSING|RECONCILIATION_REQUIRED`, без checkout URL и с
`Cache-Control: no-store`.

## 5. Storage/security design

- Activation code: 100 random bits из `SecureRandom`.
- Plain code: только one-time issuance response, в БД не хранится.
- Code/token/device: HMAC-SHA256 с `LICENSE_CODE_PEPPER` из secret manager.
- API token: 256 random bits; в PostgreSQL хранится только HMAC.
- Device ID: plaintext не хранится.
- Provider webhook payload: хранится только SHA-256 hash и нормализованные поля.
- Account ownership берётся из authenticated token, не из request body.
- Plan, amount, duration, product и entitlement выбирает только сервер.
- Arbitrary client metadata не может задавать status/payment/expiry.
- Code redemption выполняется под `SELECT ... FOR UPDATE` и conditional update.
- 64 concurrent redeems дают ровно одного победителя и один token/account.
- Event fulfillment защищён двумя уровнями:
  unique `(provider,event_id)` и переходом локального order state.
- Signed event с конфликтующими local/provider order/subscription references
  fail-closed до изменения entitlement.
- Open checkout creation сериализуется PostgreSQL advisory lock и дополнительно
  защищена partial unique index.
- Transport timeout/5xx/malformed success не переводит order в `FAILED`: order
  блокирует повторный provider call и восстанавливается delayed signed webhook.
- Разные Paddle события одной transaction не продлевают license дважды.
- Refund atomically revokes license и tokens.
- Failed renewal не уничтожает уже оплаченный период.
- Expired account сохраняет auth token, может открыть checkout и после verified
  payment снова получает entitlement.
- AI auth без server entitlement возвращает `402` до AI provider call.
- Unknown и reused activation codes имеют одинаковые status/code/message.

## 6. Paddle

Реализовано:

- server-side transaction creation только с configured `price_id`;
- account/order/plan correlation через `custom_data`;
- trusted HTTPS checkout-host validation;
- `Paddle-Signature` parsing с поддержкой нескольких `h1`;
- HMAC-SHA256 по `timestamp:rawBody`;
- replay timestamp tolerance без integer-overflow bypass;
- constant-time signature comparison;
- transaction completed/payment-failed/canceled;
- subscription canceled/paused/past-due;
- approved refund adjustment;
- explicit 4xx/rejection/configuration errors classified terminal;
- transport/5xx/malformed successful responses classified ambiguous;
- webhook idempotency and delayed local-order reconciliation.

Live Paddle API не вызывался: credentials/merchant verification отсутствуют.

## 7. HELEKET

Реализовано по официальному API:

- `POST https://api.heleket.com/v1/payment`;
- provider-required request signature;
- unique local UUID `order_id`;
- exact payment (`is_payment_multiple=false`, accuracy 0);
- trusted HELEKET checkout URL;
- callback signature verification по provider canonicalization;
- default source-IP allowlist `31.133.220.8`;
- local order UUID, provider invoice UUID, amount и currency matching;
- paid/overpaid/failure/cancel/refund states;
- replay idempotency.

Live invoice/webhook test не выполнялся: merchant/API credentials отсутствуют.

## 8. Rate limiting

License/billing endpoints используют `PostgresRateLimiter`, а не process-local
state. Для каждой scope + SHA-256(identity):

- transaction-level advisory lock;
- minute/day windows;
- atomic count/insert;
- cleanup старше суток;
- shared limits между server instances;
- `Retry-After` с округлением вверх;
- reset точной identity;
- plaintext IP/account keys в таблице не хранятся.

Stress: 100 concurrent calls через два limiter instances дали 10 allowed и 90
limited при лимите 10.

## 9. Android integration

- Activation использует `/v1/license/redeem`, получает server expiry и `jrv_` token.
- Activation code больше не сохраняется на клиенте; legacy value удаляется.
- `/v1/license/validate` вызывается с Bearer token.
- Локальный expiry не создаётся и не продлевается.
- Persisted state является только display cache.
- Каждый app process начинает с `isActivated=false`; `MainActivity` сначала
  выполняет server refresh и только после valid response открывает основной UI.
- Offline/outage/rate-limit/unauthorized/revoked/wrong-device работают fail closed.
- Server AI endpoint повторно проверяет entitlement, поэтому модифицированный APK
  не обходит billing решением UI.
- Renewal button остаётся disabled.

## 10. Tests

Новые/расширенные server suites:

- `LicensePersistenceIntegrationTest` — 10 tests;
- `BillingPersistenceIntegrationTest` — 8;
- `LicenseApiIntegrationTest` — 6;
- `BillingProviderSecurityTest` — 6;
- `LicenseConfigTest` — 4;
- `PostgresRateLimiterTest` — 2;
- `DeploymentSecurityTest` — 8 TLS/proxy/deployment tests;
- Android `ApiTransportSecurityTest` — 2 HTTPS/Bearer origin tests;
- дополнительный entitlement regression в `ApiIntegrationTest`.

Проверены:

- issuance и отсутствие plaintext code;
- valid/unknown/malformed/expired/revoked/wrong-device/corrupt DB state;
- expiration и entitlement calculation;
- one-time replay и 64-way concurrent redemption;
- dynamic token authentication;
- API auth/authz и IDOR-resistant account derivation;
- brute-force limiter и reset;
- checkout idempotency и concurrent provider-call claim;
- ambiguous timeout + другой idempotency key => один provider call;
- delayed webhook recovery по trusted local order ID;
- стабильный HTTP 202 reconciliation contract;
- renewal после expiration;
- payment failure, cancel, refund;
- duplicate/different webhook replay;
- amount/currency и conflicting order-reference mismatch;
- terminal/ambiguous provider response classification;
- authenticated admin revoke rate limit;
- invalid/stale/extreme-timestamp Paddle signatures;
- modified/wrong-IP HELEKET callbacks;
- clean and repeated migrations;
- production TLS fail-fast config, trusted proxy spoofing, HTTPS scheme/client IP;
- Android exact HTTPS origin and no-redirect Bearer policy;
- full Docker Compose Caddy TLS 1.2/1.3, redirect, authenticated API,
  exact-proxy `/32`, private-port and header-deleting log smoke.

Итоговые результаты:

| Проверка | Результат |
|---|---|
| Android JVM | **420 passed**, 0 failed/errors/skipped (47 suites) |
| Server JVM/PostgreSQL | **110 passed**, 0 failed/errors/skipped (13 suites, `--rerun-tasks`) |
| Total JVM | **530 passed** |
| Android lintDebug | passed |
| Debug APK | passed, 141,252,700 bytes |
| androidTest APK | passed, 870,595 bytes |
| Server build/JAR/TAR/ZIP | passed |
| Release Kotlin/Java/Hilt/lintVital | passed |
| Full release APK | passed, unsigned artifact 125,385,381 bytes |
| Benchmark | 99/100, dangerous false routes 0 |
| Real PostgreSQL HTTP smoke | issue 201, redeem 200, replay 404, validate 200, wrong-device 403, unauth 401, unconfigured checkout 503, entitled AI reached provider layer |
| Instrumented execution | not run: `adb devices -l` returned no Android target |
| Release signing/install | not run: no protected release keystore or physical target |

Artifact SHA-256:

- debug APK: `e57b22292f64a765ba191c80e8e291a1d4747e0ef09b16b787e61140cf1fd8a7`;
- androidTest APK: `30d81c582075581170f1545e3793d701e327b73c2bd7918dadf8dfe65386bf30`;
- unsigned release APK: `9bb1fde1af9c62cd1dafcdcd456cd7bcc0d5c36c3b34e8e221bd8b28a4357a2c`.

`apksigner verify` correctly rejected the release artifact as unsigned; no debug
or invented production signing key was used.

## 11. Bugs found during implementation

1. **High:** API tokens initially expired together with entitlement, making renewal
   after expiry impossible. Token lifetime was separated from entitlement.
2. **High:** checkout initially required a currently active license, also blocking
   expired users from renewal. Latest non-revoked license is now renewable.
3. **High:** concurrent account/order insert conflict handling attempted SQL after
   a PostgreSQL unique violation had aborted the transaction. Replaced with
   `INSERT ... ON CONFLICT DO NOTHING`.
4. **High:** process-local license limiter was bypassable across instances.
   Replaced in production composition by PostgreSQL limiter.
5. **Medium:** Paddle timestamp subtraction could overflow for `Long.MIN_VALUE`.
   Replaced with bounded comparisons and regression test.
6. **Medium:** Android network cancellation was converted to ServiceUnavailable.
   Cancellation now propagates.
7. **Medium:** fallback hardware ID used blocking preferences commit and triggered
   lint; `apply()` preserves immediate in-memory identity without blocking UI.
8. **Medium:** Paddle/HELEKET body and checkout URLs needed explicit trust and
   amount matching; added fail-closed validation.
9. **Critical:** ambiguous checkout timeout originally became `FAILED`; retry with
   a different idempotency key could create a second remote charge. Added V004,
   serialized/open-order reuse, explicit failure classes, HTTP 202 reconciliation
   and delayed-webhook recovery regression.
10. **High:** signed event correlation accepted a local order ID without rejecting
    conflicting existing provider order/subscription references. Added fail-closed
    reference consistency checks and regression.
11. **Medium:** initial V004 retained `VARCHAR(16)`, too short for
    `RECONCILIATION_REQUIRED`; migration now expands it to `VARCHAR(32)`.
12. **Medium:** the first local-order webhook SQL used untyped nullable JDBC
    parameters and PostgreSQL could not infer parameter types. Replaced with a
    closed-set, type-inferred lookup and verified it against PostgreSQL 17.

## 12. Acceptance status

Code-level acceptance:

- [x] `/v1/license/validate` implemented;
- [x] PostgreSQL source of truth;
- [x] issuance;
- [x] atomic one-time redemption;
- [x] server expiration/entitlement;
- [x] billing state and renewal transaction;
- [x] authorization;
- [x] persistent rate limiting;
- [x] migrations/constraints/indexes;
- [x] unit/integration/security/concurrency/replay tests;
- [x] full JVM suites, lint, debug/test/release APK assembly and server build;
- [x] ambiguous-checkout duplicate-charge guard and reconciliation regressions;
- [x] renewal UI remains disabled.

Deployment acceptance still pending:

- [ ] Paddle merchant account/live or sandbox credentials supplied and dashboard
  webhook E2E completed;
- [ ] HELEKET merchant/API credentials supplied and real invoice/webhook E2E completed;
- [ ] provider-side query/reconciliation runbook implemented for the case where an
  ambiguous checkout never produces a webhook; current behavior blocks retries
  safely but may require operator intervention;
- [ ] existing production billing data preflighted for duplicate open orders
  before V004 (migration intentionally fails rather than choosing/deleting one);
- [ ] PostgreSQL production TLS/backups/monitoring deployed;
- [x] repository Caddy/Compose TLS topology, trusted-proxy enforcement and local
  production-like TLS smoke implemented (`audit/TLS_DEPLOYMENT_REPORT.md`);
- [ ] actual public DNS/ACME certificate/firewall/external authenticated HTTPS
  smoke completed (`api.jarvis.ai` currently does not resolve);
- [ ] Turkmenistan mobile/phone merchant API selected and contracted;
- [ ] protected release keystore/signing pipeline configured (verified release
  artifact is currently unsigned);
- [ ] physical Android activation/renewal E2E executed;
- [ ] only after all above: production renewal UI enabled.

Therefore the source-code blocker is substantially implemented, but the overall
production blocker is **not declared removed** until real provider/deployment E2E
and the local Turkmenistan payment contract exist.
