# ADR-0001: One production application instance with PostgreSQL shared security state

- **Status:** Accepted
- **Date:** 2026-08-22
- **Owners:** JARVIS server maintainers

## Context

The server contains two kinds of mutable state:

1. Business/security state whose loss or per-instance divergence is unacceptable: authentication/licensing, billing, webhook/idempotency state, rate limits, and AI usage accounting.
2. Operational/adaptive state whose loss on restart is acceptable: provider circuit-breaker observations and process metrics.

Running multiple application instances while the second category remains process-local would produce inconsistent provider health and non-aggregated metrics. Historically the AI rate limiter and usage ledger were also process-local, which made restart and horizontal scaling materially change enforcement/accounting.

PostgreSQL is already a mandatory production dependency. Adding Redis only to silence an architectural warning would duplicate infrastructure and failure modes.

## Decision

### Shared PostgreSQL state

The following state is shared by every attempted server process and survives application restart:

- accounts, licenses, opaque API-token hashes and authorization state;
- billing plans, orders, reconciliation state, provider IDs and webhook events;
- checkout and webhook idempotency constraints;
- every HTTP rate-limit scope, including `/v1/ai/execute`;
- AI usage records with a 30-day retention window;
- schema migration serialization and billing/license transactional locks.

Security-critical shared-store failure is fail-closed. A PostgreSQL error does not become an allowed rate-limit decision or silently discarded usage record.

### Deliberately process-local state

The following remains in memory:

- provider circuit-breaker/health observations;
- exported operational metrics counters;
- retry attempt variables and request-local orchestration;
- immutable configuration/provider registries loaded at startup;
- HTTP executor threads and connection-pool internals.

Circuit and metric loss at restart is acceptable: breakers restart `CLOSED` and metrics restart at zero. They are not authorization, entitlement, billing, idempotency, quota or rate-limit sources of truth. Eventual consistency is not claimed; these values are explicitly per-process.

### Maximum topology

Until a later ADR replaces the remaining process-local operational state, production supports exactly:

```text
1 Caddy edge instance
1 JARVIS application instance
1 PostgreSQL logical primary (provider-managed HA/replicas are outside the app topology)
```

Enforcement is defense in depth:

1. production config requires explicit `APPLICATION_REPLICA_COUNT=1`;
2. Compose declares one statically addressed application service;
3. deployment validation rejects a declared count other than one;
4. startup acquires a dedicated PostgreSQL session advisory lock;
5. a second application process fails startup closed while the first lock is held.

The advisory lock connection is held for process lifetime. PostgreSQL releases it automatically on process/connection death. Production database pools must contain at least two connections because one is reserved by the topology guard.

## Restart consequences

- License, auth, billing, idempotency, all rate limits and usage records survive.
- Provider breaker history and process metrics reset.
- In-flight provider calls, retry delays and HTTP requests are lost and may be retried by clients under existing request/idempotency policy.
- No background job queue exists in the current server.

## Failure and consistency rules

- PostgreSQL unavailable at startup: application startup fails.
- PostgreSQL unavailable during rate-limit check: request fails; it is never treated as unlimited.
- PostgreSQL unavailable during usage write: accounting failure is visible; it is never silently ignored.
- Rate-limit check-and-insert is serialized per hashed identity with transaction-scoped advisory locks.
- Billing/license transitions use database transactions, uniqueness constraints and row/advisory locking.
- Usage duplicate writes are idempotent on `(client_id, request_id)`.
- Usage retention is eventual cleanup performed transactionally during writes.

## Horizontal-scaling exit criteria

A future multi-instance ADR must, before increasing replicas:

1. replace or explicitly redesign provider circuit-breaker semantics in a shared backend;
2. export metrics to an external aggregator rather than treating one process as global;
3. remove the replica-count restriction and session lock;
4. add multi-instance provider-health and metrics tests;
5. retain PostgreSQL-backed rate limits, usage, auth, billing and idempotency tests;
6. update Caddy/orchestrator routing, health checks and deployment documentation.

## Consequences

### Positive

- No new Redis operational dependency.
- Security/business state is restart-safe and multi-process-safe.
- Accidental horizontal scaling is actively rejected rather than merely documented.
- Remaining in-memory state has bounded, non-authoritative semantics.

### Negative

- One pool connection is permanently reserved.
- Application horizontal scaling is intentionally unavailable.
- Circuit state and metrics reset on restart.
- PostgreSQL is a hard availability dependency; this is intentional fail-closed behavior.
