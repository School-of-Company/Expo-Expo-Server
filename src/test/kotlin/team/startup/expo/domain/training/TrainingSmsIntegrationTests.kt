package team.startup.expo.domain.training

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.DeleteExpoService
import team.startup.expo.domain.training.entity.Category
import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramsRequest
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.repository.TrainingSmsOutboxRepository
import team.startup.expo.domain.training.service.PublishTrainingSmsService
import team.startup.expo.domain.training.service.RecoverTrainingOperationsService
import team.startup.expo.domain.training.service.TrainingApplicationCommand
import team.startup.expo.domain.training.service.TrainingApplicationSmsService
import team.startup.expo.domain.training.service.TrainingDependenciesClient
import team.startup.expo.domain.training.service.TrainingOperationType
import team.startup.expo.domain.training.service.TrainingProgramReference
import team.startup.expo.domain.training.service.TrainingTraineeReference
import team.startup.expo.domain.training.service.impl.ApplyTrainingProgramListServiceImpl
import team.startup.expo.domain.training.service.impl.PublishTrainingSmsServiceImpl
import team.startup.expo.domain.training.service.impl.RecoverTrainingOperationsServiceImpl
import team.startup.expo.global.exception.ExpectedException
import team.startup.expo.support.TestJwt
import team.startup.expo.support.TrainingApplicationStub
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "expo.training.sms.enabled=true",
        "expo.training.sms.contract-verified=true",
        "expo.training.operations.recovery-delay-ms=3600000",
        "expo.training.sms.relay-delay-ms=3600000",
        "spring.kafka.producer.properties.delivery.timeout.ms=2000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=1000",
    ],
)
@EmbeddedKafka(partitions = 1, topics = ["notification.sms.requested"], brokerProperties = ["auto.create.topics.enable=false"])
@EntityScan("team.startup.expo.domain")
@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
class TrainingSmsIntegrationTests {
    @LocalServerPort
    private var port = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var relay: PublishTrainingSmsService

    @Autowired
    private lateinit var recovery: RecoverTrainingOperationsService

    @Autowired
    private lateinit var outbox: TrainingSmsOutboxRepository

    @Autowired
    private lateinit var dependencies: TrainingDependenciesClient

    @Autowired
    private lateinit var expos: ExpoRepository

    @Autowired
    private lateinit var programs: TrainingProgramRepository

    @Autowired
    private lateinit var applicationSms: TrainingApplicationSmsService

    @Autowired
    private lateinit var deleteExpo: DeleteExpoService

    @Autowired
    private lateinit var dbTransactions: TransactionTemplate

    @Autowired
    private lateinit var broker: EmbeddedKafkaBroker

    @Autowired
    private lateinit var producers: ProducerFactory<String, String>

    @BeforeEach
    fun setup() {
        jdbc.execute("TRUNCATE TABLE tb_training_sms_outbox, tb_expo RESTART IDENTITY CASCADE")
        val day = LocalDate.now(ZoneId.of("Asia/Seoul")).toString()
        jdbc.update(
            """INSERT INTO tb_expo(id,title,description,started_day,finished_day,location,x,y,application_person,yesterday_application_person)
               VALUES (?,'테스트 박람회','description',?,?,'location','127','37',0,0)""",
            EXPO,
            day,
            day,
        )
        listOf("2026-09-24T09:00", "2026-09-25T09:00").forEach { start ->
            jdbc.update(
                """INSERT INTO tb_training_program(title,started_at,ended_at,category,expo_id)
                   VALUES ('연수',?,?,'CHOICE',?)""",
                start,
                start.replace("09:00", "10:00"),
                EXPO,
            )
        }
        recipientBody = """{"items":[{"traineeId":42,"phoneNumber":"010-9876-5432"}],"nextCursor":null}"""
        recipientStatus = 200
        receiptStatus = 200
        replaceStatus = 204
        addStatus = 201
        applicationCalls.set(0)
        recipientCalls.set(0)
        dropApplicationResponse = false
        applicationDelayMillis = 0
        applicationStub.reset()
        beforeApplicationWrite = { body ->
            val id = mapper.readTree(body).path("operationId").asString()
            jdbc.queryForObject("SELECT command_json FROM tb_training_sms_outbox WHERE event_id = ?", String::class.java, id) shouldBe body
        }
    }

    @Test
    fun `등록 교체 성공은 저장된 수신번호로 CUSTOM을 발행하고 동일 변경 재시도는 발행하지 않는다`() {
        consumer().use { consumer ->
            applyReplace("[2,1]").also {
                it.statusCode() shouldBe 201
                it.body() shouldBe ""
            }
            state() shouldBe "READY"
            relay.execute()
            val record = nextRecord(consumer)
            val event = mapper.readTree(record.value())
            record.key() shouldBe event.path("eventId").asString()
            event.properties().map { it.key }.toSet() shouldBe setOf("eventId", "version", "type", "phoneNumbers", "senderType", "text")
            event.path("version").asInt() shouldBe 1
            event.path("type").asString() shouldBe "CUSTOM"
            event.path("senderType").asString() shouldBe "TRAINEE"
            event.path("phoneNumbers")[0].asString() shouldBe "01098765432"
            val text = event.path("text").asString()
            text.contains("2026-09-24T09:00") shouldBe true
            text.contains("2026-09-25T09:00") shouldBe true
            listOf("기(", "380-4587", "80%", "2025 AI").any(text::contains) shouldBe false
            state() shouldBe "SENT"
            jdbc.queryForObject("SELECT payload FROM tb_training_sms_outbox", String::class.java) shouldBe null
            applyReplace("[1,2,1]").statusCode() shouldBe 201
            count() shouldBe 2L
            state() shouldBe "SUPPRESSED"
            relay.execute()
            consumer.poll(Duration.ofMillis(500)).isEmpty shouldBe true
            applicationCalls.get() shouldBe 2
        }
    }

