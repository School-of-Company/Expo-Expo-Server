# #256 구현 보고

브랜치: `feat/256-expo-member-role`, 시작점: `origin/develop@3d8cbdb` (#252 포함).
로컬 구현과 운영 SQL 문서 준비를 완료했다. 외부 서비스는 스텁으로 검증했다.
push·PR·운영 DB 이관은 수행하지 않는다.

## 이슈 완료 기준 (원문)

- [x] V44: `tb_expo_member(expo_id, admin_id, role, UNIQUE(expo_id, admin_id))`, `tb_expo_staff_invite(id, expo_id, label, secret_hash, expires_at, revoked_at)`. 두 표 모두 V31처럼 `expo_id`를 `REFERENCES tb_expo(id) ON DELETE CASCADE`로 둬서 박람회 삭제(`DeleteExpoServiceImpl`) 때 같이 지워지게 합니다.
- [x] 박람회 생성 시 생성자를 OWNER로 넣습니다(`CreateExpoServiceImpl.kt:73-77`의 취소·확정 부여를 대체).
- [x] OWNER는 박람회당 최대 3명, 마지막 OWNER는 내보내거나 강등할 수 없습니다. 동시 요청은 박람회 행 잠금으로 막습니다.
- [x] 운영진 API(OWNER): `GET·POST /expo/{id}/members`, `PATCH·DELETE /expo/{id}/members/{adminId}`.
- [x] 스태프 링크 API(OWNER): `POST·GET /expo/{id}/staff-invites`, `DELETE /expo/{id}/staff-invites/{inviteId}`(회수). 비밀값은 만들 때 한 번만 돌려주고 해시만 저장합니다.
- [x] 내부 `POST /internal/staff-invites/exchange`: User가 비밀값을 넘기면 유효한 링크의 `{inviteId, expoId, expiresAt}`를 돌려줍니다.
- [x] `admin-access`에 선택 파라미터 `permission`(`APPLICATION_READ`·`STATS_READ`·`CHECKIN`)을 받습니다.
  - 없으면 `APPLICATION_READ`(MANAGER 이상)로 판정하고 응답 문자열(`APPLICATION_READ`·`GLOBAL_ADMIN_APPLICATION_READ_V1`·`GLOBAL_ADMIN`)은 그대로 둡니다. 호출자 3곳이 문자열을 그대로 비교하므로 기존 호출은 바꾸지 않아도 됩니다.
  - `role=ROLE_STAFF` 토큰은 User `/admin/my`를 부르지 않고, `expoId` claim이 경로와 같고 링크가 유효하며 `permission=CHECKIN` 또는 `STATS_READ`일 때만 허용합니다.
- [ ] 취소·수동 확정 판정을 "MANAGER 이상"으로 바꾸고 응답·403 문구는 유지합니다. 이관이 끝나면 두 표와 엔티티를 지웁니다.
- [x] 공개 관리자 API에 역할 검사를 붙입니다. 조회 = 운영진 전원, 수정 = MANAGER 이상, 삭제 = OWNER 또는 최고 관리자. `/standard/{programId}`·`/training/{programId}`는 프로그램에서 박람회를 찾아 판정합니다. 이 두 GET은 참가자 이름이 나오므로 MANAGER 이상입니다.
- [x] `GET /expo`, `GET /expo/valid`는 내가 속한 박람회만 돌려주고, 목록 항목에 `myRole`을 싣습니다. 최고 관리자는 전체를 봅니다(User `/admin/my`의 `superAdmin`, School-of-Company/Expo-User-Server#X).
- [ ] 이관: 기존 박람회마다 `gsmstarup` = OWNER, 그 밖의 승인된 관리자 = MANAGER. Expo DB에는 관리자 ID 목록이 없으므로 User DB에서 ID를 뽑아 운영 SQL로 넣고, 전후 행 수를 기록합니다. 배포를 둘로 나눕니다. ① V44 표와 생성자 OWNER 부여만 배포 ② 이관 SQL 실행·행 수 확인 ③ 판정을 켜는 배포.
- [x] `docs/admin-application-cancel-access.md`의 "권한 관리 API는 제공하지 않는다" 전제를 고칩니다.

8번의 MANAGER 이상 판정·기존 응답/403 문구 유지는 구현했다.
기존 두 권한표·엔티티 제거는 운영 이관 검증과 롤백 기간 종료 이후의 후속 배포 조건이다.
11번은 요청에 따라 운영 실행 SQL만 문서로 준비했다. 실제 ID 추출·SQL 실행·행 수 기록은 운영 작업으로 남는다.
따라서 체크 해제 2개는 이 운영 조건을 뜻하며, 추가 사용자 질문으로 막힌 구현은 없다.

## 단계 배포 판단

`EXPO_MEMBER_ROLES_ENABLED=false`를 기본값으로 둔다.
V44 표 배포와 생성자 OWNER 저장 → 운영 SQL 실행 및 전후 행 수 검증 → true로 재시작 순서다.
플래그 없이 판정을 강제하면 이관 전 기존 관리자 접근이 차단되므로 플래그가 필요하다.
새 운영진·링크 관리 API는 false 상태에서도 OWNER 검사를 강제한다.
기존 권한표는 true 상태에서 사용하지 않는다.
운영 절차·SQL·서비스 계약은 [expo-member-roles.md](expo-member-roles.md)를 따른다.

## 검증 결과

- 최초 대상 명령: `throt run -- ./gradlew spotlessApply test --tests 'team.startup.expo.domain.expo.AdminExpoAccessPostgresHttpTests' --tests 'team.startup.expo.domain.expo.ExpoApiPostgresHttpTests' --no-daemon`.
  nullable JWT subject 두 곳의 컴파일 오류로 테스트 실행 전 중단했다. 수정 완료.
- 전체 검사 1회: `throt run -- ./gradlew spotlessApply build --no-daemon`.
  410개 중 408개 통과, 2개 실패, 생략 0개. 실행 시간 5분 37초.
  컴파일·jar 생성·Spotless·ktlint는 통과했다.
  실패는 valid 조회 수(1 → 2)와 Flyway 최신 버전 기대값(43 → 44)이었다.
  비활성 상태의 역할 조회를 생략해 기존 SQL 1회를 유지하고, 새 myRole·V44 기대값을 반영했다.
- 최종 대상 실행 1회:
  `throt run -- ./gradlew spotlessApply test --tests 'team.startup.expo.domain.expo.AdminExpoAccessPostgresHttpTests' --tests 'team.startup.expo.domain.expo.ExpoApiPostgresHttpTests' --tests 'team.startup.expo.domain.expo.presentation.dto.ExpoDtoContractTests' --tests 'team.startup.expo.persistence.ExpoPersistenceTests' --tests 'team.startup.expo.global.client.DownstreamCircuitBreakersTests' --no-daemon`.
  126개 전부 통과, 실패 0개, 생략 0개. 명령 실행 시간 55초.
  위 두 실패 수정과 최종 VIEWER CHECKIN 거절, Swagger 공개 유지도 이 실행에서 확인했다.
- 역할별 허용/거절, 행사 격리·목록/페이지/myRole·valid, 최고 관리자, 원본 토큰/내부 토큰,
  OWNER 상한 동시 변경·마지막 OWNER 보호, 스태프 교환·해시 저장·노출 제한·회수·만료·행사 범위,
  삭제 cascade, 생성자 OWNER, 기존 응답 문자열 및 DB migration을 검증했다.
- Kotlin/SQL 보안 검토: 새 쿼리는 JPA 바인딩을 사용하며 비밀값은 해시로 저장하고 로그 호출을 추가하지 않았다.
  실제 참가자 개인정보나 운영 비밀값은 픽스처에 추가하지 않았다.
- `git diff --check` 통과. 무거운 명령에는 모두 throt를 사용했다.
  throtd가 실행 중이지 않아 throt의 기본 동작대로 대기 없이 실행됐다.
- 요청한 횟수 제한에 따라 최종 수정 후 전체 검사는 반복하지 않았다. 운영 SQL과 실제 타 서비스 배포는 미검증이다.

## 변경 파일 (56개)

- [.env.example](../.env.example)
- [docs/admin-application-cancel-access.md](admin-application-cancel-access.md)
- [docs/expo-member-role-implementation.md](expo-member-role-implementation.md)
- [docs/expo-member-roles.md](expo-member-roles.md)
- [src/main/kotlin/team/startup/expo/domain/expo/entity/ExpoMember.kt](../src/main/kotlin/team/startup/expo/domain/expo/entity/ExpoMember.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/entity/ExpoStaffInvite.kt](../src/main/kotlin/team/startup/expo/domain/expo/entity/ExpoStaffInvite.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/presentation/ExpoMemberController.kt](../src/main/kotlin/team/startup/expo/domain/expo/presentation/ExpoMemberController.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/presentation/InternalAdminExpoAccessController.kt](../src/main/kotlin/team/startup/expo/domain/expo/presentation/InternalAdminExpoAccessController.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/presentation/InternalStaffInviteController.kt](../src/main/kotlin/team/startup/expo/domain/expo/presentation/InternalStaffInviteController.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/presentation/dto/request/ExpoMemberRequests.kt](../src/main/kotlin/team/startup/expo/domain/expo/presentation/dto/request/ExpoMemberRequests.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/presentation/dto/response/AdminExpoAccessResponse.kt](../src/main/kotlin/team/startup/expo/domain/expo/presentation/dto/response/AdminExpoAccessResponse.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/presentation/dto/response/ExpoMemberResponses.kt](../src/main/kotlin/team/startup/expo/domain/expo/presentation/dto/response/ExpoMemberResponses.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/presentation/dto/response/ExpoSummaryResponse.kt](../src/main/kotlin/team/startup/expo/domain/expo/presentation/dto/response/ExpoSummaryResponse.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/presentation/dto/response/ExpoValidationResponse.kt](../src/main/kotlin/team/startup/expo/domain/expo/presentation/dto/response/ExpoValidationResponse.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/repository/ExpoMemberRepository.kt](../src/main/kotlin/team/startup/expo/domain/expo/repository/ExpoMemberRepository.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/repository/ExpoRepository.kt](../src/main/kotlin/team/startup/expo/domain/expo/repository/ExpoRepository.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/repository/ExpoStaffInviteRepository.kt](../src/main/kotlin/team/startup/expo/domain/expo/repository/ExpoStaffInviteRepository.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/AdminAccessSupport.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/AdminAccessSupport.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/AdminAuthorityClient.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/AdminAuthorityClient.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/CreateExpoMemberService.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/CreateExpoMemberService.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/CreateExpoStaffInviteService.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/CreateExpoStaffInviteService.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/DeleteExpoMemberService.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/DeleteExpoMemberService.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/ExchangeStaffInviteService.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/ExchangeStaffInviteService.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/ExpoMemberAccessSupport.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/ExpoMemberAccessSupport.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/ExpoMemberSupport.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/ExpoMemberSupport.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/ExpoRoleSupport.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/ExpoRoleSupport.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/GetAdminExpoAccessService.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/GetAdminExpoAccessService.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/GetExpoMemberListService.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/GetExpoMemberListService.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/GetExpoStaffInviteListService.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/GetExpoStaffInviteListService.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/RevokeExpoStaffInviteService.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/RevokeExpoStaffInviteService.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/StaffInviteSupport.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/StaffInviteSupport.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/UpdateExpoMemberService.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/UpdateExpoMemberService.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/impl/CreateExpoMemberServiceImpl.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/impl/CreateExpoMemberServiceImpl.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/impl/CreateExpoServiceImpl.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/impl/CreateExpoServiceImpl.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/impl/CreateExpoStaffInviteServiceImpl.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/impl/CreateExpoStaffInviteServiceImpl.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/impl/DeleteExpoMemberServiceImpl.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/impl/DeleteExpoMemberServiceImpl.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/impl/ExchangeStaffInviteServiceImpl.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/impl/ExchangeStaffInviteServiceImpl.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/impl/GetAdminApplicationCancelAccessServiceImpl.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/impl/GetAdminApplicationCancelAccessServiceImpl.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/impl/GetAdminApplicationPromoteAccessServiceImpl.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/impl/GetAdminApplicationPromoteAccessServiceImpl.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/impl/GetAdminExpoAccessServiceImpl.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/impl/GetAdminExpoAccessServiceImpl.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/impl/GetExpoListServiceImpl.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/impl/GetExpoListServiceImpl.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/impl/GetExpoMemberListServiceImpl.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/impl/GetExpoMemberListServiceImpl.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/impl/GetExpoStaffInviteListServiceImpl.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/impl/GetExpoStaffInviteListServiceImpl.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/impl/GetExpoValidationServiceImpl.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/impl/GetExpoValidationServiceImpl.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/impl/RevokeExpoStaffInviteServiceImpl.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/impl/RevokeExpoStaffInviteServiceImpl.kt)
- [src/main/kotlin/team/startup/expo/domain/expo/service/impl/UpdateExpoMemberServiceImpl.kt](../src/main/kotlin/team/startup/expo/domain/expo/service/impl/UpdateExpoMemberServiceImpl.kt)
- [src/main/kotlin/team/startup/expo/domain/standard/repository/StandardProgramRepository.kt](../src/main/kotlin/team/startup/expo/domain/standard/repository/StandardProgramRepository.kt)
- [src/main/kotlin/team/startup/expo/global/security/ExpoRoleInterceptor.kt](../src/main/kotlin/team/startup/expo/global/security/ExpoRoleInterceptor.kt)
- [src/main/kotlin/team/startup/expo/global/security/SecurityConfig.kt](../src/main/kotlin/team/startup/expo/global/security/SecurityConfig.kt)
- [src/main/resources/application.yaml](../src/main/resources/application.yaml)
- [src/main/resources/db/migration/V44__expo_members_and_staff_invites.sql](../src/main/resources/db/migration/V44__expo_members_and_staff_invites.sql)
- [src/test/kotlin/team/startup/expo/domain/expo/AdminExpoAccessPostgresHttpTests.kt](../src/test/kotlin/team/startup/expo/domain/expo/AdminExpoAccessPostgresHttpTests.kt)
- [src/test/kotlin/team/startup/expo/domain/expo/ExpoApiPostgresHttpTests.kt](../src/test/kotlin/team/startup/expo/domain/expo/ExpoApiPostgresHttpTests.kt)
- [src/test/kotlin/team/startup/expo/domain/expo/presentation/dto/ExpoDtoContractTests.kt](../src/test/kotlin/team/startup/expo/domain/expo/presentation/dto/ExpoDtoContractTests.kt)
- [src/test/kotlin/team/startup/expo/persistence/ExpoPersistenceTests.kt](../src/test/kotlin/team/startup/expo/persistence/ExpoPersistenceTests.kt)
- [src/test/kotlin/team/startup/expo/support/TestJwt.kt](../src/test/kotlin/team/startup/expo/support/TestJwt.kt)
