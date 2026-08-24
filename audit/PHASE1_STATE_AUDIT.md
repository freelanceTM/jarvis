# Phase 1 — Stateful Component Audit

**Date:** 2026-08-22  
**Decision:** hybrid shared PostgreSQL + formally enforced single application instance  
**ADR:** `docs/adr/0001-single-application-instance.md`

## Classification fields

- **Critical:** loss/divergence can violate authorization, money, idempotency, quota, privacy or integrity.
- **Shared:** must be visible across attempted instances/restarts.
- **Loss OK:** process restart may discard it without violating a source-of-truth guarantee.
- **Atomic/lock:** compound operations require database transaction, uniqueness constraint, advisory/row lock or process synchronization.
- **TTL:** state has expiration/retention semantics.

## Server inventory

| Component/state | Critical | Shared | Loss OK | Consistency / atomicity / TTL | Decision |
|---|---:|---:|---:|---|---|
| Accounts, licenses, entitlement and opaque API-token hashes | Yes | Yes | No | PostgreSQL transactions, unique hashes, row updates; expiry timestamps | Already PostgreSQL; source of truth |
| Static bootstrap client-token map and admin-client set | Yes | Configuration must match | Reload only | Immutable after startup; supplied by secret manager/env | Process-local immutable config; single-instance guard prevents divergence |
| Authentication request state | Yes | No session exists | Yes | Bearer credential validated per request against immutable bootstrap map or PostgreSQL token hash | Request-local; no server session map |
| Billing plans/catalog | Yes | Yes | No | PostgreSQL upsert from validated startup config | PostgreSQL source of truth |
| Billing orders/status/reconciliation | Yes | Yes | No | Transactions, partial unique open-order constraint, provider IDs | PostgreSQL |
| Checkout idempotency | Yes | Yes | No | Unique `(account_id, idempotency_key)` plus transaction/advisory locking | PostgreSQL |
| Webhook replay/idempotency and fulfillment transitions | Yes | Yes | No | Unique provider event IDs, payload hash, transaction/status guard | PostgreSQL |
| License redemption/replay state | Yes | Yes | No | Atomic transaction, one-time status transition, device/token hashes | PostgreSQL |
| License/auth/webhook rate limits | Yes | Yes | No | Hashed key, transaction-scoped advisory lock; 1-minute/1-day windows | PostgreSQL (existing) |
| AI execute rate limit | Yes | Yes | No | Same atomic PostgreSQL limiter under `ai_execute`; day-window cleanup | **Migrated from in-memory to PostgreSQL** |
| AI usage ledger/counters | Accounting | Yes | No | Idempotent `(client_id, request_id)` insert; concurrent transactions; 30-day retention | **Migrated from bounded queue to PostgreSQL** |
| Schema migration state | Yes | Yes | No | Immutable checksums and global PostgreSQL advisory lock | PostgreSQL |
| Single application ownership | Topology/security | Yes | Lock loss means process death | Session advisory lock; exactly one holder | **Added PostgreSQL single-instance guard** |
| Provider circuit-breaker entries | Operational | No under ADR-0001 | Yes | Atomic CAS/counters; cooldown; one half-open probe per process | Process-local; reset on restart is documented |
| Provider permanent-disable reason | Operational | No under ADR-0001 | Yes | Atomic reference; reset by restart/reconfiguration | Process-local; never auth/billing source |
| Metrics counters/provider latency/failure maps | Operational | No under ADR-0001 | Yes | Atomic counters/maps; endpoint is per-process | Process-local; reset on restart |
| Provider retry/fallback attempt state | No | No | Yes | Coroutine/request-local variables and timeout | Request-local only |
| HTTP request/parser/auth/routing state | Yes while active | No | Yes | Stack/coroutine-local; no reusable session | Request-local only |
| HTTP executor threads | No | No | Yes | Fixed process pool | Process resource |
| Hikari connection pool | Availability | No | Yes | Local pool; PostgreSQL is shared. One connection reserved for topology lock | Process resource |
| Structured logger | No | No | Yes | Stateless except output sink/clock | Process-local |
| Server caches | — | — | — | No application data cache found | Not present |
| Server background job queue/scheduler state | — | — | — | No background job system found | Not present |
| Feature flags/runtime mutations | — | — | — | Startup configuration is immutable; no runtime feature-flag store | No mutable flag state |

