CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    external_ref VARCHAR(128) UNIQUE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE billing_plans (
    id VARCHAR(64) PRIMARY KEY,
    product_id VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    duration_days INTEGER NOT NULL CHECK (duration_days BETWEEN 1 AND 3650),
    amount_minor BIGINT NOT NULL CHECK (amount_minor >= 0),
    currency CHAR(3) NOT NULL CHECK (currency = UPPER(currency)),
    paddle_price_id VARCHAR(64),
    heleket_currency VARCHAR(16),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE licenses (
    id UUID PRIMARY KEY,
    code_hash BYTEA NOT NULL UNIQUE,
    code_hint VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ISSUED', 'ACTIVE', 'EXPIRED', 'REVOKED', 'DISABLED')),
    billing_status VARCHAR(16) NOT NULL CHECK (billing_status IN ('GRANTED', 'PENDING', 'PAID', 'PAST_DUE', 'CANCELED', 'REFUNDED')),
    issued_at TIMESTAMPTZ NOT NULL,
    starts_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    product_id VARCHAR(64) NOT NULL,
    plan_id VARCHAR(64) NOT NULL REFERENCES billing_plans(id),
    account_id UUID REFERENCES accounts(id),
    one_time BOOLEAN NOT NULL DEFAULT TRUE,
    redeemed_at TIMESTAMPTZ,
    redeemed_device_hash BYTEA,
    revoked_at TIMESTAMPTZ,
    revoked_reason VARCHAR(256),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CHECK ((status <> 'ACTIVE') OR (account_id IS NOT NULL AND redeemed_at IS NOT NULL)),
    CHECK ((status <> 'REVOKED') OR revoked_at IS NOT NULL),
    CHECK (expires_at IS NULL OR starts_at IS NULL OR expires_at > starts_at)
);

CREATE INDEX idx_licenses_account_status ON licenses(account_id, status);
CREATE INDEX idx_licenses_expiry ON licenses(expires_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_licenses_plan ON licenses(plan_id);

CREATE TABLE api_tokens (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at IS NULL OR expires_at > issued_at),
    CHECK ((status <> 'REVOKED') OR revoked_at IS NOT NULL)
);

CREATE INDEX idx_api_tokens_account ON api_tokens(account_id, status);

CREATE TABLE license_audit_log (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128),
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id UUID,
    request_id VARCHAR(64),
    remote_address VARCHAR(64),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_license_audit_entity ON license_audit_log(entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_license_audit_actor ON license_audit_log(actor_type, actor_id, occurred_at DESC);
