package team.startup.expo.domain.expo

import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.presentation.dto.request.PreregisterSessionRequest
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.CreatePreregisterSessionService
import team.startup.expo.domain.expo.service.ExpoEventSupport
import team.startup.expo.domain.expo.service.PublishExpoEventsService
import team.startup.expo.domain.expo.service.RecoverExpoEventsService
import team.startup.expo.domain.image.entity.ExpoImage
import team.startup.expo.domain.image.repository.ExpoImageRepository
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.support.TestJwt
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "expo.events.enabled=true",
        "expo.events.relay-delay-ms=3600000",
        "expo.events.recovery-delay-ms=3600000",
        "image.storage.local.directory=./build/test-images",
        "EXPO_INTERNAL_TOKEN=test-internal-token",
    ],
)
@EmbeddedKafka(partitions = 1, topics = [ExpoEventPostgresKafkaTests.TOPIC], brokerProperties = ["auto.create.topics.enable=false"])
@EntityScan("team.startup.expo.domain")
@Testcontainers
class ExpoEventPostgresKafkaTests {
    @LocalServerPort
    private var port = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var expos: ExpoRepository

    @Autowired
    private lateinit var images: ExpoImageRepository

    @Autowired
    private lateinit var standards: StandardProgramRepository

    @Autowired
    private lateinit var events: ExpoEventSupport

    @Autowired
    private lateinit var relay: PublishExpoEventsService

    @Autowired
    private lateinit var recovery: RecoverExpoEventsService

    @Autowired
    private lateinit var createSession: CreatePreregisterSessionService

    @Autowired
    private lateinit var dbTransactions: TransactionTemplate

    @Autowired
    private lateinit var broker: EmbeddedKafkaBroker

    @BeforeEach
    fun setup() {
        jdbc.execute("TRUNCATE TABLE tb_expo_event_outbox, tb_program_change, tb_expo RESTART IDENTITY CASCADE")
    }

    @Test
    fun `박람회 생성은 같은 트랜잭션에서 전체 상태 한 건을 쌓고 릴레이가 expoId 키로 발행한다`() {
        consumer().use { consumer ->
            val response = request("/expo", "POST", createBody(), authorization = "Bearer ${TestJwt.token("ROLE_ADMIN")}")
            response.statusCode() shouldBe 201
            val expoId = mapper.readTree(response.body()).path("expoId").asString()

            rows(expoId) shouldBe 1
            val snapshot = mapper.readTree(jdbc.queryForObject("SELECT snapshot FROM tb_expo_event_outbox", String::class.java))
            snapshot.path("expo").path("title").asString() shouldBe "이벤트 박람회"
            snapshot.path("standardPrograms")[0].path("startedAt").asString() shouldBe "2026-09-24T09:00"
            snapshot.path("trainingPrograms")[0].path("category").asString() shouldBe "ESSENTIAL"

            relay.execute()

            val record = nextRecord(consumer)
            record.key() shouldBe expoId
            val message = mapper.readTree(record.value())
            message.path("schemaVersion").asInt() shouldBe 1
            message.path("version").asLong() shouldBe jdbc.queryForObject("SELECT sequence FROM tb_expo_event_outbox", Long::class.java)
            message.path("eventId").asString() shouldBe jdbc.queryForObject("SELECT event_id FROM tb_expo_event_outbox", String::class.java)
            message.path("deleted").asBoolean() shouldBe false
            message.path("standardPrograms").size() shouldBe 1
            jdbc.queryForObject("SELECT state FROM tb_expo_event_outbox", String::class.java) shouldBe "PUBLISHED"
        }
    }