    @Test
    fun `다건 성공만 SMS를 준비하고 단건은 수신자 조회도 하지 않는다`() {
        request("/training/application/1", """{"trainingId":"training-1"}""").statusCode() shouldBe 201
        count() shouldBe 1L
        state() shouldBe "SUPPRESSED"
        recipientCalls.get() shouldBe 0
        request("/training/application/list", """{"trainingId":"training-1","trainingProIds":[2]}""").statusCode() shouldBe 201
        state() shouldBe "READY"
        addStatus = 409
        request("/training/application/list", """{"trainingId":"training-1","trainingProIds":[2,1]}""").statusCode() shouldBe 409
        state() shouldBe "REJECTED"
        count() shouldBe 3L
    }

    @Test
    fun `A B A의 실제 신청 변경은 서로 다른 이벤트를 만들고 실패한 B 뒤 A 재시도는 중복시키지 않는다`() {
        applyReplace("[1]").statusCode() shouldBe 201
        val first = eventId()
        replaceStatus = 409
        applyReplace("[2]").statusCode() shouldBe 409
        applyReplace("[1]").statusCode() shouldBe 409
        count() shouldBe 3L
        replaceStatus = 204
        applyReplace("[2]").statusCode() shouldBe 201
        applyReplace("[1]").statusCode() shouldBe 201
        val ids =
            jdbc.queryForList(
                "SELECT event_id FROM tb_training_sms_outbox WHERE state = 'READY' ORDER BY sequence",
                String::class.java,
            )
        ids.size shouldBe 3
        ids.distinct().size shouldBe 3
        ids.first() shouldBe first
    }

    @Test
    fun `수신자 계약 오류는 신청 성공을 유지하고 발행만 대기시킨다`(output: CapturedOutput) {
        applyReplace("[1]").statusCode() shouldBe 201
        listOf(
            """{"items":[{"traineeId":43,"phoneNumber":"010-secret-recipient"}]}""",
            """{"items":[{"traineeId":42,"phoneNumber":"010-secret-recipient"}]}""",
            """{"items":[{"traineeId":42.5,"phoneNumber":"01098765432"}]}""",
            """{"items":[{"traineeId":"42","phoneNumber":"01098765432"}]}""",
            """{"items":[{"traineeId":42,"phoneNumber":1098765432}]}""",
            """{"items":[]}""",
            "invalid-sensitive-response",
        ).forEach { body ->
            recipientBody = body
            jdbc.update("UPDATE tb_training_sms_outbox SET next_attempt_at = CURRENT_TIMESTAMP")
            relay.execute()
            state() shouldBe "READY"
        }
        recipientStatus = 500
        jdbc.update("UPDATE tb_training_sms_outbox SET next_attempt_at = CURRENT_TIMESTAMP")
        relay.execute()
        count() shouldBe 1L
        applicationCalls.get() shouldBe 1
        recipientStatus = 200
        recipientBody = """{"items":[{"traineeId":42,"phoneNumber":"010-9876-5432"}],"nextCursor":null}"""
        jdbc.update("UPDATE tb_training_sms_outbox SET next_attempt_at = CURRENT_TIMESTAMP")
        relay.execute()
        state() shouldBe "SENT"
        output.all.contains("secret-recipient") shouldBe false
        output.all.contains("invalid-sensitive-response") shouldBe false
    }

    @Test
    fun `명시적 거절은 문자를 준비하지 않고 불확실한 실패는 자동발행하지 않는다`() {
        replaceStatus = 409
        applyReplace("[1]").statusCode() shouldBe 409
        state() shouldBe "REJECTED"
        replaceStatus = 500
        applyReplace("[1]").statusCode() shouldBe 502
        state() shouldBe "UNKNOWN"
        val id = eventId()
        relay.execute()
        state() shouldBe "UNKNOWN"
        replaceStatus = 409
        applyReplace("[1]").statusCode() shouldBe 409
        state() shouldBe "REJECTED"
        replaceStatus = 204
        applyReplace("[1]").statusCode() shouldBe 201
        state() shouldBe "READY"
        (eventId() != id) shouldBe true
    }

    @Test
    fun `commit 후 응답 유실은 영수증으로 복구하며 새 공개 ADD의 409와 구분한다`() {
        dropApplicationResponse = true
        request("/training/application/list", """{"trainingId":"training-1","trainingProIds":[1]}""").statusCode() shouldBe 503
        state() shouldBe "UNKNOWN"
        val original = eventId()
        dropApplicationResponse = false
        addStatus = 409
        request("/training/application/list", """{"trainingId":"training-1","trainingProIds":[1]}""").statusCode() shouldBe 409
        state() shouldBe "REJECTED"
        count() shouldBe 2L
        jdbc.update("UPDATE tb_training_sms_outbox SET recover_after = CURRENT_TIMESTAMP WHERE event_id = ?", original)
        recovery.execute()
        jdbc.queryForObject("SELECT state FROM tb_training_sms_outbox WHERE event_id = ?", String::class.java, original) shouldBe "READY"
        applicationStub.version shouldBe 1L
        applicationStub.programIds shouldBe setOf(1L)
    }

