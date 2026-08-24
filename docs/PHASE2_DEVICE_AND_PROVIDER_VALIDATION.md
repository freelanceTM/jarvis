# Phase 2 device and provider validation

These jobs are intentionally separate from ordinary JVM/PR tests. A skipped fake test is not release evidence.

## Real MediaPipe model

Prerequisites:

- a physical Android target visible to `adb` with enough RAM/storage;
- an installed `devDebug` app/test APK;
- the authorized, compatible Gemma `.task` file (expected size about 529 MB);
- its independently reviewed SHA-256.

Run:

```bash
MODEL_FILE=/secure/path/model.task \
MODEL_SHA256=<reviewed-sha256> \
ADB_SERIAL=<device-serial> \
bash ./scripts/run-real-model-device-test.sh
```

The script verifies the local file before copying it, invokes only `RealMediaPipeInferenceInstrumentedTest`, and removes the device copy on both success and failure. The test covers cancelled initialization, real initialization, inference, repeat/concurrent calls, active-generation cancellation, recovery, unload and reload. It records basic PSS availability, but native-memory soak/profiling still requires Android Studio/Perfetto and repeated process-restart runs on the release hardware matrix.

Do not commit, upload as a Gradle artifact, or print the model path/content. Confirm redistribution rights separately.

## Live provider staging smoke

Use three authorized staging/test accounts and low-quota credentials supplied only through the process environment or CI secret store. The test is excluded from ordinary `:server:test` runs.

Required variables:

- `RUN_LIVE_PROVIDER_SMOKE=true`;
- `GROQ_STAGING_API_KEY`, `GROQ_STAGING_MODEL`;
- `GEMINI_STAGING_API_KEY`, `GEMINI_STAGING_MODEL`;
- `OPENROUTER_STAGING_API_KEY`, `OPENROUTER_STAGING_MODEL`.

Optional HTTPS endpoint overrides are `GROQ_STAGING_BASE_URL`, `GEMINI_STAGING_BASE_URL`, and `OPENROUTER_STAGING_BASE_URL`.

```bash
bash ./scripts/run-provider-staging-smoke.sh
```

The task sends one synthetic, non-sensitive request per provider, does not print raw responses, and verifies normal provider parsing. Cancellation is tested independently against a real local HTTP socket by `OkHttpTransportCancellationTest`; the transport now cancels the underlying OkHttp call when its coroutine is cancelled. Provider outage/rate-limit/auth-failure drills need dedicated authorized staging controls and must not intentionally damage production accounts.

## Voice/platform matrix

`connectedDevDebugAndroidTest` can validate app-owned permission gates, service stop cleanup, receiver registration, STT/TTS callbacks and alarm delivery. It cannot by itself prove:

- Bluetooth SCO/communication-device routing without a compatible physical headset;
- carrier call/SMS behavior without a controlled SIM/test number;
- OEM battery restrictions without each vendor device;
- production signing/install/upgrade behavior using a debug-signed APK.

The gated physical checks can be invoked after installing `devDebug` and granting only the required permissions:

```bash
# Operator connects a compatible headset and speaks the safe phrase when prompted.
bash ./gradlew :app:connectedDevDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.jarvis.assistant.voice.PhysicalVoicePlatformInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.requireBluetoothHeadset=true \
  -Pandroid.testInstrumentationRunnerArguments.requireVoicePlatform=true \
  -Pandroid.testInstrumentationRunnerArguments.expectedSpeech='jarvis test'
```

These checks use the real communication device, TTS engine and platform recognizer; they skip unless the explicit gates are set. Carrier call/SMS tests remain manual and must use a controlled test SIM/number—automated instrumentation must never place a real call or send an SMS. The negative call/SMS instrumentation now skips if dangerous permissions are already granted.

Record target model, OS/build, permissions, headset/carrier, battery mode, app variant and logcat window for each manual run. Never place phone numbers, recognized speech, prompts, provider responses, credentials or tokens in reports/logs.
