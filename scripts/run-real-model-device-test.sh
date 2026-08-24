#!/usr/bin/env bash
set -euo pipefail

: "${MODEL_FILE:?Set MODEL_FILE to the licensed Gemma .task path}"
: "${MODEL_SHA256:?Set MODEL_SHA256 to the reviewed model SHA-256}"
PACKAGE=${ANDROID_TEST_PACKAGE:-com.jarvis.assistant.dev}
VARIANT=${ANDROID_TEST_VARIANT:-DevDebug}
ADB=(adb)
if [[ -n "${ADB_SERIAL:-}" ]]; then ADB+=( -s "$ADB_SERIAL" ); fi

[[ -f "$MODEL_FILE" ]] || { echo "model file not found" >&2; exit 2; }
echo "$MODEL_SHA256  $MODEL_FILE" | sha256sum --check --status
SIZE=$(stat -c %s "$MODEL_FILE")
(( SIZE > 500 * 1024 * 1024 )) || { echo "model file is too small" >&2; exit 2; }

"${ADB[@]}" get-state >/dev/null
REMOTE_TMP=/data/local/tmp/gemma3-1b-it-int4.task
cleanup() {
  "${ADB[@]}" shell rm -f "$REMOTE_TMP" >/dev/null 2>&1 || true
  "${ADB[@]}" shell run-as "$PACKAGE" rm -f files/llm/gemma3-1b-it-int4.task >/dev/null 2>&1 || true
}
trap cleanup EXIT

"${ADB[@]}" push "$MODEL_FILE" "$REMOTE_TMP" >/dev/null
"${ADB[@]}" shell run-as "$PACKAGE" mkdir -p files/llm
"${ADB[@]}" shell "cat '$REMOTE_TMP' | run-as '$PACKAGE' sh -c 'cat > files/llm/gemma3-1b-it-int4.task'"
"${ADB[@]}" shell rm -f "$REMOTE_TMP"

TASK=":app:connected${VARIANT}AndroidTest"
for pass in initial post-force-stop; do
  echo "running real-model instrumentation pass: $pass"
  bash ./gradlew "$TASK" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.jarvis.assistant.agent.localai.RealMediaPipeInferenceInstrumentedTest \
    -Pandroid.testInstrumentationRunnerArguments.requireRealModel=true \
    -Pandroid.testInstrumentationRunnerArguments.modelExpectedSha256="$MODEL_SHA256" \
    --no-daemon
  if [[ "$pass" == "initial" ]]; then
    "${ADB[@]}" shell am force-stop "$PACKAGE"
  fi
done

cleanup
trap - EXIT
echo "real MediaPipe device test passed and model copy removed"
