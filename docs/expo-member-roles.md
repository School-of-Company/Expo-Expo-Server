# 박람회 운영진 역할 (#256)

기준: School-of-Company/Expo-Expo-Server#256. 선행 코드는 develop #252까지 포함한다.
다른 서비스 구현·배포는 별도이며 User 응답은 테스트에서 스텁으로 제공한다.

## 계약

운영진 표에는 OWNER·MANAGER·VIEWER만 저장한다. STAFF는 링크 기반 JWT이다.
OWNER·MANAGER는 수정·개인정보 조회·취소·수동 확정이 가능하다.
모든 운영진은 일반 조회·STATS_READ가 가능하다. CHECKIN은 OWNER·MANAGER·STAFF만 가능하다.
운영진·링크 관리와 행사 삭제는 OWNER 권한이다. OWNER는 최대 3명이며 마지막 OWNER는 제거·강등할 수 없다.
User `GET /admin/my`의 boolean `superAdmin:true`는 전체 행사 접근을 허용한다.
필드가 없거나 false면 일반 관리자이며 User 인증 실패는 우회하지 않는다.

- `GET /expo/{id}/members`: `[{adminId,role}]`
- `POST /expo/{id}/members`: `{adminId:양수,role:OWNER|MANAGER|VIEWER}`, 201
- `PATCH /expo/{id}/members/{adminId}`: `{role}`, 200
- `DELETE /expo/{id}/members/{adminId}`: 204
- `POST /expo/{id}/staff-invites`: `{label,expiresAt}`, 201
- `GET /expo/{id}/staff-invites`: 목록, secret은 null
- `DELETE /expo/{id}/staff-invites/{inviteId}`: 회수, 204, 반복 가능

링크 응답: `{inviteId,expoId,label,expiresAt,revokedAt,secret}`.
생성 때만 256비트 난수의 base64url 비밀값을 반환하며 SHA-256 해시만 저장한다.
label은 공백 외 1~100자, expiresAt은 미래 시각이다.
비밀값·토큰을 로그에 기록하지 않는다.

`POST /internal/staff-invites/exchange`는 내부 토큰과 `{secret}`을 받아
`{inviteId,expoId,expiresAt}`를 반환한다. 만료·회수 전까지 반복 교환할 수 있다.
User는 RS256 토큰을 `sub=staff:<inviteId>`, `role=ROLE_STAFF`, `expoId` claim으로 발급한다.
iat/exp는 15분 이하이며 링크 만료를 넘기지 않아야 한다.
Expo는 매 판정마다 링크 만료·회수를 확인하며 User /admin/my를 호출하지 않는다.

`GET /internal/expo/{id}/admin-access?permission=APPLICATION_READ|STATS_READ|CHECKIN`:
permission 생략은 APPLICATION_READ이며 MANAGER 이상만 허용한다.
기존 관리자 응답의 `APPLICATION_READ / GLOBAL_ADMIN_APPLICATION_READ_V1 / GLOBAL_ADMIN`을 유지한다.
명시한 permission은 응답에도 반영한다.
STAFF는 CHECKIN·STATS_READ만 허용한다. 응답은
`{expoId,adminId:null,inviteId,permission,policyVersion:"EXPO_STAFF_ACCESS_V1",scope:"EXPO_STAFF"}`이다.
내부 토큰과 원본 JWT를 모두 요구하고 캐시하지 않는다.

관리자 HTTP API는 MVC 인터셉터에서 검사한다. 기존 공개 경로는 공개를 유지한다.
`/standard/{programId}`·`/training/{programId}`는 프로그램의 행사에서 MANAGER 이상을 판정한다.
`GET /expo` 배열·페이지 및 `GET /expo/valid`는 소속 행사만 조회하고 항목에 `myRole`을 반환한다.
최고 관리자는 전체를 보며 비소속 행사 myRole은 null이다.

## 단계 배포

1. `EXPO_MEMBER_ROLES_ENABLED=false`(기본값)로 V44와 생성자 OWNER 저장을 배포한다.
   기존 전역 조회·수정과 취소·확정 허용표를 유지한다. 신규 운영진·링크 API는 이때도 OWNER 검사를 강제한다.
