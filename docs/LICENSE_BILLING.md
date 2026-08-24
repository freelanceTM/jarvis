# Server-side licensing and billing

## Status

The repository now contains a PostgreSQL-backed, fail-closed license and billing
subsystem. Client preferences are a display cache only. On each app process
start, Android calls the authenticated validation endpoint before unlocking the
main UI. AI execution is independently entitlement-gated on the server.

The renewal button remains disabled until real Paddle/HELEKET credentials,
webhook destinations and production deployment are configured and tested.
Turkmenistan mobile/phone billing remains disabled because no public merchant
API exists in the repository; a bank or telecom merchant contract and official
callback specification are required.

## Runtime flow

```text
admin Bearer token
  -> POST /v1/admin/licenses/issue
  -> SecureRandom activation code returned once
  -> only HMAC-SHA256(code, LICENSE_CODE_PEPPER) stored in PostgreSQL

Android code + device ID
  -> POST /v1/license/redeem (rate limited by remote peer)
  -> SELECT ... FOR UPDATE
  -> atomic ISSUED -> ACTIVE transition
  -> account + opaque jrv_ Bearer token created
  -> repeated/concurrent redemption rejected

jrv_ Bearer token + device ID
  -> POST /v1/license/validate
  -> token/account/device/license/plan/billing/expiry checked in PostgreSQL
  -> current server entitlement returned

Authenticated account
  -> POST /v1/billing/checkout
  -> PostgreSQL advisory lock + at most one open account/plan/provider order
  -> idempotent local order + atomic provider-call claim
  -> Paddle transaction or HELEKET invoice
  -> ambiguous timeout/5xx/malformed success => HTTP 202 RECONCILIATION_REQUIRED
  -> no provider retry, even with a different client idempotency key
  -> delayed signed webhook correlates the trusted local order ID
  -> idempotent billing event
  -> transactionally extend/revoke entitlement
```

## Endpoints

| Method | Path | Authentication | Purpose |
|---|---|---|---|
| POST | `/v1/admin/licenses/issue` | static ADMIN Bearer | Issue one-time code |
| POST | `/v1/admin/licenses/revoke` | static ADMIN Bearer | Revoke license and its tokens |
| POST | `/v1/license/redeem` | activation code in body, IP rate limit | Atomic first activation |
| POST | `/v1/license/validate` | DB-backed `jrv_` Bearer | Validate account/device entitlement |
| POST | `/v1/billing/checkout` | DB-backed `jrv_` Bearer | Create idempotent provider checkout |
| POST | `/v1/billing/webhooks/paddle` | Paddle HMAC signature | Paddle lifecycle events |
| POST | `/v1/billing/webhooks/heleket` | HELEKET signature + IP allowlist | Crypto payment events |

Invalid states use non-200 statuses (`401`, `402`, `403`, `404`, `409`, `410`,
`429`, `503`). Checkout returns `202` with the stable `BillingCheckoutResponse`
shape, `status=PROCESSING|RECONCILIATION_REQUIRED`, the local `order_id`, no
checkout URL, and `Cache-Control: no-store` when completion is uncertain.
Redemption deliberately returns the same `LICENSE_NOT_REDEEMABLE` response for
unknown, already-used, expired and revoked codes to reduce license enumeration.

## Migrations

- `V001__license_core.sql`: accounts, plans, licenses, hashed API tokens and audit log.
- `V002__billing_orders_events.sql`: idempotent orders and provider events.
- `V003__persistent_license_rate_limits.sql`: multi-instance redemption,
  validation and webhook limits.
- `V004__billing_reconciliation_guard.sql`: expands billing order status storage,
  adds `RECONCILIATION_REQUIRED`, and enforces one open order per
  account/plan/provider with a partial unique index.

Migrations are immutable resources. `DatabaseMigrator` stores SHA-256 checksums
and serializes multi-instance startup with a PostgreSQL advisory lock. Before an
upgrade of a database that already contains billing data, reconcile duplicate
open account/plan/provider orders; V004 intentionally fails rather than silently
deleting or choosing between conflicting payment records.

## Stored security properties

