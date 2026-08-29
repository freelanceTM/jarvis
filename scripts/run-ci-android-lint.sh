#!/usr/bin/env bash
set -euo pipefail

LOG_DIR=${RUNNER_TEMP:-${TMPDIR:-/tmp}}
LOG_FILE="$LOG_DIR/lint-gradle.log"
mkdir -p "$LOG_DIR"

set +e
bash ./gradlew :app:lintDevDebug \
  --stacktrace --no-daemon --no-parallel --max-workers=2 \
  2>&1 | tee "$LOG_FILE"
status=${PIPESTATUS[0]}
set -e

if [[ $status -eq 0 ]]; then
  exit 0
fi

# Консоль Gradle при провале lint печатает только «First failure».
# Полный список ошибок лежит в текстовом отчёте — выводим его в лог,
# чтобы провал можно было починить за один виток CI.
report="app/build/reports/lint-results-devDebug.txt"
if [[ -f "$report" ]]; then
  echo "--- full lint report ($report) ---"
  cat "$report"
  echo "--- end of lint report ---"
fi

summary=$(grep -E \
  'Lint found|First failure:|Error:|FAILURE:|What went wrong|Execution failed' \
  "$LOG_FILE" | tail -n 30 || true)
if [[ -z "$summary" ]]; then
  summary=$(tail -n 20 "$LOG_FILE")
fi
summary=${summary//'%'/'%25'}
summary=${summary//$'\r'/'%0D'}
summary=${summary//$'\n'/'%0A'}
printf '::error title=Android lint failed::%s\n' "$summary"
exit "$status"
