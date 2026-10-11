CREATE TABLE tb_preregister_session_change (
    session_id BIGINT PRIMARY KEY REFERENCES tb_preregister_session (id) ON DELETE CASCADE,
    change_id UUID NOT NULL,
    next_revision BIGINT NOT NULL CHECK (next_revision > 1),
    operation VARCHAR(6) NOT NULL CHECK (operation IN ('UPDATE', 'DELETE')),
    definition_changed BOOLEAN NOT NULL,
    definition_json TEXT,
    CONSTRAINT preregister_change_definition CHECK (
        (operation = 'UPDATE' AND definition_json IS NOT NULL)
        OR (operation = 'DELETE' AND definition_json IS NULL)
    )
);
