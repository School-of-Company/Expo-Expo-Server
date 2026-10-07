CREATE TABLE tb_training_sms_outbox (
    sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL UNIQUE,
    expo_id VARCHAR(36) NOT NULL,
    trainee_id BIGINT NOT NULL,
    fingerprint TEXT NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PREPARED', 'UNKNOWN', 'REJECTED', 'READY', 'SENT')),
    payload TEXT,
    notification_text TEXT,
    lease_token VARCHAR(36),
    lease_until TIMESTAMPTZ,
    publish_token VARCHAR(36),
    publish_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX training_sms_latest ON tb_training_sms_outbox (expo_id, trainee_id, sequence DESC);
CREATE INDEX training_sms_ready ON tb_training_sms_outbox (next_attempt_at, sequence) WHERE state = 'READY';
