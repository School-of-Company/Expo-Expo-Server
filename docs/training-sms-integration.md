# 연수 신청 완료 SMS

## 적용 범위와 계약

`KAFKA_TRAINING_SMS_ENABLED=true`일 때 `POST /training/application/list`,
`POST /training/application/list/trainee/{expo_id}`의 Application 성공 이후에만 Kafka 발행 대기 상태를 만든다.
URL, 요청 필드, 성공 `201` 빈 응답은 유지한다. 단건 신청에는 수신자 조회나 SMS가 없다.
기본값은 `false`다. 정책과 배포 설정을 확인한 뒤 활성화한다.

토픽 `notification.sms.requested`, Kafka key = `eventId`, JSON:

```json
{"eventId":"<durable UUID>","version":1,"type":"CUSTOM","phoneNumbers":["01098765432"],"senderType":"TRAINEE","text":"[박람회명] 연수 신청이 완료되었습니다.\n* 시작일시 ~ 종료일시: 프로그램명"}
```

Notification의 기존 CUSTOM 소비자를 재사용한다. QR 재발송 PR #7은 별개이며 의존하지 않는다.
Kafka ACK는 Notification 문자 발송 완료가 아니다. 문자 전부 실패 시 Notification 재시도/DLQ가 책임진다.
한 이벤트는 한 수신자만 담는다.

## 실제 수신번호

User resolve/resolve-or-create 응답은 `traineeId`만 주므로 요청 전화번호를 발송에 사용하지 않는다.
기존 `GET /internal/expos/{expoId}/trainees/details?cursor={traineeId-1}&size=1`을
`X-Internal-Token: TRAINING_INTERNAL_TOKEN`으로 조회한다. 반환 항목은 정확히 하나이고 ID가 일치해야 한다.
박람회별 조회이므로 다른 박람회의 연수자는 사용할 수 없다. 저장 번호의 숫자·공백·하이픈만 허용하고
공백/하이픈을 제거한 뒤 Notification의 `^01[0-9]{8,9}$` 계약을 검사한다.
번호 조회는 Application 성공이 확인된 READY의 발행 단계에서만 수행한다.
조회 실패/잘못된 번호는 SMS만 대기 상태로 남겨 재시도하며 신청 성공 `201`을 바꾸지 않는다.
첫 발행 전에 실제 번호를 확인하고 JSON을 별도로 커밋한 뒤 발행한다. 이후 복구는 같은 JSON을 재사용한다.
최초 조회 전 번호 변경은 반영하지만 이미 발행을 시도한 이벤트의 번호/본문은 바꾸지 않는다.

User resolve-or-create는 같은 박람회·전화번호의 기존 연수자를 재사용하며 요청 이름/연수번호로 덮어쓰지 않는다.
이는 기존 Java 정책과 같다. 실제 저장 번호를 사용하지만 연수번호 소유권 검증이나 본인 인증을 추가하지는 않는다.

## 내구성과 불확실성

신청 서비스는 Expo/프로그램 검증을 짧은 TransactionTemplate 구간에서 수행하고 연결을 반환한다.
User/Form/Application 외부 호출과 SMS 선점 대기 중에는 DB 트랜잭션·행 잠금·연결을 유지하지 않는다.
outbox 기록도 각 단계의 짧은 독립 트랜잭션으로 커밋하며 REQUIRES_NEW를 사용하지 않는다.
outbox는 Expo 외래 키를 두지 않아 외부 성공 확인 기록이 행사 삭제에 의해 사라지지 않도록 한다.

동시 신청은 Expo/연수자별 짧은 transaction advisory lock으로 outbox 선점을 결정하고,
30초 lease와 시도별 token으로 같은 연수자의 작업을 직렬화한다. 다른 요청은 연결을 반환한 상태에서
최대 5초 기다린다. journal 장애/선점 장기 대기에서는 SMS 기록 없이 Application만 호출하고 민감정보 없는
경고를 남긴다. 이 경우 신청 결과는 보존하지만 문자 복구 기록을 보장할 수 없다.
만료된 이전 시도의 늦은 상태 기록은 token 검사로 거절한다. 장시간 프로세스 정지 후 외부 호출 순서까지
보장하려면 Application의 operationId/fencing 계약이 필요하다.

