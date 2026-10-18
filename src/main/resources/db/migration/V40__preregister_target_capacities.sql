-- 사전등록 대상 구분(교사·예비교사 / 그 외)별 회차 정원(#144).
-- 한쪽만 NULL이면 비교식이 NULL이 되어 CHECK를 통과하므로 IS TRUE로 막는다.
-- 기존 박람회·회차는 NULL(미설정, 기존 단일 정원 운영)로 둔다. 값을 임의로 채우거나 나누지 않는다.
ALTER TABLE tb_expo
    ADD COLUMN preregister_teacher_capacity INTEGER,
    ADD COLUMN preregister_non_teacher_capacity INTEGER,
    ADD CONSTRAINT ck_expo_preregister_target_capacities CHECK (
        (preregister_teacher_capacity IS NULL AND preregister_non_teacher_capacity IS NULL)
        OR (
            preregister_teacher_capacity >= 0
            AND preregister_non_teacher_capacity >= 0
            AND preregister_teacher_capacity::BIGINT + preregister_non_teacher_capacity::BIGINT BETWEEN 1 AND 2147483647
        ) IS TRUE
    );

ALTER TABLE tb_preregister_session
    ADD COLUMN teacher_capacity INTEGER,
    ADD COLUMN non_teacher_capacity INTEGER,
    ADD CONSTRAINT ck_preregister_session_target_capacities CHECK (
        (teacher_capacity IS NULL AND non_teacher_capacity IS NULL)
        OR (
            teacher_capacity >= 0
            AND non_teacher_capacity >= 0
            AND teacher_capacity::BIGINT + non_teacher_capacity::BIGINT = capacity
        ) IS TRUE
    );