    @Test
    fun `설정이 실제로 바뀐 요청만 아웃박스에 쌓는다`() {
        seedExpo(EXPO)
        rows(EXPO) shouldBe 0

        val path = "/expo/$EXPO/participant-phone-verification"
        val admin = "Bearer ${TestJwt.token("ROLE_ADMIN")}"
        request(path, "PATCH", """{"participantPhoneVerificationRequired":false}""", authorization = admin).statusCode() shouldBe 200
        request(path, "PATCH", """{"participantPhoneVerificationRequired":false}""", authorization = admin).statusCode() shouldBe 200

        rows(EXPO) shouldBe 1
        latestSnapshot(EXPO).path("expo").let {
            it.path("participantPhoneVerificationRequired").asBoolean() shouldBe false
            it.path("participantPhoneVerificationRevision").asLong() shouldBe 1
        }
    }

    @Test
    fun `프로그램 수정은 변경 예약과 적용 트랜잭션에서 각각 스냅샷을 쌓는다`() {
        seedExpo(EXPO)
        val id = seedProgram()

        updateProgram(id).statusCode() shouldBe 204

        rows(EXPO) shouldBe 2
        snapshots(EXPO)[0].let { reserved ->
            reserved.path("expo").path("programChangePending").asBoolean() shouldBe true
            reserved.path("standardPrograms")[0].path("revision").asLong() shouldBe 1
            reserved.path("standardPrograms")[0].path("pendingRevision").asLong() shouldBe 2
        }
        latestSnapshot(EXPO).let { applied ->
            applied.path("expo").path("programChangePending").asBoolean() shouldBe false
            applied.path("standardPrograms")[0].let {
                it.path("title").asString() shouldBe "수정 프로그램"
                it.path("endedAt").asString() shouldBe "2030-01-01T11:00"
                it.path("revision").asLong() shouldBe 2
                it.path("pendingRevision").isNull shouldBe true
            }
        }
    }

    @Test
    fun `적용에 실패해 진행 중 변경을 해제하면 해제된 상태의 스냅샷을 쌓는다`() {
        seedExpo(EXPO)
        val id = seedProgram()
        try {
            jdbc.execute(
                """CREATE FUNCTION fail_event_program_update() RETURNS trigger AS ${'$'}t${'$'} BEGIN RAISE EXCEPTION 'forced'; END; ${'$'}t${'$'} LANGUAGE plpgsql""",
            )
            jdbc.execute(
                "CREATE TRIGGER fail_event_program_update BEFORE UPDATE ON tb_standard_program FOR EACH ROW EXECUTE FUNCTION fail_event_program_update()",
            )
            updateProgram(id).statusCode() shouldBe 500
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_event_program_update ON tb_standard_program")
            jdbc.execute("DROP FUNCTION IF EXISTS fail_event_program_update()")
        }

        jdbc.queryForObject("SELECT count(*) FROM tb_program_change", Long::class.java) shouldBe 0
        rows(EXPO) shouldBe 2
        snapshots(EXPO)[0].path("expo").path("programChangePending").asBoolean() shouldBe true
        latestSnapshot(EXPO).let { released ->
            released.path("expo").path("programChangePending").asBoolean() shouldBe false
            released.path("standardPrograms")[0].path("revision").asLong() shouldBe 1
            released.path("standardPrograms")[0].path("pendingRevision").isNull shouldBe true
        }
    }

    @Test
    fun `회차 생성은 회차가 담긴 스냅샷을 쌓는다`() {
        seedExpo(EXPO)

        createSession.execute(
            EXPO,
            PreregisterSessionRequest(
                title = "1회차",
                startedAt = Instant.parse("2030-01-01T01:00:00Z"),
                endedAt = Instant.parse("2030-01-01T02:00:00Z"),
                place = "강당",
                capacity = 30,
                waitingCapacity = 5,
                closed = false,
            ),
        )

        rows(EXPO) shouldBe 1
        latestSnapshot(EXPO).path("preregisterSessions")[0].path("capacity").asInt() shouldBe 30
    }

