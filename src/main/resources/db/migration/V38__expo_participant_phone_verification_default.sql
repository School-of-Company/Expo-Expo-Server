-- 사전·현장 등록은 박람회 생성 시점부터 참가자 휴대폰 인증을 요구한다(Application #170).
-- 관리자 로그인 상태에서도 인증 없이 등록되던 문제를 막는다. 필요하면 관리자가 박람회별로 다시 끌 수 있다.
ALTER TABLE tb_expo ALTER COLUMN participant_phone_verification_required SET DEFAULT TRUE;

-- 기존 박람회도 켠다. 값이 바뀐 행만 revision을 올려 신청 서비스가 변경을 알아차리게 한다.
UPDATE tb_expo
SET participant_phone_verification_required = TRUE,
    participant_phone_verification_revision = participant_phone_verification_revision + 1
WHERE participant_phone_verification_required = FALSE;
