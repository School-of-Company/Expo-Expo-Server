package team.startup.expo.domain.training.service.impl

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import team.startup.expo.domain.training.repository.TrainingSmsOutboxRepository
import team.startup.expo.domain.training.service.PublishTrainingSmsService
import team.startup.expo.domain.training.service.TrainingDependenciesClient
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

@Service
@ConditionalOnProperty(prefix = "expo.training.sms", name = ["enabled"], havingValue = "true")
class PublishTrainingSmsServiceImpl(
    private val outbox: TrainingSmsOutboxRepository,
    producers: ProducerFactory<String, String>,
    private val dependencies: TrainingDependenciesClient,
    private val mapper: ObjectMapper,
    @Value("\${expo.training.sms.batch-size:20}") private val batchSize: Int = 20,
    @Value("\${expo.training.sms.contract-verified:false}") private val contractVerified: Boolean = false,
) : PublishTrainingSmsService {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val kafka = KafkaTemplate(producers, mapOf("max.block.ms" to 5000)).apply { setProducerListener(null) }

    init {
        require(batchSize in 1..100) { "Training SMS batch size must be between 1 and 100" }
        require(contractVerified) { "Training SMS requires verified Notification deployment and replay contracts" }
    }

    @PreDestroy
    fun shutdown() {
        kafka.destroy()
    }

    @Scheduled(
        fixedDelayString = "\${expo.training.sms.relay-delay-ms:5000}",
        initialDelayString = "\${expo.training.sms.relay-delay-ms:5000}",
    )
    override fun execute() {
        repeat(batchSize) {
            val publication =
                try {
                    outbox.claim()
                } catch (exception: Exception) {
                    logger.warn("Training SMS claim failed: type={}", exception.javaClass.simpleName)
                    return
                } ?: return
            var acknowledged = false
            try {
                if (publication.firstPublishAttemptAt == null &&
                    dependencies.applicationVersion(publication.traineeId) != publication.receiptVersion
                ) {
                    outbox.holdPublication(publication, "STALE_RECEIPT")
                    return@repeat
                }
                val payload =
                    publication.payload ?: mapper
                        .writeValueAsString(
                            TrainingSmsEvent(
                                publication.eventId,
                                phoneNumbers = listOf(dependencies.traineePhoneNumber(publication.expoId, publication.traineeId)),
                                text = requireNotNull(publication.text),
                            ),
                        ).also { if (!outbox.savePayload(publication, it)) return@repeat }
                if (!outbox.beginPublication(publication)) return@repeat
                kafka.send("notification.sms.requested", publication.eventId, payload).get(10, TimeUnit.SECONDS)
                acknowledged = true
                outbox.sent(publication)
            } catch (exception: Exception) {
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                logger.warn(
                    "Training SMS publication pending: eventId={}, acknowledged={}, type={}",
                    publication.eventId,
                    acknowledged,
                    exception.javaClass.simpleName,
                )
                try {
                    outbox.retry(publication)
                } catch (recording: Exception) {
                    logger.warn(
                        "Training SMS retry recording failed: eventId={}, type={}",
                        publication.eventId,
                        recording.javaClass.simpleName,
                    )
                }
                if (exception is InterruptedException) return
            }
        }
    }

    private data class TrainingSmsEvent(
        val eventId: String,
        val version: Int = 1,
        val type: String = "CUSTOM",
        val phoneNumbers: List<String>,
        val senderType: String = "TRAINEE",
        val text: String,
    )
}
