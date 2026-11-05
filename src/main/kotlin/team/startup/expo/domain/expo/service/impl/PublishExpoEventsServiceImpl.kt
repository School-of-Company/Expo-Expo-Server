package team.startup.expo.domain.expo.service.impl

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.messaging.ExpoChangedEvent
import team.startup.expo.domain.expo.repository.ExpoEventOutboxRepository
import team.startup.expo.domain.expo.service.PublishExpoEventsService
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

@Service
@ConditionalOnProperty(prefix = "expo.events", name = ["enabled"], havingValue = "true")
class PublishExpoEventsServiceImpl(
    private val outbox: ExpoEventOutboxRepository,
    @Qualifier("outboxKafkaTemplate") private val kafka: KafkaTemplate<String, String>,
    private val mapper: ObjectMapper,
    @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
    @Value("\${expo.events.batch-size}") private val batchSize: Int,
) : PublishExpoEventsService {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(bootstrapServers.isNotBlank()) { "KAFKA_BOOTSTRAP_SERVERS is required when expo events are enabled" }
        require(batchSize in 1..500) { "Expo event batch size must be between 1 and 500" }
    }

    @Scheduled(
        fixedDelayString = "\${expo.events.relay-delay-ms}",
        initialDelayString = "\${expo.events.relay-delay-ms}",
    )
    override fun execute() {
        repeat(batchSize) {
            val publication =
                try {
                    outbox.claim()
                } catch (exception: Exception) {
                    logger.warn("Expo event claim failed: type={}", exception.javaClass.simpleName)
                    return
                } ?: return
            try {
                val message =
                    ExpoChangedEvent.message(
                        mapper,
                        publication.eventId,
                        publication.sequence,
                        publication.createdAt,
                        publication.expoId,
                        publication.snapshot,
                    )
                kafka.send(ExpoChangedEvent.TOPIC, publication.expoId, message).get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                outbox.published(publication)
            } catch (exception: Exception) {
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                logger.warn(
                    "Expo event publication pending: eventId={}, version={}, type={}",
                    publication.eventId,
                    publication.sequence,
                    exception.javaClass.simpleName,
                )
                try {
                    outbox.retry(publication)
                } catch (recording: Exception) {
                    logger.warn(
                        "Expo event retry recording failed: eventId={}, type={}",
                        publication.eventId,
                        recording.javaClass.simpleName,
                    )
                }
                // 브로커 장애면 남은 행도 실패하므로 이번 주기는 멈추고 다음 주기를 기다린다.
                return
            }
        }
    }

    private companion object {
        const val PUBLISH_TIMEOUT_SECONDS = 10L
    }
}
