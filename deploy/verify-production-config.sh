#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
COMPOSE_FILE="$ROOT_DIR/deploy/docker-compose.production.yml"
CADDY_FILE="$ROOT_DIR/deploy/Caddyfile"

if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null; then
  COMPOSE=(docker-compose)
else
  echo "docker compose or docker-compose is required for topology validation" >&2
  exit 2
fi

export PUBLIC_DOMAIN=${PUBLIC_DOMAIN:-api.example.com}
export ACME_EMAIL=${ACME_EMAIL:-security@example.com}
export DATABASE_USER=${DATABASE_USER:-jarvis}
export DATABASE_PASSWORD=${DATABASE_PASSWORD:-configuration-validation-only}
export LICENSE_CODE_PEPPER=${LICENSE_CODE_PEPPER:-$(printf 'p%.0s' {1..64})}
export BILLING_PLANS=${BILLING_PLANS:-'earclip-monthly|jarvis-earclip|Monthly|30|1400|USD|pri_1234567890|USDT'}
export JARVIS_CLIENT_TOKENS=${JARVIS_CLIENT_TOKENS:-"$(printf 'a%.0s' {1..64}):operations"}
export JARVIS_ADMIN_CLIENTS=${JARVIS_ADMIN_CLIENTS:-operations}

CONFIG_JSON=$(mktemp)
trap 'rm -f "$CONFIG_JSON"' EXIT

"${COMPOSE[@]}" -f "$COMPOSE_FILE" config --format json >"$CONFIG_JSON"
python3 - "$CONFIG_JSON" <<'PY'
import json, sys
config = json.load(open(sys.argv[1], encoding="utf-8"))
services = config["services"]
assert set(services) == {"caddy", "jarvis-server", "postgres"}
assert services["jarvis-server"].get("ports") in (None, []), "application port must not be published"
assert services["postgres"].get("ports") in (None, []), "database port must not be published"
ports = services["caddy"].get("ports", [])
published = {int(item["published"]) for item in ports}
assert published == {80, 443}, f"only 80/443 may be public, got {published}"
env = services["jarvis-server"]["environment"]
assert str(env["APP_ENV"]).lower() == "production"
assert str(env["APPLICATION_REPLICA_COUNT"]) == "1", "single-instance decision requires exactly one app replica"
assert str(env["PRODUCTION_TLS_TERMINATED"]).lower() == "true"
assert str(env["TRUST_PROXY_HEADERS"]).lower() == "true"
assert env["PUBLIC_BASE_URL"].startswith("https://")
assert env["TRUSTED_PROXY_CIDRS"] == "172.30.0.2/32"
assert services["caddy"]["networks"]["proxy_backend"]["ipv4_address"] == "172.30.0.2"
print("compose topology: only Caddy publishes 80/443; app/database are private")
PY

grep -Fq 'request>headers delete' "$CADDY_FILE" || {
  echo "Caddy access logs must delete the complete request header map" >&2
  exit 1
}
grep -Fq 'header_up -Forwarded' "$CADDY_FILE" || {
  echo "Caddy must remove the client-supplied Forwarded header" >&2
  exit 1
}

if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
  docker run --rm --network none \
    -e PUBLIC_DOMAIN="$PUBLIC_DOMAIN" \
    -e ACME_EMAIL="$ACME_EMAIL" \
    -v "$CADDY_FILE:/etc/caddy/Caddyfile:ro" \
    caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648 \
    caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
elif command -v caddy >/dev/null; then
  PUBLIC_DOMAIN="$PUBLIC_DOMAIN" ACME_EMAIL="$ACME_EMAIL" \
    caddy validate --config "$CADDY_FILE" --adapter caddyfile
else
  echo "docker daemon or local caddy is required for Caddyfile validation" >&2
  exit 2
fi

echo "production Compose and Caddy configuration are valid"