    @Test
    fun `재시작 복구는 최초 호출 전 PREPARED의 정확한 UUID 명령으로 재전달한다`() {
        val original = prepare(TrainingOperationType.ADD)
        jdbc.update(
            "UPDATE tb_training_sms_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second', recover_after = CURRENT_TIMESTAMP",
        )
        RecoverTrainingOperationsServiceImpl(outbox, dependencies).execute()
        state() shouldBe "READY"
        count() shouldBe 1L
        eventId() shouldBe original.eventId
        applicationStub.commands.single() shouldBe original.commandJson
    }

    @Test
    fun `DB 풀 두 연결로 외부 호출 중인 동시 요청 열두 개를 처리한다`() {
        applicationDelayMillis = 150
        val requests = (1..12).map { CompletableFuture.supplyAsync { applyReplace("[1]").statusCode() } }
        val statuses = requests.map { it.get(30, TimeUnit.SECONDS) }
        statuses.all { it in setOf(201, 409) } shouldBe true
        statuses.contains(201) shouldBe true
        count() shouldBe 12L
        applicationStub.version shouldBe 1L
        applicationCalls.get() shouldBe 12
    }

    @Test
    fun `외부 성공 뒤 로컬 확정 실패는 새 신청 없이 원본 영수증으로 복구한다`() {
        jdbc.execute(
            """CREATE FUNCTION fail_sms_confirm() RETURNS trigger AS ${'$'}body${'$'}
               BEGIN IF NEW.state = 'READY' THEN RAISE EXCEPTION 'forced confirmation failure'; END IF;
               RETURN NEW; END; ${'$'}body${'$'} LANGUAGE plpgsql""",
        )
        jdbc.execute(
            "CREATE TRIGGER fail_sms_confirm BEFORE UPDATE ON tb_training_sms_outbox FOR EACH ROW EXECUTE FUNCTION fail_sms_confirm()",
        )
        try {
            applyReplace("[1]").statusCode() shouldBe 201
            applicationCalls.get() shouldBe 1
            state() shouldBe "PREPARED"
            relay.execute()
            state() shouldBe "PREPARED"
        } finally {
            jdbc.execute("DROP TRIGGER fail_sms_confirm ON tb_training_sms_outbox")
            jdbc.execute("DROP FUNCTION fail_sms_confirm()")
        }
        val id = eventId()
        jdbc.update(
            "UPDATE tb_training_sms_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second', recover_after = CURRENT_TIMESTAMP",
        )
        recovery.execute()
        state() shouldBe "READY"
        eventId() shouldBe id
    }

    @Test
    fun `Kafka 장애는 내구 대기와 민감정보 없는 로그를 남기고 새 relay가 동일 키로 복구한다`(output: CapturedOutput) {
        applyReplace("[1]").statusCode() shouldBe 201
        val id = eventId()
        Admin.create(mapOf("bootstrap.servers" to broker.brokersAsString)).use { admin ->
            admin.deleteTopics(listOf(TOPIC)).all().get(10, TimeUnit.SECONDS)
            try {
                relay.execute()
                state() shouldBe "READY"
                jdbc.queryForObject("SELECT attempts FROM tb_training_sms_outbox", Int::class.java) shouldBe 1
                output.all.contains("Training SMS publication pending: eventId=$id") shouldBe true
                output.all.contains("01098765432") shouldBe false
                output.all.contains("테스트 박람회") shouldBe false
            } finally {
                admin.createTopics(listOf(NewTopic(TOPIC, 1, 1.toShort()))).all().get(10, TimeUnit.SECONDS)
            }
        }
        consumer().use { consumer ->
            jdbc.update("UPDATE tb_training_sms_outbox SET next_attempt_at = CURRENT_TIMESTAMP")
            val restarted = PublishTrainingSmsServiceImpl(outbox, producers, dependencies, mapper, contractVerified = true)
            try {
                restarted.execute()
            } finally {
                restarted.shutdown()
            }
            nextRecord(consumer).key() shouldBe id
            state() shouldBe "SENT"
        }
    }

    @Test
    fun `Kafka ACK 뒤 상태 저장 유실의 재발행도 같은 이벤트 키를 사용한다`() {
        applyReplace("[1]").statusCode() shouldBe 201
        val id = eventId()
        consumer().use { consumer ->
            jdbc.execute(
                """CREATE FUNCTION fail_sms_sent() RETURNS trigger AS ${'$'}body${'$'}
                   BEGIN IF NEW.state = 'SENT' THEN RAISE EXCEPTION 'forced sent failure'; END IF;
                   RETURN NEW; END; ${'$'}body${'$'} LANGUAGE plpgsql""",
            )
            jdbc.execute(
                "CREATE TRIGGER fail_sms_sent BEFORE UPDATE ON tb_training_sms_outbox FOR EACH ROW EXECUTE FUNCTION fail_sms_sent()",
            )
            try {
                relay.execute()
                state() shouldBe "READY"
                jdbc.queryForObject("SELECT attempts FROM tb_training_sms_outbox", Int::class.java) shouldBe 1
            } finally {
                jdbc.execute("DROP TRIGGER fail_sms_sent ON tb_training_sms_outbox")
                jdbc.execute("DROP FUNCTION fail_sms_sent()")
            }
            val first = nextRecord(consumer)
            jdbc.update("UPDATE tb_training_sms_outbox SET next_attempt_at = CURRENT_TIMESTAMP")
            relay.execute()
            val duplicate = nextRecord(consumer)
            first.key() shouldBe id
            duplicate.key() shouldBe id
            duplicate.value() shouldBe first.value()
            state() shouldBe "SENT"
        }
    }

