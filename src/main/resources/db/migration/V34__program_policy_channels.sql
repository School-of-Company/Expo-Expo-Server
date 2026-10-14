-- 회차별 신청 채널: channels가 없으면 기존 단일 정원. 있으면 채널 사이 직종이 겹치지 않고,
-- capacity·waitingCapacity는 채널 합, allowedOccupations는 채널 직종 합집합과 같아야 한다.
-- CHECK에는 서브쿼리를 쓸 수 없어 IMMUTABLE 함수로 검사한다. 기존 ck_*_program_policy는 그대로 둔다.
CREATE FUNCTION program_policy_channels_valid(policy JSONB) RETURNS BOOLEAN
    LANGUAGE SQL IMMUTABLE AS $$
SELECT COALESCE(jsonb_typeof(policy -> 'channels'), 'null') = 'null' OR (
    jsonb_typeof(policy -> 'channels') = 'array'
    AND jsonb_array_length(policy -> 'channels') > 0
    AND (
        SELECT bool_and(
                jsonb_typeof(c -> 'name') = 'string'
                AND length(btrim(c ->> 'name')) BETWEEN 1 AND 50
                AND (c ->> 'capacity')::BIGINT > 0
                AND (c ->> 'waitingCapacity')::BIGINT >= 0
                AND jsonb_typeof(c -> 'allowedOccupations') = 'array'
                AND jsonb_array_length(c -> 'allowedOccupations') > 0
            )
            AND count(DISTINCT btrim(c ->> 'name')) = count(*)
            AND sum((c ->> 'capacity')::BIGINT) = (policy ->> 'capacity')::BIGINT
            AND sum((c ->> 'waitingCapacity')::BIGINT) = (policy ->> 'waitingCapacity')::BIGINT
        FROM jsonb_array_elements(policy -> 'channels') c
    ) IS TRUE
    AND (
        SELECT count(*) = count(DISTINCT o)
            AND jsonb_agg(o) @> (policy -> 'allowedOccupations')
            AND (policy -> 'allowedOccupations') @> jsonb_agg(o)
        FROM jsonb_array_elements(policy -> 'channels') c, jsonb_array_elements(c -> 'allowedOccupations') o
    ) IS TRUE
)
$$;

ALTER TABLE tb_standard_program ADD CONSTRAINT ck_standard_program_policy_channels CHECK (
    policy_json IS NULL OR program_policy_channels_valid(policy_json::jsonb)
);
ALTER TABLE tb_training_program ADD CONSTRAINT ck_training_program_policy_channels CHECK (
    policy_json IS NULL OR program_policy_channels_valid(policy_json::jsonb)
);
