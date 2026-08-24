# Automatic Privacy Classification — Production Blocker Closure

**Audit date:** 2026-08-21  
**Scope:** Android application and Kotlin server  
**Result:** **PASS locally — no known Critical/High privacy-routing bypass remains**

## 1. Executive result

The prompt pipeline now fails closed at multiple independent boundaries. `UNKNOWN` is a real state and is never treated as `NORMAL`. Deterministic classification runs locally on Android and again on the JARVIS server; no user text is sent to an external classifier.

Every implemented path that can send prompt, context, history, tool arguments, implicit device location, or translated text outside the application now has a preceding classification and policy decision. Server retries and provider fallback occur only after the server router has accepted the effective privacy decision.

The ordinary chat/voice UI does not grant restricted-cloud consent. A restricted request can reach an AI provider only when the caller supplies explicit per-request consent and both Android and server classifiers complete successfully. Consent cannot override `UNKNOWN`, classifier failure, malformed input, or invalid metadata. External tools and third-party app hand-offs use a separate conservative rule: restricted arguments remain blocked because there is no dedicated external-disclosure consent UI.

## 2. Effective policy

| Effective state | AI cloud | External tools / app hand-offs |
|---|---|---|
| `NORMAL` | Allowed | Allowed after tool classification |
| `PRIVATE` | Local-only unless explicit per-request AI-cloud consent | Blocked (no dedicated disclosure consent surface) |
| `SENSITIVE` | Local-only unless explicit per-request AI-cloud consent | Blocked (no dedicated disclosure consent surface) |
| `UNKNOWN` | Always blocked | Always blocked |
| Classifier exception, incomplete result, malformed/empty/oversized input | Becomes `UNKNOWN`; always blocked | Becomes `UNKNOWN`; always blocked |
| Invalid privacy category | Rejected/fails closed | Not accepted as `NORMAL` |

Production configuration cannot globally enable `PRIVATE` or `SENSITIVE` cloud routing. Per-request consent is serialized end-to-end as `cloudExplicitlyAllowed`; it is evaluated only after independent content classification.

## 3. Classification inputs and behavior

Android and server classifiers inspect:

- current prompt or tool arguments;
- relevant conversation history;
- configured/client system context;
- implicit external context declared by a tool (for example current device location);
- client privacy metadata only as a non-authoritative hint.

The strongest completed classification wins. Client `NORMAL` cannot weaken automatic `PRIVATE`/`SENSITIVE`, and client `UNKNOWN` can become `NORMAL` only after a complete local/server classification of the actual content.

Detectors cover:

- password/PIN/secret assignments;
- Bearer, JWT, provider/API/access/refresh tokens and common key families;
- private keys and credential-bearing database URLs;
- OTP/SMS codes, payment cards, IBAN and payment security data;
- government identifiers and owned medical information;
- email addresses, international phone numbers and structured recipients;
- precise coordinates and owned/current device location;
- private correspondence/documents and confidential business content.

Normalization includes NFKC Unicode normalization, zero-width removal, whitespace folding, locale-stable case normalization, URL decoding, escaped newline/tab handling, and escaped `=`/`:` handling. Values are never logged.

## 4. Implemented outbound path inventory

### 4.1 Main chat and voice AI path

`ChatViewModel` / `VoiceInteractionOrchestrator`
→ `SendPromptUseCase`
→ `ExecutionRequest`
→ `ExecutionDecisionEngine`
→ `RepositoryCloudAiExecutor`
→ `AIRepositoryImpl`
→ `JarvisApiAiClient`
→ `JarvisApiClient`
→ `/v1/ai/execute`
→ `AiRouter`
→ `ProviderManager`
→ configured AI provider.

Controls:

1. Composer/voice state begins as `UNKNOWN` and displays all four states.
2. Before sending, UI classification includes prompt, recent history, and system prompt.
3. `SendPromptUseCase` independently rebuilds that contextual classification before engine routing.
4. The repository and JARVIS API adapter independently classify prompt/system context again before constructing the HTTP request.
5. Server request metadata defaults to `UNKNOWN`; the router independently classifies request text and cloud-bound system context.
6. Provider selection, retry and fallback execute only after router acceptance.
7. API responses include `Cache-Control: no-store`.

### 4.2 Live translation

`LiveTranslatorEngine`
→ `LlmTranslationProvider`
→ legacy `AIRepository.generateResponse`
→ repository classifier
→ guarded AI client.

The metadata-dropping translation bypass is closed. The legacy overload has no restricted-cloud consent and therefore allows only successfully classified `NORMAL` content. A dedicated flow regression proves sensitive translation text never reaches the AI client.

### 4.3 Web search

`WebSearchTool` performs direct OkHttp calls to search providers. `ExecutionDecisionEngine` gates routed calls, and `ToolExecutor` independently classifies serialized arguments immediately before execution. Direct, planned, background and batch invocation cannot skip the executor guard.

### 4.4 Weather and location

`WeatherTool` can geocode a named place or obtain device coordinates and call Open-Meteo. It is externally disclosing. If no named place is supplied, the tool contributes `my current device location` as implicit privacy context before execution, so GPS-derived data is classified `PRIVATE` and blocked without a dedicated disclosure consent surface.

