# Android build environments

The Android app has one flavor dimension, `environment`:

| Flavor | Application ID | Default backend | Cleartext |
|---|---|---|---|
| `dev` | `com.jarvis.assistant.dev` | `http://10.0.2.2:8080` | only localhost/127.0.0.1/10.0.2.2 |
| `staging` | `com.jarvis.assistant.staging` | `https://staging-api.jarvis.ai` | denied |
| `prod` | `com.jarvis.assistant` | `https://api.jarvis.ai` | denied |

AI and license origins are separate BuildConfig fields even though each current environment uses the same server origin. Source code no longer owns a production host constant.

## Build commands

```bash
bash ./gradlew :app:assembleDevDebug
bash ./gradlew :app:assembleStagingDebug
bash ./gradlew :app:assembleProdRelease
```

Development and staging can be overridden at configuration time without editing source. Development HTTP overrides are accepted only for `localhost`, `127.0.0.1`, or the emulator host `10.0.2.2`; every other override must use HTTPS:

```bash
bash ./gradlew :app:assembleDevDebug \
  -PJARVIS_DEV_API_BASE_URL=http://10.0.2.2:8080
bash ./gradlew :app:assembleStagingDebug \
  -PJARVIS_STAGING_API_BASE_URL=https://staging.example.internal
```

Production is intentionally fixed to the canonical HTTPS origin and cannot be overridden by an environment variable or Gradle property.

## Artifact validation

After building, verify compiled DEX origins:

```bash
bash scripts/verify-android-flavor-apk.sh dev \
  app/build/outputs/apk/dev/debug/app-dev-debug.apk
bash scripts/verify-android-flavor-apk.sh staging \
  app/build/outputs/apk/staging/debug/app-staging-debug.apk
bash scripts/verify-android-flavor-apk.sh prod \
  app/build/outputs/apk/prod/release/app-prod-release-unsigned.apk
```

The production check fails if local or staging JARVIS origins occur in compiled DEX.

## Third-party endpoints

DuckDuckGo, Wikipedia, Open-Meteo and Google Maps URLs are direct third-party tool endpoints, not JARVIS deployment environments. They remain explicit per-tool origins and are protected by the external-disclosure privacy guard. No WebSocket, OAuth callback or telemetry endpoint exists in the current Android code.
