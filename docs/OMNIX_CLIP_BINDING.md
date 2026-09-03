# OMNIX Clip: криптографическая привязка устройства

**Ревизия:** `main` (V008) · Сервер: `server/clip/` · Android: `core/clip/`

## Принцип

Идентичность Clip = **пара ключей** (EC P-256), сгенерированная на устройстве
при производстве. Bluetooth-имя и MAC используются ТОЛЬКО как транспортный
адрес (discovery/подключение) и НЕ участвуют в доверии: их можно
скопировать/подменить. Доверие = подпись challenge приватным ключом,
проверенная зарегистрированным публичным ключом.

## Жизненный цикл

```
Производство:  Clip генерирует ключ → публичный ключ уходит на сервер
               POST /v1/admin/clips/provision {clip_serial, public_key}
               → clip_devices: serial, PUBLIC key, status=PROVISIONED

Подключение:   Phone → POST /v1/clip/challenge {clip_serial}
                     → {challenge_id, nonce(32), issued_at_ms}  (single-use, TTL 120s)
               Phone → каноническое сообщение → Clip по BLE-транспорту
               Clip  → подпись ECDSA (ключ не покидает устройство)
               Phone → POST /v1/clip/attest {clip_serial, challenge_id, signature}
               Server→ проверка подписи зарегистрированным ключом
                     → первый attest привязывает владельца (account)
                     → VALID / CLIP_REVOKED / CLIP_BAD_SIGNATURE / ...
```

Каноническое сообщение (единое для сервера, Android и firmware):

```
"JARVIS-CLIP-ATTEST-v1" \0 clip_serial \0 nonce(32) \0 issuedAtMs(8, BE)
```

Всё, кроме подписи, контролируется сервером (nonce single-use, issued_at из
строки challenge в БД) — replay и подмена serial/таймингов невозможны.

## Серверный реестр (`clip_devices`, V008)

| Поле | Смысл (по спецификации) |
|---|---|
| `clip_serial` | «Clip #12345» — уникальный производственный номер |
| `public_key` | зарегистрированный публичный ключ (X.509 SPKI, P-256) |
| `owner_account_id` | владелец (привязка первым attest,(accounts) |
| `status` | PROVISIONED → ACTIVE → REVOKED |

Ошибки API: `CLIP_UNKNOWN` (404), `CLIP_ALREADY_PROVISIONED` (409),
`CLIP_REVOKED` (403), `CLIP_CHALLENGE_INVALID` (401, replay/истёк),
`CLIP_BAD_SIGNATURE` (401), `CLIP_OWNER_MISMATCH` (403).

## Android (`core/clip/`)

| Компонент | Роль |
|---|---|
| `ClipAttestationProtocol` | каноническое сообщение + nonce (зеркало сервера) |
| `ClipIdentityVerifier` | чистая ECDSA-проверка, fail-closed |
| `ClipTrustStore` (`EncryptedClipTrustStore`) | ключ закрепляется ТОЛЬКО из ответов сервера — офлайн-проверка с серверным якорем |
| `ClipAttestationApi` / `HttpClipAttestationApi` | challenge/attest (bearer jrv_, паттерн HttpLicenseServerValidator) |
| `ClipAttestationManager` | онлайн (сервер) / офлайн (закреплённый ключ + свежесть ±90с) |
| `ClipTransport` | **контракт для firmware** (см. ниже) |

`ClipTrustStore.forget()` вызывается после серверного `Revoked`.

## Честная граница: firmware

В репо нет firmware/SDK Clip (аудит v0.3 §1.3: «HARDWARE IMPLEMENTATION NOT
AVAILABLE»). Поэтому `ClipTransport` — явный контракт: BLE GATT-запись
канонического сообщения → DER-подпись в ответ. Фейковой реализации НЕТ:
транспорт возвращает `Unavailable`, и `ClipAttestationManager` честно отдаёт
`TransportUnavailable` — никакого «VALID» без криптографического доказательства.

Требование к производству: ключ генерируется НА устройстве; наружу уходит
только публичная часть; приватный ключ в secure element / зашитый keypair.

## Ограничение локальной проверки

Офлайн-verify не видит revocation в реальном времени (якорь — момент
последнего успешного attest). При наличии сети использовать
`verifyWithServer`; `forget()` сбрасывает закрепление после серверного
отказа. Это осознанный компромисс, задокументированный в KDoc.

## Тесты

- Сервер (реальный PostgreSQL): provision/duplicate, challenge → attest →
  VALID + привязка владельца, replay-challenge → 401, чужой ключ →
  CLIP_BAD_SIGNATURE (аналог подмены MAC), второй аккаунт →
  CLIP_OWNER_MISMATCH, revoked → CLIP_REVOKED, unknown → CLIP_UNKNOWN.
- Android (JVM): верификатор (подлинная/испорченная/чужая подпись),
  NotPinned без серверного якоря, TransportUnavailable без firmware,
  онлайн-путь закрепляет ключ → офлайн-путь принимает свой и отвергает
  клонированный ключ.
- 8 CLIP-инвариантов в `scripts/verify-architectural-invariants.sh`.
