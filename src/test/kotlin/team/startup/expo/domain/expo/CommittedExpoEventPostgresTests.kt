package team.startup.expo.domain.expo

import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.mock.MockProducerFactory
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.domain.expo.repository.CommittedExpoEventRepository
import team.startup.expo.domain.expo.repository.ExpoEventOutboxRepository
import team.startup.expo.domain.expo.service.ExpoEventSupport
import team.startup.expo.domain.expo.service.PublishCommittedExpoEventService
import team.startup.expo.domain.expo.service.PublishExpoEventsService
import team.startup.expo.support.TestJwt
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "expo.events.enabled=true",
        "spring.kafka.bootstrap-servers=localhost:9092",
        "expo.events.relay-delay-ms=3600000",
        "expo.events.recovery-delay-ms=3600000",
    ],
)
@Import(CommittedExpoEventPostgresTests.Producers::class)
@EntityScan("team.startup.expo.domain")
@Testcontainers
class CommittedExpoEventPostgresTests {
    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var transactions: TransactionTemplate

    @Autowired private lateinit var events: ExpoEventSupport

    @Autowired private lateinit var immediate: PublishCommittedExpoEventService

    @Autowired private lateinit var committed: CommittedExpoEventRepository

    @Autowired private lateinit var outbox: ExpoEventOutboxRepository

    @Autowired private lateinit var relay: PublishExpoEventsService

    @Autowired private lateinit var producer: MockProducer<String, String>

    @Autowired private lateinit var registry: MeterRegistry

    @BeforeEach
    fun setup() {
        transactions.executeWithoutResult {
            entityManager.createNativeQuery("TRUNCATE TABLE tb_expo_event_outbox RESTART IDENTITY").executeUpdate()
        }
        producer.clear()
        producer.sendException = null
    }

    @Test
    fun `커밋 후 즉시 발행하며 ACK를 기다리지 않고 반환하고 릴레이와 중복 발행하지 않는다`() {
        val successes = count("success")
        transactions.executeWithoutResult {
            events.changed("deleted-expo")
            events.changed("deleted-expo")
            producer.history().size shouldBe 0
        }

        await { producer.history().size == 1 }
        state() shouldBe "PENDING"
        relay.execute()
        immediate.execute(eventId())
        producer.completeNext() shouldBe true
        await { count("success") == successes + 1 }
        state() shouldBe "PUBLISHED"
        relay.execute()
        producer.history().size shouldBe 1
        producer.history().single().key() shouldBe "deleted-expo"
    }

    @Test
    fun `롤백하면 행과 즉시 발행이 없다`() {
        transactions.executeWithoutResult {
            events.changed("rolled-back")
            it.setRollbackOnly()
        }

        scalar("SELECT count(*) FROM tb_expo_event_outbox").toString() shouldBe "0"
        producer.history().size shouldBe 0
    }

    @Test
    fun `즉시 발행이 실패해도 커밋은 성공하고 재시도 시점에 릴레이가 보낸다`() {
        val failures = count("failure")
        producer.sendException = KafkaException("broker down")

        transactions.executeWithoutResult { events.changed("failed-expo") }

        await { count("failure") == failures + 1 && scalar("SELECT attempts FROM tb_expo_event_outbox").toString() == "1" }
        state() shouldBe "PENDING"
        scalar("SELECT publish_token IS NULL FROM tb_expo_event_outbox") shouldBe true
        producer.sendException = null
        await { scalar("SELECT next_attempt_at <= CURRENT_TIMESTAMP FROM tb_expo_event_outbox") == true }
        val publishing = CompletableFuture.runAsync { relay.execute() }
        await { producer.history().size == 1 }
        producer.completeNext() shouldBe true
        publishing.get(5, TimeUnit.SECONDS)
        state() shouldBe "PUBLISHED"
        producer.history().size shouldBe 1
    }

    @Test
    fun `앞선 미발행 행이 있으면 즉시 선점하지 않고 다른 박람회는 선점한다`() {
        val first = insert("same-expo")
        val later = insert("same-expo")
        val other = insert("other-expo")

        transactions.executeWithoutResult {
            entityManager
                .createNativeQuery(
                    "UPDATE tb_expo_event_outbox SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE event_id = :id",
                ).setParameter("id", first)
                .executeUpdate()
        }
        committed.claim(later) shouldBe null
        committed.claim(other)?.eventId shouldBe other
        transactions.executeWithoutResult {
            entityManager
                .createNativeQuery("UPDATE tb_expo_event_outbox SET next_attempt_at = CURRENT_TIMESTAMP WHERE event_id = :id")
                .setParameter("id", first)
                .executeUpdate()
        }
        val claimedFirst = requireNotNull(committed.claim(first))
        committed.claim(later) shouldBe null
        outbox.published(claimedFirst)
        committed.claim(later)?.eventId shouldBe later
    }

    @Test
    fun `릴레이가 이미 선점했거나 행 잠금을 잡은 행은 즉시 발행이 가져가지 않는다`() {
        val id = insert("locked-expo")
        transactions.executeWithoutResult {
            entityManager
                .createNativeQuery("SELECT sequence FROM tb_expo_event_outbox WHERE event_id = :id FOR UPDATE")
                .setParameter("id", id)
                .singleResult
            CompletableFuture.supplyAsync { committed.claim(id) }.get(5, TimeUnit.SECONDS) shouldBe null
        }
        val publication = requireNotNull(outbox.claim())
        committed.claim(id) shouldBe null
        outbox.published(publication)
        committed.claim(id) shouldBe null
    }

    private fun insert(expoId: String): String {
        val id = UUID.randomUUID().toString()
        transactions.executeWithoutResult {
            entityManager
                .createNativeQuery("INSERT INTO tb_expo_event_outbox (event_id, expo_id, deleted) VALUES (:id, :expoId, true)")
                .setParameter("id", id)
                .setParameter("expoId", expoId)
                .executeUpdate()
        }
        return id
    }

    private fun scalar(sql: String): Any = requireNotNull(transactions.execute { entityManager.createNativeQuery(sql).singleResult })

    private fun state() = scalar("SELECT state FROM tb_expo_event_outbox").toString()

    private fun eventId() = scalar("SELECT event_id FROM tb_expo_event_outbox").toString()

    private fun count(result: String) = registry.counter("expo.events.immediate", "result", result).count()

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for immediate publication" }
            Thread.sleep(10)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Producers {
        @Bean
        fun kafkaTemplate(producers: ProducerFactory<String, String>) = KafkaTemplate(producers)

        @Bean
        fun producer(): MockProducer<String, String> =
            object : MockProducer<String, String>(false, null, StringSerializer(), StringSerializer()) {
                override fun close() {}

                override fun close(timeout: Duration) {}
            }

        @Bean
        @Primary
        @Suppress("UNCHECKED_CAST")
        fun producers(producer: MockProducer<String, String>): ProducerFactory<String, String> =
            (mock(ProducerFactory::class.java) as ProducerFactory<String, String>).also {
                `when`(it.copyWithConfigurationOverride(anyMap())).thenReturn(MockProducerFactory { producer })
            }
    }

    companion object {
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
