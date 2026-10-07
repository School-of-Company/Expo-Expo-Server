package team.startup.expo.domain.expo

import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.event.ConsumerStoppedEvent
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.CannotCreateTransactionException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.domain.expo.messaging.StandardCreatedConsumer
import team.startup.expo.domain.expo.service.RecordStandardRegistrationService
import team.startup.expo.support.TestJwt
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "eureka.client.enabled=false",
        "EXPO_INTERNAL_TOKEN=test-internal-token",
        "expo.standard-created.enabled=true",
        "spring.kafka.consumer.group-id=standard-created-test",
        "spring.kafka.producer.properties.max.block.ms=1000",
        "spring.kafka.producer.properties.delivery.timeout.ms=2000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
    ],
)
@EntityScan("team.startup.expo.domain")
@EmbeddedKafka(
    partitions = 1,
    topics = [StandardCreatedConsumer.TOPIC, StandardCreatedConsumer.TOPIC + ".dlt"],
    brokerProperties = ["auto.create.topics.enable=false"],
)
@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
class StandardCreatedKafkaTests {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var kafka: KafkaTemplate<String, String>

    @Autowired
    private lateinit var broker: EmbeddedKafkaBroker

    @Autowired
    private lateinit var listeners: KafkaListenerEndpointRegistry

    @Autowired
    private lateinit var events: ApplicationEventPublisher

    @MockitoSpyBean
    private lateinit var registrations: RecordStandardRegistrationService

    @MockitoSpyBean
    private lateinit var consumer: StandardCreatedConsumer

    @LocalServerPort
    private var port = 0

    @BeforeEach
    fun setUp() {
        jdbc.execute("TRUNCATE TABLE tb_expo RESTART IDENTITY CASCADE")
        jdbc.update(
            """INSERT INTO tb_expo (id,title,description,started_day,finished_day,location,x,y,application_person,yesterday_application_person)
               VALUES (?,'expo','description','2026-09-24','2026-09-25','location','127','37',5,3)""",
            EXPO_ID,
        )
    }

    @Test
    fun `missing registration recovers once and duplicate events or already counted participants add zero`() {
        val event = payload(42)
        awaitCommitted(send(42, event))
        count() shouldBe 6L
        awaitCommitted(send(42, event))
        awaitCommitted(send(42, payload(42)))
        count() shouldBe 6L
        ledger() shouldBe 1L

        put(43) shouldBe 204
        awaitCommitted(send(43, payload(43)))
        count() shouldBe 7L
        ledger() shouldBe 2L
    }

    @Test
    fun `offset loss after database commit replays the same source record without incrementing twice`() {
        val metadata = send(42, payload(42))
        awaitCommitted(metadata)
        count() shouldBe 6L
        listeners.stop()
        try {
            admin().use { admin ->
                admin
                    .alterConsumerGroupOffsets(
                        "standard-created-test",
                        mapOf(TopicPartition(metadata.topic(), metadata.partition()) to OffsetAndMetadata(metadata.offset())),
                    ).all()
                    .get(10, TimeUnit.SECONDS)
            }
        } finally {
            listeners.start()
        }
        awaitCommitted(metadata)
        serviceAttempts(42) shouldBe 2
        count() shouldBe 6L
        ledger() shouldBe 1L
    }

    @Test
    fun `missing deleted and deleting expos skip without retry or ledger writes`() {
        dltConsumer().use { dlt ->
            jdbc.update("DELETE FROM tb_expo WHERE id = ?", EXPO_ID)
            awaitCommitted(send(42, payload(42)))
            serviceAttempts(42) shouldBe 1
            ledger() shouldBe 0L

            setUp()
            jdbc.update("UPDATE tb_expo SET deleting_at = now() WHERE id = ?", EXPO_ID)
            awaitCommitted(send(43, payload(43)))
            serviceAttempts(43) shouldBe 1
            count() shouldBe 5L
            ledger() shouldBe 0L
            dlt.poll(Duration.ofSeconds(1)).isEmpty shouldBe true
        }
    }

    @Test
    fun `PUT and Kafka event concurrently count the participant once`() {
        val requests = (1..12).map { CompletableFuture.supplyAsync { put(42) } }
        val records = (1..12).map { send(42, payload(42)) }
        requests.forEach { it.get(30, TimeUnit.SECONDS) shouldBe 204 }
        awaitCommitted(records.last())
        count() shouldBe 6L
        ledger() shouldBe 1L
    }

