# TLS termination / trusted proxy security report

Дата: 2026-08-21
Статус: repository implementation verified; **public production deployment verification pending**.

## 1. Discovery

До реализации:

- единственная server entry point — `server/.../Main.kt`;
- JDK `HttpServer` был жёстко привязан к `0.0.0.0:$PORT`;
- Dockerfile, Docker Compose, Kubernetes, Ingress, nginx/Caddy, Terraform и cloud
  deployment manifests отсутствовали;
- существующего TLS terminator/reverse proxy не было, поэтому дублирования
  infrastructure не произошло;
- application напрямую использовал socket peer IP и не доверял forwarded headers;
- Android API URL был HTTPS, но два раза дублировался в source;
- README не содержал deploy topology; `SERVER_AI_LAYER.md` лишь рекомендовал proxy,
  но production startup оставался fail-open;
- health endpoint: `GET /v1/health`;
- Android release запрещал cleartext, а logger redacted Authorization, но server
  deployment сам не обеспечивал encrypted public transport.

Найденные API URL sources после нормализации:

- Android canonical backend: единственный `AppConstants.JARVIS_API_BASE_URL =
  https://api.jarvis.ai`; AI и license clients используют его;
- production callback/canonical origin: `PUBLIC_BASE_URL` environment variable;
- AI/Paddle/HELEKET upstream defaults: HTTPS, а configured production provider с
  credentials теперь не может использовать HTTP;
- development docs/env: `http://127.0.0.1:8080`/localhost only.

## 2. Фактическая repository production topology

```text
Internet
  -> public 80/TCP: Caddy redirect-only (308, no reverse_proxy, query dropped)
  -> public 443/TCP+UDP: Caddy TLS 1.2/1.3 + ACME renewal
  -> private proxy_backend: jarvis-server HTTP 8080
  -> private database network: PostgreSQL 5432
```

TLS termination происходит в Caddy. Application не управляет certificates и
оставляет HTTP только внутренним transport. Compose не публикует application или
database ports. Если cloud platform уже предоставляет LB/Ingress TLS, docs требуют
не запускать второй Caddy, а сохранить тот же private-port/trusted-CIDR contract.

## 3. Изменённые/добавленные файлы

### Runtime/security

- `server/src/main/kotlin/com/jarvis/server/config/DeploymentSecurityConfig.kt`
- `server/src/main/kotlin/com/jarvis/server/config/ServerConfig.kt`
- `server/src/main/kotlin/com/jarvis/server/http/ProxyRequestSecurity.kt`
- `server/src/main/kotlin/com/jarvis/server/http/JarvisApiHandler.kt`
- `server/src/main/kotlin/com/jarvis/server/Main.kt`
- `app/.../core/license/LicenseServerValidator.kt`
- `app/.../data/remote/JarvisApiClient.kt`
- `app/.../data/remote/interceptor/AuthInterceptor.kt`

### Deployment/CI

- `.dockerignore`
- `.gitignore`
- `deploy/server.Dockerfile`
- `deploy/docker-compose.production.yml`
- `deploy/Caddyfile`
- `deploy/.env.production.example`
- `deploy/verify-production-config.sh`
- `deploy/smoke-production-tls.sh`
- `.github/workflows/build.yml`
- `server/.env.example`

### Tests/docs/audit

- `server/src/test/kotlin/com/jarvis/server/DeploymentSecurityTest.kt`
- `app/src/test/.../core/security/ApiTransportSecurityTest.kt`
- `README.md`
- `docs/PRODUCTION_DEPLOYMENT.md`
- `docs/SERVER_AI_LAYER.md`
- `docs/LICENSE_BILLING.md`
- audit/inventory files.

## 4. Security properties

- Development default изменён на loopback `127.0.0.1`; HTTP остаётся доступным.
- Production требует explicit `BIND_HOST`, HTTPS `PUBLIC_BASE_URL`,
  `PRODUCTION_TLS_TERMINATED=true`, `TRUST_PROXY_HEADERS=true` и хотя бы один
  non-wildcard trusted proxy CIDR. Ошибка останавливает startup.
- Production provider URL с configured credential также обязан быть HTTPS.
- Direct production request от untrusted peer отклоняется до body parsing/auth.
- Compose доверяет exact Caddy `172.30.0.2/32`, не Docker gateway/всей subnet.
- Direct loopback HTTP разрешён только для internal `/v1/health`.
- Caddy удаляет `Forwarded` и заменяет X-Forwarded identity headers.
- Application принимает proxy headers только от allow-listed peer, требует
  `X-Forwarded-Proto=https`, один literal IP и canonical Host.
- Malformed/multiple XFF, spoofed untrusted headers, plaintext scheme и wrong Host
  fail closed (`400/403/426`).
- Caddy port 80 никогда не проксирует application и отбрасывает query при redirect.
- Android имеет единственный canonical `https://api.jarvis.ai` origin.
- Auth interceptor не добавляет и отказывается передавать Authorization на HTTP,
  alternate port или другой host.
- Authenticated Android API/license clients не следуют redirects.
- Android production cleartext запрещён network security config.
- Application logs не содержат request headers; Caddy access-log filter удаляет
  весь `request.headers` map до serialization, сохраняя method/URI/status/IP.

## 5. Tests

Добавлено 10 JVM tests:

- `DeploymentSecurityTest`: 8 configuration/proxy/topology tests;
- `ApiTransportSecurityTest`: 2 Android origin/Bearer policy tests.

Проверено автоматически:

- production config fail-fast по каждому обязательному TLS/proxy signal;
- HTTP public origin, wildcard CIDR, invalid security bool и plaintext provider
  credential URL отклоняются;