Application URL/내부 토큰 누락은 외부 호출 전에 확인해 기존 `503`으로 응답하고 outbox를 만들지 않는다.
신청 실패를 기록하다 DB 장애가 나도 원래 Application 오류를 유지한다.
Application 성공 후 outbox 확정 장애가 나면 `201`을 유지하고 PREPARED/UNKNOWN을 수동 확인 대상으로 남긴다.
검증 후 행사/프로그램 변경·삭제와 외부 호출 사이에는 잠금을 유지하지 않는다. 외부 저장의 정원·삭제 경합은
Application의 기존 잠금/삭제 tombstone 계약을 사용하며 두 서비스 사이 원자성을 보장하지 않는다.

| 상태 | 의미 | 자동 Kafka 발행 |
|---|---|---|
| PREPARED | Application 호출 전 의도 저장. 호출 전 또는 호출/확정 도중 중단일 수 있음 | 없음 |
| UNKNOWN | 5xx/연결 실패/응답 유실 또는 PREPARED 재시도. 외부 성공 여부 미확정 | 없음 |
| REJECTED | 처음 시도의 명시적 400/404/409 거절 | 없음 |
| READY | Application ADD 201 / REPLACE 204를 관측하고 별도 커밋 완료 | 있음 |
| SENT | Kafka ACK를 관측하고 DB 기록 완료. 문자 전달 완료를 뜻하지 않음 | 없음 |

동일 연수자의 마지막 거절되지 않은 작업과 `(ADD/REPLACE, 정렬된 프로그램 ID 집합)`이 같으면
기존 eventId를 재사용한다. 요청 배열 순서/중복(REPLACE), 이름/전화번호 표현 변경은 새 SMS를 만들지 않는다.
A→B→A의 성공 변경은 세 작업/세 eventId다. 거절된 B 뒤 A 재시도는 A eventId를 재사용한다.
성공 뒤 동일 요청을 재시도해도 Application을 다시 호출하므로 기존 201/204/409 계약을 보존한다.
READY/SENT는 후속 409로 취소하지 않는다.

PREPARED 재시도는 이전 호출 성공 가능성이 있으므로 UNKNOWN으로 전환한다. UNKNOWN에서 409가 와도
이전 성공 증거가 아니므로 발행하지 않는다. 동일 REPLACE의 204를 다시 받으면 기존 eventId로 READY가 된다.
ADD의 응답 유실 후 409는 이미 신청/정원 초과를 구분할 수 없으며 수동 확인이 필요하다.
서버는 외부 Application 호출을 백그라운드에서 자동 재시도하지 않는다.

**외부 Application 트랜잭션과 Expo DB는 원자적이지 않다.** 외부 성공 직후 프로세스 중단, 확정 DB 장애,
HTTP 타임아웃에는 신청은 저장됐지만 SMS는 PREPARED/UNKNOWN에 남을 수 있다. outbox로 완전히 해결하지 못한다.
완전한 자동 복구에는 Application의 operationId 멱등 처리/성공 영수증 또는 변경 이벤트가 필요하다.
현재 계약에는 이 증거가 없다. 작업 식별은 이 두 Expo 경로가 관측한 변경에 한정한다.
단건 신청/다른 클라이언트/관리자 삭제/취소 후 동일 신청처럼 Expo가 관측하지 못한 실제 변경은 구분할 수 없다.
그 경우 새 신청 문자 보장은 Application 변경 버전 계약이 추가된 뒤 가능하다.