2. 생성·운영진 변경을 잠시 중지하고 아래 운영 SQL을 실행한다.
   User DB에서 승인된 관리자 ID와 gsmstarup의 실제 ID를 따로 추출한다.
   전후 행 수와 행사별 OWNER 수를 보관한다.
3. 검증 후 `EXPO_MEMBER_ROLES_ENABLED=true`로 재시작한다.
   User의 superAdmin 계약·서명 키를 확인한다.
4. User·Form·Attendance·Application·Report의 판정 연결 후 Client를 배포한다.
   새 승인 관리자는 OWNER가 추가하기 전까지 행사가 보이지 않는다고 안내한다.

판정 플래그는 이관 전 접근 차단을 피하기 위해 필요하다.
활성화 후 false로 되돌리면 전역 접근이 복원되므로 운영 승인을 거쳐야 한다.
기존 취소·확정 두 표·엔티티는 롤백 기간에 보존한다. 운영 이관 검증과 롤백 기간 종료 후
별도 배포에서 두 표·엔티티와 비활성 판정 경로를 제거한다.
운영 이관 완료를 코드 배포 시점에 알 수 없으므로 V44에서 기존 표를 삭제하지 않는다.

## 운영 SQL (Flyway에 포함하지 않는다)

User DB에서 추출한 승인 ID와 gsmstarup 여부만 임시 표에 넣는다. 실제 ID를 추측하지 않는다.
역할 판정 활성화 전에 실행한다. 활성화 후 역할을 덮어쓰는 작업에 재사용하지 않는다.

```sql
BEGIN;
LOCK TABLE tb_expo IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE tb_expo_member IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE approved_admins (
    admin_id BIGINT PRIMARY KEY CHECK (admin_id > 0),
    is_gsmstarup BOOLEAN NOT NULL
) ON COMMIT DROP;
-- 운영자가 User DB에서 추출한 승인 ID와 gsmstarup 여부를 INSERT 또는 COPY한다.
DO $$
BEGIN
    IF (SELECT count(*) FROM approved_admins WHERE is_gsmstarup) <> 1 THEN
        RAISE EXCEPTION '승인된 gsmstarup 한 명이 필요합니다';
    END IF;
END $$;
SELECT count(*) AS expo_count FROM tb_expo;
SELECT count(*) AS approved_admin_count FROM approved_admins;
SELECT count(*) AS members_before FROM tb_expo_member;
SELECT count(*) AS legacy_cancel_before FROM tb_admin_application_cancel_access;
SELECT count(*) AS legacy_promote_before FROM tb_admin_application_promote_access;

INSERT INTO tb_expo_member(expo_id,admin_id,role)
SELECT e.id,a.admin_id,CASE WHEN a.is_gsmstarup THEN 'OWNER' ELSE 'MANAGER' END
FROM tb_expo e CROSS JOIN approved_admins a
ON CONFLICT (expo_id,admin_id) DO UPDATE SET role=EXCLUDED.role;

SELECT count(*) AS members_after FROM tb_expo_member;
SELECT (SELECT count(*) FROM tb_expo)*count(*) AS expected_imported_members FROM approved_admins;
SELECT e.id,count(m.id) FILTER (WHERE m.role='OWNER') AS owners
FROM tb_expo e LEFT JOIN tb_expo_member m ON m.expo_id=e.id GROUP BY e.id ORDER BY e.id;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM tb_expo e CROSS JOIN approved_admins a
        LEFT JOIN tb_expo_member m ON m.expo_id=e.id AND m.admin_id=a.admin_id
        WHERE m.id IS NULL OR m.role<>CASE WHEN a.is_gsmstarup THEN 'OWNER' ELSE 'MANAGER' END
    ) OR EXISTS (
        SELECT 1 FROM tb_expo e LEFT JOIN tb_expo_member m ON m.expo_id=e.id
        GROUP BY e.id HAVING count(m.id) FILTER (WHERE m.role='OWNER') NOT BETWEEN 1 AND 3
    ) THEN
        RAISE EXCEPTION '이관 검증 실패';
    END IF;
END $$;
-- 전후 결과를 보관하고 기존 추가 운영진 행을 검토한 후 확정한다. 문제 발생 시 ROLLBACK한다.
COMMIT;
```