- development loopback HTTP сохраняется;
- forwarded HTTPS/client IP/Host принимаются от trusted proxy;
- untrusted spoof, forwarded HTTP, multiple XFF и wrong Host отклоняются;
- internal health разрешён, direct loopback authenticated HTTP запрещён;
- IPv4/IPv6 CIDR matching;
- Compose не публикует 8080/5432;
- Caddy TLS/redirect/header replacement contract;
- Android HTTPS exact-origin Bearer policy.

Итоговые suites после изменения:

| Check | Result |
|---|---|
| Server JVM/PostgreSQL | **110/110**, 13 suites, 0 failures/errors/skips, `--rerun-tasks` |
| Android JVM | **420/420**, 47 suites, 0 failures/errors/skips |
| Total JVM | **530/530** |
| Server build/distributions | passed |
| Android lintDebug | passed |
| Android lintVitalRelease | passed |
| Debug / androidTest / release APK assembly | passed |
| Compose topology (`config --format json`) | passed |
| Caddyfile validation | passed with local 2.6.2 and exact pinned Caddy 2.8.4 binary |
| Production server Docker image | built successfully; non-root UID 10001, healthcheck present, 275,665,945 bytes |
| Full local production Compose startup | passed; PostgreSQL and application healthy, Caddy running |
| Explicit Caddy header-log deletion canary | passed; headers/canary absent, method/URI/status retained |
| Shell syntax | passed |

Первый container build выявил реальный packaging defect: repository Gradle wrapper
не имел executable bit внутри build context, поэтому `RUN ./gradlew` завершался
`Permission denied`. Dockerfile переведён на `RUN bash ./gradlew`; повторный build
и полный Compose startup прошли. Image history не содержал token/password/pepper.

## 6. Production-like TLS smoke

Локально поднят полный реальный `docker-compose.production.yml`: pinned Caddy
2.8.4 + production-mode non-root JARVIS server image + PostgreSQL 17. Для
isolated smoke Caddy использовал internal CA, явно переданный клиенту.

Runtime inspection подтвердил: Caddy был единственным container с published
80/443; app/PostgreSQL не имели host port mappings; `proxy_backend` и `database`
были `internal=true`; Caddy был `172.30.0.2`, app — `172.30.0.3` и
`172.31.0.2`, PostgreSQL — `172.31.0.3`; app работал как UID 10001 с read-only
rootfs и `no-new-privileges`. Caddy image metadata показывала exposed 2019/TCP,
но port не был published, а `admin off` отключал listener.

Результаты:

1. `localhost` DNS resolution — passed.
2. TCP/TLS handshake + chain validation against test CA — passed.
3. TLS 1.2 — passed.
4. TLS 1.3 — passed.
5. TLS 1.1 negotiation — rejected.
6. HTTPS `/v1/health` — 200.
7. Authenticated HTTPS `/v1/admin/metrics` — 200.
8. HTTP request — 308; query `access_token=...` отсутствовал в `Location`.
9. Direct plaintext authenticated application request — 403.
10. HSTS, `nosniff`, `no-referrer` — present; `Server` response header removed.
11. Client-supplied `X-Forwarded-For: 203.0.113.77` не был принят: audit DB
    сохранила реальный loopback client IP.
12. Signed/admin issuance через HTTPS — 201.
13. Реальный Bearer token и spoofed XFF отсутствовали в Caddy/application logs;
    `request.headers` отсутствовал целиком, method/URI/status сохранились.
14. Попытка обратиться к private app IP от Docker gateway с forged HTTPS/XFF
    получила `403 UNTRUSTED_PROXY`; exact Caddy `/32` trust подтверждён.
15. Отдельный canary test Caddy log filter подтвердил отсутствие
    Authorization/Cookie/proxy headers и canary value.

`bash deploy/smoke-production-tls.sh` завершился успешно: **10/10 scripted checks**.

## 7. Public production verification still required

Проверка canonical shipped origin `api.jarvis.ai` выполнена: DNS resolution
завершился с exit 2, а `curl https://api.jarvis.ai/v1/health` — exit 6
`Could not resolve host`. Следовательно public deployment сейчас фактически не
существует, certificate/firewall/auth smoke невозможен. Не выполнены:

- успешный public DNS resolution;
- public CA certificate validation и long-term renewal observation;
- Internet scan, подтверждающий закрытые 8080/5432;
- production cloud/LB logs;
- external authenticated request к фактическому production hostname.

## 8. Acceptance status

- [x] Repository production topology имеет TLS terminator/reverse proxy.
- [x] Application/database ports не публикуются Compose.
- [x] TLS 1.2/1.3 и automatic Caddy ACME renewal configured.
- [x] HTTP redirect-only behavior defined; no application proxy on port 80.
- [x] Application понимает forwarded HTTPS scheme и client IP.
- [x] Trusted proxy spoofing fail-closed.
- [x] Bearer token не используется в URL и защищён exact HTTPS origin policy.
- [x] Application/Caddy logging проверены на test token leakage.
- [x] Production insecure startup configuration отклоняется.
- [x] Configuration/unit/full Docker Compose production-like integration passed.
- [x] Non-root production image build, health checks and private networks verified.
- [x] Deployment documentation/CI validation added.
- [ ] Public production DNS and TCP connectivity verified.
- [ ] Public CA certificate and expiry verified.
- [ ] Actual production firewall/security groups verified from Internet.
- [ ] External production authenticated smoke completed.
- [ ] Production logs inspected after external smoke.

**Вывод:** Critical code/configuration gap закрыт и случайный insecure production
startup теперь fail-closed. Однако по строгому acceptance из задачи общий
production blocker **не объявляется снятым**, пока выбранный public deployment не
пройдёт DNS/public-certificate/firewall/external-auth smoke проверки.
