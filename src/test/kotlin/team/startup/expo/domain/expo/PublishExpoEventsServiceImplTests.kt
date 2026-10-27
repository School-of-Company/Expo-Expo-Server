package team.startup.expo.domain.expo

import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.mock.MockProducerFactory
import team.startup.expo.domain.expo.repository.ExpoEventOutboxRepository
import team.startup.expo.domain.expo.repository.ExpoEventOutboxRepository.Publication
import team.startup.expo.domain.expo.service.impl.PublishExpoEventsServiceImpl
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

class PublishExpoEventsServiceImplTests {
    private val mapper = JsonMapper.builder().build()
    private val outbox = mock(ExpoEventOutboxRepository::class.java)

    // KafkaTemplate은 비트랜잭션 송신 뒤 producer를 닫으므로 같은 MockProducer를 계속 쓰도록 닫기를 무시한다
    private val producer =
        object : MockProducer<String, String>(true, null, StringSerializer(), StringSerializer()) {
            override fun close() {}

            override fun close(timeout: Duration) {}
        }

    @Suppress("UNCHECKED_CAST")
    private val producers =
        (mock(ProducerFactory::class.java) as ProducerFactory<String, String>).also {
            `when`(it.copyWithConfigurationOverride(anyMap())).thenReturn(MockProducerFactory { producer })
        }

    private fun relay(batchSize: Int = 10) = PublishExpoEventsServiceImpl(outbox, producers, mapper, "localhost:9092", batchSize)

    private fun publication(
        sequence: Long,
        expoId: String,
        snapshot: String? = """{"expo":{"title":"제목"}}""",
    ) = Publication(sequence, "event-$sequence", "token-$sequence", expoId, snapshot, Instant.parse("2030-01-01T00:00:00Z"))

    @Test
    fun `선점한 순서대로 expoId 키로 발행하고 발행 완료로 표시한다`() {
        val first = publication(1, "expo-a")
        val second = publication(2, "expo-b", snapshot = null)
        `when`(outbox.claim()).thenReturn(first, second, null)

        relay().execute()

        producer.history().map { it.topic() to it.key() } shouldBe
            listOf("expo.expo.changed" to "expo-a", "expo.expo.changed" to "expo-b")
        val firstMessage = mapper.readTree(producer.history()[0].value())
        firstMessage.path("version").asLong() shouldBe 1
        firstMessage.path("eventId").asString() shouldBe "event-1"
        firstMessage.path("expo").path("title").asString() shouldBe "제목"
        mapper.readTree(producer.history()[1].value()).path("deleted").asBoolean() shouldBe true
        verify(outbox).published(first)
        verify(outbox).published(second)
    }

    @Test
    fun `발행에 실패하면 재시도로 미루고 이번 주기를 멈춘다`() {
        val first = publication(1, "expo-a")
        `when`(outbox.claim()).thenReturn(first, publication(2, "expo-b"), null)
        producer.sendException = KafkaException("broker down")

        relay().execute()

        verify(outbox).retry(first)
        verify(outbox, never()).published(first)
        // 실패 뒤에는 다음 행을 선점하지 않는다
        verify(outbox).claim()
    }

    @Test
    fun `한 주기에 batch-size 건까지만 발행한다`() {
        `when`(outbox.claim()).thenReturn(publication(1, "a"), publication(2, "b"), publication(3, "c"))

        relay(batchSize = 2).execute()

        producer.history().size shouldBe 2
    }

    @Test
    fun `켰는데 KAFKA_BOOTSTRAP_SERVERS가 없으면 시작을 거절한다`() {
        val failure = assertThrows(IllegalArgumentException::class.java) { PublishExpoEventsServiceImpl(outbox, producers, mapper, "") }

        failure.message shouldBe "KAFKA_BOOTSTRAP_SERVERS is required when expo events are enabled"
    }
}
