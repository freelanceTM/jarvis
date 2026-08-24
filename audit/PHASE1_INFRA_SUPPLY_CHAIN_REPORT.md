# Phase 1 — Infrastructure and Supply-chain Audit

**Date:** 2026-08-22  
**Overall status:** **PARTIALLY COMPLETE — architecture and supply chain pass locally; full Git-history scan blocked**

## 1. Architecture/state result

### Shared state

PostgreSQL is the existing mandatory production backend and was selected instead of adding Redis.

Shared/restart-safe state now includes:

- authentication/license/token-hash and entitlement state;
- billing plans, orders, reconciliation and webhook events;
- redemption, checkout and webhook idempotency;
- license/auth/webhook rate limiting;
- **AI execute rate limiting** (migrated from process memory);
- **AI usage accounting** (migrated from a bounded process queue);
- migration and transactional/advisory locks.

Migration `V005__shared_ai_usage.sql` adds an idempotent usage ledger, client/time and retention indexes. `JdbcUsageRepository` uses `(client_id, request_id)` idempotency and a 30-day cleanup window.

### Deliberately in-memory

Provider circuit-breaker observations, process metrics, request retry variables, immutable startup registries/config and runtime thread/connection resources remain process-local. None is an authentication, entitlement, billing, idempotency, quota or rate-limit source of truth.

### Single-instance decision and enforcement

ADR: `docs/adr/0001-single-application-instance.md`.

Production supports one JARVIS application instance. Enforcement:

- explicit `APPLICATION_REPLICA_COUNT=1` is mandatory in production config;
- production config rejects any other count;
- Compose declares one statically addressed app service;
- deployment validator asserts the count;
- a process-lifetime PostgreSQL session advisory lock rejects a second live application process;
- production pool size must leave a connection for the lock.

This is an active prohibition, not only documentation. Horizontal scaling remains forbidden until circuit/metrics semantics are redesigned and a replacement ADR is accepted.

Full component matrix: `audit/PHASE1_STATE_AUDIT.md`.

## 2. Shared-state tests

Verified:

- two limiter instances share one rate window;
- 100 concurrent calls admit exactly the configured allowance;
- limiter recreation preserves state;
- minute TTL expiration restores allowance;
- two JDBC usage repositories see shared records;
- 100 concurrent usage writes are retained;
- duplicate `(client, request)` writes are idempotent;
- usage retention expires old rows;
- shared-store outage does not become unlimited access or silent accounting loss;
- a subsequent request reconnects after simulated transient rate/usage store failure;
- second application lock acquisition fails closed;
- lock release permits restart acquisition;
- production replica count >1 is rejected.

## 3. Immutable CI and supply chain

All GitHub Actions in both workflows are pinned to full commit SHAs with human-readable version comments. No mutable action refs remain.

Pinned direct actions:

- `actions/checkout` v4.2.2;
- `actions/setup-java` v4.7.1;
- `gradle/actions/setup-gradle` v4.4.4;
- `actions/upload-artifact` v4.6.2;
- `actions/dependency-review-action` v5.0.0;
- `aquasecurity/trivy-action` v0.36.0;
- `anchore/sbom-action` v0.24.0.

The pinned Trivy composite's transitive setup/cache action references were inspected and are immutable. Repository use disables Trivy action caching.

Additional hardening:

- workflows use `permissions: contents: read` only;
- checkout disables persisted credentials;
- no `pull_request_target`;
- no production secrets exposed to PR jobs;
- PR Gradle caches are read-only;
- shell commands do not interpolate PR titles/branch/user content;
- artifact paths and retention are explicit;
- Ubuntu runner changed from floating `ubuntu-latest` to `ubuntu-24.04`;
- Gradle wrapper has a SHA-256 distribution checksum;
- app/server dependency lockfiles are checked in and stable;
- Gradle artifact verification metadata contains SHA-256 checksums;
- Dockerfile frontend/base images and Compose Caddy/PostgreSQL images use exact versions and immutable digests.

Static regression tests reject mutable actions/unpinned container inputs or missing controls. `actionlint` v1.7.12 and YAML parsing pass locally.

## 4. Dependency scanning and remediation

Trivy v0.74.0 initially found **22 High** source dependency findings:

- vulnerable protobuf Java/Javalite versions;
- vulnerable Commons IO build tooling;
- multiple Netty codec/HTTP/HTTP2/TLS findings;
- PostgreSQL JDBC 42.7.7 findings.

Remediation:

- PostgreSQL JDBC → `42.7.13`;
- Netty security floor → `4.1.137.Final`;
- protobuf-java → `3.25.5`;
- protobuf-javalite → `4.28.2`;
- Commons IO → `2.14.0`;
- Guava → `33.7.1-android` (also removed the remaining Medium/Low source findings).

Final source result:

- High/Critical: **0**;
- Medium/Low/Unknown: **0**;
- misconfigurations at selected severities: **0**;
- evidence: `audit/evidence/trivy-source-high-critical.json` and `trivy-source-all.json`.

CI policy:

- High/Critical: blocking (`exit-code: 1`);
- Medium/Low/Unknown: JSON report, non-blocking;
- no vulnerability or directory ignores configured.

Dependabot is configured weekly for Gradle, GitHub Actions and Docker. Major versions produce review PRs and are not auto-merged.

## 5. SBOM

CycloneDX JSON is generated with Syft v1.51.0.

Local evidence:

- repository SBOM: `audit/evidence/jarvis-repository.cdx.json` — 352 components;
- final image SBOM: `audit/evidence/jarvis-server-image.cdx.json` — 5,033 components and 133 dependency relationships.

CI produces corresponding `artifacts/jarvis-repository.cdx.json` and `artifacts/jarvis-server-image.cdx.json`, uploaded for 30 days.

## 6. Final production image scan

Built final image from immutable Docker inputs:

- local tag: `jarvis-server:phase1-final`;
- image ID: `sha256:57828ada9a1e439fe129571a195c17d0a882eed5e785fec2b997007e86c13536`;
- runtime user: `10001:10001`;
- size: 273,268,288 bytes.

Trivy final-image result:

- High/Critical OS/library vulnerabilities: **0**;
- Medium: **12**;
- Low: **17**;
- Medium/Low findings are Ubuntu Jammy packages without a currently listed fix and remain report-only under policy;
- no scanner exclusions were added.

Evidence:

- `audit/evidence/trivy-image-high-critical.json`;
- `audit/evidence/trivy-image-medium-low.json`;
- final image CycloneDX SBOM.

Production Compose/Caddy validation passes with immutable Caddy/PostgreSQL images and the single-instance declaration.

## 7. Secret scanning

Current working tree:

- Gitleaks v8.30.1 with verified release checksum;
- initial candidates: 1 synthetic idempotency fixture;
- classification: false positive/non-credential;
- fixed by making the fixture clearly synthetic; no allowlist;
- final findings: **0**.

Preventive CI checks out full history/tags and runs a script that refuses shallow/non-Git repositories.

**Full historical scan is not complete:** this workspace has no `.git` metadata and no exact GitHub `owner/repository` URL. A PAT pasted into chat was not used and must be revoked. See `audit/PHASE1_SECRET_HISTORY_REPORT.md`.

## 8. Final verification evidence

| Check | Result |
|---|---:|
| Android JVM tests | **439 passed, 0 failed/skipped** |
| Server/PostgreSQL tests | **143 passed, 0 failed/skipped** |
| Android `lintDebug` | PASS |
| Android `assembleDebug` | PASS |
| Android `assembleRelease` + `lintVitalRelease` | PASS |
| Server build/package | PASS |
| Final production image build | PASS |
| Production Compose/Caddy validation | PASS |
| Dependency locks reproducibility | PASS |
| Gradle artifact checksum verification | PASS |
| `actionlint` + YAML parse | PASS |
| Trivy source High/Critical | **0** |
| Trivy final image High/Critical | **0** |
| Current-tree Gitleaks | **0** |
| Full-history Gitleaks | **BLOCKED — no full Git clone/remote URL** |

Android connected/instrumented tests remain unrun because no adb target exists.

## 9. Remaining blockers/risks

1. Exact GitHub URL/full clone is required for branches/tags/deleted-file history scan.
2. The chat-exposed PAT must be confirmed revoked; it was not used by this work.
3. Historical findings and any required credential rotations/history rewrite cannot be assessed before the full clone exists.
4. GitHub-hosted execution of the new workflows cannot be observed from this metadata-less workspace; local actionlint, scanners and equivalent commands pass.
5. Medium/Low base-image CVEs without listed fixes remain report-only and require weekly scanner review.
6. Horizontal scaling remains intentionally prohibited; metrics and provider circuit state are process-local by ADR.
7. Android physical-device/instrumented behavior remains outside this sandbox evidence.

## 10. Completion decision

Architecture/state management and supply-chain hardening satisfy their local acceptance criteria. **Phase 1 as a whole is not declared complete** because complete Git history and token revocation evidence are unavailable.
