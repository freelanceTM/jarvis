# Операционный ранбук JARVIS API

Кому: дежурный/владелец. Цель — диагностировать и устранить инцидент за
5–10 минут. Дополняется по мере появления реальных инцидентов.

## 1. Наблюдаемость: где смотреть

| Что | Где |
|---|---|
| Liveness/health | `GET /v1/health` (без auth, кэш 2 с, circuit-статусы провайдеров) |
| Метрики JSON | `GET /v1/admin/metrics` (Bearer + VIEW_ADMIN) |
| Метрики Prometheus | `GET /v1/admin/metrics/prometheus` (Bearer + VIEW_ADMIN, `text/plain; version=0.0.4`) |
| Логи | stdout контейнера (`docker logs jarvis-server`), формат `ts level msg k=v` |

Ключевые метрики (имена стабильны — используются в алертах):

- `jarvis_requests_total / jarvis_requests_success_total / jarvis_requests_failed_total`
- `jarvis_requests_rate_limited_total`, `jarvis_requests_unauthorized_total`,
  `jarvis_requests_privacy_blocked_total`
- `jarvis_provider_success_total{provider}`, `jarvis_provider_failure_total{provider}`,
  `jarvis_provider_latency_ms_sum{provider}`
- `jarvis_provider_failure_kind_total{kind}` (TIMEOUT / UNAVAILABLE / ERROR / …)
- `jarvis_named_*` — внутренние счётчики (usage_dropped, usage_retry, …)

Пример scrape-конфига — `deploy/prometheus/prometheus.yml`,
правила алертов — `deploy/prometheus/alerts.yml`.

## 2. Алерты (минимальный набор)

| Алерт | Условие | Первое действие |
|---|---|---|
| HighRequestFailureRate | `rate(jarvis_requests_failed_total[5m]) > 0.2 * rate(jarvis_requests_total[5m])` | §3.1 |
| AllProvidersFailing | `rate(jarvis_provider_failure_kind_total[5m]) > 0` у ВСЕХ провайдеров и успехов нет | §3.2 |
| RateLimitSaturation | `increase(jarvis_requests_rate_limited_total[15m]) > 100` | §3.3 |
| UnauthorizedSpike | `increase(jarvis_requests_unauthorized_total[15m]) > 50` | §3.4 |
| UsageDropped | `increase(jarvis_named_usage_dropped[15m]) > 0` | §3.5 |
| InstanceDown | health-check не отвечает 1 мин | §3.6 |

## 3. Плейбуки

### 3.1 Высокая доля ошибок запросов

1. `GET /v1/health` — статус circuit-брейкеров провайдеров.
2. `jarvis_provider_failure_kind_total` — какой kind преобладает:
   - `TIMEOUT` — провайдеры медленные: проверить статус-страницы провайдеров;
     при устойчивом деградировании снизить нагрузку (rate-limit) или временно
     отключить провайдера (`*_ENABLED=false` + рестарт);
   - `UNAVAILABLE`/5xx — инцидент на стороне провайдера;
   - рост `jarvis_requests_privacy_blocked_total` — всплеск приватных запросов
     (не инцидент доступности).
3. Проверить последние деплои (`git log`), при регрессии — rollback по §3.7.

### 3.2 Все провайдеры недоступны

1. `curl -s $HEALTH` → все `circuit:OPEN`?
2. Проверить ключи/квоты провайдеров (429/401 в логах — ротация ключа или лимит).
3. Если ключи в порядке — это инцидент провайдеров; клиенты получат
   `ALL_PROVIDERS_UNAVAILABLE` (5xx). Восстановление автоматическое
   (circuit HALF_OPEN после cooldown `CB_OPEN_COOLDOWN_MS`).
4. Убедиться, что deadline-бюджет не съедается: startup-лог
   «provider timeout budget exceeds request deadline» должен отсутствовать.

### 3.3 Насыщение rate-limit

1. Кто лимитируется: логи `rate limit exceeded` + `client_id`.
2. Легитимный клиент вырос → поднять `RATE_LIMIT_PER_MINUTE/DAY`.
3. Аномалия (один client_id флудит) → отозвать токен (§3.8).

