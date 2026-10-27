package team.startup.expo.domain.expo.service.impl

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.repository.ExpoEventOutboxRepository
import team.startup.expo.domain.expo.service.RecoverExpoEventsService

@Service
@ConditionalOnProperty(prefix = "expo.events", name = ["enabled"], havingValue = "true")
class RecoverExpoEventsServiceImpl(
    private val outbox: ExpoEventOutboxRepository,
) : RecoverExpoEventsService {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${expo.events.recovery-delay-ms:60000}",
        initialDelayString = "\${expo.events.recovery-delay-ms:60000}",
    )
    override fun execute() {
        val stuck =
            try {
                outbox.maintain()
            } catch (exception: Exception) {
                logger.warn("Expo event maintenance pending: type={}", exception.javaClass.simpleName)
                return
            }
        if (stuck.count > 0) {
            logger.error("Expo events pending over 10 minutes: count={}, oldestVersion={}", stuck.count, stuck.oldestSequence)
        }
    }
}
