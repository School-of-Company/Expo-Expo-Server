package team.startup.expo.domain.expo.messaging

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import team.startup.expo.domain.expo.service.RecordStandardRegistrationService
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
@ConditionalOnProperty(name = ["expo.standard-created.enabled"], havingValue = "true")
class StandardCreatedConsumer(
    private val mapper: ObjectMapper,
    private val registrations: RecordStandardRegistrationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [TOPIC], containerFactory = "standardCreatedContainerFactory")
    fun consume(record: ConsumerRecord<String, String>) {
        val event =
            try {
                val body = mapper.readTree(record.value())
                require(body.isObject)
                val eventId = body.get("eventId")
                val expoId = body.get("expoId")
                val participantId = body.get("participantId")
                require(eventId != null && eventId.isString && canonicalUuid(eventId.asString()))
                require(expoId != null && expoId.isString && canonicalUuid(expoId.asString()))
                require(participantId != null && participantId.isIntegralNumber && participantId.canConvertToLong())
                require(participantId.asLong() > 0)
                require(record.key() == "${expoId.asString()}:${participantId.asLong()}")
                expoId.asString() to participantId.asLong()
            } catch (_: Exception) {
                // Do not retain parser exceptions: they can contain the original payload.
                throw IllegalArgumentException("Invalid StandardCreated event")
            }
        try {
            registrations.execute(event.first, event.second)
        } catch (exception: ExpectedException) {
            if (exception.status != HttpStatus.NOT_FOUND && exception.status != HttpStatus.CONFLICT) throw exception
            log.info(
                "StandardCreated skipped: partition={}, offset={}, status={}",
                record.partition(),
                record.offset(),
                exception.status.value(),
            )
        }
    }

    private fun canonicalUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    companion object {
        const val TOPIC = "user.standard-participant.created"
    }
}
