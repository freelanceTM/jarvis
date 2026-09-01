#!/usr/bin/env bash
# §5–8, 22 ТЗ: захват голосового цикла. Запускать ПЕРЕД серией прогонов.
# 20+ повторений каждого сценария выполняются ГОЛОСОМ; этот скрипт пишет
# logcat (epoch-ms) + экран; latency считается по маркерам из README.
set -euo pipefail
OUT="${1:-voice-run-$(date +%s)}"
mkdir -p "$OUT"
adb wait-for-device
adb logcat -c
echo "logcat → $OUT/logcat-epoch.log (Ctrl+C для остановки серии)"
adb logcat -v epoch > "$OUT/logcat-epoch.log" &
LOGPID=$!
echo "screenrecord → $OUT/screen.mp4 (макс 3 мин/файл, перезапускайте на серию)"
adb shell screenrecord --time-limit 180 "/sdcard/$OUT-screen.mp4" &
SRVPID=$!
trap 'kill $LOGPID 2>/dev/null; adb pull "/sdcard/$OUT-screen.mp4" "$OUT/screen.mp4" 2>/dev/null; adb shell rm -f "/sdcard/$OUT-screen.mp4"' EXIT
wait $SRVPID 2>/dev/null || true
echo "Серия завершена. Файлы в $OUT/. Извлечение маркеров:"
echo "  grep -E 'startListening session|route=|route=DEVICE_TOOL tool|TTS' $OUT/logcat-epoch.log"
