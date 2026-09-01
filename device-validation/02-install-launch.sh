#!/usr/bin/env bash
# §2–3 ТЗ: чистая установка, запуск, startup time, разрешения. → install-launch.txt
set -euo pipefail
exec > >(tee install-launch.txt) 2>&1
APK="${1:?usage: 02-install-launch.sh <path-to-apk>}"
PKG=com.jarvis.assistant.dev
ACTIVITY="$PKG/com.jarvis.assistant.presentation.MainActivity"
adb wait-for-device
echo "=== FRESH INSTALL ==="
adb uninstall "$PKG" >/dev/null 2>&1 || true
T0=$(date +%s%3N)
adb install -r "$APK"
T1=$(date +%s%3N)
echo "install_ms: $((T1-T0))"
echo "apk_size_bytes: $(stat -c%s "$APK")"
echo "=== LAUNCH (cold, TotalTime из am start -W) ==="
adb shell am start -W -n "$ACTIVITY" | grep -E "Status|TotalTime|WaitTime|LaunchState"
echo "=== PERMISSION STATES (§3) ==="
adb shell dumpsys package "$PKG" | grep -E "RECORD_AUDIO|BLUETOOTH_(CONNECT|SCAN)|POST_NOTIFICATIONS|READ_PHONE_STATE|SEND_SMS|READ_CONTACTS|SYSTEM_ALERT_WINDOW|ACCESSIBILITY|FOREGROUND_SERVICE" | tr -d '\r' || true
echo "=== BATTERY OPTIMIZATION ==="
adb shell dumpsys deviceidle whitelist | grep -i jarvis || echo "NOT in whitelist (протестировать поведение при Doze)"
echo "=== CRASH CHECK (30s после первого запуска) ==="
sleep 30
adb logcat -d -b crash | tail -20 || echo "crash-buffer пуст"
echo "=== ANR CHECK ==="
adb shell ls /data/anr/ 2>/dev/null || echo "нет ANR-трейсов (или нет root-доступа к /data/anr)"
