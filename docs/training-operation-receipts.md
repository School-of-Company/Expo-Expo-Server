# 연수 신청 작업과 SMS 복구 운영

관련: [Expo #64](https://github.com/School-of-Company/Expo-Expo-Server/issues/64), [Application #28 / PR #31](https://github.com/School-of-Company/Expo-Application-Server/pull/31), [Notification #8](https://github.com/School-of-Company/Expo-Notification-Server/issues/8).

## 신청 계약

공개 필드·본인 인증·기존 공개 201 빈 응답과 409 오류를 유지한다. 내부 ADD는 201, 내부 REPLACE는 204다.

단건 ADD, 다건 ADD, 등록 REPLACE 모두 최초 Application 쓰기 전에 UUID operationId, HTTP method/path, 정확한 정규화 명령과 expectedVersion을 PostgreSQL에 저장한다. 저장 실패는 Application 쓰기 없이 503이다. 프로그램/Form/User 검증 후 전체 신청 버전을 한 번 읽으며 재전달에서 기대 버전·프로그램 category·명령을 고치지 않는다.

공개 HTTP 호출은 각각 새 작업이다. 서버가 저장된 작업을 복구할 때만 동일 ID를 재사용한다. 공개 키가 없으므로 브라우저 retry와 새로운 의도를 완전히 구분하지 못한다. 서버 버전 조회는 오래된 화면이 관측한 버전까지 보호하지 않는다. 중복 ADD는 계속 409이며 자동 새 버전 재시도를 하지 않는다.

Application의 인증된 영수증 200에서 UUID/type/expoId/traineeId/status와 version/changed/정렬된 전체 programIds/completedAt을 검증한다. ADD의 전체 결과에는 요청 부분 목록이 포함되어야 하고 REPLACE는 요청 집합과 같아야 한다. 현재 목록·집합 지문·409·영수증 404는 성공 증거가 아니다.

정상 Application 성공 응답 뒤 영수증 조회 장애는 공개 성공을 뒤집지 않는다. SMS는 성공 영수증 확인 전 발행하지 않는다. `response_received`가 저장된 작업은 영수증 404여도 다시 쓰지 않고 조회만 반복한다. 응답 유실/재시작 뒤에는 같은 원본 명령으로 조회·재전달한다. 이미 성공한 과거 작업의 재전달은 취소/삭제/새 신청을 되돌리지 않는다.

## 내구 기록과 기간

기존 `tb_training_sms_outbox`를 V24로 확장한다. Application 성공과 SMS 발행 상태는 분리한다.

| 항목 | 정책 |
| --- | --- |
| application_state | PENDING: 확인 대기, SUCCEEDED: 검증 영수증, REJECTED: 최초 시도 명시 거절, HELD: 불확정 운영보류, LEGACY: 기존 무키 기록 |
| state | PREPARED/UNKNOWN, READY, SENT, REJECTED, SUPPRESSED, HELD |
| 명령 복구 | 30초 간격, 최초 저장부터 최대 15분 또는 30 recovery cycle. cycle당 영수증 조회와 필요 시 동일 명령 재전달 |
| 동기 영수증 조회 | 기존 5초 HTTP timeout, 한 번 조회. 추가 동기 retry 없음 |
| 문자 | 단건 제외. changed=false/빈 취소 제외. eventId=operationId, CUSTOM/TRAINEE, 수신자 1명 |
| 미발행 과거 성공 | 발행 전 현재 버전이 영수증 version과 다르면 HELD. 번호/본문 제거 |
| SMS 재생 | 최초 시도부터 최대 23시간. 본문 스냅샷 체류기간 때문에 생성 후 23시간 제한도 함께 적용 |
| 개인정보 payload | SENT/억제/보류 시 제거. 미완료 번호/본문도 생성 후 최대 23시간. 제거 후 새 번호/본문으로 같은 이벤트 재생 금지 |
| terminal 기록 | 성공/명시 거절/억제 기록은 terminal_at부터 30일 뒤 정리. 정리된 작업을 재생하거나 키 재사용하지 않음 |
| 불확정 기록 | 자동 삭제하지 않음. 15분/30회 뒤 쓰기를 멈추고 운영보류. 담당자가 7일 내 검토 |

같은 UUID/원본 JSON을 유지하는 lease/CAS와 `FOR UPDATE SKIP LOCKED`로 동시 worker를 선점한다. 만료된 worker의 token으로 새 worker의 결과를 덮어쓰지 못한다. 네트워크 대기 동안 DB transaction/connection을 붙잡지 않는다. 주기 작업은 DB 장애를 개인정보 없는 오류 종류로 기록하고 다음 실행을 기다린다.

최초 발행 시각을 Kafka send 전에 내구 저장한다. send 이후 ACK 유실/상태 기록 실패는 같은 ID·저장 JSON으로 복구한다. 이미 발행을 시도했다면 전화번호/본문을 다시 구성하거나 현재 신청에 맞춰 바꾸지 않는다. SENT는 broker ACK이며 실제 SMS 전달 완료가 아니다.

첫 발행 전 버전 확인과 취소가 경합할 수 있다. 이 구조는 취소 뒤 문자 발송이 절대 없거나 정확히 한 번 전달되는 것을 보장하지 않는다. Notification 완료 마커 만료/Redis 장애 뒤에는 동일 ID도 중복 발송될 수 있다.

## legacy 전환과 배포 순서

V23의 PREPARED/UNKNOWN에는 원본 명령/기대 버전이 없어 자동 keyed 복구하지 않는다. READY에도 내구 최초 발행 시각이 없어 이전 ACK 유실과 안전한 재생 기간을 판단하지 못하므로 `LEGACY_PUBLICATION_UNCERTAIN`으로 보류한다. 본문/payload를 지운다. 기존 SENT/REJECTED는 상태를 보존한다. 불확정 기록의 eventId를 과거 Application operationId라고 해석하지 않는다.

1. 대상 환경의 Application V4.1 migration 적용과 **모든 쓰기 인스턴스**의 PR #31 계약 지원을 확인한다. dev workflow 성공/PR 병합만으로 prod 또는 모든 인스턴스 전환을 판정하지 않는다. ADD/REPLACE/취소/프로그램 삭제 및 purge 경로, 구버전 프로세스/배치/운영 쓰기 도구까지 확인한다.
2. 구 Expo 신청 쓰기/relay를 drain하거나 중단하고 V24를 적용한다. #62 등 병행 작업의 migration 번호·배포 순서를 다시 대조한다. 이미 적용한 migration을 재번호하지 않는다.
3. 모든 Expo 쓰기 인스턴스를 전환한다. keyed 미완료 기록을 무키 호출·새 UUID·새 기대 버전으로 바꾸는 rollback은 금지한다. 구 binary로 rollback이 필요하면 신청 트래픽/relay를 먼저 중단하고 원본을 보존한다.
4. SMS는 비활성 상태에서 실제 Application·전용 DB 통합 검증을 완료한다.
5. Notification #8의 실제 배포 SHA, Kafka/Redis + 강제 fake sender, processing/done, Redis 완료 장애, DLQ 재생 기간, consumer group offset, TRAINEE 발신번호·승인 본문을 확인한다. 확인한 develop은 done 24시간이며 7일 변경 PR #7의 병합·배포를 별도로 확인해야 한다.
6. group 준비 후에만 SMS 활성화한다. 기존 offset 없는 group의 fromBeginning=false는 이전 발행을 놓칠 수 있다. 23시간 제한은 24시간의 1시간 여유일 뿐이며 clock/backlog/DLQ 지연 안전성의 증거를 대신하지 않는다.

기본 설정은 다음과 같다. 두 값을 true로 설정하는 것은 운영 검증 완료를 선언하는 배포 작업이며 이 변경에서 실제 활성화하지 않았다.

```dotenv
KAFKA_TRAINING_SMS_ENABLED=false
KAFKA_TRAINING_SMS_CONTRACT_VERIFIED=false
```

SMS enabled=true에 contract-verified=false면 시작을 거절한다. 실제 발송 공급자에 테스트를 연결하지 않는다. 설정 flag가 외부 서비스의 배포 상태를 자동 검사해 주지는 않는다.

## 운영보류 검토

Expo 운영 담당자는 배포 전에 실제 담당자와 기존 운영 알림/검토 절차를 지정한다. 담당 미지정 상태에서는 운영 활성화하지 않는다. 다음 읽기 전용 조회로 불확정 기록·재생 보류와 7일 검토 지연을 확인한다. token/전화번호/본문/command_json/receipt_json은 일반 로그나 이슈에 복사하지 않는다.

```sql
SELECT event_id, application_state, state, hold_reason,
       recovery_attempts, created_at, updated_at,
       created_at <= CURRENT_TIMESTAMP - INTERVAL '7 days' AS review_overdue
FROM tb_training_sms_outbox
WHERE application_state = 'HELD' OR state = 'HELD'
ORDER BY created_at;
```

- 새 keyed 불확정 작업은 인증된 `GET /internal/training-program-applications/operations/{원본 UUID}`로 확인한다. 404는 미호출/진행 중/실패를 구분하지 못한다. 최신 목록으로 성공을 대체 판정하지 않는다.
- 늦게 200을 확인한 경우 원본 명령/대상/버전/결과와 대조하고, 운영 승인과 감사 기록을 남긴 후 원래 기록에 결과를 반영하는 별도 통제 절차를 사용한다. 이 변경은 수동 재생 공개 API나 자동 성공 추정 SQL을 제공하지 않는다.
- 영수증이 없는 버전 충돌/명시 거절은 원본을 자동 수정하지 않는다. 신청자가 다시 확인하고 새 공개 요청을 보내야 한다. 불확정 원본을 먼저 삭제하고 새 ID로 복구하지 않는다.
- legacy 무키 기록은 영수증 복구가 불가능하다. 승인된 사용자/운영 확인 없이는 신청이나 문자를 자동 재실행하지 않는다.
- 발행 시도/ACK 여부가 불확실하거나 재생 기간이 지났으면 SMS 보류를 유지한다. 새 eventId로 중복 억제를 우회하지 않는다. 원문을 제거한 이벤트는 새 payload를 구성해 재생하지 않는다.
- 불확정 command의 장기 보존/삭제 요청/백업과 DLQ 보존은 7일 내 운영 검토에서 결정하고 감사 기록을 남긴다. 성공 확정 기록의 자동 30일 정리와 혼동하지 않는다. Application의 무기한 영수증 정리는 이 서비스가 소유하지 않는다.

## 검증 명령

명령 저장과 박람회 삭제 시작은 같은 박람회 행 잠금으로 직렬화한다. 삭제가 먼저 시작되면 명령을 저장하지 않고 409를 반환한다. PENDING/HELD 신청 또는 `LEGACY_UNCERTAIN`이 남아 있으면 삭제 표시와 원격 purge 전에 409를 반환하며, 영수증 확인이나 운영 확인 후 삭제를 재시도한다. 성공이 확인된 신청의 SMS 보류는 삭제를 막지 않는다. 이 잠금은 짧은 로컬 트랜잭션에서만 유지한다.

정상 Application 응답 뒤 영수증 조회가 실패하면 공개 성공 응답을 유지하고 선점을 해제한다. 복구는 영수증 조회만 수행한다. 보존 기간 유지보수는 30초 복구 스케줄러가 담당하며 SMS relay는 발행만 담당한다. relay 자체의 23시간 발행 제한은 유지한다.

```sh
./gradlew test --tests 'team.startup.expo.domain.training.TrainingApplicationHttpContractTests' --tests 'team.startup.expo.domain.training.TrainingSmsIntegrationTests'
./gradlew build --no-daemon
git diff --check
```

Testcontainers PostgreSQL과 embedded Kafka를 사용한다. HTTP 계약, 최초 호출 전 내구 저장, commit 뒤 응답 유실, 동일 명령 복구, 동시 선점, 취소/삭제 후 재신청, 늦은 REPLACE, 무변경/404, 버전·손상 영수증, 개인정보·보존 경계, legacy migration을 검증한다. 실제 Application pinned commit의 프로세스 재시작 검증은 로컬 `.omo/integration/verify-training-receipts.mjs`와 `.omo/reports/issue-64-integration/results.json`에 기록하며 `.omo`는 Git exclude 대상이다.
