CREATE TABLE billing_orders (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    license_id UUID REFERENCES licenses(id),
    plan_id VARCHAR(64) NOT NULL REFERENCES billing_plans(id),
    provider VARCHAR(16) NOT NULL CHECK (provider IN ('PADDLE', 'HELEKET', 'LOCAL_TMT')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('CREATED', 'PROCESSING', 'PENDING', 'PAID', 'FAILED', 'CANCELED', 'REFUNDED', 'EXPIRED')),
    amount_minor BIGINT NOT NULL CHECK (amount_minor >= 0),
    currency CHAR(3) NOT NULL CHECK (currency = UPPER(currency)),
    idempotency_key VARCHAR(128) NOT NULL,
    provider_order_id VARCHAR(128),
    provider_subscription_id VARCHAR(128),
    checkout_url VARCHAR(2048),
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (account_id, idempotency_key),
    CHECK ((status <> 'PAID') OR paid_at IS NOT NULL)
);

CREATE UNIQUE INDEX uq_billing_order_provider_id
    ON billing_orders(provider, provider_order_id)
    WHERE provider_order_id IS NOT NULL;
CREATE INDEX idx_billing_orders_account ON billing_orders(account_id, created_at DESC);
CREATE INDEX idx_billing_orders_status ON billing_orders(status, created_at);
CREATE INDEX idx_billing_orders_subscription ON billing_orders(provider, provider_subscription_id)
    WHERE provider_subscription_id IS NOT NULL;

CREATE TABLE billing_events (
    id UUID PRIMARY KEY,
    provider VARCHAR(16) NOT NULL CHECK (provider IN ('PADDLE', 'HELEKET', 'LOCAL_TMT')),
    provider_event_id VARCHAR(192) NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    payload_hash BYTEA NOT NULL,
    order_id UUID REFERENCES billing_orders(id),
    account_id UUID REFERENCES accounts(id),
    signature_verified BOOLEAN NOT NULL,
    processing_status VARCHAR(16) NOT NULL CHECK (processing_status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED')),
    failure_code VARCHAR(64),
    occurred_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    UNIQUE (provider, provider_event_id)
);

CREATE INDEX idx_billing_events_order ON billing_events(order_id, received_at DESC);
CREATE INDEX idx_billing_events_account ON billing_events(account_id, received_at DESC);
