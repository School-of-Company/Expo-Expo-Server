# 박람회 변경 이벤트 (`expo.expo.changed`)

관련: [Expo #212](https://github.com/School-of-Company/Expo-Expo-Server/issues/212)

박람회·일반 프로그램·연수 프로그램·사전등록 회차가 생성·변경·삭제되면 Expo는 그 박람회의 **변경 후 전체 상태**를 Kafka로 발행한다. 다른 서비스는 이 이벤트로 로컬 프로젝션(읽기 전용 복사본)을 만들어 `/internal/expo/**` 동기 조회를 줄인다. 이 문서는 발행 쪽 계약만 다룬다. 소비자와 프로젝션은 서비스별 이슈에서 만든다.

기존 `/internal/expo/**` 조회 API와 관리자 권한 확인(`/admin-access` 계열)은 그대로 동기로 남는다.

## 토픽과 키

| 항목 | 값                         |
| ---- | -------------------------- |
| 토픽 | `expo.expo.changed` (하나) |
| 키   | `expoId`                   |
| 값   | UTF-8 JSON 문자열          |

프로그램과 회차도 별도 토픽 없이 이 토픽에 박람회 단위로 실린다. 키가 `expoId`이므로 한 박람회의 모든 변경은 같은 파티션에 순서대로 쌓인다. 토픽은 운영에서 미리 만든다(브로커 자동 생성에 기대지 않는다).

## 메시지

```json
{
  "schemaVersion": 1,
  "eventId": "6f1c3a0e-8a43-4d4e-9a55-0d6f3e1b2c11",
  "version": 1042,
  "occurredAt": "2030-01-01T00:00:00.123456Z",
  "expoId": "0192f0c8-...",
  "deleted": false,
  "expo": {
    "title": "예시 박람회",
    "description": "설명",
    "startedDay": "2030-01-01",
    "finishedDay": "2030-01-02",
    "location": "광주 김대중컨벤션센터",
    "coverImage": "https://.../image/...",
    "x": "126.84",
    "y": "35.14",
    "preregisterTeacherCapacity": 10,
    "preregisterNonTeacherCapacity": 20,
    "participantPhoneVerificationRequired": true,
    "participantPhoneVerificationRevision": 0,
    "deleting": false,
    "programChangePending": false
  },
  "standardPrograms": [
    {
      "id": 1,
      "title": "일반 프로그램",
      "startedAt": "2030-01-01T09:00",
      "endedAt": "2030-01-01T10:00",
      "groupId": "00000000-0000-0000-0000-000000000001",
      "policy": null,
      "place": "1관",
      "revision": 1,
      "pendingRevision": null
    }
  ],
  "trainingPrograms": [
    {
      "id": 5,
      "title": "연수 프로그램",
      "startedAt": "2030-01-01T10:00",
      "endedAt": "2030-01-01T11:00",
      "category": "ESSENTIAL",
      "groupId": "00000000-0000-0000-0000-000000000002",
      "policy": null,
      "place": null,
      "revision": 1,
      "pendingRevision": null
    }
  ],
  "preregisterSessions": [
    {
      "id": 7,
      "title": "1회차",
      "startedAt": "2030-01-01T01:00:00Z",
      "endedAt": "2030-01-01T02:00:00Z",
      "place": "강당",
      "capacity": 30,
      "waitingCapacity": 5,
      "closed": false,
      "revision": 1,
      "cancelEndedAt": null,
      "teacherCapacity": 10,
      "nonTeacherCapacity": 20
    }
  ]
}
```

| 필드                           | 설명                                                                                                                                    |
| ------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- |
| `schemaVersion`                | 메시지 형식 버전. 지금은 `1`. 필드를 더하는 변경은 같은 버전에서 하고, 의미를 바꾸거나 빼는 변경만 올린다. 모르는 필드는 무시한다.      |
| `eventId`                      | 아웃박스 행마다 새 UUID. 릴레이가 같은 행을 다시 보내면 같은 값이다.                                                                               |
| `version`                      | 아웃박스 행의 sequence. Expo 전체에서 단조 증가하며, 같은 박람회 안에서는 커밋 순서와 같다. 박람회마다 연속하지는 않는다.               |
| `occurredAt`                   | 아웃박스에 쌓인 시각(UTC). 순서 판단에는 `version`을 쓴다.                                                                              |
| `deleted`                      | `true`면 tombstone. 아래 참고.                                                                                                          |
| `expo.deleting`                | 박람회 삭제가 시작돼 다른 서비스 정리를 기다리는 중. 삭제가 끝나면 tombstone이 이어진다. Expo는 이 상태에서 신청·수정을 409로 거절한다. |
| 프로그램 `startedAt`/`endedAt` | KST 지역 일시 `yyyy-MM-ddTHH:mm`. 예전 `yyyy-MM-dd HH:mm` 저장값도 이 형식으로 맞춘다.                                                  |
| 프로그램 `policy`              | 저장된 모집 정책 JSON 그대로(`ProgramPolicyRequest`와 같은 모양). 없으면 `null`.                                                        |
| 회차 일시                      | UTC ISO 8601 (`/internal/expo/{id}/preregister-sessions`와 같다).                                                                       |
| `revision`                     | 프로그램·회차 정의 버전. `/internal/**` 응답의 revision과 같다.                                                                         |
| `expo.programChangePending`    | 진행 중인 프로그램 변경(`tb_program_change` 행)이 있다. 이 동안 Expo는 프로그램 조회(`GET .../standard-programs/{id}`, `POST .../training-programs/batch`)를 409로 답한다(`ProgramPolicySupport.ensureAvailable`). 박람회 단위라 바뀌는 프로그램이 없는 변경(박람회 수정, 새 프로그램 추가)도 `true`다. 필드가 없는 예전 메시지는 `false`로 읽는다. |
| 프로그램 `pendingRevision`     | 진행 중 변경이 이 프로그램을 수정·삭제하면 그 다음 revision, 아니면 `null`. 관리자 목록 API의 `pendingRevision`과 같다. 409 판단에는 `expo.programChangePending`을 쓴다. |

배열은 프로그램은 id 순, 회차는 시작 일시·id 순이다. 메시지 하나가 그 박람회의 전부이므로 배열에 없는 프로그램·회차는 지워진 것이다. 신청 인원 집계(`applicationPerson`)는 싣지 않는다.

### 삭제(tombstone)

박람회 행이 지워지면 상태 없이 다음만 보낸다. Kafka 값 `null` tombstone이 아니라 JSON이다.

```json
{
  "schemaVersion": 1,
  "eventId": "...",
  "version": 1043,
  "occurredAt": "...",
  "expoId": "0192f0c8-...",
  "deleted": true
}
```

받는 쪽은 그 박람회의 프로젝션을 지우되 `version`은 기억해 두어, 늦게 도착한 옛 메시지로 되살리지 않는다.

## 발행 시점과 보장

1. 쓰기 경로는 같은 트랜잭션에서 `ExpoEventSupport.changed(expoId)`를 부른다. 커밋 직전에 박람회 행 락을 잡고 그 시점 상태로 스냅샷을 만들어 `tb_expo_event_outbox`에 한 행을 쓴다. 한 트랜잭션에서 여러 번 불러도 박람회마다 한 행이다. 롤백되면 행도 없다.
2. 릴레이(`PublishExpoEventsServiceImpl`)가 대기 행을 sequence 순으로 선점해 발행하고, broker ACK를 받으면 `PUBLISHED`로 표시한다. 한 박람회의 앞선 행이 남아 있으면 뒤 행은 기다린다.
3. 실패하면 1초부터 두 배씩 최대 5분 간격으로 다시 시도한다. 복구 작업(`RecoverExpoEventsServiceImpl`, 1분 주기)은 멈춘 선점을 풀고, 발행 7일 지난 행을 지우며, 10분 넘게 남은 대기 행이 있으면 ERROR 로그(`Expo events pending over 10 minutes`)를 남긴다.

전달은 at-least-once다. ACK 유실·재시작 뒤에는 같은 `eventId`/`version` 메시지가 다시 나갈 수 있다. 메시지 본문은 행에 저장된 값으로만 만들므로 재전송해도 내용이 같다.

이벤트를 쌓는 쓰기 경로:

- 박람회 생성(`CreateExpoServiceImpl`)
- 프로그램 변경 적용(`ApplyProgramChangeServiceImpl`): 박람회 수정, 일반·연수 프로그램 수정·삭제, 신청 기간 일괄 변경, 변경 재시도
- 일반·연수 프로그램 단건·목록 생성
- 사전등록 회차 생성(`CreatePreregisterSessionServiceImpl`)과 수정·삭제 적용(`ApplyPreregisterSessionChangeServiceImpl`)
- 참가자 전화번호 인증 설정 변경(값이 실제로 바뀔 때)
- 박람회 삭제 시작(`deleting: true`)과 삭제 완료(tombstone)
- 진행 중인 프로그램 변경의 생성과 해제(`programChangePending`): 생성은 `ProgramPolicySupport.stage`, 해제는 `ApplyProgramChangeServiceImpl`의 적용 완료, Application 거절(409)·적용 실패로 인한 해제, 삭제 프로그램의 원격 정리 완료. 그래서 프로그램 수정 한 번은 예약과 적용 두 건을 쌓는다.

일반 참가자 등록 집계(`StandardCreatedConsumer`)는 이벤트에 싣지 않는 인원 수만 바꾸므로 이벤트를 쌓지 않는다. 새 쓰기 경로를 더할 때는 같은 트랜잭션에서 `changed(expoId)`를 부른다.

## 받는 쪽 규칙

- `eventId`로 중복을 거른다.
- 박람회마다 마지막으로 반영한 `version`을 저장하고, 그 값 이하인 메시지는 버린다. 순서가 바뀌거나 재발행이 섞여도 최신 상태만 남는다.
- 메시지 하나로 그 박람회의 프로젝션 전체를 덮어쓴다(부분 병합하지 않는다).
- 최종 일관성이다. 발행 지연 동안 받는 쪽에는 몇 초까지 옛 값이 보일 수 있다. 박람회 기간처럼 접수 가능 여부를 가르는 값에서 이 지연을 감수할 수 있는지는 각 서비스가 판단한다.

## 처음 만들기·다시 만들기 (republish)

별도 스냅샷 조회 API는 두지 않는다. 받는 쪽이 프로젝션을 처음 만들거나 다시 만들 때는 consumer를 띄운 뒤 다음을 호출한다.

```http
POST /internal/expo-events/republish
X-Internal-Token: <EXPO_INTERNAL_TOKEN>
```

Expo는 모든 박람회(삭제 중 포함)의 현재 상태를 100개씩 나눠 아웃박스에 다시 싣고 `202 {"enqueued": n}`을 돌려준다. 발행은 릴레이가 이어서 한다. 재발행 메시지도 새 `eventId`·더 큰 `version`을 가지므로 위 규칙대로 처리하면 된다. 이미 지워진 박람회의 tombstone은 다시 보내지 않는다. 기능이 꺼져 있으면 503이다.

## 설정

| 환경 변수                          | 기본값  | 설명                                                                                                                        |
| ---------------------------------- | ------- | --------------------------------------------------------------------------------------------------------------------------- |
| `KAFKA_EXPO_EVENTS_ENABLED`        | `false` | 끄면 아웃박스에 쓰지 않고 릴레이·복구도 뜨지 않는다. 켜려면 `KAFKA_BOOTSTRAP_SERVERS`가 있어야 하며 없으면 시작을 거절한다. |
| `KAFKA_EXPO_EVENTS_RELAY_DELAY_MS` | `1000`  | 릴레이 주기                                                                                                                 |
| `KAFKA_EXPO_EVENTS_BATCH_SIZE`     | `100`   | 주기마다 발행할 최대 행 수(1~500)                                                                                           |

Producer 설정은 `spring.kafka.producer`(acks=all, idempotence)를 따른다. 꺼진 동안의 변경은 쌓이지 않으므로, 켠 뒤에는 republish로 전체 상태를 한 번 싣는다.

## 확인 SQL

```sql
SELECT state, count(*), min(sequence), max(attempts)
FROM tb_expo_event_outbox
GROUP BY state;
```

## 테스트

- `ExpoChangedEventTests`: 스냅샷 필드·정렬·일시 형식, 진행 중 변경(`programChangePending`, `pendingRevision`), 메시지 봉투와 tombstone
- `PublishExpoEventsServiceImplTests`: 발행 순서·키, 실패 시 재시도와 주기 중단, batch-size, bootstrap 필수
- `ExpoEventPostgresKafkaTests`(Testcontainers PostgreSQL + embedded Kafka): 생성·프로그램 수정(변경 예약과 적용)·적용 실패로 인한 변경 해제·회차 생성·전화번호 인증 경로의 아웃박스 기록, 롤백 시 미기록, tombstone, 박람회별 순서, 복구, republish 인증
- `ExpoEventDisabledPostgresTests`: 꺼져 있을 때 행·릴레이·재발행 없음