    @Test
    fun `긴 문자 때문에 신청을 거절하지 않고 안전한 일반 완료 안내를 발행한다`() {
        jdbc.update("UPDATE tb_expo SET title = ?", "박람회".repeat(800))
        consumer().use { consumer ->
            applyReplace("[1,2]").statusCode() shouldBe 201
            relay.execute()
            val text = mapper.readTree(nextRecord(consumer).value()).path("text").asString()
            text shouldBe "연수 신청이 완료되었습니다. 신청 내역은 신청 화면에서 확인해 주세요."
            state() shouldBe "SENT"
        }
    }

    @Test
    fun `신청 준비 기록 장애는 Application 쓰기 전에 503이고 민감정보를 기록하지 않는다`(output: CapturedOutput) {
        jdbc.execute(
            """CREATE FUNCTION fail_sms_prepare() RETURNS trigger AS ${'$'}body${'$'}
               BEGIN RAISE EXCEPTION 'sensitive-prepare-marker'; END; ${'$'}body${'$'} LANGUAGE plpgsql""",
        )
        jdbc.execute(
            "CREATE TRIGGER fail_sms_prepare BEFORE INSERT ON tb_training_sms_outbox FOR EACH ROW EXECUTE FUNCTION fail_sms_prepare()",
        )
        try {
            applyReplace("[1]").statusCode() shouldBe 503
            applicationCalls.get() shouldBe 0
            count() shouldBe 0L
            output.all.contains("Training operation preparation failed") shouldBe true
            output.all.contains("sensitive-prepare-marker") shouldBe false
        } finally {
            jdbc.execute("DROP TRIGGER fail_sms_prepare ON tb_training_sms_outbox")
            jdbc.execute("DROP FUNCTION fail_sms_prepare()")
        }
    }

    @Test
    fun `실패 기록 DB 장애가 Application의 원래 409를 덮어쓰지 않는다`() {
        jdbc.execute(
            """CREATE FUNCTION fail_sms_rejection() RETURNS trigger AS ${'$'}body${'$'}
               BEGIN IF NEW.state = 'REJECTED' THEN RAISE EXCEPTION 'forced rejection failure'; END IF;
               RETURN NEW; END; ${'$'}body${'$'} LANGUAGE plpgsql""",
        )
        jdbc.execute(
            "CREATE TRIGGER fail_sms_rejection BEFORE UPDATE ON tb_training_sms_outbox FOR EACH ROW EXECUTE FUNCTION fail_sms_rejection()",
        )
        try {
            replaceStatus = 409
            val response = applyReplace("[1]")
            response.statusCode() shouldBe 409
            mapper.readTree(response.body()).path("message").asString() shouldBe "삭제됐거나 정원이 찬 연수 프로그램이 있습니다."
            state() shouldBe "PREPARED"
        } finally {
            jdbc.execute("DROP TRIGGER fail_sms_rejection ON tb_training_sms_outbox")
            jdbc.execute("DROP FUNCTION fail_sms_rejection()")
        }
    }

    @Test
    fun `발행 직전에 저장 번호를 조회하되 발행 복구에는 같은 페이로드를 사용한다`() {
        applyReplace("[1]").statusCode() shouldBe 201
        recipientCalls.get() shouldBe 0
        recipientBody = """{"items":[{"traineeId":42,"phoneNumber":"010-8888-7777"}],"nextCursor":null}"""
        consumer().use { consumer ->
            relay.execute()
            val first = nextRecord(consumer)
            mapper.readTree(first.value()).path("phoneNumbers")[0].asString() shouldBe "01088887777"
            recipientBody = """{"items":[{"traineeId":42,"phoneNumber":"010-9999-6666"}],"nextCursor":null}"""
            jdbc.update("UPDATE tb_training_sms_outbox SET state = 'READY', payload = ?", first.value())
            relay.execute()
            nextRecord(consumer).value() shouldBe first.value()
            recipientCalls.get() shouldBe 1
        }
    }

