-- 신청기간 null = 신청 불가, promotionEndedAt null = 모집 종료까지 승급, promotionMode 미지정 = AUTO
ALTER TABLE tb_standard_program DROP CONSTRAINT ck_standard_program_policy;
ALTER TABLE tb_training_program DROP CONSTRAINT ck_training_program_policy;

ALTER TABLE tb_standard_program ADD CONSTRAINT ck_standard_program_policy CHECK (
    policy_json IS NULL OR (
        (policy_json::jsonb->>'capacity')::BIGINT > 0
        AND (policy_json::jsonb->>'waitingCapacity')::BIGINT >= 0
        AND (policy_json::jsonb->>'groupId')::UUID = group_id
        AND jsonb_array_length(policy_json::jsonb->'allowedOccupations') > 0
        AND (policy_json::jsonb->'allowedOccupations') <@
            '["KINDERGARTEN_STUDENT","ELEMENTARY_STUDENT","MIDDLE_SCHOOL_STUDENT","HIGH_SCHOOL_STUDENT","SCHOOL_STAFF","PARENT","GENERAL","TEACHER","PRE_SERVICE_TEACHER"]'::jsonb
        AND (
            (policy_json::jsonb->>'applicationStartedAt' IS NULL AND policy_json::jsonb->>'applicationEndedAt' IS NULL)
            OR (
                (policy_json::jsonb->>'applicationStartedAt')::TIMESTAMPTZ <
                    (policy_json::jsonb->>'applicationEndedAt')::TIMESTAMPTZ
                AND (policy_json::jsonb->>'cancelEndedAt')::TIMESTAMPTZ >=
                    (policy_json::jsonb->>'applicationStartedAt')::TIMESTAMPTZ
                AND COALESCE((policy_json::jsonb->>'promotionEndedAt')::TIMESTAMPTZ >=
                    (policy_json::jsonb->>'applicationStartedAt')::TIMESTAMPTZ, TRUE)
            )
        )
        AND (policy_json::jsonb->>'cancelEndedAt')::TIMESTAMPTZ IS NOT NULL
        AND COALESCE(policy_json::jsonb->>'promotionMode', 'AUTO') IN ('AUTO', 'MANUAL')
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
        AND (
            (policy_json::jsonb->>'applicationStartedAt' IS NULL AND policy_json::jsonb->>'applicationEndedAt' IS NULL)
            OR (
                (policy_json::jsonb->>'applicationStartedAt')::TIMESTAMPTZ <
                    (policy_json::jsonb->>'applicationEndedAt')::TIMESTAMPTZ
                AND (policy_json::jsonb->>'cancelEndedAt')::TIMESTAMPTZ >=
                    (policy_json::jsonb->>'applicationStartedAt')::TIMESTAMPTZ
                AND COALESCE((policy_json::jsonb->>'promotionEndedAt')::TIMESTAMPTZ >=
                    (policy_json::jsonb->>'applicationStartedAt')::TIMESTAMPTZ, TRUE)
            )
        )
        AND (policy_json::jsonb->>'cancelEndedAt')::TIMESTAMPTZ IS NOT NULL
        AND COALESCE(policy_json::jsonb->>'promotionMode', 'AUTO') IN ('AUTO', 'MANUAL')
        AND (policy_json::jsonb->>'formId')::UUID IS NOT NULL
        AND jsonb_typeof(policy_json::jsonb->'closed') = 'boolean'
    ) IS TRUE
);