    @Test
    fun `malformed payloads and mismatched keys go to DLT on first attempt`() {
        dltConsumer().use { dlt ->
            val bad =
                listOf(
                    "broken secret-phone",
                    "null",
                    "{}",
                    payload(42).replace("42}", "0}"),
                    payload(42).replace("42}", "1.5}"),
                    payload(42).replace("42}", "9223372036854775808}"),
                    payload(42).replace(EXPO_ID, "invalid"),
                    payload(42).replace(Regex("\"eventId\":\"[^\"]+\""), "\"eventId\":\"invalid\""),
                )
            (bad.map { 42L to it } + (99L to payload(42))).forEach { (keyId, value) ->
                val metadata = send(keyId, value)
                val recovered = nextDlt(dlt)
                recovered.value() shouldBe value
                recovered.key() shouldBe "$EXPO_ID:$keyId"
                awaitCommitted(metadata)
                mockingDetails(consumer).invocations.count { invocation ->
                    invocation.method.name == "consume" &&
                        (invocation.arguments[0] as org.apache.kafka.clients.consumer.ConsumerRecord<*, *>).offset() == metadata.offset()
                } shouldBe 1
            }
        }
        ledger() shouldBe 0L
        count() shouldBe 5L
    }

    @Test
    fun `transient failure retries then commits one registration`() {
        doThrow(TransientDataAccessResourceException("temporary outage"))
            .doThrow(TransientDataAccessResourceException("temporary outage"))
            .doCallRealMethod()
            .`when`(registrations)
            .execute(EXPO_ID, 42)
        awaitCommitted(send(42, payload(42)))
        serviceAttempts(42) shouldBe 3
        count() shouldBe 6L
        ledger() shouldBe 1L
    }

    @Test
    fun `exhausted transient failures reach DLT after four attempts and replay recovers`() {
        doThrow(TransientDataAccessResourceException("temporary outage")).`when`(registrations).execute(EXPO_ID, 42)
        dltConsumer().use { dlt ->
            val value = payload(42)
            val metadata = send(42, value)
            nextDlt(dlt).value() shouldBe value
            awaitCommitted(metadata)
            serviceAttempts(42) shouldBe 6
            ledger() shouldBe 0L
            doCallRealMethod().`when`(registrations).execute(EXPO_ID, 42)
            awaitCommitted(send(42, value))
            count() shouldBe 6L
            ledger() shouldBe 1L
        }
    }

    @Test
    fun `database transaction failure rolls back ledger before DLT and replay`() {
        jdbc.execute(
            """CREATE FUNCTION fail_event_count() RETURNS trigger AS ${'$'}trigger${'$'}
               BEGIN RAISE EXCEPTION 'forced count failure'; END;
               ${'$'}trigger${'$'} LANGUAGE plpgsql""",
        )
        jdbc.execute("CREATE TRIGGER fail_event_count BEFORE UPDATE ON tb_expo FOR EACH ROW EXECUTE FUNCTION fail_event_count()")
        val value = payload(42)
        try {
            dltConsumer().use { dlt ->
                val metadata = send(42, value)
                nextDlt(dlt).value() shouldBe value
                awaitCommitted(metadata)
                serviceAttempts(42) shouldBe 1
                ledger() shouldBe 0L
                count() shouldBe 5L
            }
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_event_count ON tb_expo")
            jdbc.execute("DROP FUNCTION IF EXISTS fail_event_count()")
        }
        awaitCommitted(send(42, value))
        count() shouldBe 6L
        ledger() shouldBe 1L
    }

    @Test
    fun `PostgreSQL connection termination rolls back then retries the real transaction`() {
        jdbc.execute("CREATE SEQUENCE fail_event_connection_seq")
        jdbc.execute(
            """CREATE FUNCTION fail_event_connection() RETURNS trigger AS ${'$'}trigger${'$'}
               BEGIN
                   IF nextval('fail_event_connection_seq') = 1 THEN
                       PERFORM pg_terminate_backend(pg_backend_pid());
                   END IF;
                   RETURN NEW;
               END;
               ${'$'}trigger${'$'} LANGUAGE plpgsql""",
        )
        jdbc.execute("CREATE TRIGGER fail_event_connection BEFORE UPDATE ON tb_expo FOR EACH ROW EXECUTE FUNCTION fail_event_connection()")
        try {
            awaitCommitted(send(42, payload(42)))
            serviceAttempts(42) shouldBe 2
            count() shouldBe 6L
            ledger() shouldBe 1L
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_event_connection ON tb_expo")
            jdbc.execute("DROP FUNCTION IF EXISTS fail_event_connection()")
            jdbc.execute("DROP SEQUENCE IF EXISTS fail_event_connection_seq")
        }
    }

