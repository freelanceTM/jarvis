# Phase 2 — Mandatory Execution Plan

**Plan date:** 2026-08-23  
**Workspace:** `/home/user/jarvis`  
**Git state:** `.git` metadata is absent; `git status`, current branch and `git diff` are unavailable. Existing Phase 0/1/privacy changes are treated as user work and must be preserved.

## Repository findings affecting estimates

- No Android scheduler currently calls `PersonalAutomationEngine.onTimeSchedule()`.
- Automation rules and cooldown timestamps are persisted in Room, but scheduling registration is not.
- Manifest has one system-event receiver and no boot/time/timezone receiver.
- Only Room schema `5.json` exists. No v1–v4 schema/export/history exists in the workspace or Git metadata, so authentic migrations cannot be reconstructed safely.
- No adb, emulator installation or AVD is currently available in the restored environment.
- Only two instrumentation classes exist; they test honest failure without permissions, not the complete required device matrix.
- The 529 MB MediaPipe model is not present and no repository-controlled download source/checksum is configured.
- No Groq/Gemini/OpenRouter staging credentials are available through environment/secret store.
- AGENT-010 is reproducible from saved benchmark evidence: laptop comparison expected `AGENT`, actually routed to successful `CLOUD_AI`.
- The JARVIS API origin is a compile-time hardcoded constant and there are no product flavors.
- Release minification and resource shrinking are disabled; current keep rules are broad.
- Previous release dex OOM was caused by the 2 GB/no-swap sandbox and large MediaPipe/Android dependency graph; a release build passed only after adding temporary swap and increasing Gradle heap.

## Detailed estimate

| Task | Analysis | Implementation | Automated testing | Debugging allowance | External/manual verification | Total |
|---|---:|---:|---:|---:|---:|---:|
| 7 — real scheduler | 1–2 h | 5–8 h | 3–5 h | 2–4 h | 2–4 h | **13–23 h** |
| 8 — Room 1→5 strategy | 2–4 h | 2–4 h | 2–4 h | 1–2 h | 1 h | **8–15 h** |
| 9 — Android device flows | 1–2 h | 4–7 h | 4–7 h | 4–8 h | 8–16 h | **21–40 h** |
| 10 — real MediaPipe model | 1–2 h | 2–4 h | 3–6 h | 3–8 h | 2–4 h | **11–24 h** |
| 11 — live staging providers | 1 h | 2–3 h | 1–2 h | 1–2 h | 1–2 h | **6–10 h** |
| 12 — AGENT-010 | 1 h | 1–2 h | 1 h | 1 h | 0–1 h | **4–6 h** |
| 13 — dev/staging/prod flavors | 1–2 h | 3–5 h | 2–4 h | 1–3 h | 0–1 h | **7–15 h** |
| 14 — R8/release/OOM verification | 1–2 h | 3–6 h | 4–8 h | 4–10 h | 3–6 h | **15–32 h** |

```text
Task 7  — 13–23 h
Task 8  —  8–15 h
Task 9  — 21–40 h
Task 10 — 11–24 h
Task 11 —  6–10 h
Task 12 —  4–6 h
Task 13 —  7–15 h
Task 14 — 15–32 h
-------------------
Total engineering effort — 85–165 h
Optimistic elapsed time with safe parallelization — 45–65 h
Realistic elapsed time — 80–120 h
```

These estimates are intentionally not reduced to fit a single chat session. Physical-device, model and live-provider evidence cannot be manufactured with mocks.

## Dependencies

1. Task 8 decision must precede scheduler/release database claims.
2. Task 7 production code can proceed in parallel with Task 13, but its real trigger proof requires Task 9 device infrastructure.
3. Task 13 should precede Task 11 and final Task 14 so staging/prod endpoints are unambiguous.
4. Task 12 is independent and can run in parallel with Tasks 7/8/13.
5. Task 10 and most of Task 9 require a booted device/emulator; model testing also requires a licensed model file and sufficient RAM/storage.
6. Task 14 follows production changes from Tasks 7, 8, 12 and 13, then requires release install/device smoke.

## Parallel work

- Static implementation: Tasks 7, 8, 12 and 13 can be developed in parallel conceptually, but repository edits will be serialized.
- Staging-provider harness (Task 11) can be prepared while Android builds run.
- R8 rule analysis can start before device availability; runtime R8 proof cannot.
- Device test classes can be written before an emulator exists, but do not count as executed evidence.

## Physical device/emulator requirements

Required for:

- Task 7 AlarmManager/BroadcastReceiver trigger integration;
- Task 8 `MigrationTestHelper` instrumentation;
- all Task 9 scenarios;
- Task 10 real MediaPipe inference and native-memory behavior;
- Task 14 release installation, launch and runtime smoke.

A generic emulator cannot prove Bluetooth SCO, telephony carrier behavior, OEM battery policies or realistic MediaPipe performance. Those require suitable physical hardware.

## Staging credentials/external assets

- Task 11: separate authorized staging credentials for Groq, Gemini and OpenRouter.
- Task 10: exact compatible Gemma `.task` model, source/license, SHA-256 and approximately 529 MB download/storage.
- Task 14: release signing keystore through protected secrets if a signed installable production artifact is required. No key belongs in the repository.

## Initial implementation order

1. Implement Task 8's explicit unsupported-upgrade strategy because historical schemas cannot be proven.
2. Implement Task 7 AlarmManager scheduler, boot/time/timezone receiver, reconciliation and tests.
3. Reproduce/decide AGENT-010.
4. Introduce endpoint flavors and validation.
5. Add safe manual live-provider and model/device harnesses.
6. Enable/tune R8, investigate dependency/dex footprint, build variants.
7. Attempt emulator/device setup and run every feasible instrumentation suite.
8. Run full audit and update estimate-vs-actual report.
