# 박람회 기능 설정

관련: [#264](https://github.com/School-of-Company/Expo-Expo-Server/issues/264) 총괄, [#265](https://github.com/School-of-Company/Expo-Expo-Server/issues/265) F01 계약, [#266](https://github.com/School-of-Company/Expo-Expo-Server/issues/266) F02 저장·API, [#267](https://github.com/School-of-Company/Expo-Expo-Server/issues/267) F05 프로그램·준비 상태

Expo가 박람회별 기능 ON/OFF의 원본이다. 기능 목록과 의존 관계는 코드(`ExpoFeatureCatalog`)에 있고, 박람회별 선택값은 `tb_expo_feature`(`(expo_id, feature_key)` 유일), revision은 `tb_expo.feature_revision`에 있다. 프론트와 다른 서비스는 이 문서의 키·필드·오류 의미만 쓴다. 문자열 메시지로 분기하지 않는다.

## 카탈로그

그룹은 화면 분류일 뿐이다. 그룹을 켠다고 그 안의 기능이 켜지지 않는다. `order`는 응답 `features` 배열 순서다.

| 키 | 라벨 | 그룹 | 선행 | 새 박람회 | 기존 박람회 | 공개 조회 |
| --- | --- | --- | --- | --- | --- | --- |
| `PRE_REGISTRATION` | 사전등록 | REGISTRATION 참가 등록 | - | ON | ON | O |
| `FIELD_REGISTRATION` | 현장등록 | REGISTRATION | - | OFF | ON | O |
| `COMPANION_REGISTRATION` | 동행자 등록 | REGISTRATION | (사용 경로의 등록 기능, 아래) | OFF | ON | O |
| `QR_ADMISSION` | QR 입장 | ADMISSION 입장 | - | OFF | ON | O |
| `PAPER_QR` | 종이 QR 발급 | ADMISSION | `QR_ADMISSION` | OFF | ON | X |
| `STANDARD_PROGRAM` | 일반 프로그램 | PROGRAM 프로그램 | - | OFF¹ | ON | O |
| `TRAINING_PROGRAM` | 연수 프로그램 | PROGRAM | - | OFF¹ | ON | O |
| `STANDARD_ATTENDANCE` | 일반 프로그램 출석 | PROGRAM_ATTENDANCE 프로그램 출석 | `STANDARD_PROGRAM` | OFF | ON | X |
| `TRAINING_ATTENDANCE` | 연수 프로그램 출석 | PROGRAM_ATTENDANCE | `TRAINING_PROGRAM` | OFF | ON | X |
| `SURVEY` | 만족도 조사 | SURVEY 만족도·경품 | - | OFF | ON | O |
| `SURVEY_LOTTERY` | 설문 경품 추첨 | SURVEY | `SURVEY` | OFF | ON | O |
| `AUTO_NOTIFICATION` | 자동 알림 | NOTIFICATION 알림 | - | OFF | ON | X |
| `BULK_MESSAGE` | 단체 메시지 | NOTIFICATION | - | OFF | ON | X |

¹ 생성 요청에 그 종류의 프로그램이 있으면 ON으로 만든다(만든 프로그램이 바로 숨지 않게).

- **동행자 등록**: 선행 키를 두지 않는다. 동행자를 받는 요청은 `COMPANION_REGISTRATION`과 그 요청 경로의 등록 기능(사전 `PRE_REGISTRATION`, 현장 `FIELD_REGISTRATION`)이 모두 실효 ON이어야 한다. 둘 다 OFF면 관리자 응답의 준비 상태가 `NEEDS_SETUP`(`NO_REGISTRATION_PATH`)이다.
- **전화번호 인증**: 기능 행이 아니다. 기존 `participantPhoneVerificationRequired`/`participantPhoneVerificationRevision`을 그대로 쓰며 관리자 응답의 `authentication`에 따로 나온다. 인증번호(OTP) 발송은 `AUTO_NOTIFICATION`·`BULK_MESSAGE`와 무관하다.
- **경품 추첨**: `SURVEY_LOTTERY` 실효 ON과 Form의 기존 추첨 운영 설정(`/surveys/{expoId}/lottery`의 enabled·번호·순번)은 다른 상태다. 실제 추첨은 둘 다 만족해야 한다. 기능을 켜도 추첨 운영을 시작하지 않는다.
- **새 박람회 기본값**: 사전등록 ON, 나머지 OFF, 전화 인증은 현재 생성 정책(true) 그대로. 설정 화면(F09)을 공개하기 전에는 `EXPO_FEATURE_NEW_DEFAULTS_ENABLED=false`(기본)라 새 박람회도 모두 ON으로 만든다. 화면 공개와 함께 `true`로 바꾼다.
- **기존 박람회**: V45 이관이 13개 키를 모두 ON으로 넣는다. 행이 없는 키(나중에 추가한 키)는 그 키의 기존 기본값(지금은 모두 ON)으로 읽는다. 이관은 회차·설문·추첨 운영 설정과 전화 인증 값을 바꾸지 않는다.
- 허용 키는 카탈로그뿐이다. 알 수 없는 키는 400이며 DB에 쓰지 않는다. 카탈로그의 중복 키·없는 선행 키·순환은 앱 시작 때 실패한다(`ExpoFeatureGraph`).

## 상태 의미

| 필드 | 뜻 |
| --- | --- |
| `configuredEnabled` | 운영자가 고른 값. 부모가 꺼져도 자식 값은 그대로 남는다. |
| `effectiveEnabled` | `configuredEnabled`가 ON이고 모든 선행 기능이 실효 ON일 때만 ON. 정책 검사는 이 값만 쓴다. |
| `blockedBy` | 실효 OFF로 만든 직접 선행 기능. |
| `readiness.status` | `READY`: Expo가 확인할 수 있는 준비 조건을 갖췄다. `NEEDS_SETUP`: 운영자 설정이 더 필요하다. `UNAVAILABLE`: 진행 중인 변경 등으로 지금은 확인·사용할 수 없다. `UNKNOWN`: 외부 조회 실패로 판단하지 못했다. |
| `readiness.reasons[]` | `{code, message}`. 분기는 `code`로 한다. |

준비 상태는 ON/OFF와 따로 계산한다(OFF여도 준비를 확인할 수 있다). `UNKNOWN`을 OFF나 "설정 없음"으로 저장하거나 보여 주지 않는다. Expo가 외부 준비 정보를 모으는 기능은 프로그램 2개와 설문·추첨 2개다(아래 외부 준비 상태). 그 밖의 기능은 Expo가 판단하는 준비 조건이 없어 `READY`이며, 소유 서비스 쪽 준비 조건은 각 서비스가 같은 상태 이름으로 보여 준다.

### 변경 규칙

- 자식을 켜면 모든 조상 선행 기능의 선택값도 켠다(`applied.autoEnabled`).
- 부모를 끄면 자식 선택값은 남기고 실효만 끈다(`applied.deactivated`).
- 부모를 다시 켜면 남아 있던 자식이 실효 ON이 된다(`applied.reactivated`).
- 같은 요청에서 부모 OFF와 그 자식 ON을 함께 보내면 400이다.
- 공유 예제: [`docs/expo-feature-examples.json`](expo-feature-examples.json). 서버는 `ExpoFeatureCatalogTests`가 이 파일을 그대로 읽어 검사한다. 프론트도 같은 파일로 같은 결과를 얻어야 한다.

## 관리자 API

권한은 기존과 같다. `ROLE_ADMIN` JWT가 없으면 401, 다른 역할이면 403. 박람회 멤버 역할(`EXPO_MEMBER_ROLES_ENABLED`)이 켜져 있으면 `ExpoRoleInterceptor`가 GET은 READ, PATCH는 WRITE 권한을 요구한다. 기존 전체 교체 `PATCH /expo/{expo_id}`와 완전히 분리돼 있어 기능 저장이 프로그램을 지우거나 바꾸지 않는다.

### `GET /expo/{expo_id}/features`

```json
{
  "expoId": "0192f0c8-...",
  "revision": 3,
  "groups": [{ "key": "REGISTRATION", "label": "참가 등록" }],
  "features": [
    {
      "key": "STANDARD_PROGRAM", "label": "일반 프로그램", "description": "...", "group": "PROGRAM", "order": 5,
      "requires": [], "configuredEnabled": true, "effectiveEnabled": true, "blockedBy": [],
      "readiness": { "status": "READY", "reasons": [] },
      "programReadiness": {
        "totalSessions": 2, "readySessions": 1,
        "sessions": [
          { "programId": 1, "title": "1회차", "status": "READY", "reasons": [], "recruitment": "UPCOMING" },
          { "programId": 2, "title": "2회차", "status": "NEEDS_SETUP", "reasons": [{ "code": "FORM_NOT_FOUND", "message": "프로그램 폼을 찾지 못 했습니다." }], "recruitment": "OPEN" }
        ]
      }
    }
  ],
  "authentication": { "participantPhoneVerificationRequired": true, "participantPhoneVerificationRevision": 0 },
  "applied": null
}
```

없는 박람회 404. 삭제 중이어도 조회는 된다.

### `PATCH /expo/{expo_id}/features`

```json
{
  "expectedRevision": 3,
  "changes": [{ "key": "PAPER_QR", "enabled": true }],
  "participantPhoneVerificationRequired": false,
  "expectedPhoneRevision": 0
}
```

- `changes`는 바꿀 키만 보낸다(빈 배열 허용). 전화 인증은 바꿀 때만 두 필드를 함께 보낸다.
- 처리 순서: 요청 검증 → 박람회 행 잠금(`findLockedById`, 최대 5초) → 삭제 중·진행 중 프로그램 변경 확인 → revision 비교 → 변경안 계산 → 일괄 저장 → 같은 트랜잭션에서 `ExpoEventSupport.changed`.
- 응답은 GET과 같은 모양이며 `applied`에 `{autoEnabled, reactivated, deactivated, changed}`가 있다.
- 기능 선택값이 실제로 바뀔 때만 `revision`이 1 늘고, 기능이나 전화 인증 값이 실제로 바뀔 때만 이벤트를 쌓는다. 같은 값 재저장은 `changed: false`이며 revision·이벤트가 늘지 않는다.
- 프로그램 준비 상태는 커밋 뒤 잠금 밖에서 조회해 붙인다. 이 조회가 실패해도 저장은 그대로이고 해당 항목만 `UNKNOWN`이다.

| 상태 | 경우 | 저장 |
| --- | --- | --- |
| 400 `잘못된 요청입니다.` | `expectedRevision`/`changes` 누락, 음수 revision, `enabled`가 boolean이 아님, 키나 `enabled` 누락 | 없음 |
| 400 | 알 수 없는 키(`알 수 없는 기능입니다: KEY`), 같은 키 두 번, 부모 OFF+자식 ON 동시 요청, 전화 인증 값만 있고 `expectedPhoneRevision` 없음 | 없음 |
| 401 / 403 | 인증 없음 / 관리자 아님(또는 멤버 권한 부족) | 없음 |
| 404 | 없는 박람회 | 없음 |
| 409 | `expectedRevision` 또는 `expectedPhoneRevision`이 현재 값과 다름, 삭제 중, 진행 중인 프로그램 변경, 잠금 대기 5초 초과 | 없음(전화 인증 포함 부분 저장 없음) |

기존 `PATCH /expo/{expo_id}/participant-phone-verification`도 선택 필드 `expectedRevision`(전화 인증 revision)을 받는다. 보내면 같은 잠금 아래에서 비교해 다르면 409다. 보내지 않는 기존 호출은 그대로 동작한다. 두 경로 모두 같은 `participantPhoneVerificationRevision`을 쓰므로 한쪽 변경을 다른 쪽이 덮어쓰지 않는다.

## 공개 조회 `GET /expo/{expo_id}/public`

기존 필드에 `features`만 더했다. 공개 조회 열이 O인 키만 카탈로그 순서로 `[{ "key": "SURVEY", "effectiveEnabled": true }, ...]` 모양으로 싣는다(`configuredEnabled` 없음). 선택값·revision·준비 상태·관리자 전용 키(`PAPER_QR`, 출석, 알림)는 내보내지 않는다. 참가자 화면은 메뉴·링크를 숨기는 데만 쓴다. 서버 검사를 대신하지 않는다.

## 내부 정책 조회 계약 (소비 서버 기준)

User·Application·Attendance·Form·Notification은 신규 쓰기를 허용하기 전에 이 조회를 쓴다.

```http
GET /internal/expo/{expo_id}
X-Internal-Token: <EXPO_INTERNAL_TOKEN>
```

```json
{
  "title": "예시 박람회",
  "startedDay": "2030-01-01",
  "finishedDay": "2030-01-02",
  "participantPhoneVerificationRequired": true,
  "participantPhoneVerificationRevision": 0,
  "location": "광주 김대중컨벤션센터",
  "features": [
    { "key": "PRE_REGISTRATION", "configuredEnabled": true, "effectiveEnabled": true },
    { "key": "FIELD_REGISTRATION", "configuredEnabled": true, "effectiveEnabled": true },
    { "key": "COMPANION_REGISTRATION", "configuredEnabled": true, "effectiveEnabled": true },
    { "key": "QR_ADMISSION", "configuredEnabled": true, "effectiveEnabled": true },
    { "key": "PAPER_QR", "configuredEnabled": true, "effectiveEnabled": true },
    { "key": "STANDARD_PROGRAM", "configuredEnabled": true, "effectiveEnabled": true },
    { "key": "TRAINING_PROGRAM", "configuredEnabled": false, "effectiveEnabled": false },
    { "key": "STANDARD_ATTENDANCE", "configuredEnabled": true, "effectiveEnabled": true },
    { "key": "TRAINING_ATTENDANCE", "configuredEnabled": true, "effectiveEnabled": false },
    { "key": "SURVEY", "configuredEnabled": false, "effectiveEnabled": false },
    { "key": "SURVEY_LOTTERY", "configuredEnabled": true, "effectiveEnabled": false },
    { "key": "AUTO_NOTIFICATION", "configuredEnabled": true, "effectiveEnabled": true },
    { "key": "BULK_MESSAGE", "configuredEnabled": true, "effectiveEnabled": true }
  ],
  "featureRevision": 4
}
```

- `features`는 카탈로그 순서의 배열이며 **모든 키**를 한 번씩 싣는다. 항목은 `key`, `configuredEnabled`(운영자 선택), `effectiveEnabled`(선행 기능 반영 완료)다. 정책 검사는 `effectiveEnabled`만 쓴다. 선택값만 보면 부모 OFF를 놓친다. 소비자는 의존 관계를 다시 계산하지 않는다. `featureRevision`(long)은 로그·캐시 비교용이다.
- 형식 결정(2026-11-19): 처음 초안은 `{키: boolean}` 맵이었으나, 이미 구현된 소비자 Form PR [#303](https://github.com/School-of-Company/Expo-Form-Server/pull/303)이 이 배열 모양(`key`/`configuredEnabled`/`effectiveEnabled`)을 읽으므로 배열로 확정했다. 배열은 카탈로그 순서를 보존하고, 선택값과 실효 값을 함께 실어 관리 화면·진단에도 쓸 수 있다. 공개 응답과 이벤트 snapshot도 같은 모양을 쓴다.
- 잠금 없이 커밋된 값만 읽는다. 삭제 중인 박람회도 200이다(삭제 중 거절은 각 서비스의 기존 규칙).
- 기존 필드는 그대로이며 가산 확장이다.

소비자 해석 규칙:

| 조회 결과 | 신규 쓰기 |
| --- | --- |
| 200이고 `key == KEY`인 항목의 `effectiveEnabled == true` | 허용(기존 검사 계속) |
| 200이고 그 항목의 `effectiveEnabled == false` | **409**, 각 서비스의 기존 오류 envelope. 가능하면 사유 코드 `FEATURE_DISABLED` |
| 200인데 `features` 필드 없음·배열 아님, `KEY` 항목 없음, `effectiveEnabled`가 boolean 아님, JSON 해석 실패 | **503** (구형 Expo 응답. 허용으로 보지 않는다) |
| 404 | 각 서비스의 기존 "박람회 없음" 처리 |
| 401/403(토큰 오류), 5xx, 타임아웃, 연결 실패, 서킷 열림 | **503** (기본 ON/OFF로 대체하지 않는다) |

- 정책 조회는 소비 서버의 DB 잠금·트랜잭션 밖에서 한다. 잠금 안에서 원격 호출을 새로 넣지 않는다.
- `expo.expo.changed` projection의 `expo.features`는 화면 표시·지연 허용 작업용이다. projection이 늦거나 필드가 없다는 이유로 OFF 동작을 허용하지 않는다.
- 권한(401/403)은 기존 규칙이 먼저다. 기능 OFF가 권한 오류를 바꾸지 않는다.

프로그램 신청용 내부 조회(`GET /internal/expo/{id}/standard-programs/{program_id}`, `POST /internal/expo/{id}/training-programs/batch`)와 공개 목록(`GET /standard/program/{id}`, `GET /training/program/{id}`)은 항목마다 `featureEnabled`(그 종류 프로그램 기능의 실효 값)를 싣는다. `false`면 신규 신청을 409로 막는다. 빈 목록·404(없는 프로그램)와 구분된다. 관리자 조회·편집에는 필터를 걸지 않으므로 목록은 OFF여도 모든 회차를 돌려준다. 내부 조회에서 `featureEnabled`가 없으면 위 표처럼 503으로 본다.

## 행동 표

OFF는 **신규 사용**만 막는다. 준비용 관리, 내역 조회, 허용된 취소·철회, 이미 얻은 경품 수령, 이미 시작된 outbox·작업은 OFF만으로 막지 않는다. 컨트롤러 전체가 아니라 행동별로 검사한다. 진입점은 2026-11-19 각 저장소 기본 체크아웃(Application은 `origin/develop` 8b5226e)에서 확인한 것이며, 연결 이슈 작업자가 우회 경로를 다시 확인한다.

| 기능 | NEW_WRITE (OFF면 409) | SETUP (OFF여도 허용) | READ_HISTORY (허용) | CANCEL_WITHDRAW (허용) | PRIZE_PICKUP | BACKGROUND |
| --- | --- | --- | --- | --- | --- | --- |
| `PRE_REGISTRATION` | Application `POST /application/pre-standard/{expoId}`, `POST /application/{expoId}`(연수자 사전), `POST /application/expos/{expoId}/my-applications/preregister-sessions` | Expo 사전등록 회차 관리 `/expo/{id}/preregister-sessions/**` | 관리자 사전등록 조회·통계, `GET .../my-applications`, 대기 조회 | `.../my-applications/{type}/{id}/cancellation`, 대기 철회, 관리자 취소 | - | 이미 접수된 예약 재시도·회수(`/internal/expos/{id}/preregister-reservations/**`)는 계속 |
| `FIELD_REGISTRATION` | Application `POST /application/field/standard/{expoId}`, `POST /application/field/{expoId}` | - | 현장 방문 조회 | 현장 방문 취소 guard | - | 진행 중 현장 방문 복구 스케줄러 계속. Attendance `PUT /internal/expos/{id}/participants/{pid}/preregister-session/field-registration`, `PUT /internal/expos/{id}/participants/{pid}/field-visits/{registrationId}`는 진행 중 outbox 투영(Application 진입에서 이미 검사)이라 OFF여도 계속 |
| `COMPANION_REGISTRATION` | 동행자가 있는 등록 요청, `POST .../my-applications/PREREGISTER/{id}/companions` (해당 등록 경로 기능도 ON 필요) | - | 일행 조회 | 동행자 취소 | - | - |
| `QR_ADMISSION` | Attendance `PATCH /attendance/{expoId}`(입장 스캔), `PATCH /attendance/qr/{expoId}`(종이 QR·현장 코드 스캔). `PAPER_QR`가 OFF여도 `QR_ADMISSION`이 ON이면 이미 발급한 종이 QR·미리 인쇄한 현장 코드를 쓸 수 있다(#325) | - | 입장 집계 `/attendance/summary/**` | 퇴장 스캔 `PATCH /attendance/exit/{expoId}`는 이미 입장한 기록의 마무리라 허용 | - | - |
| `PAPER_QR` | Attendance `POST /qr-tokens/{expoId}`(발급) | - | `GET /qr-tokens/{expoId}`, 요약 | `PATCH /qr-tokens/{expoId}/revoke` | - | - |
| `STANDARD_PROGRAM` | Expo `POST /standard/application/{expo_id}`(구현됨), Application `POST /application/expos/{id}/program-sessions/STANDARD/{pid}/applications`·`onsite-applications`·`application-batches`·`onsite-application-batches` | Expo 회차·묶음·폼 연결·정원·모집 기간 편집(`/standard/**`, `PATCH /expo/{id}/programs/application-period`) | 목록·참가자 조회, 내 신청 조회 | 내 신청 취소, 관리자 취소 | - | 대기 승급·회차 변경 조정(`ProgramWaitlistScheduler`, reconciliation)은 계속 |
| `TRAINING_PROGRAM` | Expo `POST /training/application/{id}`, `POST /training/application/list`, `POST /training/application/list/trainee/{expo_id}`(빈 목록 교체=철회는 허용)(구현됨), Application 같은 경로 `TRAINING` | Expo `/training/**` 편집 | 목록·연수자 조회 | 취소·빈 목록 교체 | - | 연수 SMS outbox 발송 계속 |
| `STANDARD_ATTENDANCE` | Attendance `PATCH /attendance/standard/{programId}` | - | `/internal/program-attendances/**` 조회 | - | - | Application `SessionAttendanceScheduler` 집계는 기존 기록만 다룸 |
| `TRAINING_ATTENDANCE` | Attendance `PATCH /attendance/training/{programId}` | - | 같음 | - | - | 같음 |
| `SURVEY` | Form `POST /surveys/answer/standard/{expoId}`, `.../trainee/{expoId}`, `POST /surveys/answer/public/{expoId}` | 설문 편집 `POST/PATCH /surveys/{expoId}` | 응답 수·내 응답 상태 | - | - | 이미 받은 응답 relay·reconcile 계속 |
| `SURVEY_LOTTERY` | Form 설문 응답 제출(`POST /surveys/answer/standard/{expoId}`, `POST /surveys/answer/public/{expoId}`) 처리 중 새 추첨 번호 부여·추첨 | 추첨 운영 설정 `PATCH /surveys/{expoId}/lottery`, 핀 `PUT .../gift-pin` | `GET .../lottery` | - | 이미 당첨된 경품 수령 `POST /surveys/{expoId}/gift-pickup/confirm`·상태 조회는 OFF여도 허용 | 이미 확정된 당첨 결과 relay(`SurveyDrawResultRelayService`, `src/survey/survey-draw-result-relay.service.ts`)는 OFF여도 계속 |
| `AUTO_NOTIFICATION` | 새로 쌓는 업무 알림(Application `ApplicationNotificationPublisher`·`ProgramPromotionPublisher`, Notification `sms-kafka`·`promotion`·`promotion-offer` consumer의 발송 직전 재확인) | 채널·템플릿 설정 | 발송 내역 | - | - | 사업 상태 전이(승급·취소 등)는 계속하고 알림만 생략. OTP는 대상 아님 |
| `BULK_MESSAGE` | Application `POST /application/expos/{id}/admin-group-messages/previews/{previewId}/jobs`, `.../jobs/{jobId}/retries` → Notification `/internal/admin-group-messages/**` | `GET .../config`, 미리보기 `POST .../previews` | `GET .../jobs/{jobId}`, `.../history` | - | - | 이미 실행된 job은 끝까지 |

회차 정책(대기 정원, 현장 접수 기간, 자동 승급, 취소 기한)은 지금처럼 회차별 `policy`가 원본이다. 박람회 전역 스위치를 따로 만들지 않는다.

### 설정 변경과 진행 중 요청

- 설정 변경은 이미 받은 접수·출석·응답·발송을 되돌리지 않는다. 다시 켜도 회차를 만들거나 `closed`·모집 기간·정원을 바꾸지 않는다.
- 경합 경계: 소비 서버가 정책 조회에서 ON을 받은 뒤 자기 트랜잭션을 커밋하기 전에 관리자가 OFF로 바꾸면, 그 요청은 ON 기준으로 끝날 수 있다(정책 조회는 잠금 밖이라 서비스 간 즉시 취소를 약속하지 않는다). Expo가 직접 받는 프로그램 신청은 박람회 행 잠금 안에서 다시 확인하므로 OFF 커밋 뒤 잠금을 얻은 요청은 409다. 예약·발송·추첨처럼 시간이 지나 실행되는 작업은 실행 직전에 다시 조회한다.

## 외부 준비 상태 (F05·F07)

### 설문·추첨 준비 상태

`SURVEY`·`SURVEY_LOTTERY`의 준비 상태는 Form `POST /internal/surveys/summaries`(`{"expoIds":[id]}`) 응답을 근거로 한다. Form PR #303이 항목에 `lotteryEnabled`(추첨 운영 시작)와 `lotteryNumbersConfigured`(당첨 번호 지정)를 더했다.

| 기능 | 상태 |
| --- | --- |
| `SURVEY` | 설문이 1개 이상이면 `READY`, 없으면 `NEEDS_SETUP`(`NO_SURVEY`), 조회 실패면 `UNKNOWN`(`LOOKUP_FAILED`) |
| `SURVEY_LOTTERY` | `lotteryEnabled`와 `lotteryNumbersConfigured`가 모두 true인 설문이 있으면 `READY`. 아니면 `NEEDS_SETUP`(`LOTTERY_NOT_STARTED`, `LOTTERY_NUMBERS_MISSING`, 설문 없음은 `NO_SURVEY`). 두 필드가 없는 구형 Form 응답이나 조회 실패는 `UNKNOWN` |

기능 ON은 추첨 운영을 시작하지 않는다. 실제 추첨은 `SURVEY_LOTTERY` 실효 ON과 Form 추첨 운영 준비가 모두 필요하다.

### 프로그램 준비 상태

`STANDARD_PROGRAM`·`TRAINING_PROGRAM` 항목에만 `programReadiness`가 있다. 회차 판단은 기존 신청 검증(`ProgramPolicySupport.checkForms`: 정책 값, 폼 존재·행사·용도·참여 유형·기간, 대기 0일 때 승급 설정 금지, Application 지원 여부)을 그대로 쓴다. `/expo/valid`(폼 생성 여부 계약)는 기능 ON/OFF로 재해석하지 않는다.

| 회차 상태 | 사유 code |
| --- | --- |
| `READY` | 정책 없는 기존 회차(기존 신청 API), 또는 검증 통과 |
| `NEEDS_SETUP` | `FORM_NOT_FOUND`(폼 없음), `POLICY_INVALID`(정원·기간·대기/승급·폼 기간 불일치 등 기존 검증 실패) |
| `UNAVAILABLE` | `FORM_CHANGE_PENDING`(폼 변경 진행 중·복구 필요) |
| `UNKNOWN` | `LOOKUP_FAILED`(Form·Application 5xx, 연결 실패, 미설정) |

- `recruitment`(`NO_POLICY`/`UPCOMING`/`OPEN`/`ENDED`/`CLOSED`)는 정보용이다. 모집 전·후라는 이유로 `NEEDS_SETUP`이 되지 않는다.
- 기능 준비 상태: 회차 0개면 `NEEDS_SETUP`(`NO_SESSIONS`), 준비된 회차가 1개 이상이면 `READY`(모든 회차가 동시에 준비될 필요 없음). 준비된 회차가 없으면 `UNKNOWN` > `UNAVAILABLE` > `NEEDS_SETUP`(`NO_READY_SESSIONS`) 순으로 정한다.
- 원격 조회는 트랜잭션·박람회 행 잠금 밖에서 한다. 실패해도 저장·기능 값을 바꾸지 않는다.

## 새 기능 추가 체크리스트

1. `ExpoFeatureCatalog`에 키(`[A-Z][A-Z0-9_]*`, 바꾸지 않는다)·라벨·설명·그룹·선행 키·`newDefault`·`legacyDefault`(지금 동작 보존이면 `true`)·`participantVisible`을 더한다. 행이 없는 기존 박람회는 `legacyDefault`로 읽으므로 이관이 꼭 필요하지는 않다.
2. 그 기능을 실제로 쓰는 서비스의 신규 쓰기 진입점마다 정책 검사(위 내부 정책 조회 계약)를 넣고, 준비·내역·취소·이미 얻은 혜택 예외를 행동 표에 적는다.
3. 프론트 메뉴·라우트(Expo-Client `expoActions`·`expoRoutes`)를 키에 연결한다. 설정 화면은 카탈로그 응답으로 그리므로 행을 따로 만들지 않는다.
4. 테스트: `docs/expo-feature-examples.json`에 의존 예제를 더하고, 소비 서버에 ON/OFF/조회 장애(503)/구형 응답/보존 예외 테스트를 더한다.
5. 이 문서의 카탈로그 표와 행동 표를 갱신한다. 새 키는 `expo.expo.changed`의 `expo.features`에도 자동으로 실린다.
