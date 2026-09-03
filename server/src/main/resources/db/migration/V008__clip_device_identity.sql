-- V008: криптографическая идентичность OMNIX Clip.
--
-- Правило (Activation/Lockdown для носимого устройства): Bluetooth-имя и MAC —
-- копируемые/подменяемые идентификаторы, идентичность Clip = ПАРЫ КЛЮЧЕЙ,
-- сгенерированной на устройстве при производстве. Приватный ключ не покидает
-- Clip; сервер получает только публичный ключ (provisioning).
--
-- При подключении телефона: challenge (single-use, TTL) → Clip подписывает
-- каноническое сообщение → сервер/локальный верификатор проверяет подпись
-- зарегистрированным публичным ключом → VALID. Владелец привязывается при
-- первом успешном attest; REVOKED клип не проходит проверку никогда.
CREATE TABLE clip_devices (
    id UUID PRIMARY KEY,
    clip_serial VARCHAR(64) NOT NULL UNIQUE,
    public_key BYTEA NOT NULL,
    owner_account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PROVISIONED', 'ACTIVE', 'REVOKED')),
    bound_at TIMESTAMPTZ,
    last_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_clip_devices_owner ON clip_devices(owner_account_id, status);

CREATE TABLE clip_attest_challenges (
    id UUID PRIMARY KEY,
    clip_serial VARCHAR(64) NOT NULL,
    nonce BYTEA NOT NULL,
    issued_at_ms BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_clip_challenges_serial ON clip_attest_challenges(clip_serial, expires_at);
