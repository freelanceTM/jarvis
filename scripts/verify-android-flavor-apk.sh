#!/usr/bin/env bash
set -euo pipefail

FLAVOR=${1:?usage: verify-android-flavor-apk.sh dev|staging|prod APK_PATH}
APK=${2:?usage: verify-android-flavor-apk.sh dev|staging|prod APK_PATH}
[[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 2; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -q "$APK" 'classes*.dex' -d "$TMP"
strings "$TMP"/classes*.dex >"$TMP/strings.txt"

case "$FLAVOR" in
  dev)
    grep -Fq 'http://10.0.2.2:8080' "$TMP/strings.txt"
    ! grep -Fq 'https://staging-api.jarvis.ai' "$TMP/strings.txt"
    ;;
  staging)
    grep -Fq 'https://staging-api.jarvis.ai' "$TMP/strings.txt"
    ! grep -Fq 'http://10.0.2.2:8080' "$TMP/strings.txt"
    ! grep -Fq 'https://api.jarvis.ai' "$TMP/strings.txt"
    ;;
  prod)
    grep -Fq 'https://api.jarvis.ai' "$TMP/strings.txt"
    ! grep -Fq 'http://10.0.2.2:8080' "$TMP/strings.txt"
    ! grep -Fq 'https://staging-api.jarvis.ai' "$TMP/strings.txt"
    ;;
  *)
    echo "unknown flavor: $FLAVOR" >&2
    exit 2
    ;;
esac

echo "$FLAVOR APK endpoint validation passed: $APK"
