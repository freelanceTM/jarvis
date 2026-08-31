#!/usr/bin/env bash
# §13–14, 19 ТЗ: прерывания (20+ итераций вручную), рестарты, сеть off/on.
# Скрипт автоматизирует механику; вердикты по наблюдаемому поведению — вручную.
set -euo pipefail
PKG=com.jarvis.assistant.dev
ACTIVITY="$PKG/com.jarvis.assistant.presentation.MainActivity"
exec > >(tee recovery.txt) 2>&1
echo "=== INTERRUPTION HARNESS ==="
echo "Инструкция: на каждый гудок скрипта произнесите команду и ПРЕРВИТЕ её"
echo "следующей. Верните вердикты: cancel/replace/queue/duplicate-tts/stale — в шаблон."
for i in $(seq 1 20); do
  echo "beep: iteration $i @ $(date -Is)"
  adb shell input keyevent KEYCODE_VOLUME_UP >/dev/null 2>&1 || true
  sleep 8
done
echo "=== APP RESTART (force-stop → cold start) ==="
adb shell am force-stop "$PKG"
sleep 2
adb shell am start -W -n "$ACTIVITY" | grep -E "Status|TotalTime"
echo "=== NETWORK OFF/ON (§19) ==="
adb shell svc wifi disable; echo "wifi OFF @ $(date -Is)"; sleep 10
adb shell svc wifi enable;  echo "wifi ON @ $(date -Is)"
echo "Проверить в логах: таймаут → честная ошибка (не false success) → восстановление."
echo "=== SERVER UNREACHABLE SIMULATION ==="
echo "Вариант A: остановить сервер (docker compose stop server) и повторить cloud-команду."
echo "Вариант B: airplane mode на 30с во время запроса. Фиксировать сообщения пользователю."
