#!/usr/bin/env bash
# §17–18 ТЗ: RAM/CPU/threads/battery снапшоты. Вызывать: до сессии, после idle 5м,
# после listening 5м, после active 15м, после long 30м+. → resources-<label>.txt
set -euo pipefail
LABEL="${1:?usage: 04-resources-battery.sh <label: start|idle|listening|active|long>}"
PKG=com.jarvis.assistant.dev
exec > >(tee "resources-$LABEL.txt") 2>&1
echo "=== SNAPSHOT: $LABEL @ $(date -Is) ==="
echo "--- battery ---"
adb shell dumpsys battery | grep -E "level|charge counter|status" | tr -d '\r'
echo "--- RAM (app) ---"
adb shell dumpsys meminfo "$PKG" | grep -E "TOTAL PSS|Native Heap|Java Heap|Views|Activities" | tr -d '\r'
echo "--- device RAM ---"
adb shell dumpsys meminfo | grep -E "Total RAM|Free RAM|Used RAM" | tr -d '\r'
echo "--- threads/CPU (app) ---"
PID=$(adb shell pidof "$PKG" | tr -d '\r' || true)
if [ -n "$PID" ]; then
  adb shell cat "/proc/$PID/status" | grep -E "Threads|VmRSS" | tr -d '\r'
  adb shell top -n 1 -p "$PID" | tail -2 | tr -d '\r'
else
  echo "процесс не запущен"
fi
echo "--- wakelocks приложения ---"
adb shell dumpsys power | grep -i jarvis | head -5 | tr -d '\r' || echo "wakelocks не найдены"
echo "--- foreground service ---"
adb shell dumpsys activity services "$PKG" | grep -E "ServiceRecord|foreground" | head -5 | tr -d '\r' || true
