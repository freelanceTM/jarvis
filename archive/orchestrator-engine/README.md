# OrchestratorEngine — archived prototype (H-01)

These files were drafted during Phase 7 (AR-07) as a pure-Kotlin state machine
for `VoiceInteractionOrchestrator`, intended to make transition rules testable
on the JVM without Robolectric.

## Why archived (H-01, Этап 4)

After C-02 (privacy-consent) and H-02 (classifier deduplication) landed, the
production orchestrator drifted from the prototype faster than the prototype
could be wired up:

| Aspect | `OrchestratorMode` (production) | `OrchMode` (engine) |
|---|---|---|
| Privacy consent | `AWAITING_PRIVACY_CONSENT` mode | missing entirely |
| Wake-word | `STANDBY_WAKE_WORD` | `STANDBY` |
| Listening | `LISTENING_USER_QUERY` | `LISTENING` |
| Interpreter | `LIVE_EAR_INTERPRETER` | `LIVE_INTERPRETER` |
| Silence timeout | 1200 ms | 3500 ms |
| Follow-up window | 8000 ms | 6000 ms |
| Events | session-epoch, translation "last wins", `PendingCloudConsent`, privacy-classification hints | none of these |

Option A (adapter) was estimated at 2–3 days and would have introduced its own
drift surface — the engine tests validate engine transitions, not adapter
correctness, so the promised regression-safety would have moved rather than
disappeared. With the rest of the phase-4 crash fixes on the critical path,
spending days wiring a 0-coverage prototype into the live flow was judged
higher-risk than deleting it.

## When to revive

If the orchestrator ever grows another 3–4 modes or a second event source
(e.g. Auto / Wear), pull this back out, regenerate `OrchMode` as a typealias
of `OrchestratorMode`, add the missing C-02 consent mode, and write an
adapter that replaces the ad-hoc `_currentMode.value = …` writes. Until
then, leaving dead state-machine code in `app/src/main/` actively misleads
readers into thinking there are two sources of truth for the mode graph.

## Files

- `OrchestratorEngine.kt` — pure-JVM state machine (no Android deps).
- `OrchestratorEngineTest.kt` — JVM transition tests that were passing at
  archive time.

The archive directory is outside Gradle's source sets, so it won't compile
into the APK.