- Activation codes: 100 random bits, formatted as `JRV-XXXXX-...`.
- Plain activation code: returned once, never persisted.
- Code/token/device representation: HMAC-SHA256 with an external pepper.
- API token: 256 random bits, only keyed hash stored.
- Device identifier: only keyed hash stored with license.
- Provider event payload: only SHA-256 hash stored.
- Billing fulfillment: guarded both by unique provider event ID and the local
  order transition, so two distinct event types cannot double-extend a license.
- Conflicting local/provider order or subscription references in a signed event
  fail closed before entitlement changes.
- Ambiguous checkout results are never converted to `FAILED`; a new checkout is
  blocked until a signed delayed webhook or an explicit reconciliation resolves
  the original order.
- Checkout price/plan/account are selected server-side. Client-supplied amount,
  product, account or entitlement fields do not exist in the API contract.

`LICENSE_CODE_PEPPER`, database password, Paddle secrets and HELEKET API key
must be supplied from a secret manager. Never rotate the code pepper without a
planned dual-key migration because existing code/token hashes depend on it.

## Paddle

Integration follows Paddle Billing:

- API transaction creation uses a server-side configured `price_id`;
- account/order/plan correlation is placed in `custom_data`;
- `Paddle-Signature` is verified over `timestamp:rawBody` using HMAC-SHA256;
- timestamp tolerance rejects replayed old requests;
- multiple `h1` values are supported for secret rotation;
- transaction complete/failure/cancel, subscription cancel/pause/past-due and
  approved refund adjustments are normalized into local events;
- explicit provider 4xx/rejection/configuration failures are terminal, while
  transport errors, provider 5xx and malformed successful responses require
  reconciliation and are not retried as a new transaction.

Official references:

- https://developer.paddle.com/webhooks/about/signature-verification/
- https://developer.paddle.com/build/mobile-apps/link-out-mobile-app-custom-workflow/

A Paddle live account requires merchant verification. Use sandbox keys until
that process and a real payment test are complete.

## HELEKET

- Invoice creation: `POST https://api.heleket.com/v1/payment`.
- The local order UUID is the provider `order_id`.
- Multiple/underpaid completion is disabled (`is_payment_multiple=false`,
  `accuracy_payment_percent=0`).
- Callback signature uses the provider-mandated MD5(base64(JSON)+API key)
  scheme and constant-time comparison.
- The default source allowlist is `31.133.220.8`; do not trust
  `X-Forwarded-For` without a separately hardened trusted-proxy design.
- Local order ID, provider invoice UUID, amount and currency must all match
  before entitlement changes.

Official references:

- https://doc.heleket.com/general/request-format
- https://doc.heleket.com/methods/payments/creating-invoice
- https://doc.heleket.com/methods/payments/webhook

## Plan catalog format

`BILLING_PLANS` contains semicolon-separated entries:

```text
id|product|displayName|durationDays|amountMinor|currency|paddlePriceId|heleketTargetCurrency
```

Use `-` for an unavailable provider. Amounts are minor fiat units; the example
`1400|USD` means USD 14.00. Paddle remains authoritative for its configured
price and taxes. HELEKET receives the configured amount/currency and converts
to the target crypto currency.

## Deployment prerequisites

1. PostgreSQL with TLS/network policy and backups.
2. Unique 32+ byte `LICENSE_CODE_PEPPER` from a secret manager.
3. At least one static ADMIN token and `JARVIS_ADMIN_CLIENTS` entry.
4. Paddle sandbox/live credentials and notification destination.
5. HELEKET merchant/API key and direct callback reachability.
6. Mandatory HTTPS reverse proxy/TLS termination from
   [`PRODUCTION_DEPLOYMENT.md`](PRODUCTION_DEPLOYMENT.md); application port must
   remain private and production startup safety checks must pass.
7. Provider webhook tests from their dashboards before enabling renewal UI.
8. A separate Turkmenistan bank/telecom contract before `LOCAL_TMT` can be enabled.
9. A protected Android release keystore and signing pipeline; the locally verified
   release artifact is intentionally unsigned.