    @Test
    fun `롤백된 트랜잭션은 아웃박스 행을 남기지 않고 한 트랜잭션의 여러 변경은 한 건으로 묶는다`() {
        seedExpo(EXPO)

        dbTransactions.executeWithoutResult { status ->
            events.changed(EXPO)
            status.setRollbackOnly()
        }
        rows(EXPO) shouldBe 0

        dbTransactions.executeWithoutResult {
            events.changed(EXPO)
            events.changed(EXPO)
        }
        rows(EXPO) shouldBe 1
    }

    @Test
    fun `박람회가 지워지면 deleted만 담은 tombstone을 발행한다`() {
        seedExpo(EXPO)

        dbTransactions.executeWithoutResult {
            expos.deleteById(EXPO)
            events.changed(EXPO)
        }

        jdbc.queryForObject("SELECT deleted FROM tb_expo_event_outbox", Boolean::class.java) shouldBe true
        consumer().use { consumer ->
            relay.execute()
            val message = mapper.readTree(nextRecord(consumer).value())
            message.path("deleted").asBoolean() shouldBe true
            message.path("expoId").asString() shouldBe EXPO
            message.has("expo") shouldBe false
        }
    }

    @Test
    fun `한 박람회의 앞선 행이 미뤄지면 그 박람회의 뒤 행도 기다리고 다른 박람회는 발행한다`() {
        seedExpo(EXPO)
        seedExpo(OTHER)
        listOf(EXPO, OTHER, EXPO).forEach { id -> dbTransactions.executeWithoutResult { events.changed(id) } }
        jdbc.update("UPDATE tb_expo_event_outbox SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE sequence = 1")

        consumer().use { consumer ->
            relay.execute()
            nextRecord(consumer).key() shouldBe OTHER
            consumer.poll(Duration.ofMillis(500)).isEmpty shouldBe true
            states() shouldBe listOf("PENDING", "PUBLISHED", "PENDING")

            jdbc.update("UPDATE tb_expo_event_outbox SET next_attempt_at = CURRENT_TIMESTAMP WHERE sequence = 1")
            relay.execute()
            val versions = listOf(nextRecord(consumer), nextRecord(consumer)).map { mapper.readTree(it.value()).path("version").asLong() }
            versions shouldBe listOf(1L, 3L)
            states() shouldBe listOf("PUBLISHED", "PUBLISHED", "PUBLISHED")
        }
    }

    @Test
    fun `복구는 멈춘 선점을 풀고 오래된 발행 행을 지운다`() {
        seedExpo(EXPO)
        repeat(2) { dbTransactions.executeWithoutResult { events.changed(EXPO) } }
        jdbc.update(
            """UPDATE tb_expo_event_outbox SET publish_token = 'stale', publish_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
               WHERE sequence = 2""",
        )
        jdbc.update(
            """UPDATE tb_expo_event_outbox SET state = 'PUBLISHED', published_at = CURRENT_TIMESTAMP - INTERVAL '8 days'
               WHERE sequence = 1""",
        )

        recovery.execute()

        jdbc.queryForList("SELECT sequence FROM tb_expo_event_outbox", Long::class.java) shouldBe listOf(2L)
        jdbc.queryForObject("SELECT publish_token IS NULL FROM tb_expo_event_outbox", Boolean::class.java) shouldBe true
    }

    @Test
    fun `재발행은 내부 토큰으로만 호출하고 모든 박람회의 현재 상태를 쌓는다`() {
        seedExpo(EXPO)
        seedExpo(OTHER)

        request("/internal/expo-events/republish", "POST").statusCode() shouldBe 401
        request("/internal/expo-events/republish", "POST", internalToken = "wrong").statusCode() shouldBe 401
        val response = request("/internal/expo-events/republish", "POST", internalToken = "test-internal-token")

        response.statusCode() shouldBe 202
        mapper.readTree(response.body()).path("enqueued").asInt() shouldBe 2
        rows(EXPO) shouldBe 1
        rows(OTHER) shouldBe 1
    }

