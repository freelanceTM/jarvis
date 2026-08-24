CREATE TABLE ai_usage_records (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    client_id VARCHAR(128) NOT NULL,
    provider VARCHAR(32),
    model VARCHAR(128),
    latency_ms BIGINT NOT NULL CHECK (latency_ms >= 0),
    input_tokens BIGINT CHECK (input_tokens IS NULL OR input_tokens >= 0),
    output_tokens BIGINT CHECK (output_tokens IS NULL OR output_tokens >= 0),
    total_tokens BIGINT CHECK (total_tokens IS NULL OR total_tokens >= 0),
    success BOOLEAN NOT NULL,
    error_code VARCHAR(64),
    prompt_chars INTEGER NOT NULL CHECK (prompt_chars >= 0),
    response_chars INTEGER NOT NULL CHECK (response_chars >= 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (client_id, request_id)
);

CREATE INDEX idx_ai_usage_client_time
    ON ai_usage_records(client_id, occurred_at DESC, id DESC);
CREATE INDEX idx_ai_usage_cleanup
    ON ai_usage_records(occurred_at);