    @Test
    fun `transaction creation and wrapped transient SQL failures retry`() {
        listOf(
            CannotCreateTransactionException("connection unavailable"),
            RuntimeException("SQL operation failed", SQLTransientException("transient")),
            RuntimeException("SQL operation failed", SQLRecoverableException("recoverable")),
            RuntimeException("SQL operation failed", SQLException("connection", "08006")),
            RuntimeException("SQL operation failed", SQLException("shutdown", "57P02")),
            RuntimeException("SQL operation failed", SQLException("unavailable", "57P03")),
        ).forEachIndexed { index, failure ->
            val participantId = 100L + index
            doThrow(failure).doCallRealMethod().`when`(registrations).execute(EXPO_ID, participantId)
            awaitCommitted(send(participantId, payload(participantId)))
            serviceAttempts(participantId) shouldBe 2
        }
        ledger() shouldBe 6L
        count() shouldBe 11L
    }

    @Test
    fun `abnormal consumer stop is observable without logging event source`(output: CapturedOutput) {
        events.publishEvent(ConsumerStoppedEvent("sensitive-stop-marker", this, ConsumerStoppedEvent.Reason.AUTH))
        output.all.contains("Kafka consumer stopped: reason=AUTH") shouldBe true
        output.all.contains("sensitive-stop-marker") shouldBe false
    }

    @Test
    fun `DLT broker rejection does not commit source offset and recovers when topic returns`(output: CapturedOutput) {
        admin().use { admin ->
            admin.deleteTopics(listOf(DLT)).all().get(10, TimeUnit.SECONDS)
            val value = "broken sensitive-dlt-marker"
            val metadata = send(42, value)
            try {
                Thread.sleep(5_000)
                output.all.contains("Exception thrown when sending") shouldBe true
                val offset = committedOffset(admin)
                (offset == null || offset <= metadata.offset()) shouldBe true
            } finally {
                admin.createTopics(listOf(NewTopic(DLT, 1, 1.toShort()))).all().get(10, TimeUnit.SECONDS)
            }
            dltConsumer(fromBeginning = true).use { dlt ->
                nextDlt(dlt).value() shouldBe value
                awaitCommitted(metadata)
            }
        }
        ledger() shouldBe 0L
        output.all.contains("sensitive-dlt-marker") shouldBe false
        output.all.contains("StandardCreated DLT publication failed") shouldBe true
    }

    private fun send(
        participantId: Long,
        value: String,
    ): RecordMetadata = kafka.send(StandardCreatedConsumer.TOPIC, "$EXPO_ID:$participantId", value).get(10, TimeUnit.SECONDS).recordMetadata

    private fun payload(participantId: Long): String =
        """{"eventId":"${UUID.randomUUID()}","expoId":"$EXPO_ID","participantId":$participantId}"""

    private fun count(): Long? = jdbc.queryForObject("SELECT application_person FROM tb_expo WHERE id = ?", Long::class.java, EXPO_ID)

    private fun ledger(): Long? = jdbc.queryForObject("SELECT COUNT(*) FROM tb_standard_registration", Long::class.java)

    private fun serviceAttempts(participantId: Long): Int =
        mockingDetails(registrations).invocations.count {
            it.method.name == "execute" && it.arguments.toList() == listOf(EXPO_ID, participantId)
        }

    private fun admin(): Admin = Admin.create(mapOf("bootstrap.servers" to broker.brokersAsString))

    private fun committedOffset(admin: Admin): Long? =
        admin
            .listConsumerGroupOffsets("standard-created-test")
            .partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS)[TopicPartition(StandardCreatedConsumer.TOPIC, 0)]
            ?.offset()

    private fun awaitCommitted(metadata: RecordMetadata) {
        admin().use { admin ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (System.nanoTime() < deadline) {
                if ((committedOffset(admin) ?: -1) > metadata.offset()) return
                Thread.sleep(100)
            }
            error("Source offset was not committed")
        }
    }

    private fun dltConsumer(fromBeginning: Boolean = false): KafkaConsumer<String, String> =
        KafkaConsumer<String, String>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to broker.brokersAsString,
                ConsumerConfig.GROUP_ID_CONFIG to UUID.randomUUID().toString(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ),
        ).apply {
            val partition = TopicPartition(DLT, 0)
            assign(listOf(partition))
            if (fromBeginning) seekToBeginning(listOf(partition)) else seekToEnd(listOf(partition))
            position(partition)
        }

    private fun nextDlt(consumer: KafkaConsumer<String, String>): org.apache.kafka.clients.consumer.ConsumerRecord<String, String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (System.nanoTime() < deadline) {
            val records = consumer.poll(Duration.ofMillis(200))
            if (!records.isEmpty) return records.first()
        }
        error("No DLT record received")
    }

    private fun put(participantId: Long): Int =
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/internal/expo/$EXPO_ID/standard-registrations/$participantId"))
                    .header("X-Internal-Token", "test-internal-token")
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()

    companion object {
        private const val EXPO_ID = "0190abcd-0000-7000-8000-000000000001"
        private const val DLT = StandardCreatedConsumer.TOPIC + ".dlt"

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
