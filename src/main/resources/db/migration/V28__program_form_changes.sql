CREATE TABLE tb_program_form_guard (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO tb_program_form_guard VALUES (1);

CREATE TABLE tb_program_form_change (
    form_id UUID NOT NULL,
    change_id UUID NOT NULL,
    request_json TEXT,
    response_json TEXT,
    expires_at TIMESTAMPTZ,
    released BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (form_id, change_id),
    CHECK (released OR (request_json IS NOT NULL AND response_json IS NOT NULL AND expires_at IS NOT NULL))
);
CREATE UNIQUE INDEX ux_program_form_active_change ON tb_program_form_change (form_id) WHERE NOT released;
CREATE INDEX ix_standard_program_form ON tb_standard_program ((policy_json::jsonb->>'formId')) WHERE policy_json IS NOT NULL;
CREATE INDEX ix_training_program_form ON tb_training_program ((policy_json::jsonb->>'formId')) WHERE policy_json IS NOT NULL;
