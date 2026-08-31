-- OMNIX Control Plane (V006): admin identities, sessions, audit, settings, feature flags.
-- Existing entities (accounts, licenses, api_tokens, license_audit_log, ai_usage_records)
-- are REUSED as the source of truth; nothing here duplicates them.

CREATE TABLE admin_accounts (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    -- PBKDF2-WithHmacSHA256, format: pbkdf2$iterations$saltB64$hashB64
    password_hash VARCHAR(256) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('SUPER_ADMIN', 'ADMIN', 'SUPPORT', 'VIEWER')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE admin_sessions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES admin_accounts(id) ON DELETE CASCADE,
    -- SHA-256 of the bearer token; the raw token is never stored.
    token_hash BYTEA NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    remote_address VARCHAR(64),
    CHECK (expires_at > created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX idx_admin_sessions_account ON admin_sessions(account_id, expires_at);

-- Append-only administrative audit trail (immutable by design: the application
-- contains no UPDATE/DELETE path; see AdminControlPlaneIntegrationTest).
CREATE TABLE admin_audit_log (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id VARCHAR(128),
    old_value JSONB NOT NULL DEFAULT '{}'::jsonb,
    new_value JSONB NOT NULL DEFAULT '{}'::jsonb,
    remote_address VARCHAR(64),
    session_id UUID,
    request_id VARCHAR(64)
);

CREATE INDEX idx_admin_audit_time ON admin_audit_log(occurred_at DESC);
CREATE INDEX idx_admin_audit_actor ON admin_audit_log(actor, occurred_at DESC);
CREATE INDEX idx_admin_audit_action ON admin_audit_log(action, occurred_at DESC);

CREATE TABLE admin_settings (
    key VARCHAR(64) PRIMARY KEY,
    value JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE feature_flags (
    key VARCHAR(64) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    rollout_percent INT NOT NULL DEFAULT 0 CHECK (rollout_percent BETWEEN 0 AND 100),
    description VARCHAR(256) NOT NULL DEFAULT '',
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