### 3.4 Всплеск 401

1. Взять request_id из логов; проверить, не утёк ли токен (git/logs/билд-артефакты).
2. При подозрении — ротация: `JARVIS_CLIENT_TOKENS` (статические) или отзыв
   DB-backed `jrv_` токена в БД (`api_tokens`).
3. Всплеск после релиза клиента — проверить, что клиент шлёт `Authorization`.

### 3.5 usage_dropped > 0

`AsyncUsageTracker` не смог записать usage в Postgres (переполнение очереди
или недоступность БД). Проверить §3.5.1; метрики счётчиков не теряются,
но usage-записи за окно деградации неполны.

### 3.6 Инстанс не отвечает

1. `docker ps`, `docker logs --tail 200 jarvis-server`.
2. OOM → проверить память хоста; контейнер рестартует compose-политикой.
3. Postgres недоступен → §3.5.1.
4. После рестарта: in-memory circuit/метрики сбрасываются (single-instance решение) —
   это ожидаемо; health должен стать `ok` в течение 2–3 минут.

### 3.7 Rollback деплоя

```bash
# На хосте деплоя:
git checkout <previous-tag>
docker compose -f deploy/docker-compose.yml up -d --build server
# или для образа:
docker compose ... image: ghcr.io/...@<previous-digest>
```

Миграции БД вперёд-совместимы не гарантированы: миграция применяется при
старте; откат кода на старую схему требует сверки `server/src/main/resources/db/migration`.

### 3.5.1 Postgres недоступен

Симптомы: fail-closed на старте (сервер не поднимается), 503 на запросах,
`usage_dropped`.

1. `docker logs postgres`, `pg_isready`.
2. Диск: `df -h` (WAL/таблицы растут).
3. Восстановление из бэкапа — §4 (только при потере данных; иначе дождаться
   восстановления инстанса).
4. Пока БД недоступна, лицензии/billing/rate-limit недоступны — это
   осознанный fail-closed (single-instance решение). Android-клиент покажет «сервер
   недоступен», локальные команды продолжают работать.

### 3.8 Ротация скомпрометированного токена

1. Статический клиент: изменить `JARVIS_CLIENT_TOKENS` (новый token:clientId),
   рестарт. Старый токен перестаёт действовать немедленно.
2. DB-backed `jrv_` токен: `DELETE FROM api_tokens WHERE token_hash = ...`
   (или пометить отозванным, если схема поддерживает) — клиент получит 401
   и перейдёт на повторный redeem лицензии.
3. Инцидент-процедура — SECURITY.md.

## 4. Бэкап и восстановление Postgres

- Расписание: ежедневный `pg_dump -Fc` + хранение 14 дней вне хоста
  (владелец настраивает cron/managed backup — см. PRODUCTION_DEPLOYMENT.md).
- RPO: 24 ч (дневной дамп) / RTO: ~30 мин (поднятие дампа в новый контейнер).
- Проверка восстановления (раз в месяц, на staging):
  `pg_restore --list` читается → поднять копию → `/v1/health` + логин
  тестовой лицензии.
- Before-миграционный дамп: обязателен при каждом апгрейде схемы.

## 5. Осиротевшие reconciliation-заказы (биллинг)

Состояние `RECONCILIATION_REQUIRED` означает неоднозначный исход вызова
провайдера. Операционная процедура (до появления фонового воркера):

1. `SELECT * FROM billing_orders WHERE status='RECONCILIATION_REQUIRED';`
2. Для каждого заказа сверить статус в панели провайдера (Paddle/HELEKET).
3. Если платёж прошёл — применить продление лицензии вручную/через
   доверенный local-order-webhook и пометить заказ `PAID`.
4. Если не прошёл — пометить `FAILED` с комментарием в audit-логе.
5. Дубликатный webhook невозможен (уникальный индекс `(provider, provider_event_id)`).

## 6. Известные ограничения (осознанные, single-instance решение)

- Один инстанс приложения: рестарт сбрасывает in-memory circuit-состояние и
  метрики; горизонтальное масштабирование намеренно отключено.
- Метрики не персистентны: дашборды строятся по scrape в внешнее хранилище
  (Prometheus), а не по данным процесса.
