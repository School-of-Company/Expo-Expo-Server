package team.startup.expo.domain.training.service.impl

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import team.startup.expo.domain.training.service.HoldReason
import team.startup.expo.domain.training.service.PublishTrainingSmsService
import team.startup.expo.domain.training.service.TrainingDependenciesClient
import team.startup.expo.domain.training.service.TrainingSmsOutboxSupport
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

@Service
@ConditionalOnProperty(prefix = "expo.training.sms", name = ["enabled"], havingValue = "true")
class PublishTrainingSmsServiceImpl(
    private val outbox: TrainingSmsOutboxSupport,
    @Qualifier("outboxKafkaTemplate") private val kafka: KafkaTemplate<String, String>,
    private val dependencies: TrainingDependenciesClient,
    private val mapper: ObjectMapper,
    @Value("\${expo.training.sms.batch-size}") private val batchSize: Int,
    @Value("\${expo.training.sms.contract-verified}") private val contractVerified: Boolean,
) : PublishTrainingSmsService {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(batchSize in 1..100) { "Training SMS batch size must be between 1 and 100" }
        require(contractVerified) { "Training SMS requires verified Notification deployment and replay contracts" }
    }

    @Scheduled(
        fixedDelayString = "\${expo.training.sms.relay-delay-ms}",
        initialDelayString = "\${expo.training.sms.relay-delay-ms}",
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
                    outbox.holdPublication(publication, HoldReason.STALE_RECEIPT)
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
                kafka.send(SMS_TOPIC, publication.eventId, payload).get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
                return
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

    private companion object {
        const val PUBLISH_TIMEOUT_SECONDS = 10L
        const val SMS_TOPIC = "notification.sms.requested"
    }
}
