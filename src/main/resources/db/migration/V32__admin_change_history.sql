ALTER TABLE tb_program_change ADD COLUMN admin_id BIGINT;
ALTER TABLE tb_preregister_session_change ADD COLUMN admin_id BIGINT;

CREATE TABLE tb_admin_change_history (
    id BIGSERIAL PRIMARY KEY,
    expo_id VARCHAR(36) NOT NULL REFERENCES tb_expo(id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL,
    admin_id BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    summary TEXT NOT NULL
);
CREATE INDEX idx_admin_change_history_expo_time ON tb_admin_change_history(expo_id, occurred_at DESC, id DESC);
