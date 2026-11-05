package team.startup.expo.domain.expo.service.impl

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.messaging.ExpoChangedEvent
import team.startup.expo.domain.expo.repository.ExpoEventOutboxRepository
import team.startup.expo.domain.expo.service.PublishCommittedExpoEventService
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service
@ConditionalOnProperty(prefix = "expo.events", name = ["enabled"], havingValue = "true")
class PublishCommittedExpoEventServiceImpl(
    private val outbox: ExpoEventOutboxRepository,
    @Qualifier("outboxKafkaTemplate") private val kafka: KafkaTemplate<String, String>,
    private val mapper: ObjectMapper,
    registry: MeterRegistry,
) : PublishCommittedExpoEventService {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val success = registry.counter("expo.events.immediate", "result", "success")
    private val failure = registry.counter("expo.events.immediate", "result", "failure")
    private val executor =
        ThreadPoolExecutor(
            IMMEDIATE_THREADS,
            IMMEDIATE_THREADS,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(IMMEDIATE_QUEUE_CAPACITY),
        ) { task ->
            Thread(task, "expo-event-immediate").apply { isDaemon = true }
        }

    override fun execute(eventId: String) {
        try {
            executor.execute { publish(eventId) }
        } catch (_: RejectedExecutionException) {
            // 큐 포화·종료 시 요청 스레드에서 보내지 않고 안전망 릴레이에 맡긴다.
            failure.increment()
        }
    }

    private fun publish(eventId: String) {
        var publication: ExpoEventOutboxRepository.Publication? = null
        try {
            val claimed = outbox.claim(eventId) ?: return
            publication = claimed
            val message =
                ExpoChangedEvent.message(mapper, claimed.eventId, claimed.sequence, claimed.createdAt, claimed.expoId, claimed.snapshot)
            kafka.send(ExpoChangedEvent.TOPIC, claimed.expoId, message).get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            outbox.published(claimed)
            success.increment()
        } catch (exception: Exception) {
            if (exception is InterruptedException) Thread.currentThread().interrupt()
            failure.increment()
            logger.warn("Immediate expo event publication pending: eventId={}, type={}", eventId, exception.javaClass.simpleName)
            publication?.let {
                try {
                    outbox.retry(it)
                } catch (recording: Exception) {
                    logger.warn("Expo event retry recording failed: eventId={}, type={}", eventId, recording.javaClass.simpleName)
                }
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }

    private companion object {
        const val PUBLISH_TIMEOUT_SECONDS = 10L
        const val IMMEDIATE_THREADS = 1
        const val IMMEDIATE_QUEUE_CAPACITY = 100
    }
}
