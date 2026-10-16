ALTER TABLE tb_standard_program DROP CONSTRAINT ck_standard_program_policy_channels;
ALTER TABLE tb_training_program DROP CONSTRAINT ck_training_program_policy_channels;
DROP FUNCTION program_policy_channels_valid(JSONB);

-- 이전 정책은 원본을 보존하고 전체 정원을 사전신청으로 이관한다. 현장 정원을 자동으로 열지 않는다.
CREATE TABLE tb_program_policy_v37_backup AS
SELECT 'STANDARD'::TEXT AS program_type, id AS program_id, policy_json
FROM tb_standard_program
WHERE policy_json IS NOT NULL AND (
    policy_json::jsonb ? 'channels'
    OR NOT (policy_json::jsonb ?| ARRAY['preRegistrationCapacity', 'onsiteCapacity'])
)
UNION ALL
SELECT 'TRAINING'::TEXT, id, policy_json
FROM tb_training_program
WHERE policy_json IS NOT NULL AND (
    policy_json::jsonb ? 'channels'
    OR NOT (policy_json::jsonb ?| ARRAY['preRegistrationCapacity', 'onsiteCapacity'])
);

UPDATE tb_standard_program p
SET policy_json = ((b.policy_json::jsonb - 'channels') ||
    CASE WHEN NOT (b.policy_json::jsonb ?| ARRAY['preRegistrationCapacity', 'onsiteCapacity'])
        THEN jsonb_build_object('preRegistrationCapacity', b.policy_json::jsonb -> 'capacity', 'onsiteCapacity', 0)
        ELSE '{}'::jsonb END)::TEXT
FROM tb_program_policy_v37_backup b
WHERE b.program_type = 'STANDARD' AND b.program_id = p.id;

UPDATE tb_training_program p
SET policy_json = ((b.policy_json::jsonb - 'channels') ||
    CASE WHEN NOT (b.policy_json::jsonb ?| ARRAY['preRegistrationCapacity', 'onsiteCapacity'])
        THEN jsonb_build_object('preRegistrationCapacity', b.policy_json::jsonb -> 'capacity', 'onsiteCapacity', 0)
        ELSE '{}'::jsonb END)::TEXT
FROM tb_program_policy_v37_backup b
WHERE b.program_type = 'TRAINING' AND b.program_id = p.id;

CREATE FUNCTION program_admission_capacities_valid(policy JSONB) RETURNS BOOLEAN
    LANGUAGE SQL IMMUTABLE AS $$
SELECT (
    NOT (policy ? 'channels')
    AND jsonb_typeof(policy -> 'preRegistrationCapacity') = 'number'
    AND jsonb_typeof(policy -> 'onsiteCapacity') = 'number'
    AND (policy ->> 'preRegistrationCapacity') ~ '^[0-9]+$'
    AND (policy ->> 'onsiteCapacity') ~ '^[0-9]+$'
    AND (policy ->> 'preRegistrationCapacity')::NUMERIC BETWEEN 0 AND 2147483647
    AND (policy ->> 'onsiteCapacity')::NUMERIC BETWEEN 0 AND 2147483647
    AND (policy ->> 'preRegistrationCapacity')::NUMERIC
        + (policy ->> 'onsiteCapacity')::NUMERIC = (policy ->> 'capacity')::NUMERIC
) IS TRUE
$$;

ALTER TABLE tb_standard_program ADD CONSTRAINT ck_standard_program_admission_capacities CHECK (
    policy_json IS NULL OR program_admission_capacities_valid(policy_json::jsonb)
);
ALTER TABLE tb_training_program ADD CONSTRAINT ck_training_program_admission_capacities CHECK (
    policy_json IS NULL OR program_admission_capacities_valid(policy_json::jsonb)
);
