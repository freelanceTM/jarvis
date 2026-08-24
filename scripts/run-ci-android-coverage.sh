#!/usr/bin/env bash
set -euo pipefail

LOG_DIR=${RUNNER_TEMP:-${TMPDIR:-/tmp}}
LOG_FILE="$LOG_DIR/instrumentation-gradle.log"
mkdir -p "$LOG_DIR"

set +e
bash ./gradlew :app:createDevDebugCoverageReport \
  --stacktrace --no-daemon --no-parallel --max-workers=2 \
  2>&1 | tee "$LOG_FILE"
status=${PIPESTATUS[0]}
set -e

if [[ $status -eq 0 ]]; then
  exit 0
fi

summary=$(grep -E \
  'FAILURE:|What went wrong|Execution failed|FAILED|failing tests|error:' \
  "$LOG_FILE" | tail -n 20 || true)
if [[ -z "$summary" ]]; then
  summary=$(tail -n 20 "$LOG_FILE")
fi
summary=${summary//'%'/'%25'}
summary=${summary//$'\r'/'%0D'}
summary=${summary//$'\n'/'%0A'}
printf '::error title=Android instrumentation failed::%s\n' "$summary"
exit "$status"
