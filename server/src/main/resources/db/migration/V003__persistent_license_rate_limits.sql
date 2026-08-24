CREATE TABLE license_rate_limit_events (
    id BIGSERIAL PRIMARY KEY,
    scope VARCHAR(32) NOT NULL,
    key_hash BYTEA NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_license_rate_limit_lookup
    ON license_rate_limit_events(scope, key_hash, occurred_at);
CREATE INDEX idx_license_rate_limit_cleanup
    ON license_rate_limit_events(occurred_at);
