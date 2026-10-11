ALTER TABLE tb_expo_image ADD COLUMN reserved_expo_id VARCHAR(36);

ALTER TABLE tb_standard_program
    ADD COLUMN group_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN policy_json TEXT,
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0);
ALTER TABLE tb_training_program
    ADD COLUMN group_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN policy_json TEXT,
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0);
CREATE INDEX ix_standard_program_group ON tb_standard_program (group_id, expo_id);
CREATE INDEX ix_training_program_group ON tb_training_program (group_id, expo_id);

CREATE TABLE tb_program_change (
    expo_id VARCHAR(36) PRIMARY KEY REFERENCES tb_expo(id) ON DELETE CASCADE,
    change_id UUID NOT NULL UNIQUE,
    command_json TEXT NOT NULL,
    payload_json TEXT NOT NULL
);


ALTER TABLE tb_standard_program ADD CONSTRAINT ck_standard_program_policy CHECK (
    policy_json IS NULL OR (
        (policy_json::jsonb->>'capacity')::BIGINT > 0
        AND (policy_json::jsonb->>'waitingCapacity')::BIGINT >= 0
        AND (policy_json::jsonb->>'groupId')::UUID = group_id
        AND jsonb_array_length(policy_json::jsonb->'allowedOccupations') > 0
        AND (policy_json::jsonb->'allowedOccupations') <@
            '["KINDERGARTEN_STUDENT","ELEMENTARY_STUDENT","MIDDLE_SCHOOL_STUDENT","HIGH_SCHOOL_STUDENT","SCHOOL_STAFF","PARENT","GENERAL","TEACHER","PRE_SERVICE_TEACHER"]'::jsonb
        AND (policy_json::jsonb->>'applicationStartedAt')::TIMESTAMPTZ <
            (policy_json::jsonb->>'applicationEndedAt')::TIMESTAMPTZ
        AND (policy_json::jsonb->>'cancelEndedAt')::TIMESTAMPTZ >=
            (policy_json::jsonb->>'applicationStartedAt')::TIMESTAMPTZ
        AND (policy_json::jsonb->>'promotionEndedAt')::TIMESTAMPTZ >=
            (policy_json::jsonb->>'applicationStartedAt')::TIMESTAMPTZ
        AND (policy_json::jsonb->>'formId')::UUID IS NOT NULL
        AND jsonb_typeof(policy_json::jsonb->'closed') = 'boolean'
    ) IS TRUE
);
ALTER TABLE tb_training_program ADD CONSTRAINT ck_training_program_policy CHECK (
    policy_json IS NULL OR (
        (policy_json::jsonb->>'capacity')::BIGINT > 0
        AND (policy_json::jsonb->>'waitingCapacity')::BIGINT >= 0
        AND (policy_json::jsonb->>'groupId')::UUID = group_id
        AND jsonb_array_length(policy_json::jsonb->'allowedOccupations') > 0
        AND (policy_json::jsonb->'allowedOccupations') <@
            '["KINDERGARTEN_STUDENT","ELEMENTARY_STUDENT","MIDDLE_SCHOOL_STUDENT","HIGH_SCHOOL_STUDENT","SCHOOL_STAFF","PARENT","GENERAL","TEACHER","PRE_SERVICE_TEACHER"]'::jsonb
        AND (policy_json::jsonb->>'applicationStartedAt')::TIMESTAMPTZ <
            (policy_json::jsonb->>'applicationEndedAt')::TIMESTAMPTZ
        AND (policy_json::jsonb->>'cancelEndedAt')::TIMESTAMPTZ >=
            (policy_json::jsonb->>'applicationStartedAt')::TIMESTAMPTZ
        AND (policy_json::jsonb->>'promotionEndedAt')::TIMESTAMPTZ >=
            (policy_json::jsonb->>'applicationStartedAt')::TIMESTAMPTZ
        AND (policy_json::jsonb->>'formId')::UUID IS NOT NULL
        AND jsonb_typeof(policy_json::jsonb->'closed') = 'boolean'
    ) IS TRUE
);
