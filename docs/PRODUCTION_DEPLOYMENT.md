# Production deployment: обязательный TLS

> **Production deployment без TLS/reverse proxy запрещён, поскольку authentication Bearer tokens передаются по сети.**

## 1. Результат discovery и topology

До этого изменения в репозитории не было Dockerfile, Compose, Kubernetes,
Ingress, nginx/Caddy, Terraform или cloud deployment configuration. Точка входа
`Main.kt` напрямую создавала JDK `HttpServer` на `0.0.0.0:$PORT`. Единственная
production-рекомендация про reverse proxy не предотвращала случайный публичный
HTTP deployment.

Поддерживаемая repository topology теперь следующая:

```text
Internet
  -> TCP/UDP 443 (TLS 1.2/1.3) -> Caddy (certificate + automatic renewal)
  -> private Docker network HTTP -> jarvis-server:8080
  -> private Docker network -> PostgreSQL:5432

Internet -> TCP 80 -> Caddy 308 redirect only (never reverse_proxy)
```

Single-instance решение ограничивает topology одним application instance. Production требует
`APPLICATION_REPLICA_COUNT=1`, а PostgreSQL session advisory lock не позволяет
запуститься второму процессу. Не используйте `docker compose --scale`, Kubernetes
replicas >1 или несколько VM с этим application до replacement ADR/shared
circuit+metrics layer. Security/business state (auth, billing, idempotency, all
rate limits, usage) уже shared в PostgreSQL.

`deploy/docker-compose.production.yml` не публикует порты application и
PostgreSQL. Caddy — единственный container с host `ports`.

Если платформа уже предоставляет TLS load balancer/Ingress, **не запускайте
второй Caddy**. Используйте тот же application contract: не публикуйте port
8080, передавайте очищенные `X-Forwarded-*`, укажите CIDR балансировщика в
`TRUSTED_PROXY_CIDRS` и сохраните production safety flags.

## 2. Ports и network policy

| Port | Visibility | Purpose |
|---:|---|---|
| 80/TCP | public, optional | redirect to HTTPS only; no application proxy |
| 443/TCP | public | canonical HTTPS API (HTTP/1.1 and HTTP/2) |
| 443/UDP | public, optional | HTTP/3 |
| 8080/TCP | private `proxy_backend` only | internal JDK HTTP listener |
| 5432/TCP | private `database` only | PostgreSQL |
| 2019/TCP | not published; Caddy admin is `off` | no admin API listener |

Firewall/security group должен разрешать Internet ingress только на 80/443.
Port 8080 разрешается только от reverse proxy/LB, port 5432 — только от
application. Docker Compose реализует это отсутствием `ports` и двумя
`internal: true` networks. Не добавляйте `8080:8080` или `5432:5432`.

Application дополнительно отклоняет production requests от peer, который не
входит в `TRUSTED_PROXY_CIDRS`. Compose назначает Caddy статический
`172.30.0.2` и доверяет только `/32`, а не всей bridge network: Docker gateway и
другие containers не могут подделать proxy identity. Поэтому случайная
публикация port 8080 не превращает его в рабочий authenticated entry point.
Прямой loopback доступ в production разрешён только для `/v1/health` container
health check.

## 3. DNS и сертификат

1. Создайте `A`/`AAAA` record `PUBLIC_DOMAIN`, направленный на production host.
   Для текущего shipped Android build это обязано быть `api.jarvis.ai`; origin
   задан единственной compile-time константой и защищён exact-host policy.
2. Разрешите 80/TCP и 443/TCP до Caddy. Для HTTP/3 разрешите 443/UDP.
3. Задайте `ACME_EMAIL` для уведомлений об expiry.
4. Сохраните volumes `caddy_data` и `caddy_config` между redeploy/restart.
5. Caddy автоматически получает и обновляет публичный ACME certificate.
6. Мониторинг должен предупреждать минимум за 30 дней до expiry; smoke script
   проверяет certificate chain и `notAfter`.

`deploy/Caddyfile` разрешает только TLS 1.2 и TLS 1.3. Legacy TLS и небезопасные
cipher suites не включаются. Private keys/certificates в repository не хранятся.

## 4. Production configuration

Скопируйте шаблон только как локальную основу или, предпочтительно, загрузите
значения из secret manager:

```bash
cp deploy/.env.production.example deploy/.env.production
chmod 600 deploy/.env.production
# заменить ВСЕ placeholder/secrets

docker compose \
  --env-file deploy/.env.production \
  -f deploy/docker-compose.production.yml \
  config --quiet   # не печатает expanded secrets

docker compose \
  --env-file deploy/.env.production \
  -f deploy/docker-compose.production.yml \
  up -d --build
```

Обязательные application flags уже зафиксированы в production Compose:

