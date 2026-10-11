ALTER TABLE tb_training_sms_outbox DROP CONSTRAINT tb_training_sms_outbox_state_check;
ALTER TABLE tb_training_sms_outbox
    ADD CONSTRAINT tb_training_sms_outbox_state_check
    CHECK (state IN ('PREPARED', 'UNKNOWN', 'REJECTED', 'READY', 'SENT', 'SUPPRESSED', 'HELD'));

ALTER TABLE tb_training_sms_outbox
    ADD COLUMN application_state VARCHAR(16) NOT NULL DEFAULT 'LEGACY'
        CHECK (application_state IN ('LEGACY', 'PENDING', 'SUCCEEDED', 'REJECTED', 'HELD')),
    ADD COLUMN command_schema_version INTEGER,
    ADD COLUMN operation_type VARCHAR(8) CHECK (operation_type IN ('ADD', 'REPLACE')),
    ADD COLUMN request_method VARCHAR(4),
    ADD COLUMN request_path TEXT,
    ADD COLUMN command_json TEXT,
    ADD COLUMN expected_version BIGINT CHECK (expected_version >= 0),
    ADD COLUMN receipt_json TEXT,
    ADD COLUMN receipt_version BIGINT CHECK (receipt_version >= 0),
    ADD COLUMN response_received BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN sms_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN first_publish_attempt_at TIMESTAMPTZ,
    ADD COLUMN terminal_at TIMESTAMPTZ,
    ADD COLUMN hold_reason VARCHAR(40),
    ADD COLUMN recovery_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN recover_after TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE tb_training_sms_outbox ADD CONSTRAINT training_operation_command_complete
    CHECK (application_state = 'LEGACY' OR
        (command_schema_version IS NOT NULL AND command_schema_version = 1 AND operation_type IS NOT NULL AND request_method IS NOT NULL
         AND request_path IS NOT NULL AND command_json IS NOT NULL AND expected_version IS NOT NULL));

-- Unkeyed legacy writes cannot be reconstructed or replayed as keyed operations.
UPDATE tb_training_sms_outbox
SET state = 'HELD', hold_reason = 'LEGACY_UNCERTAIN', notification_text = NULL, payload = NULL,
    lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
WHERE state IN ('PREPARED', 'UNKNOWN');

-- There is no durable first-attempt timestamp for legacy READY rows; ACK loss cannot be excluded.
UPDATE tb_training_sms_outbox
SET state = 'HELD', hold_reason = 'LEGACY_PUBLICATION_UNCERTAIN', notification_text = NULL, payload = NULL,
    publish_token = NULL, publish_until = NULL, updated_at = CURRENT_TIMESTAMP
WHERE state = 'READY';

UPDATE tb_training_sms_outbox SET terminal_at = updated_at WHERE state IN ('SENT', 'REJECTED');
CREATE INDEX training_operation_pending ON tb_training_sms_outbox (recover_after, sequence)
    WHERE application_state = 'PENDING';
