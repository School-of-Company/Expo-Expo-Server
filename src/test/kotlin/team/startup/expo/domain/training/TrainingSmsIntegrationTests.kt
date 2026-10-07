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
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
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
import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramsRequest
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.repository.TrainingSmsOutboxRepository
import team.startup.expo.domain.training.service.PublishTrainingSmsService
import team.startup.expo.domain.training.service.TrainingApplicationSmsService
import team.startup.expo.domain.training.service.TrainingDependenciesClient
import team.startup.expo.domain.training.service.impl.ApplyTrainingProgramListServiceImpl
import team.startup.expo.domain.training.service.impl.PublishTrainingSmsServiceImpl
import team.startup.expo.global.exception.ExpectedException
import team.startup.expo.support.TestJwt
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
        replaceStatus = 204
        addStatus = 201
        applicationCalls.set(0)
        recipientCalls.set(0)
        dropApplicationResponse = false
        applicationDelayMillis = 0
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
            count() shouldBe 1L
            relay.execute()
            consumer.poll(Duration.ofMillis(500)).isEmpty shouldBe true
            applicationCalls.get() shouldBe 2
        }
    }

    @Test
    fun `다건 성공만 SMS를 준비하고 단건은 수신자 조회도 하지 않는다`() {
        request("/training/application/1", """{"trainingId":"training-1"}""").statusCode() shouldBe 201
        count() shouldBe 0L
        recipientCalls.get() shouldBe 0
        request("/training/application/list", """{"trainingId":"training-1","trainingProIds":[1,2]}""").statusCode() shouldBe 201
        state() shouldBe "READY"
        addStatus = 409
        request("/training/application/list", """{"trainingId":"training-1","trainingProIds":[2,1]}""").statusCode() shouldBe 409
        state() shouldBe "READY"
        count() shouldBe 1L
    }

    @Test
    fun `A B A의 실제 신청 변경은 서로 다른 이벤트를 만들고 실패한 B 뒤 A 재시도는 중복시키지 않는다`() {
        applyReplace("[1]").statusCode() shouldBe 201
        val first = eventId()
        replaceStatus = 409
        applyReplace("[2]").statusCode() shouldBe 409
        applyReplace("[1]").statusCode() shouldBe 409
        count() shouldBe 2L
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
        state() shouldBe "UNKNOWN"
        replaceStatus = 204
        applyReplace("[1]").statusCode() shouldBe 201
        state() shouldBe "READY"
        eventId() shouldBe id
    }

    @Test
    fun `Application 응답 유실 뒤 add 재시도 409는 성공 증거로 간주하지 않는다`() {
        dropApplicationResponse = true
        request("/training/application/list", """{"trainingId":"training-1","trainingProIds":[1]}""").statusCode() shouldBe 503
        state() shouldBe "UNKNOWN"
        dropApplicationResponse = false
        addStatus = 409
        request("/training/application/list", """{"trainingId":"training-1","trainingProIds":[1]}""").statusCode() shouldBe 409
        state() shouldBe "UNKNOWN"
        count() shouldBe 1L
    }

    @Test
    fun `프로세스 중단으로 남은 PREPARED의 add 재시도 거절도 성공 여부 미확정이다`() {
        outbox.prepare(EXPO, 42, "ADD:1", "신청 완료")
        jdbc.update("UPDATE tb_training_sms_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'")
        addStatus = 409
        request("/training/application/list", """{"trainingId":"training-1","trainingProIds":[1]}""").statusCode() shouldBe 409
        state() shouldBe "UNKNOWN"
        count() shouldBe 1L
    }

    @Test
    fun `DB 풀 두 연결로 외부 호출 중인 동시 요청 열두 개를 처리한다`() {
        applicationDelayMillis = 150
        val requests = (1..12).map { CompletableFuture.supplyAsync { applyReplace("[1]").statusCode() } }
        requests.forEach { it.get(30, TimeUnit.SECONDS) shouldBe 201 }
        count() shouldBe 1L
        state() shouldBe "READY"
        applicationCalls.get() shouldBe 12
    }

    @Test
    fun `외부 성공 뒤 로컬 확정 실패는 준비 기록을 남기고 다음 교체 성공으로 복구한다`() {
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
        jdbc.update("UPDATE tb_training_sms_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'")
        applyReplace("[1]").statusCode() shouldBe 201
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
            val restarted = PublishTrainingSmsServiceImpl(outbox, producers, dependencies, mapper)
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
    fun `신청 준비 기록 장애는 신청 성공을 막지 않고 민감정보를 기록하지 않는다`(output: CapturedOutput) {
        jdbc.execute(
            """CREATE FUNCTION fail_sms_prepare() RETURNS trigger AS ${'$'}body${'$'}
               BEGIN RAISE EXCEPTION 'sensitive-prepare-marker'; END; ${'$'}body${'$'} LANGUAGE plpgsql""",
        )
        jdbc.execute(
            "CREATE TRIGGER fail_sms_prepare BEFORE INSERT ON tb_training_sms_outbox FOR EACH ROW EXECUTE FUNCTION fail_sms_prepare()",
        )
        try {
            applyReplace("[1]").statusCode() shouldBe 201
            applicationCalls.get() shouldBe 1
            count() shouldBe 0L
            output.all.contains("Training SMS journal preparation failed") shouldBe true
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
        val first = requireNotNull(outbox.prepare(EXPO, 42, "REPLACE:1", "신청 완료"))
        jdbc.update("UPDATE tb_training_sms_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'")
        val second = requireNotNull(outbox.prepare(EXPO, 42, "REPLACE:1", "신청 완료"))
        first.eventId shouldBe second.eventId
        outbox.confirmed(first)
        state() shouldBe "UNKNOWN"
        outbox.confirmed(second)
        state() shouldBe "READY"
    }

    @Test
    fun `Application 설정 누락은 HTTP 호출과 SMS 준비 기록 전에 실패한다`() {
        val missing = TrainingDependenciesClient("http://127.0.0.1:${upstream.address.port}", "", "test-training-token", "", mapper)
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

    private fun applyReplace(ids: String) =
        request(
            "/training/application/list/trainee/$EXPO",
            """{"trainingId":"training-1","phoneNumber":"010-1234-5678","name":"테스트","informationJson":"{}","personalInformationStatus":true,"trainingProIds":$ids}""",
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
        private var replaceStatus = 204
        private var addStatus = 201
        private var dropApplicationResponse = false
        private var applicationDelayMillis = 0L

        private val upstream =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = Executors.newFixedThreadPool(16) { work -> Thread(work).apply { isDaemon = true } }
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    exchange.requestBody.readAllBytes()
                    if (path.contains("training-program-applications")) {
                        applicationCalls.incrementAndGet()
                        if (applicationDelayMillis > 0) Thread.sleep(applicationDelayMillis)
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
                            path.contains("/trainees/details") -> recipientStatus
                            path.endsWith("/trainee/42") -> replaceStatus
                            path.endsWith("/training-program-applications") -> addStatus
                            else -> 200
                        }
                    val body =
                        when {
                            path.contains("/trainees/details") -> recipientBody
                            path.startsWith("/forms/") -> """{"startDate":"2020-01-01T00:00:00Z","endDate":"2099-01-01T00:00:00Z"}"""
                            path.startsWith("/internal/trainees/") -> """{"traineeId":42,"created":false}"""
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
