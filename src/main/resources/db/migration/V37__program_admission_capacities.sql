ALTER TABLE tb_standard_program DROP CONSTRAINT ck_standard_program_policy_channels;
ALTER TABLE tb_training_program DROP CONSTRAINT ck_training_program_policy_channels;
DROP FUNCTION program_policy_channels_valid(JSONB);

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