    private fun seedExpo(id: String) {
        expos.saveAndFlush(
            Expo(id, "테스트 박람회", "설명", "2030-01-01", "2030-01-02", "광주", null, "127", "37", 0, 0),
        )
    }

    private fun seedProgram(): Long =
        requireNotNull(
            standards
                .saveAndFlush(
                    StandardProgram(
                        title = "일반 프로그램",
                        startedAt = "2030-01-01T09:00",
                        endedAt = "2030-01-01T10:00",
                        expo = expos.getReferenceById(EXPO),
                    ),
                ).id,
        )

    private fun updateProgram(id: Long): HttpResponse<String> =
        request(
            "/standard/$id",
            "PATCH",
            """{"id":$id,"title":"수정 프로그램","startedAt":"2030-01-01 09:00","endedAt":"2030-01-01 11:00","revision":1}""",
            authorization = "Bearer ${TestJwt.token("ROLE_ADMIN")}",
        )

    private fun snapshots(expoId: String): List<JsonNode> =
        jdbc
            .queryForList("SELECT snapshot FROM tb_expo_event_outbox WHERE expo_id = ? ORDER BY sequence", String::class.java, expoId)
            .map(mapper::readTree)

    private fun createBody(): String {
        val imageId = UUID.randomUUID().toString()
        val url = "http://localhost:8080/image/$imageId"
        images.saveAndFlush(ExpoImage(imageId, "local", "img/$imageId.png", url, "image/png", "1"))
        return """
            {
              "title": "이벤트 박람회",
              "description": "설명",
              "startedDay": "2026-09-24",
              "finishedDay": "2026-09-25",
              "location": "서울",
              "coverImage": "$url",
              "x": "127.123",
              "y": "37.456",
              "addStandardProRequestDto": [{"title": "일반 프로그램", "startedAt": "2026-09-24 09:00", "endedAt": "2026-09-24 10:00"}],
              "addTrainingProRequestDto": [
                {"title": "연수 프로그램", "startedAt": "2026-09-24 10:00", "endedAt": "2026-09-24 11:00", "category": "ESSENTIAL"}
              ]
            }
            """.trimIndent()
    }

    private fun rows(expoId: String) =
        jdbc.queryForObject("SELECT count(*) FROM tb_expo_event_outbox WHERE expo_id = ?", Long::class.java, expoId)

    private fun states() = jdbc.queryForList("SELECT state FROM tb_expo_event_outbox ORDER BY sequence", String::class.java)

    private fun latestSnapshot(expoId: String): JsonNode =
        mapper.readTree(
            jdbc.queryForObject(
                "SELECT snapshot FROM tb_expo_event_outbox WHERE expo_id = ? ORDER BY sequence DESC LIMIT 1",
                String::class.java,
                expoId,
            ),
        )

    private fun request(
        path: String,
        method: String,
        body: String? = null,
        authorization: String? = null,
        internalToken: String? = null,
    ): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .apply {
                    authorization?.let { header("Authorization", it) }
                    internalToken?.let { header("X-Internal-Token", it) }
                }.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun consumer() =
        KafkaConsumer<String, String>(
            mapOf(
                "bootstrap.servers" to broker.brokersAsString,
                "group.id" to UUID.randomUUID().toString(),
                "key.deserializer" to StringDeserializer::class.java,
                "value.deserializer" to StringDeserializer::class.java,
                "enable.auto.commit" to false,
                "max.poll.records" to 1,
            ),
        ).apply {
            val partition = TopicPartition(TOPIC, 0)
            assign(listOf(partition))
            seekToEnd(listOf(partition))
            position(partition)
        }

    private fun nextRecord(consumer: KafkaConsumer<String, String>): ConsumerRecord<String, String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val records = consumer.poll(Duration.ofMillis(200))
            if (!records.isEmpty) return records.first()
        }
        error("No expo event received")
    }

    companion object {
        const val TOPIC = "expo.expo.changed"
        private const val EXPO = "expo-event-expo"
        private const val OTHER = "expo-event-other"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
