# Phase 3 Android emulator execution attempt

Date: 2026-08-24  
Workspace: `/home/user/jarvis`

## Purpose

Attempt to close the remaining Android instrumentation evidence gap by executing
the production `LicenseManagerImpl`, DataStore, Compose, Room, scheduler, and
other ordinary `androidTest` cases on a local API 34 emulator.

## Environment

- Host architecture: x86_64.
- Host memory: approximately 2 GiB RAM.
- Hardware acceleration: unavailable; `/dev/kvm` was absent.
- Temporary swap added for the attempt: 4 GiB.
- JDK: checksum-verified Temurin 17.0.20.1.
- Android command-line tools: 15859902, archive SHA-256
  `4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583`.
- Android emulator: 37.1.11.
- Images attempted:
  - `system-images;android-34;google_apis;x86_64` revision 14;
  - `system-images;android-34;default;x86_64` revision 4.
- Emulator mode: software TCG (`-accel off`) with SwiftShader/headless graphics.

## What succeeded

1. Android SDK, emulator, platform-tools, API 34 platform, and both system images
   installed.
2. API 34 AVDs were created and appeared through adb as x86_64 API 34 devices.
3. The dev app and instrumentation APKs were rebuilt from a clean generated
   state:

   ```text
   :app:assembleDevDebug :app:assembleDevDebugAndroidTest — BUILD SUCCESSFUL
   app-dev-debug.apk — 158,300,491 bytes
   app-dev-debug-androidTest.apk — 2,439,174 bytes
   ```

4. A fresh Google APIs boot eventually emitted:

   ```text
   Boot completed in 1270515 ms
   ```

   This is approximately 21 minutes and confirms that the image could reach a
   nominal boot-complete event under software emulation.

## Why this is not test evidence

The guest was not stable enough to install and execute the suite.

- The package service became temporarily available, but installation of the
  158 MB dev APK failed before completion:

  ```text
  adb: failed to install app-dev-debug.apk:
  cmd: Failure calling service package: Broken pipe (32)
  ```

- The Android system process then terminated. Crash evidence included:

  ```text
  *** FATAL EXCEPTION IN SYSTEM PROCESS: main
  java.lang.IllegalStateException: Lost network stack
  ```

- Restarts repeatedly lost required APEX-provided native libraries, for example:

  ```text
  CANNOT LINK EXECUTABLE "/system/bin/app_process64":
  library "libnativeloader.so" not found
  ```

- The AOSP image also failed to provide a stable package/system-server lifecycle
  under unaccelerated TCG.
- No instrumentation test case started and no Android `.ec` coverage data was
  generated.

The failures occurred below the application boundary during emulator/system
startup. They are not JARVIS test failures and must not be reported as either
passing or failing production Android behavior.

## Result

Local instrumentation remains **not executed**. The stronger blocker is now
confirmed: this sandbox lacks KVM, and software-emulated API 34 is too slow and
unstable for valid Android test evidence.

The emulator processes were stopped after evidence collection.

## Repository follow-up

A dedicated `android-instrumentation` job was added to
`.github/workflows/build.yml`. It:

1. requires `/dev/kvm` and fails closed if hardware virtualization is absent;
2. provisions a Google APIs API 34 x86_64 AVD;
3. waits for a real `sys.boot_completed=1` result;
4. runs `:app:createDevDebugCoverageReport`;
5. publishes instrumentation results and Android coverage while preserving
   emulator diagnostics in the workflow log.

The workflow passes YAML parsing and actionlint 1.7.12 locally. Hosted execution
must be assessed from the corresponding GitHub Actions run rather than inferred
from this local software-emulator attempt.

A later hardware-accelerated GitHub run closed the ordinary emulator gap:
`android-instrumentation` completed successfully on API 34 in run `32783667894`
and published Android test/coverage evidence. This does not change the local TCG
result above and does not replace physical voice, Bluetooth, telephony,
Accessibility, or separately installed real-model validation.
