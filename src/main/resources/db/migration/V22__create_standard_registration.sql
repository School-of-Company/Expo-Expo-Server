CREATE TABLE tb_standard_registration (
    expo_id VARCHAR(36) NOT NULL REFERENCES tb_expo (id) ON DELETE CASCADE,
    participant_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (expo_id, participant_id)
);