Relay는 5초 간격으로 최대 20건(설정 1~100)을 처리한다. 각 건을 짧은 `FOR UPDATE SKIP LOCKED` 구간에서
30초 lease/token으로 선점하고 연결을 반환한 뒤 User 조회·Kafka ACK 대기를 수행한다.
실제 처리량은 각 외부 호출 시간에 좌우되며 한 batch 전체를 긴 트랜잭션이나 비동기 일괄 발행으로 묶지 않는다.
발행 실패는 새 트랜잭션으로 기록하고 30초 뒤 같은 eventId/JSON으로 재시도한다.
Kafka ACK 후 SENT 기록 실패에서도 실패한 PostgreSQL 트랜잭션을 재사용하지 않는다.
기록 자체가 실패하면 lease 만료 후 다시 선점한다. 프로세스를 다시 시작해도 DB의 READY가 복구 대상이다.
Kafka ACK 후 DB 기록 전 중단/ACK 유실은 중복 Kafka 이벤트를 만들 수 있다.
Notification Redis guard는 완료를 **24시간만** 기억한다. 24시간 이후 재생, 문자 성공 후 Redis 완료 실패,
5분 processing lease 중 중복 메시지 처리에는 정확히 한 번 발송을 보장할 수 없다.
지연된 SMS는 성공 당시 프로그램 스냅샷이며 후속 변경/삭제에 의해 취소되지 않는다.

## 문구 결정안

기존 Java 핸들러는 필수 프로그램이 없으면 발송하지 않고, 날짜의 일(day)이 21/22인 선택 프로그램만 모아
21일 선택 1~2개는 1~2기, 22일 선택 1~4개는 3~6기를 안내한다. 각 날짜의 필수 프로그램도 필요하다.
`2025 AI광주미래교육`, `문의:380-4587`, `이수 조건:신청 시수 80% 이상`을 고정 출력한다.
월/연도/박람회 범위를 확인하지 않아 이를 그대로 다른 박람회에 적용하면 잘못된 안내가 된다.

현재 구현 문구는 확인 가능한 박람회명·선택한 모든 프로그램의 날짜/시간/제목만 포함한다.
2000자를 넘으면 일정을 임의로 잘라 안내하지 않고 `연수 신청이 완료되었습니다. 신청 내역은 신청 화면에서 확인해 주세요.`로 대체한다.
기수·문의번호·이수 조건은 생략하며 새로운 기수 정책으로 일반화하지 않는다.
활성화 전 결정안:

1. 일반 완료 안내를 승인하면 현재 문구를 사용한다. CHOICE만 있는 신청도 성공 안내를 받는다.
2. 기수 안내가 필요하면 **expoId + 정확한 날짜 + 프로그램 조합 → 기수/시수**의 행사별 정책을 제공한다.
   2025 행사의 기존 규칙을 유지할 경우 해당 expoId/월/연도도 명시해야 한다.
3. 문의번호는 행사별 승인 번호를 정한다. Notification의 QR 연락처 설정을 CUSTOM 문구 정책으로 추정하지 않는다.
4. 80% 이수 조건을 유지할 행사를 명시한다. 모든 행사에 공통 적용한다는 근거가 없으므로 현재는 보내지 않는다.

## 운영과 검증

환경: `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_TRAINING_SMS_ENABLED`,
`KAFKA_TRAINING_SMS_RELAY_DELAY_MS`, `KAFKA_TRAINING_SMS_BATCH_SIZE`,
`USER_SERVICE_URL`, `APPLICATION_SERVICE_URL`, `TRAINING_INTERNAL_TOKEN`.
Kafka 생산자는 기존 String serializer, `acks=all`, idempotence를 사용한다.
SMS 전용 template은 기본 producer 오류 본문 로깅을 끄고 복구 로그에 eventId만 남긴다. 실패메일은 만들지 않는다.
운영 Kafka ACL은 Expo의 해당 토픽 write만 허용하고 Notification의 기존 read 권한과 맞춘다.
User cursor 상세 API, Application ADD/REPLACE, Notification CUSTOM 소비자가 **실제로 배포**됐는지 확인해야 한다.
Expo 다건 신청 PR #48, 등록/교체 PR #52, 기존 Kafka 설정 PR #55는 기준 develop(edf846b)에 포함됐다.
새 PR을 만들 때 목표 base는 develop이며 이 선행 변경 위에 병합한다. Notification #7 merge/deploy는 전제 조건이 아니다.