    @Test
    fun `한 번의 발행 실행은 여러 건을 처리하되 batch 상한을 지킨다`() {
        repeat(21) { index ->
            jdbc.update(
                """INSERT INTO tb_training_sms_outbox(event_id,expo_id,trainee_id,fingerprint,state,notification_text)
                   VALUES (?,?,42,?,'READY','신청 완료')""",
                UUID.randomUUID().toString(),
                EXPO,
                "REPLACE:$index",
            )
        }
        jdbc.update(
            """UPDATE tb_training_sms_outbox SET application_state = 'SUCCEEDED', command_schema_version = 1,
            operation_type = 'ADD', request_method = 'POST', request_path = '/internal/training-program-applications',
            command_json = '{}', expected_version = 0, receipt_version = 0""",
        )
        consumer().use { consumer ->
            relay.execute()
            jdbc.queryForObject("SELECT count(*) FROM tb_training_sms_outbox WHERE state = 'SENT'", Long::class.java) shouldBe 20L
            val received = mutableSetOf<String>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (received.size < 20 && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(100)).forEach { received.add(it.key()) }
            }
            received.size shouldBe 20
            relay.execute()
            nextRecord(consumer).key().let { it in received } shouldBe false
            jdbc.queryForObject("SELECT count(*) FROM tb_training_sms_outbox WHERE state = 'SENT'", Long::class.java) shouldBe 21L
        }
    }

    @Test
    fun `만료된 신청 시도의 늦은 확정은 새 시도의 상태를 덮어쓰지 않는다`() {
        val first = prepare(TrainingOperationType.REPLACE)
        dependencies.executeOperation(first)
        val receipt = requireNotNull(dependencies.operationReceipt(first))
        jdbc.update(
            "UPDATE tb_training_sms_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second', recover_after = CURRENT_TIMESTAMP",
        )
        val second = requireNotNull(outbox.claimRecovery())
        first.eventId shouldBe second.eventId
        outbox.confirmed(first, receipt)
        state() shouldBe "PREPARED"
        outbox.confirmed(second, receipt)
        state() shouldBe "READY"
    }

    @Test
    fun `Application 설정 누락은 HTTP 호출과 SMS 준비 기록 전에 실패한다`() {
        val missing =
            TrainingDependenciesClient(
                "http://127.0.0.1:${upstream.address.port}",
                "",
                "test-training-token",
                "",
                mapper,
                team.startup.expo.global.attendance
                    .ProgramAttendanceClient("", "", mapper),
            )
        val service = ApplyTrainingProgramListServiceImpl(expos, programs, missing, applicationSms, dbTransactions)
        val error = assertThrows(ExpectedException::class.java) { service.execute(ApplyTrainingProgramsRequest("training-1", listOf(1))) }
        error.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
        count() shouldBe 0L
        applicationCalls.get() shouldBe 0
        recipientCalls.get() shouldBe 0
    }

    @Test
    fun `발행 선점은 중복 작업자를 막고 만료 뒤 같은 이벤트를 복구한다`() {
        applyReplace("[1]").statusCode() shouldBe 201
        val old = requireNotNull(outbox.claim())
        outbox.claim() shouldBe null
        jdbc.update("UPDATE tb_training_sms_outbox SET publish_until = CURRENT_TIMESTAMP - INTERVAL '1 second'")
        val recovered = requireNotNull(outbox.claim())
        recovered.eventId shouldBe old.eventId
        outbox.sent(old)
        state() shouldBe "READY"
        outbox.sent(recovered)
        state() shouldBe "SENT"
    }

    @Test
    fun `영수증 조회 장애는 성공 응답을 보존하고 선점을 해제하여 조회만 복구한다`() {
        receiptStatus = 503
        applyReplace("[1]").statusCode() shouldBe 201
        state() shouldBe "UNKNOWN"
        jdbc.queryForObject("SELECT lease_token IS NULL AND response_received FROM tb_training_sms_outbox", Boolean::class.java) shouldBe
            true
        receiptStatus = 200
        jdbc.update("UPDATE tb_training_sms_outbox SET recover_after = CURRENT_TIMESTAMP")
        recovery.execute()
        state() shouldBe "READY"
        applicationCalls.get() shouldBe 1
    }

    @Test
    fun `삭제가 시작된 박람회는 명령 저장과 Application 호출 전에 충돌한다`() {
        val expo = expos.findById(EXPO).orElseThrow()
        val selected = programs.findByExpoIdOrderByIdAsc(EXPO)
        jdbc.update("UPDATE tb_expo SET deleting_at = CURRENT_TIMESTAMP WHERE id = ?", EXPO)
        val failure = assertThrows(ExpectedException::class.java) { prepare(TrainingOperationType.ADD) }
        failure.status shouldBe HttpStatus.CONFLICT
        assertThrows(ExpectedException::class.java) {
            applicationSms.execute(expo, 42, selected, TrainingOperationType.ADD, false)
        }.status shouldBe HttpStatus.CONFLICT
        count() shouldBe 0L
        applicationCalls.get() shouldBe 0
    }

    @Test
    fun `미확정 신청과 수동 확인 보류는 박람회 삭제 표시와 원격 삭제 전에 충돌한다`() {
        val operation = prepare(TrainingOperationType.ADD)
        assertThrows(ExpectedException::class.java) { deleteExpo.execute(EXPO) }.status shouldBe HttpStatus.CONFLICT
        outbox.hold(operation, "RECOVERY_LIMIT")
        assertThrows(ExpectedException::class.java) { deleteExpo.execute(EXPO) }.status shouldBe HttpStatus.CONFLICT
        jdbc.queryForObject("SELECT deleting_at IS NULL FROM tb_expo WHERE id = ?", Boolean::class.java, EXPO) shouldBe true
        outbox.hasUnresolvedApplications(EXPO) shouldBe true
        jdbc.update("UPDATE tb_training_sms_outbox SET application_state = 'SUCCEEDED', hold_reason = 'STALE_RECEIPT'")
        outbox.hasUnresolvedApplications(EXPO) shouldBe false
        jdbc.update("UPDATE tb_training_sms_outbox SET application_state = 'LEGACY', hold_reason = 'LEGACY_UNCERTAIN'")
        outbox.hasUnresolvedApplications(EXPO) shouldBe true
    }

    @Test
    fun `30번째 복구 선점은 유효하며 해제한 뒤 추가 복구 없이 보류한다`() {
        prepare(TrainingOperationType.ADD)
        jdbc.update("UPDATE tb_training_sms_outbox SET recovery_attempts = 29, lease_until = NULL, recover_after = CURRENT_TIMESTAMP")
        val last = requireNotNull(outbox.claimRecovery())
        outbox.canReplay(last) shouldBe true
        outbox.maintain()
        outbox.canReplay(last) shouldBe true
        outbox.pending(last)
        outbox.maintain()
        state() shouldBe "HELD"
        outbox.claimRecovery() shouldBe null
        jdbc.queryForObject("SELECT recovery_attempts FROM tb_training_sms_outbox", Int::class.java) shouldBe 30
    }

    @Test
    fun `다른 작업의 유지보수 장애가 확정 문자 발행을 막지 않는다`() {
        applyReplace("[1]").statusCode() shouldBe 201
        val ready = eventId()
        val expired = prepare(TrainingOperationType.ADD)
        jdbc.update(
            "UPDATE tb_training_sms_outbox SET created_at = CURRENT_TIMESTAMP - INTERVAL '16 minutes', lease_until = NULL WHERE event_id = ?",
            expired.eventId,
        )
        jdbc.execute(
            """CREATE FUNCTION fail_review_maintenance() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN IF NEW.application_state = 'HELD' THEN RAISE EXCEPTION 'maintenance failure'; END IF; RETURN NEW; END $$""",
        )
        jdbc.execute(
            "CREATE TRIGGER fail_review_maintenance BEFORE UPDATE ON tb_training_sms_outbox FOR EACH ROW EXECUTE FUNCTION fail_review_maintenance()",
        )
        try {
            assertThrows(Exception::class.java) { outbox.maintain() }
            consumer().use { consumer ->
                relay.execute()
                nextRecord(consumer).key() shouldBe ready
            }
            jdbc.queryForObject("SELECT state FROM tb_training_sms_outbox WHERE event_id = ?", String::class.java, ready) shouldBe "SENT"
        } finally {
            jdbc.execute("DROP TRIGGER fail_review_maintenance ON tb_training_sms_outbox")
            jdbc.execute("DROP FUNCTION fail_review_maintenance()")
        }
    }

    @Test
    fun `영수증 404는 정상 성공 응답 뒤에도 문자나 재실행의 근거가 아니다`() {
        applicationStub.hiddenReceipts = true
        applyReplace("[1]").statusCode() shouldBe 201
        state() shouldBe "UNKNOWN"
        jdbc.update("UPDATE tb_training_sms_outbox SET recover_after = CURRENT_TIMESTAMP")
        recovery.execute()
        state() shouldBe "UNKNOWN"
        applicationCalls.get() shouldBe 1
        recipientCalls.get() shouldBe 0
        relay.execute()
        state() shouldBe "UNKNOWN"
        applicationStub.hiddenReceipts = false
        jdbc.update("UPDATE tb_training_sms_outbox SET recover_after = CURRENT_TIMESTAMP")
        recovery.execute()
        state() shouldBe "READY"
    }

    @Test
    fun `영수증의 다른 대상 손상 버전 목록 상태는 모두 성공확정을 막는다`() {
        val operation = prepare(TrainingOperationType.ADD)
        dependencies.executeOperation(operation)
        val original = requireNotNull(applicationStub.receipt(operation.eventId))
        val mutations =
            listOf(
                original.replace(operation.eventId, UUID.randomUUID().toString()),
                original.replace("\"ADD\"", "\"REPLACE\""),
                original.replace(EXPO, "another-expo"),
                original.replace("\"traineeId\":42", "\"traineeId\":43"),
                original.replace("\"version\":1", "\"version\":\"1\""),
                original.replace("\"version\":1", "\"version\":2"),
                original.replace("\"changed\":true", "\"changed\":false"),
                original.replace("[1]", "[1,1]"),
                original.replace("[1]", "[2]"),
                original.replace("SUCCEEDED", "PENDING"),
            )
        mutations.forEach {
            applicationStub.receiptOverride = it
            assertThrows(ExpectedException::class.java) { dependencies.operationReceipt(operation) }.status shouldBe HttpStatus.BAD_GATEWAY
            state() shouldBe "PREPARED"
        }
    }

    @Test
    fun `취소 후 같은 프로그램 새 신청은 새 UUID이고 삭제 뒤 신규 신청은 409다`() {
        applyReplace("[1]").statusCode() shouldBe 201
        val first = eventId()
        applicationStub.cancel()
        request("/training/application/list", """{"trainingId":"training-1","trainingProIds":[1]}""").statusCode() shouldBe 201
        (first != eventId()) shouldBe true
        applicationStub.version shouldBe 3L
        applicationStub.delete(1)
        request("/training/application/list", """{"trainingId":"training-1","trainingProIds":[1]}""").statusCode() shouldBe 409
        applicationStub.version shouldBe 4L
        applicationStub.programIds shouldBe emptySet()
    }

    @Test
    fun `늦은 미완료 REPLACE 복구는 기대 버전을 최신화하지 않는다`() {
        val old = prepare(TrainingOperationType.REPLACE)
        applyReplace("[2]").statusCode() shouldBe 201
        jdbc.update(
            "UPDATE tb_training_sms_outbox SET lease_until = NULL, recover_after = CURRENT_TIMESTAMP WHERE event_id = ?",
            old.eventId,
        )
        recovery.execute()
        jdbc.queryForObject(
            "SELECT application_state FROM tb_training_sms_outbox WHERE event_id = ?",
            String::class.java,
            old.eventId,
        ) shouldBe
            "HELD"
        jdbc.queryForObject("SELECT command_json FROM tb_training_sms_outbox WHERE event_id = ?", String::class.java, old.eventId) shouldBe
            old.commandJson
        applicationStub.commands.last() shouldBe old.commandJson
        applicationStub.version shouldBe 1L
        applicationStub.programIds shouldBe setOf(2L)
        dependencies.operationReceipt(old) shouldBe null
    }

    @Test
    fun `복수 복구자도 동일 작업의 신청 버전과 발행 기록을 한 번만 만든다`() {
        val operation = prepare(TrainingOperationType.ADD)
        jdbc.update("UPDATE tb_training_sms_outbox SET lease_until = NULL, recover_after = CURRENT_TIMESTAMP")
        val workers = (1..8).map { CompletableFuture.runAsync { RecoverTrainingOperationsServiceImpl(outbox, dependencies).execute() } }
        workers.forEach { it.get(20, TimeUnit.SECONDS) }
        applicationStub.version shouldBe 1L
        applicationCalls.get() shouldBe 1
        count() shouldBe 1L
        eventId() shouldBe operation.eventId
        state() shouldBe "READY"
    }

    @Test
    fun `미발행 과거 성공은 최신 버전과 다르면 보류하고 번호 본문을 지운다`() {
        applyReplace("[1]").statusCode() shouldBe 201
        applicationStub.cancel()
        relay.execute()
        state() shouldBe "HELD"
        jdbc.queryForObject("SELECT application_state FROM tb_training_sms_outbox", String::class.java) shouldBe "SUCCEEDED"
        jdbc.queryForObject("SELECT hold_reason FROM tb_training_sms_outbox", String::class.java) shouldBe "STALE_RECEIPT"
        jdbc.queryForObject("SELECT notification_text FROM tb_training_sms_outbox", String::class.java) shouldBe null
        recipientCalls.get() shouldBe 0
    }

    @Test
    fun `빈 내부 취소와 무변경은 영수증을 소비하되 완료 문자를 만들지 않는다`() {
        applyReplace("[1]").statusCode() shouldBe 201
        val service = applicationSms
        val expo = requireNotNull(expos.findById(EXPO).orElse(null))
        service.execute(expo, 42, emptyList(), TrainingOperationType.REPLACE)
        applicationStub.version shouldBe 2L
        state() shouldBe "SUPPRESSED"
        service.execute(expo, 42, emptyList(), TrainingOperationType.REPLACE)
        applicationStub.version shouldBe 2L
        state() shouldBe "SUPPRESSED"
        recipientCalls.get() shouldBe 0
    }

    @Test
    fun `복구 시간 횟수 상한은 원본을 보존한 채 운영보류한다`() {
        val old = prepare(TrainingOperationType.ADD)
        jdbc.update("UPDATE tb_training_sms_outbox SET created_at = CURRENT_TIMESTAMP - INTERVAL '15 minutes', lease_until = NULL")
        recovery.execute()
        state() shouldBe "HELD"
        applicationCalls.get() shouldBe 0
        jdbc.queryForObject("SELECT command_json FROM tb_training_sms_outbox", String::class.java) shouldBe old.commandJson
        val capped = prepare(TrainingOperationType.ADD)
        jdbc.update("UPDATE tb_training_sms_outbox SET recovery_attempts = 30, lease_until = NULL WHERE event_id = ?", capped.eventId)
        recovery.execute()
        state() shouldBe "HELD"
        applicationCalls.get() shouldBe 0
        count() shouldBe 2L
    }

    @Test
    fun `SMS 기간 초과는 payload를 지우고 성공 기록은 30일 뒤에만 삭제한다`() {
        applyReplace("[1]").statusCode() shouldBe 201
        jdbc.update(
            "UPDATE tb_training_sms_outbox SET payload = 'synthetic-private-payload', created_at = CURRENT_TIMESTAMP - INTERVAL '23 hours'",
        )
        outbox.maintain()
        state() shouldBe "HELD"
        jdbc.queryForObject("SELECT payload FROM tb_training_sms_outbox", String::class.java) shouldBe null
        jdbc.queryForObject("SELECT receipt_json FROM tb_training_sms_outbox", String::class.java)?.contains("SUCCEEDED") shouldBe true
        jdbc.update("UPDATE tb_training_sms_outbox SET terminal_at = CURRENT_TIMESTAMP - INTERVAL '30 days'")
        outbox.maintain()
        count() shouldBe 0L
        prepare(TrainingOperationType.ADD)
        jdbc.update("UPDATE tb_training_sms_outbox SET created_at = CURRENT_TIMESTAMP - INTERVAL '31 days', lease_until = NULL")
        outbox.maintain()
        count() shouldBe 1L
        state() shouldBe "HELD"
    }

    @Test
    fun `legacy migration은 지문으로 성공을 추정하지 않고 불확정 발행도 보류한다`() {
        dbTransactions.executeWithoutResult {
            jdbc.execute("CREATE SCHEMA legacy_receipt_check")
            jdbc.execute("SET LOCAL search_path TO legacy_receipt_check")
            jdbc.execute(
                ConnectionCallback<Unit> { connection ->
                    ScriptUtils.executeSqlScript(connection, ClassPathResource("db/migration/V23__training_sms_outbox.sql"))
                },
            )
            listOf("PREPARED", "UNKNOWN", "READY", "SENT", "REJECTED").forEach { state ->
                jdbc.update(
                    "INSERT INTO tb_training_sms_outbox(event_id,expo_id,trainee_id,fingerprint,state,notification_text) VALUES (?,?,42,'ADD:1',?,'private')",
                    UUID.randomUUID().toString(),
                    EXPO,
                    state,
                )
            }
            jdbc.execute(
                ConnectionCallback<Unit> { connection ->
                    ScriptUtils.executeSqlScript(connection, ClassPathResource("db/migration/V26__training_operation_receipts.sql"))
                },
            )
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_training_sms_outbox WHERE state = 'HELD' AND notification_text IS NULL AND command_json IS NULL",
                Long::class.java,
            ) shouldBe
                3L
            jdbc.queryForObject("SELECT count(*) FROM tb_training_sms_outbox WHERE state IN ('SENT','REJECTED')", Long::class.java) shouldBe
                2L
            jdbc.execute("DROP SCHEMA legacy_receipt_check CASCADE")
        }
    }

    @Test
    fun `Notification 계약 검증 없는 SMS 활성화는 시작 단계에서 거절한다`() {
        assertThrows(IllegalArgumentException::class.java) { PublishTrainingSmsServiceImpl(outbox, producers, dependencies, mapper) }
    }

    private fun applyReplace(ids: String) =
        request(
            "/training/application/list/trainee/$EXPO",
            """{"trainingId":"training-1","phoneNumber":"010-1234-5678","name":"테스트","informationJson":"{}","personalInformationStatus":true,"trainingProIds":$ids}""",
        )

    private fun prepare(type: TrainingOperationType) =
        outbox.prepare(
            TrainingApplicationCommand(
                TrainingTraineeReference(42, EXPO),
                listOf(TrainingProgramReference(1, EXPO, Category.CHOICE)),
                UUID.randomUUID(),
                applicationStub.version,
            ),
            type,
            "신청 완료",
        )

    private fun request(
        path: String,
        body: String,
    ): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun state() = jdbc.queryForObject("SELECT state FROM tb_training_sms_outbox ORDER BY sequence DESC LIMIT 1", String::class.java)

    private fun eventId() =
        jdbc.queryForObject("SELECT event_id FROM tb_training_sms_outbox ORDER BY sequence DESC LIMIT 1", String::class.java)

    private fun count() = jdbc.queryForObject("SELECT count(*) FROM tb_training_sms_outbox", Long::class.java)

    private fun consumer() =
        KafkaConsumer<String, String>(
            mapOf(
                "bootstrap.servers" to broker.brokersAsString,
                "group.id" to UUID.randomUUID().toString(),
                "key.deserializer" to StringDeserializer::class.java,
                "value.deserializer" to StringDeserializer::class.java,
                "enable.auto.commit" to false,
            ),
        ).apply {
            val partition = TopicPartition(TOPIC, 0)
            assign(listOf(partition))
            seekToEnd(listOf(partition))
            position(partition)
        }

    private fun nextRecord(consumer: KafkaConsumer<String, String>): org.apache.kafka.clients.consumer.ConsumerRecord<String, String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val records = consumer.poll(Duration.ofMillis(200))
            if (!records.isEmpty) return records.first()
        }
        error("No SMS event received")
    }

    companion object {
        private const val EXPO = "training-sms-expo"
        private const val TOPIC = "notification.sms.requested"
        private val applicationCalls = AtomicInteger()
        private val recipientCalls = AtomicInteger()
        private var recipientBody = ""
        private var recipientStatus = 200
        private var receiptStatus = 200
        private var replaceStatus = 204
        private var addStatus = 201
        private var dropApplicationResponse = false
        private var applicationDelayMillis = 0L
        private val applicationStub = TrainingApplicationStub()
        private var beforeApplicationWrite: (String) -> Unit = {}

        private val upstream =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = Executors.newFixedThreadPool(16) { work -> Thread(work).apply { isDaemon = true } }
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    val requestBody = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    var applicationStatus: Int? = null
                    if (path.contains("training-program-applications") && exchange.requestMethod in setOf("POST", "PUT")) {
                        check(exchange.requestHeaders.getFirst("X-Internal-Token") == "test-training-token")
                        beforeApplicationWrite(requestBody)
                        applicationCalls.incrementAndGet()
                        if (applicationDelayMillis > 0) Thread.sleep(applicationDelayMillis)
                        applicationStatus =
                            applicationStub.execute(
                                requestBody,
                                exchange.requestMethod == "PUT",
                                if (exchange.requestMethod ==
                                    "PUT"
                                ) {
                                    replaceStatus
                                } else {
                                    addStatus
                                },
                            )
                        if (dropApplicationResponse) {
                            exchange.close()
                            return@createContext
                        }
                    }
                    if (path.contains("/trainees/details")) {
                        recipientCalls.incrementAndGet()
                        check(exchange.requestURI.rawQuery == "cursor=41&size=1")
                        check(exchange.requestHeaders.getFirst("X-Internal-Token") == "test-training-token")
                    }
                    val status =
                        when {
                            path.contains("/trainees/details") -> {
                                recipientStatus
                            }

                            path.contains("/operations/") -> {
                                if (applicationStub.receipt(path.substringAfterLast('/')) ==
                                    null
                                ) {
                                    404
                                } else {
                                    receiptStatus
                                }
                            }

                            applicationStatus != null -> {
                                applicationStatus
                            }

                            else -> {
                                200
                            }
                        }
                    val body =
                        when {
                            path.contains("/trainees/details") -> recipientBody
                            path.startsWith("/forms/") -> """{"startDate":"2020-01-01T00:00:00Z","endDate":"2099-01-01T00:00:00Z"}"""
                            path.startsWith("/internal/trainees/") -> """{"traineeId":42,"created":false}"""
                            path.endsWith("/version") -> """{"version":${applicationStub.version}}"""
                            path.contains("/operations/") -> applicationStub.receipt(path.substringAfterLast('/')) ?: ""
                            else -> ""
                        }.toByteArray()
                    exchange.sendResponseHeaders(status, if (status == 204 || body.isEmpty()) -1 else body.size.toLong())
                    if (status != 204 && body.isNotEmpty()) exchange.responseBody.write(body)
                    exchange.close()
                }
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
            registry.add("expo.training.user-service-url") { "http://127.0.0.1:${upstream.address.port}" }
            registry.add("expo.training.application-service-url") { "http://127.0.0.1:${upstream.address.port}" }
            registry.add("expo.form-service-url") { "http://127.0.0.1:${upstream.address.port}" }
            registry.add("expo.training.internal-token") { "test-training-token" }
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
