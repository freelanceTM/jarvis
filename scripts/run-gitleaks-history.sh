#!/usr/bin/env bash
set -euo pipefail

REPOSITORY=${1:-.}
REPORT=${2:-artifacts/gitleaks-history.json}
VERSION=8.30.1
ARCHIVE="gitleaks_${VERSION}_linux_x64.tar.gz"
EXPECTED_SHA256=551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb
DOWNLOAD_URL="https://github.com/gitleaks/gitleaks/releases/download/v${VERSION}/${ARCHIVE}"

if [[ ! -d "$REPOSITORY/.git" ]]; then
  echo "full-history scan requires a Git repository at $REPOSITORY" >&2
  exit 2
fi
if [[ "$(git -C "$REPOSITORY" rev-parse --is-shallow-repository)" != "false" ]]; then
  echo "refusing incomplete secret scan: repository is shallow" >&2
  exit 2
fi

mkdir -p "$(dirname "$REPORT")"
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT
curl --fail --silent --show-error --location "$DOWNLOAD_URL" -o "$TMP_DIR/$ARCHIVE"
echo "$EXPECTED_SHA256  $TMP_DIR/$ARCHIVE" | sha256sum --check --status
tar -xzf "$TMP_DIR/$ARCHIVE" -C "$TMP_DIR" gitleaks

"$TMP_DIR/gitleaks" git \
  --log-opts='--all' \
  --redact=100 \
  --report-format=json \
  --report-path="$REPORT" \
  "$REPOSITORY"