```text
APP_ENV=production
BIND_HOST=0.0.0.0                 # только внутри private container network
APPLICATION_REPLICA_COUNT=1       # single-instance решение; другое значение запрещено
PRODUCTION_TLS_TERMINATED=true
TRUST_PROXY_HEADERS=true
TRUSTED_PROXY_CIDRS=172.30.0.2/32        # exact Caddy container IP
PUBLIC_BASE_URL=https://<PUBLIC_DOMAIN>
```

Production startup завершается ошибкой, если отсутствуют HTTPS public URL,
explicit bind host, TLS acknowledgement, trusted-proxy mode или CIDR. CIDR
`0.0.0.0/0`/`::/0` запрещён. Enabled provider URL с credentials также обязан
использовать HTTPS.

Development остаётся loopback-only HTTP:

```text
APP_ENV=development
BIND_HOST=127.0.0.1
PRODUCTION_TLS_TERMINATED=false
TRUST_PROXY_HEADERS=false
```

## 5. Reverse proxy и trusted headers

Caddy удаляет стандартный клиентский `Forwarded` и **заменяет** (не добавляет)
входящие `X-Forwarded-For`, `X-Forwarded-Proto`, `X-Forwarded-Host`,
`X-Real-IP`, создавая ровно один авторитетный hop:

- `X-Forwarded-Proto: https`;
- `X-Forwarded-For` и `X-Real-IP`: TCP peer клиента;
- `X-Forwarded-Host`: canonical host;
- `Host`: original canonical host.

Application принимает эти значения только от configured private proxy CIDR,
требует `https`, один literal client IP и authority, совпадающий с
`PUBLIC_BASE_URL`. Заголовки от untrusted peer игнорируются в development и
полностью отклоняются в production. Несколько XFF values, HTTP scheme,
неожиданный Host и malformed IP fail closed до parsing body/authentication.

Для cloud LB/Ingress замените `TRUSTED_PROXY_CIDRS` на точные egress CIDR этого
LB. Нельзя указывать Internet-wide CIDR. LB обязан **заменять**, а не просто
добавлять к недоверенным client headers.

## 6. HTTP behavior и Bearer tokens

Port 80 не проксирует ни один route. Он возвращает `308` на canonical HTTPS,
причём query string отбрасывается. Клиент не должен отправлять Authorization на
HTTP и не должен полагаться на redirect: Android URLs compile-time HTTPS, а
cleartext traffic запрещён Android network security policy.

Bearer tokens:

- передаются только в `Authorization` header, не в URL/query;
- Android interceptor добавляет token только для exact JARVIS host;
- Android release logging выключен, debug logger redacts `Authorization`;
- application logger не логирует request headers/body/token;
- Caddy access-log filter полностью удаляет `request.headers`, поэтому
  Authorization/Cookie/proxy-header values не сериализуются;
- нельзя удалять этот filter или включать Caddy credential logging;
- error responses не содержат headers или exception details.

## 7. Health checks и operation

- External/LB health: `GET https://<PUBLIC_DOMAIN>/v1/health`.
- Internal container health: `GET http://127.0.0.1:8080/v1/health` внутри
  application container only.
- PostgreSQL: `pg_isready` внутри database network.

Не используйте authenticated endpoint как health check. Не добавляйте token в
health URL.

Просмотр логов:

```bash
docker compose --env-file deploy/.env.production \
  -f deploy/docker-compose.production.yml logs --no-color caddy jarvis-server
```

Проверьте отсутствие реального token, activation code, `Authorization: Bearer`
и provider secret. Caddy может выводить literal `REDACTED` — это ожидаемо.

## 8. Deployment verification

Static/configuration verification:

```bash
bash deploy/verify-production-config.sh
```

После DNS/certificate deployment:

```bash
export PUBLIC_API_URL=https://api.jarvis.ai
export SMOKE_BEARER_TOKEN='<read from secret manager; never pass as CLI arg>'
export APP_DIRECT_URL=http://private-or-public-host:8080  # optional negative test
bash deploy/smoke-production-tls.sh
unset SMOKE_BEARER_TOKEN
```

Script проверяет DNS, TCP, TLS handshake, certificate chain/expiry, HTTPS health,
authenticated API, HTTP redirect и optional direct-port denial. После него
оператор обязан проверить firewall/security group и sanitized proxy/application
logs.

## 9. Cloud/Ingress equivalent checklist

При замене Caddy cloud load balancer/Ingress должен обеспечивать:

- valid public certificate и automatic renewal;
- TLS 1.2+;
- HTTP port disabled или redirect-only;
- application target private, без public IP/port;
- exact health path `/v1/health`;
- sanitized/replaced proxy headers;
- no Authorization logging;
- exact LB CIDR in `TRUSTED_PROXY_CIDRS`;
- `PUBLIC_BASE_URL=https://canonical-host`;
- startup flags `APP_ENV=production`, `PRODUCTION_TLS_TERMINATED=true`,
  `TRUST_PROXY_HEADERS=true`.

Снятие TLS blocker требует реального DNS/certificate smoke test на выбранной
production infrastructure; локальный self-signed smoke подтверждает topology и
код, но не валидность будущего публичного ACME certificate.
