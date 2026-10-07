# StandardCreated consumer (issue53)

## Deployment

The consumer is disabled by default. Set `KAFKA_STANDARD_CREATED_ENABLED=true`
and `KAFKA_BOOTSTRAP_SERVERS` to enable it. Enabling without bootstrap servers
fails application startup. Kafka authentication/TLS uses the standard Spring
Boot `spring.kafka` properties. Java 21, Kotlin 2.3.21 and Boot 4.1.1 remain
unchanged; Boot's BOM selects Spring Kafka 4.1.1.

Provision these topics before enabling the consumer:

- `user.standard-participant.created`
- `user.standard-participant.created.dlt`

The DLT may have a different partition count. Give the service read/group
permissions for the source and write permissions for the DLT. Restrict DLT
access because rejected payloads and exception headers are retained there.
Use a stable `KAFKA_STANDARD_CREATED_GROUP_ID` (default
`expo-expo-server.standard-created`); changing it replays retained events.
`earliest` applies only when the group has no committed offset. Source retention
must cover deployment delays and outages. Monitor consumer lag and the DLT.

User issue50 / PR51 is a predecessor. The pre-PR recheck confirmed it was
MERGED at `2026-10-07T03:16:09Z`, with head
`c90e504cf77366a6f1e90bc8492117b739974ffd`. Deployment remains unverified.
Its actual publisher produces `{eventId, expoId, participantId}` with key
`expoId:participantId`, but its default topic is still
`standard-participant.created`. User must configure
`KAFKA_REGISTRATION_STANDARD_CREATED_TOPIC=user.standard-participant.created`
and deploy the outbox migration/relay. Merging PR51 does not prove deployment.
This change does not modify User or its training/attendance/SMS code.
Expo issue45's ledger service is already in `develop` via Expo PR49
(`4e6861d`), with implementation commit `a19eda7`. This consumer starts from
`origin/develop` at `e31721e`; no additional Expo predecessor merge is needed.
Confirm Expo issue45's ledger migration and idempotent PUT are deployed before
User begins publishing these events. If retained events were already counted
by an older deployment without ledger rows, reconcile those rows before replay;
this consumer cannot infer historical counts that have no participant ledger.

## Delivery and recovery

The listener validates canonical UUID event/expo IDs, a positive signed 64-bit
integer participant ID and the exact key. It directly calls the existing
`RecordStandardRegistrationService`; no internal HTTP call or separate ledger
logic is used. The service's database transaction and expo row lock protect
the unique `(expo_id, participant_id)` ledger and counter, including concurrent
PUT requests. Missing, deleted and deleting expos are skipped.

Auto commit is disabled. `AckMode.RECORD` with synchronous commits commits only
after the service transaction returns. A crash/offset failure after DB commit
can redeliver; the ledger makes that redelivery add zero. There is no distributed
DB/Kafka transaction and no claim of exactly-once transport.

Transient data access, database resource/connection failures, unavailable
transaction creation and recoverable/transient SQL errors get five retries
at 1, 2, 4, 8 and 10 seconds (25 seconds total). Other failures, including
invalid messages, immediately go to the source topic plus `.dlt`. DLT publishing
uses `acks=all`, idempotent production and
`DeadLetterPublishingRecoverer.setFailIfSendResultIsError(true)`. If publication
fails, recovery throws and the source offset is retained for redelivery rather
than discarded. Uncertain send acknowledgments can produce duplicate DLT
records; recovery is at least once. Restore the broker/topic/ACL to unblock the
partition. A DLT record is replayed to the source with its original key and
payload after correcting the cause; it still uses the same registration ledger.
Retry classification examines exception causes and PostgreSQL connection
SQLStates (`08xxx`, `57P01`, `57P02`, `57P03`); a `57P01` during Hibernate flush
was observed as `JpaSystemException`, not a transient Spring exception. Permanent
SQL failures receive a zero-retry backoff through `DefaultErrorHandler`'s standard
backoff function.

Framework listener error logs are disabled because they can include rejected
records/parser or SQL exception text. Sanitized delivery logs contain only
partition, offset, attempt and exception class, without payload, keys, IDs or
exception text. DLT publication failures have an explicit error log. Spring
Kafka's stopped, failed-to-start and non-responsive events report operational
failures without logging their event source or throwable. Missing/deleting expo
skips record only partition, offset and status.

## Verification

`StandardCreatedKafkaTests` runs an actual in-process Kafka broker from
`spring-kafka-test` and Testcontainers PostgreSQL 17. It exercises recovery,
duplicates/already counted registrations, missing/deleting expos, concurrent
HTTP PUT, invalid message DLT, finite transient retries, exhausted retry DLT,
DB rollback/replay and real DLT topic deletion/restoration with offset checks.
It also terminates a real PostgreSQL connection mid-transaction, covers wrapped
SQL and transaction-creation failures, asserts skips produce no DLT records, and
checks sanitized DLT and abnormal-stop diagnostics. The abnormal-stop test
publishes a synthetic Spring event; it does not test real broker authentication.
Offset-loss recovery is simulated by rewinding the actual group's committed
offset after stopping the listener, then restarting it to replay the same record.
Transient service failures are injected with a Mockito spy; normal registration,
HTTP, database and Kafka processing use actual components.
`StandardCreatedConfigurationTests` covers disabled and missing-config startup.
These tests do not establish connectivity to deployed Kafka or interoperability
with a running User outbox relay.

Official references:

- [Boot-compatible dependency](https://docs.spring.io/spring-kafka/reference/quick-tour.html)
- [Record acknowledgment, retries and DLT failure handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)
- [Consumer lifecycle events](https://docs.spring.io/spring-kafka/reference/kafka/events.html)
- [PostgreSQL SQLState definitions](https://www.postgresql.org/docs/17/errcodes-appendix.html)