### Server outage semantics

- Database unavailable at startup: migration/topology acquisition fails; server does not start.
- Database unavailable during rate limit: exception propagates to a server error; no unlimited fallback.
- Database unavailable during usage accounting: failure is visible; accounting is not silently dropped.
- Circuit breaker unavailable is not a separate dependency; it is local and conservative only for provider selection.

## Android inventory

Android is one application sandbox/process per installed app/device, not part of server horizontal scaling. Its process-local state must be thread-safe but must not be moved to Redis/PostgreSQL.

| Component/state | Critical | Persistence/loss | Synchronization / bounds | Decision |
|---|---:|---|---|---|
| Room messages, memories, facts, preferences, procedures, automations and trigger timestamps | User data/integrity | Persistent; loss not acceptable | Room transactions/DAO constraints; schema v5 migrations | Device-local Room source of truth |
| Settings DataStore | Configuration | Persistent | DataStore serialized updates | Device-local persistent |
| Access token/license cache | Security | EncryptedSharedPreferences; server refresh remains authoritative | Encrypted storage + state flows | Device-local; server is source of truth |
| WorkingMemory conversation slots | Privacy/context correctness | Loss on process restart acceptable | Synchronized; volatile context | Process-local ephemeral |
| WorkingMemory observation cache | No | Loss acceptable | LRU 128 entries, 30-minute TTL, synchronized | Process-local bounded cache |
| Pending tool-confirmation queue/tokens | Security | Loss cancels pending actions; never authorizes them | Queue max 8, synchronized compound operations, one-time random tokens | Process-local fail-closed |
| Automation initialization flag and mutexes | Integrity | Flag loss acceptable; rules/timestamps are in Room | Init/event mutex; Room re-check prevents duplicate defaults | Process-local coordination + persistent truth |
| MediaPipe model runtime/session | Availability | Loss acceptable; reload | Lifecycle mutex; single runtime; cancellation cleanup | Process resource |
| Voice orchestrator modes, jobs, last query/answer and confirmation pointers | UI/session | Loss acceptable | StateFlow, structured coroutine cancellation, atomic busy flag | Process-local interaction session |
| STT/TTS/Bluetooth/wake-word state | Device lifecycle | Loss acceptable | Main/IO coroutine scopes, callbacks/state flows | Process/device resource |
| Accessibility service singleton reference | Device lifecycle | Loss acceptable; cleared on destroy | Android service lifecycle; nullable singleton | Required bridge to active Android service |
| ViewModel/UI state | UI only | Loss/recreated by Android lifecycle/history streams | StateFlow/ViewModel scope | Process-local UI state |
| Tool registry, aliases, synonym/scenario indices | No mutable user state | Rebuilt on restart | Immutable maps/lazy deterministic index | Process-local immutable cache/config |
| Per-request planner/agent observations/retry counters | No | Loss acceptable | Coroutine-local | Request-local |

## Race and restart evidence

Added/retained tests cover:

- two PostgreSQL limiter instances sharing one rate window;
- 100 concurrent attempts admitting exactly the configured number;
- limiter recreation preserving state and TTL expiration restoring allowance;
- two JDBC usage repositories seeing the same records;
- concurrent distinct usage writes and duplicate request idempotency;
- usage retention expiration;
- shared-store outage not becoming unlimited access or silent accounting loss;
- second application topology lock failing closed;
- lock release/restart acquiring successfully;
- production config rejecting multiple declared replicas;
- deployment config explicitly declaring one replica.

## Deployment conclusion

Production horizontal scaling is intentionally prohibited, not silently unsupported. The current Compose topology, validated environment, startup advisory lock and ADR all specify one application instance. PostgreSQL may use infrastructure-level HA, but the application observes one logical primary endpoint.