outbox notification_text에는 안내 본문이 있다. 전화번호를 포함한 payload는 성공 확인된 READY의 발행 직전에 저장한다.
SENT/REJECTED에서 본문/payload를 지우고 ID/변경 기록은 남긴다. PREPARED/UNKNOWN에는 수신번호를 저장하지 않는다.
대기 중인 본문/payload는 복구를 위해 남으므로 DB/백업 접근을 제한한다.
장기 미확정 건의 개인정보 보존 기한과 수동 처리 담당자는 운영 결정이 필요하다.
미확정 건을 시간 경과만으로 READY로 바꾸지 않는다. 대상 Application 변경의 성공 증거를 확보하고,
이미 발송된 여부와 Notification의 24시간 제한을 검토한 뒤 동일 eventId로 수동 복구한다.
Application 현 상태만 같다는 사실은 특정 요청이 성공했다는 영수증이 아니다.

민감정보 없이 적체/불확실성을 조회할 수 있다:

```sql
SELECT state, count(*), min(created_at) FROM tb_training_sms_outbox GROUP BY state;
SELECT event_id, state, created_at, updated_at, attempts
FROM tb_training_sms_outbox WHERE state IN ('PREPARED', 'UNKNOWN', 'READY') ORDER BY sequence;
```

검증 명령:

```sh
./gradlew test --tests '*TrainingSmsIntegrationTests' --tests '*TrainingApplicationHttpContractTests' --no-daemon
./gradlew build --no-daemon
```

초기 연동 검증에서는 아래 고정 revision의 Notification 코드로 Expo의 실제 Kafka JSON을
schema/service/guard/store에 전달해 중복 억제, 실패 재시도, 24시간 만료를 확인했다.
Redis와 SMS sender는 메모리 가짜였으며 Solapi를 호출하지 않았다.
일회성 검증 스크립트와 내려받은 코드·의존성은 정리했다. 상대 서비스 작업 폴더/코드는 수정하지 않았다.
실제 배포 Notification consumer 프로세스 및 실제 Redis의 장애 동작까지 검증한 것은 아니다.
프로세스 중단 지점은 PREPARED 잔존/DB 확정 실패/ACK 뒤 상태 유실로 모사하며 OS 프로세스를 강제 종료하지 않는다.

조사 기준 소스:

후속 서비스 계약: [Notification #8](https://github.com/School-of-Company/Expo-Notification-Server/issues/8)은
CUSTOM 실연동·재처리·배포 조건을 추적한다. Application의 현재 프로그램 신청 DTO에는 operationId/변경 버전/성공 영수증이 없다.
기존 [Application #19](https://github.com/School-of-Company/Expo-Application-Server/issues/19)는 교체 API 구현으로 닫혔으며
이 추가 계약은 다루지 않는다. 완전한 외부 성공 판별과 관측하지 못한 재신청 식별은
[Application #28](https://github.com/School-of-Company/Expo-Application-Server/issues/28)에서
operationId 멱등 처리·성공 영수증·변경 버전 계약으로 추적한다. 해당 계약을 사용하는 Expo 전환은 Application 구현·배포 뒤 검증한다.
User는 기존 박람회별 상세 cursor API를 재사용하므로 추가 API 작업을 요구하지 않는다.

- [Notification schema/service/guard/store/docs, develop 1b2a598](https://github.com/School-of-Company/Expo-Notification-Server/tree/1b2a5980b9ce50c39a7bebf56eaedca0a1fc79f0/src/sms)
- [Notification 이벤트 문서](https://github.com/School-of-Company/Expo-Notification-Server/blob/1b2a5980b9ce50c39a7bebf56eaedca0a1fc79f0/docs/events-and-config.md)
- [User develop 47d738f 연수 계약](https://github.com/School-of-Company/Expo-User-Server/tree/47d738f4c740886f2acd1d217ebbf6973bcdf3a9/src/main/kotlin/team/startup/expo/domain/training)
- [Application develop de5e932 신청 계약](https://github.com/School-of-Company/Expo-Application-Server/tree/de5e9323619360d4e06addedb98e90b57f25fa9e/src/main/kotlin/team/startup/application/domain/application)
- [기존 Java TrainingSmsEventHandler 전체](https://github.com/School-of-Company/Expo-Server/blob/858101864235c9492fb2bd37ccf1df5346958d66/src/main/java/team/startup/expo/domain/training/event/handler/TrainingSmsEventHandler.java)