`LocationNavigationTool` is externally disclosing and is subject to the same engine/executor guards before constructing navigation/map intents.

### 4.5 Third-party app and communication hand-offs

The audit found tools that were technically “offline” but could still pass user content to another app or service. Security no longer equates `isOffline` with “cannot disclose.” The following explicitly declare external disclosure and are guarded:

- accessibility text entry;
- dial/call;
- share intents;
- Telegram hand-off;
- app/market opening with a query;
- alarm/timer hand-off;
- calendar hand-off.

Unknown tool metadata is not assumed safe.

### 4.6 Server provider calls

Repository-wide architecture regression confirms `ProviderManager.execute` has one production caller: `AiRouter`. Provider retries and fallback never re-enter routing and cannot run after a blocked/unknown decision. Provider logs contain request IDs, provider/model, status, latency, attempts and token counts—not prompt text or provider response bodies.

### 4.7 Non-prompt network paths

License activation/validation and billing/provider webhook paths do not carry prompt/history/tool content and are outside the AI classification decision. They retain their independent authentication, transport and secret-handling controls.

## 5. Confirmed bypasses fixed

1. Implicit/default `NORMAL` in compatibility entry points and omitted server metadata.
2. Translation call that discarded privacy metadata.
3. Trust in client privacy labels without server content classification.
4. Missing system-context and history classification.
5. Classifier exception/incomplete/invalid output allowing provider fallback.
6. Local/provider failure escalation into prohibited cloud processing.
7. Direct/planned/background/batch external tool invocation without argument classification.
8. “Offline” tools that handed user content to third-party apps.
9. Weather execution that could derive and disclose GPS location from empty arguments.
10. Plaintext voice query, cognitive-loop step/screen/observation/replan data, accessibility targets, automation names/errors, and local/translation failure content in logs.
11. AI responses without an explicit no-store cache policy.
12. Inconsistent explicit-consent propagation between engine, repository, Android HTTP DTO and server router.

Each confirmed routing/logging bypass has regression coverage or an architecture invariant test.

## 6. Logging, telemetry, cache and crash review

Repository-wide scans found no Firebase Analytics, Crashlytics, Sentry, OpenTelemetry, `printStackTrace`, `System.out`, or body-level HTTP logging in production Kotlin sources.

Android HTTP logging is `BASIC` only in debug builds, `NONE` in release, and sensitive headers are redacted. JARVIS AI request logs contain safe metadata only. Prompt text, tool arguments, history, system context, screen text, detected secret values, and provider bodies are not logged.

Server usage records contain prompt character counts, classification/provider metadata and token/latency totals—not prompt text. AI success and error responses are marked `Cache-Control: no-store`.

Evidence scans:

- `audit/privacy-log-scan.txt`
- `audit/privacy-external-call-scan.txt`

## 7. Regression evidence

Final local results:

| Check | Result |
|---|---:|
| Android JVM unit/integration tests | **439 passed, 0 failed, 0 skipped** |
| Server tests with PostgreSQL | **128 passed, 0 failed, 0 skipped** |
| Android `lintDebug` | **PASS** |
| Android `assembleDebug` | **PASS** |
| Server build/package (`build`, tests run separately) | **PASS** |
| Android/server classifier and routing target suites | **PASS** |
| Static architecture and plaintext-logging regressions | **PASS** |

Coverage includes normal/private/sensitive/unknown, credential families, tokens, passwords, PIN/OTP, private keys, database URLs, payment/government/medical/contact/location/document/business data, empty/malformed/oversized input, classifier exceptions and invalid output, mixed findings, Unicode/case/whitespace/newline/zero-width/URL/escaping/JSON/Markdown/code-block encodings, history/system context, translation, direct/planned/background/batch tools, implicit location, explicit consent, retry/fallback, omitted metadata, provider zero-call assertions, logging regressions and no-store responses.

## 8. Paths not present in this repository

The repository does not currently implement prompt attachments, edit/resend/regenerate actions, a cloud streaming endpoint, or a batch AI-prompt endpoint. They therefore have no production cloud path to bypass today. The local model exposes an optional token callback, but it is on-device and does not invoke a cloud provider. Any future implementation of these features must enter the same contextual classifier and outbound guards; architecture tests protect the currently enumerated cloud call sites.

## 9. Remaining limitations (not known Critical/High bypasses)

- Deterministic detection is intentionally conservative but cannot mathematically identify every possible secret or euphemistic confidential statement. Multiple independent Android/server/tool boundaries limit the effect of a single miss.
- External-tool restricted disclosure has no dedicated consent UX, so those calls fail closed rather than offering an override.
- Android instrumented/device UI tests were not executed because no adb target was available. JVM flow tests, lint and APK assembly passed.
- Public DNS/TLS deployment evidence for `api.jarvis.ai` remains an external deployment dependency documented separately in `audit/TLS_DEPLOYMENT_REPORT.md`; it is not a privacy-classification bypass in the local implementation.

## 10. Closure decision

**The automatic privacy-classification Critical/High production blocker is closed for the implemented repository paths.** No known path can invoke an AI provider or externally disclosing tool with `UNKNOWN`, failed classification, or restricted content lacking the applicable explicit consent/policy decision.
