# 관리자 신청 취소 권한 계약 (#93)

관련: Expo-Expo-Server#93, Expo-Application-Server#93. 모집 변경 이력(#94)은 별도 작업이다.

#94의 `V30__admin_change_history.sql`과 충돌하지 않도록 이 작업은
`V31__admin_application_cancel_access.sql`을 사용한다. 후속 병합 시 SecurityConfig의
#93 취소 권한 경로와 #94 이력 권한 경로를 모두 보존해야 한다.

`GET /internal/expo/{expoId}/admin-application-cancel-access`에
`X-Internal-Token`과 원본 `Authorization: Bearer <관리자 JWT>`를 함께 전달한다.
JWT의 서명, 역할, 주체, 발급/만료 및 15분 수명과 User `/admin/my`의 현재 관리자 여부를 검증한다.
이후 존재하는 행사에 대해 `tb_admin_application_cancel_access`의 `(expo_id, admin_id)` 허용 기록을 매번 조회한다.
전역 관리자나 기존 `APPLICATION_READ` 응답만으로 취소를 허용하지 않는다.

허용 응답(200, `Cache-Control: no-store`):

```json
{"expoId":"행사 ID","adminId":1,"permission":"APPLICATION_CANCEL","policyVersion":"EXPO_ADMIN_APPLICATION_CANCEL_V1","scope":"EXPO_ADMIN"}
```

- 401: 내부 토큰 누락/불일치, JWT 누락/위조/만료/잘못된 주체, User 인증 거절.
- 403: 관리자 역할 없음, 현재 관리자 권한 철회, 해당 행사 취소 허용 기록 없음.
- 404: 유효한 현재 관리자 요청의 행사가 없음.
- 503: User/DB 확인 실패 또는 삭제 중인 행사. 실패 시 허용하지 않는다.

정책 V1은 명시적 행사별 허용 목록이다. 초기 허용 기록은 없으며 기존 읽기 관리자를 자동 승격하지 않는다.
권한 관리 API는 이 작업에 포함하지 않는다. 운영 권한 부여/철회는 승인된 DB 작업으로 수행한다:

```sql
INSERT INTO tb_admin_application_cancel_access(expo_id, admin_id)
VALUES ('행사 ID', 1) ON CONFLICT (expo_id, admin_id) DO NOTHING;
DELETE FROM tb_admin_application_cancel_access WHERE expo_id = '행사 ID' AND admin_id = 1;
```

철회 트랜잭션 커밋 후 다음 HTTP 요청은 같은 JWT로도 403이다. 행사가 삭제되면 허용 기록도 삭제된다.
조회 결과를 캐시하거나 영구 취소 토큰으로 사용하지 않는다. 이미 진행 중인 취소와 철회를 원자적으로 묶는 계약은 아니다.

Application은 취소 직전에 이 경로를 호출하고 응답의 행사 ID, JWT 관리자 ID,
`permission`, `policyVersion`, `scope`를 모두 검증해야 한다.
기존 `/admin-access`의 `APPLICATION_READ`/`GLOBAL_ADMIN_APPLICATION_READ_V1` 응답은 취소 승인이 아니다.
대상 신청의 행사 소속 확인과 기존 취소 기한/출석/대기 승급 정책은 Application 책임이다.
강제 취소는 제공하지 않는다.

배포 순서: Expo 마이그레이션 및 API 배포, 명시적 허용 기록 설정, Application 소비 계약 연결/배포.
Application의 읽기 응답 거절과 실제 취소 통합 테스트는 Application 저장소에서 수행해야 한다.
이 문서가 전달할 확정 계약이며 GitHub 댓글 게시나 타 저장소 변경은 수행하지 않았다.
