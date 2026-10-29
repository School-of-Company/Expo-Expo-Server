package team.startup.expo.domain.training

import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.mock.MockProducerFactory
import team.startup.expo.domain.training.repository.TrainingSmsOutboxRepository
import team.startup.expo.domain.training.repository.TrainingSmsOutboxRepository.Publication
import team.startup.expo.domain.training.service.TrainingDependenciesClient
import team.startup.expo.domain.training.service.impl.PublishTrainingSmsServiceImpl
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

class PublishTrainingSmsServiceImplTests {
    @Test
    fun `Kafka 발행 실패는 재시도로 미루고 남은 건은 다음 주기에 발행한다`() {
        val outbox = mock(TrainingSmsOutboxRepository::class.java)
        val dependencies = mock(TrainingDependenciesClient::class.java)
        val first = publication("first")
        val second = publication("second")
        `when`(outbox.claim()).thenReturn(first, second, null)
        `when`(outbox.beginPublication(first)).thenReturn(true)
        `when`(outbox.beginPublication(second)).thenReturn(true)
        // KafkaTemplate이 송신 뒤 닫아도 다음 주기에 같은 MockProducer를 재사용한다.
        val producer =
            object : MockProducer<String, String>(true, null, StringSerializer(), StringSerializer()) {
                override fun close() {}

                override fun close(timeout: Duration) {}
            }

        @Suppress("UNCHECKED_CAST")
        val producers = mock(ProducerFactory::class.java) as ProducerFactory<String, String>
        `when`(producers.copyWithConfigurationOverride(anyMap())).thenReturn(MockProducerFactory { producer })
        val relay = PublishTrainingSmsServiceImpl(outbox, producers, dependencies, JsonMapper.builder().build(), contractVerified = true)

        producer.sendException = KafkaException("broker down")

        relay.execute()

        verify(outbox).retry(first)
        verify(outbox, never()).sent(first)
        verify(outbox, never()).beginPublication(second)
        verify(outbox).claim()

        producer.sendException = null

        relay.execute()

        producer.history().map { it.key() } shouldBe listOf(second.eventId)
        verify(outbox).sent(second)
        verify(outbox, times(3)).claim()
    }

    private fun publication(eventId: String) =
        Publication(
            eventId = eventId,
            token = "token-$eventId",
            expoId = "expo",
            traineeId = 42L,
            text = null,
            payload = "{}",
            receiptVersion = 1L,
            firstPublishAttemptAt = Instant.parse("2030-01-01T00:00:00Z"),
        )
}
