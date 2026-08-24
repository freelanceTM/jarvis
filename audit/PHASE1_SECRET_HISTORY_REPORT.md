# Phase 1 — Secret Scan Status

**Date:** 2026-08-22  
**Phase status:** **BLOCKED / NOT COMPLETE for Git history**

## Repository-history prerequisite

The authoritative workspace `/home/user/jarvis` has no `.git` directory and no remote URL. Therefore:

- `git rev-parse --is-shallow-repository` cannot run against this workspace;
- `git fetch --unshallow` cannot be performed;
- branches, tags, deleted files, renamed files and historical commits are unavailable;
- no claim of a full history audit is valid yet.

The repository name `jarvis` alone is not enough to identify a unique GitHub repository. A full `https://github.com/<owner>/jarvis` URL or a full read-only clone must be supplied.

## Credential handling incident during this phase

A GitHub personal access token was pasted into conversation text. It was **not used, stored in workspace files, placed in a URL, or passed to a shell command**. Because chat disclosure is credential exposure, it must be considered compromised and revoked. Revocation/rotation cannot be verified automatically from this workspace and remains a manual blocker.

The replacement access mechanism, if the repository is private, must be a read-only deploy credential supplied through a protected secret channel—not chat or a command-line URL.

## Current working-tree scan

Gitleaks v8.30.1 was downloaded from its release artifact and verified against SHA-256:

`551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb`

The scanner first reported one `generic-api-key` candidate:

- file: `server/src/test/kotlin/com/jarvis/server/BillingProviderSecurityTest.kt`;
- value type: synthetic idempotency test fixture;
- classification: false positive, not a credential;
- remediation: changed the synthetic fixture to an obviously non-secret low-entropy test value; no broad allowlist was added.

Final current-tree result:

- scanned size: approximately 26.8 MB;
- findings: **0**;
- evidence: `audit/evidence/gitleaks-current-tree.json`.

## Preventive CI control

`.github/workflows/security.yml` now:

1. checks out with `fetch-depth: 0` and tags;
2. verifies the clone is not shallow;
3. invokes `scripts/run-gitleaks-history.sh`;
4. scans `--all` Git history with redacted reports;
5. fails CI on a finding.

The script itself refuses to run when `.git` is absent or the clone is shallow, so an incomplete scan cannot produce a misleading green result.

## Required completion procedure

After an exact remote URL/full clone is available:

```bash
git rev-parse --is-shallow-repository
# If true:
git fetch --unshallow --tags
# Fetch remaining remote refs without rewriting history:
git fetch --all --tags --prune
bash ./scripts/run-gitleaks-history.sh . audit/evidence/gitleaks-history.json
```

Then manually classify every finding by credential type, commits/refs, scope and current validity. Any real credential must be treated as compromised and revoked/rotated before considering a history rewrite. History must not be rewritten automatically.

## Unresolved acceptance items

- [ ] Exact repository URL/full clone supplied.
- [ ] All branches/tags/history available locally.
- [ ] Full-history Gitleaks scan executed.
- [ ] Historical findings manually classified.
- [ ] The chat-exposed GitHub token confirmed revoked.
- [ ] Any other real credentials revoked/rotated.
- [ ] History-rewrite decision documented if real historical exposure exists.

Until these are resolved, Phase 1 must not be declared complete.
