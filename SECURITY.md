# Security Policy

## Supported versions

The project is currently pre-release. No production version support window has
been approved yet.

| Version | Supported |
|---|---|
| Unreleased / current development branch | Security reports accepted |
| Earlier snapshots or private builds | Best effort; owner decision required |

Before a public release, the owner must replace this table with an explicit
support and end-of-life policy.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability before maintainers have
had a reasonable opportunity to investigate and remediate it.

Preferred private reporting channel:

- **GitHub Private Vulnerability Reporting** (enabled for this repository):
  open the repository → "Security" tab → "Report a vulnerability". This creates
  a private advisory thread visible only to the security contact and you.

Fallback contact:

- Open a private GitHub discussion/message with the repository owner
  (GitHub: **freelanceTM**) requesting a private channel — without including
  exploit details in the message or any public issue.

Please do not include exploit details in public issues or pull requests.

## What to include

Provide only the information necessary to reproduce and assess the issue:

1. affected commit, version, Android version, device/server environment;
2. affected component and security boundary;
3. clear reproduction steps or a minimal proof of concept;
4. expected and actual behavior;
5. impact and realistic attack preconditions;
6. whether credentials, personal data, billing, license state, prompts, Android
   permissions, or provider routing are involved;
7. suggested mitigation, if known;
8. whether the issue is already public or actively exploited.

Remove real access tokens, API keys, license codes, phone numbers, prompts,
recognized speech, payment data, and personal information from the report. Use
revocable test credentials and synthetic data.

## Please do not

- publish exploit details, screenshots containing secrets, or proof-of-concept
  code before coordinated disclosure;
- test against accounts, devices, phone numbers, providers, or infrastructure
  you do not own or have explicit authorization to test;
- send real SMS messages or place real calls as part of a report;
- perform denial-of-service, destructive migration, data deletion, social
  engineering, or persistence on production systems;
- paste credentials into issues, chat, source files, Gradle properties, tracked
  `.env` files, logs, reports, or build artifacts;
- automatically rewrite Git history when a historical secret is found.

Treat any real credential exposed in source, history, logs, or chat as
compromised: revoke or rotate it first, preserve evidence, then investigate.

## Expected handling process

1. **Receipt** — maintainers privately acknowledge the report.
2. **Triage** — scope, reproducibility, affected versions, severity, and possible
   active exploitation are assessed.
3. **Containment** — exposed credentials are revoked and unsafe paths may be
   disabled or fail closed.
4. **Remediation** — a minimal fix and regression test are prepared without
   weakening privacy, authorization, rate limits, or Android permission gates.
5. **Validation** — relevant unit, integration, Android device, provider,
   migration, release, and security checks are run as applicable.
6. **Release** — a fixed version or deployment is published with an accurate
   changelog and upgrade guidance.
7. **Disclosure** — public details and credit are coordinated with the reporter
   after users have a reasonable opportunity to update.

Best-effort response targets for the current pre-release phase (no contractual
SLA): acknowledgement within **5 business days**, triage verdict within
**10 business days**, and a fix or mitigation for critical issues targeted
within **30 days**. These are good-faith targets for a personal project; if a
target is missed, the reporter will receive a status update instead of silence.

## Security-sensitive project areas

Reports are especially relevant for:

- privacy classification and cloud-routing bypass;
- Android bearer-token storage and exact-origin attachment;
- license activation, entitlement, rate limiting, billing, and webhooks;
- provider credentials and response normalization;
- Room migration/archive data loss;
- AccessibilityService, microphone foreground service, SMS/call, media
  projection, and exact-alarm permission abuse;
- confirmation-token replay or argument substitution;
- prompt/recognized-speech/secret leakage in logs, analytics, or artifacts;
- multi-instance shared-state consistency and fail-open behavior;
- dependency, CI workflow, container, and release-signing supply chain.

## Good-faith research policy

The owner considers good-faith security research that follows this policy to be
authorized conduct for this repository: it will not be met with legal action
from the owner, provided you respect the "Please do not" rules above, use only
accounts/devices you own, and stop and report as soon as a vulnerability is
confirmed. This statement is a good-faith commitment of the repository owner
and is not legal advice or a license of any kind.
