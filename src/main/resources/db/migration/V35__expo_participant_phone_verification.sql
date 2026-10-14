ALTER TABLE tb_expo
    ADD COLUMN participant_phone_verification_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN participant_phone_verification_revision BIGINT NOT NULL DEFAULT 0;
