#!/usr/bin/env bash
# §1 ТЗ: фиксация характеристик устройства. Вывод → device-env.txt
set -euo pipefail
exec > >(tee device-env.txt) 2>&1
adb wait-for-device
g() { adb shell getprop "$1" 2>/dev/null | tr -d '\r'; }
echo "=== DEVICE ==="
echo "model:            $(g ro.product.model)"
echo "manufacturer:     $(g ro.product.manufacturer)"
echo "android_version:  $(g ro.build.version.release)"
echo "api_level:        $(g ro.build.version.sdk)"
echo "soc:              $(g ro.soc.model) ($(g ro.board.platform))"
echo "abi:              $(g ro.product.cpu.abi) (list: $(g ro.product.cpu.abilist))"
echo "ram_kb:           $(adb shell cat /proc/meminfo | awk '/MemTotal/{print $2}')"
echo "bluetooth_ver:    $(g ro.bluetooth.version)  # может быть пусто — см. настройки"
echo "battery:          $(adb shell dumpsys battery | grep -E 'level|status' | tr -d '\r' | tr '\n' ' ')"
echo "screen:           $(adb shell wm size | tr -d '\r') / density $(adb shell wm density | tr -d '\r')"
echo "locale:           $(g persist.sys.locale) / $(g ro.product.locale)"
echo "build_date:       $(g ro.build.date)"
echo "=== BUILD UNDER TEST ==="
echo "git_commit:       $(git rev-parse HEAD 2>/dev/null || echo n/a)"
echo "branch:           $(git branch --show-current 2>/dev/null || echo n/a)"
echo "=== INSTALLED OMNIX ==="
PKG=com.jarvis.assistant.dev
adb shell pm list packages | grep -q "$PKG" && {
  echo "versionName:      $(adb shell dumpsys package $PKG | grep -m1 versionName | tr -d '\r')"
  echo "versionCode:      $(adb shell dumpsys package $PKG | grep -m1 versionCode | tr -d '\r')"
  echo "signing:          $(adb shell dumpsys package $PKG | grep -m1 -A1 'signatures\|apkSigningVersion' | tr -d '\r')"
} || echo "OMNIX не установлен"
echo "=== CONNECTIVITY ==="
echo "wifi:             $(adb shell dumpsys wifi | grep -m1 'current SSID' | tr -d '\r')"
echo "server_latency:   ЗАПОЛНИТЬ ВРУЧНУЮ: ping/curl до развёрнутого сервера (или NOT DEPLOYED)"
