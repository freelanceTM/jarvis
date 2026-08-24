#!/usr/bin/env bash
set -euo pipefail

: "${PUBLIC_API_URL:?Set PUBLIC_API_URL=https://canonical-api-host}"
: "${SMOKE_BEARER_TOKEN:?Load an ADMIN Bearer token from the secret manager}"

readarray -t URL_PARTS < <(python3 - "$PUBLIC_API_URL" <<'PY'
import sys
from urllib.parse import urlsplit
u = urlsplit(sys.argv[1])
if u.scheme != "https" or not u.hostname or u.username or u.password or u.query or u.fragment:
    raise SystemExit("PUBLIC_API_URL must be a credential-free HTTPS origin")
if u.path not in ("", "/"):
    raise SystemExit("PUBLIC_API_URL must not contain a path")
port = u.port or 443
host_for_connect = f"[{u.hostname}]" if ":" in u.hostname else u.hostname
print(u.hostname)
print(f"{host_for_connect}:{port}")
print(f"http://{host_for_connect}" + (f":{port}" if port not in (80, 443) else ""))
PY
)
HOST=${URL_PARTS[0]}
CONNECT_AUTHORITY=${URL_PARTS[1]}
HTTP_ORIGIN=${PUBLIC_HTTP_URL:-${URL_PARTS[2]}}
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"; unset SMOKE_BEARER_TOKEN' EXIT
CURL_CA_ARGS=()
OPENSSL_CA_ARGS=()
CURL_CONFIG_CA=""
if [[ -n "${SMOKE_CA_FILE:-}" ]]; then
  CURL_CA_ARGS=(--cacert "$SMOKE_CA_FILE")
  OPENSSL_CA_ARGS=(-CAfile "$SMOKE_CA_FILE")
  CURL_CONFIG_CA=$(printf 'cacert = "%s"' "$SMOKE_CA_FILE")
fi

printf '1/10 DNS: '
getent ahosts "$HOST" >"$TMP_DIR/dns"
head -n1 "$TMP_DIR/dns" | awk '{print $1}'

printf '2/10 TCP/TLS handshake and chain validation\n'
openssl s_client \
  -connect "$CONNECT_AUTHORITY" \
  -servername "$HOST" \
  -verify_return_error \
  "${OPENSSL_CA_ARGS[@]}" \
  -showcerts </dev/null >"$TMP_DIR/tls.txt" 2>"$TMP_DIR/tls.err"
grep -q 'Verify return code: 0 (ok)' "$TMP_DIR/tls.txt"
awk '/BEGIN CERTIFICATE/{on=1} on{print} /END CERTIFICATE/{exit}' \
  "$TMP_DIR/tls.txt" >"$TMP_DIR/leaf.pem"
openssl x509 -in "$TMP_DIR/leaf.pem" -noout -subject -issuer -dates
MIN_CERT_VALIDITY_SECONDS=${SMOKE_MIN_CERT_VALIDITY_SECONDS:-2592000}
openssl x509 -in "$TMP_DIR/leaf.pem" -checkend "$MIN_CERT_VALIDITY_SECONDS" -noout

printf '3/10 TLS 1.2 and TLS 1.3 endpoints\n'
curl --fail --silent --show-error --tlsv1.2 --tls-max 1.2 \
  "${CURL_CA_ARGS[@]}" "$PUBLIC_API_URL/v1/health" >/dev/null
curl --fail --silent --show-error --tlsv1.3 --tls-max 1.3 \
  "${CURL_CA_ARGS[@]}" "$PUBLIC_API_URL/v1/health" >/dev/null

printf '4/10 Legacy TLS 1.1 rejection\n'
set +e
openssl s_client -tls1_1 -connect "$CONNECT_AUTHORITY" -servername "$HOST" \
  </dev/null >"$TMP_DIR/tls11.txt" 2>&1
tls11_exit=$?
set -e
if [[ $tls11_exit -eq 0 ]] && grep -Eq 'Protocol *: TLSv1\.1|Protocol  *TLSv1\.1' "$TMP_DIR/tls11.txt"; then
  echo "server negotiated forbidden TLS 1.1" >&2
  exit 1
fi

printf '5/10 HTTPS health\n'
health_status=$(curl --silent --show-error --output "$TMP_DIR/health.json" \
  "${CURL_CA_ARGS[@]}" --write-out '%{http_code}' "$PUBLIC_API_URL/v1/health")
test "$health_status" = "200"
grep -q '"status":"ok"' "$TMP_DIR/health.json"

printf '6/10 Authenticated HTTPS API\n'
auth_status=$({
  printf 'silent\nshow-error\noutput = "%s"\nwrite-out = "%%{http_code}"\n' "$TMP_DIR/auth.json"
  printf 'url = "%s/v1/admin/metrics"\n' "$PUBLIC_API_URL"
  [[ -z "$CURL_CONFIG_CA" ]] || printf '%s\n' "$CURL_CONFIG_CA"
  printf 'header = "Authorization: Bearer %s"\n' "$SMOKE_BEARER_TOKEN"
} | curl --config -)
test "$auth_status" = "200"

printf '7/10 HTTP redirect-only behavior (no Authorization)\n'
redirect_status=$(curl --silent --show-error --output /dev/null \
  --dump-header "$TMP_DIR/redirect.headers" --write-out '%{http_code}' \
  "$HTTP_ORIGIN/v1/health?access_token=must-not-be-copied")
test "$redirect_status" = "308"
grep -qi '^location: https://' "$TMP_DIR/redirect.headers"
if grep -qi 'access_token' "$TMP_DIR/redirect.headers"; then
  echo "redirect leaked a query parameter" >&2
  exit 1
fi

printf '8/10 Direct application-port denial\n'
if [[ -n "${APP_DIRECT_URL:-}" ]]; then
  set +e
  direct_status=$({
    printf 'silent\nshow-error\noutput = "/dev/null"\nwrite-out = "%%{http_code}"\n'
    printf 'url = "%s/v1/admin/metrics"\n' "$APP_DIRECT_URL"
    printf 'header = "Authorization: Bearer %s"\n' "$SMOKE_BEARER_TOKEN"
  } | curl --connect-timeout 3 --max-time 5 --config - 2>/dev/null)
  direct_exit=$?
  set -e
  if [[ $direct_exit -eq 0 && "$direct_status" == "200" ]]; then
    echo "direct plaintext authenticated API is reachable" >&2
    exit 1
  fi
else
  echo "APP_DIRECT_URL not set; verify firewall/security group manually"
fi

printf '9/10 Token-in-URL repository contract is not used by this smoke test\n'
printf '10/10 Review Caddy/application logs for REDACTED and absence of the real token\n'
echo "production TLS smoke checks passed"
