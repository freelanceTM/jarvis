#!/usr/bin/env bash
set -euo pipefail

required=(
  GROQ_STAGING_API_KEY GROQ_STAGING_MODEL
  GEMINI_STAGING_API_KEY GEMINI_STAGING_MODEL
  OPENROUTER_STAGING_API_KEY OPENROUTER_STAGING_MODEL
)
for name in "${required[@]}"; do
  [[ -n "${!name:-}" ]] || { echo "missing protected environment variable: $name" >&2; exit 2; }
done
[[ "${RUN_LIVE_PROVIDER_SMOKE:-}" == "true" ]] || {
  echo "set RUN_LIVE_PROVIDER_SMOKE=true to confirm authorized low-volume staging use" >&2
  exit 2
}

# Never enable Gradle debug/info HTTP logging for this task. Raw responses are
# neither printed nor persisted by the smoke test.
bash ./gradlew :server:liveProviderSmokeTest --no-daemon --console=plain
