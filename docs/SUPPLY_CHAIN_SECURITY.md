# Supply-chain security policy

## Immutable CI and build inputs

Every external GitHub Action reference must use a full 40-character commit SHA. A trailing comment records the reviewed release tag. Mutable tags/branches (`@v4`, `@main`, `@latest`) are prohibited and enforced by `SupplyChainConfigurationTest`.

Reviewed direct actions:

| Action | Pinned commit | Human version |
|---|---|---|
| `actions/checkout` | `11bd71901bbe5b1630ceea73d27597364c9af683` | v4.2.2 |
| `actions/setup-java` | `c5195efecf7bdfc987ee8bae7a71cb8b11521c00` | v4.7.1 |
| `gradle/actions/setup-gradle` | `748248ddd2a24f49513d8f472f81c3a07d4d50e1` | v4.4.4 |
| `actions/upload-artifact` | `ea165f8d65b6e75b540449e92b4886f43607fa02` | v4.6.2 |
| `actions/dependency-review-action` | `a1d282b36b6f3519aa1f3fc636f609c47dddb294` | v5.0.0 |
| `aquasecurity/trivy-action` | `ed142fd0673e97e23eac54620cfb913e5ce36c25` | v0.36.0 |
| `anchore/sbom-action` | `e22c389904149dbc22b58101806040fa8d37a610` | v0.24.0 |

The pinned Trivy composite action was inspected. Its transitive actions are themselves pinned to immutable commits (`aquasecurity/setup-trivy` and `actions/cache`). Caching is disabled in this repository's security jobs to reduce cache-poisoning exposure.

The Gradle 8.7 wrapper distribution has a SHA-256 checksum. Gradle dependency locks cover Android and server direct/transitive configurations. `gradle/verification-metadata.xml` verifies downloaded artifacts by SHA-256.

Production Dockerfile frontend/base images and Compose Caddy/PostgreSQL images use exact versions plus immutable multi-arch manifest digests.

## Automated vulnerability policy

`.github/workflows/security.yml` runs on push, pull request, weekly schedule and manual dispatch.

- GitHub Dependency Review blocks newly introduced **High/Critical** vulnerable dependencies on pull requests.
- Trivy filesystem scanning checks lockfiles/dependencies and configuration.
- Trivy scans the **final `jarvis-server:security` production image**, including OS and application packages.
- **Critical and High:** CI failure; production blocker until fixed or a narrowly reviewed exception is added.
- **Medium, Low and Unknown:** non-blocking JSON evidence artifact for triage.

No broad scanner ignores are configured. Any future exception must identify the exact vulnerability/package, reason, scope, owner and review/expiry date.

## SBOM

Syft v1.51.0 runs through the immutable Anchore action and generates CycloneDX JSON:

- `artifacts/jarvis-repository.cdx.json` — source/lockfile dependency inventory;
- `artifacts/jarvis-server-image.cdx.json` — exact final production container image.

Security reports and SBOMs are uploaded for 30 days. The image SBOM relates to the same local image tag scanned by Trivy in that job.

## Dependency update policy

Dependabot is the sole automated updater. It creates weekly PRs for:

- Gradle dependencies/plugins (grouped by ecosystem area);
- GitHub Actions;
- Docker build inputs.

Major updates are allowed as review PRs; none are auto-merged. Every update must refresh lock/checksum metadata as needed and pass full build, tests, lint, dependency review, secret scan, Trivy and SBOM generation before merge.

To intentionally update dependencies:

```bash
bash ./gradlew :app:dependencies :server:dependencies \
  --write-locks --write-verification-metadata sha256
```

Review dependency diffs and checksum provenance before committing. Do not blindly accept generated checksum changes.

## CI permissions and untrusted pull requests

Workflows declare only:

```yaml
permissions:
  contents: read
```

There is no `pull_request_target`, deployment credential use, write token, or shell interpolation from PR titles/branch names. Checkout disables credential persistence. Pull requests execute untrusted code only with read-only repository permissions and no project production secrets. Gradle cache writes are disabled for pull-request builds.

Production deployment is not performed by these workflows. Artifact retention is explicit and APK/SBOM paths are narrow.

## Secret scanning

`scripts/run-gitleaks-history.sh`:

- requires a non-shallow Git repository;
- downloads Gitleaks v8.30.1;
- verifies the archive SHA-256 before execution;
- scans `--all` refs/history;
- redacts findings in console/report output;
- fails CI on findings.

The security workflow checks out full history and tags before running it. Never paste credentials into tickets/chat or command lines. Use a secret manager or read-only deploy credentials configured outside repository text.

### Developer/pre-commit guidance

Before pushing security-sensitive changes, run a current-tree scan and, from a full clone, the history script. Do not add broad allowlists. Synthetic test values should be obviously fake and narrowly ignored only when a scanner proves they trigger a false positive.
