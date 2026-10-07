package team.startup.expo.domain.training.service.impl

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.repository.TrainingSmsOutboxRepository
import team.startup.expo.domain.training.service.TrainingApplicationSmsService
import team.startup.expo.global.exception.ExpectedException
import java.util.concurrent.TimeUnit

@Service
class TrainingApplicationSmsServiceImpl(
    @Value("\${expo.training.sms.enabled:false}") private val enabled: Boolean,
    private val outbox: TrainingSmsOutboxRepository,
) : TrainingApplicationSmsService {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun execute(
        expo: Expo,
        traineeId: Long,
        programs: List<TrainingProgram>,
        mode: String,
        apply: () -> Unit,
    ) {
        if (!enabled || programs.isEmpty()) {
            apply()
            return
        }
        val fullText =
            buildString {
                append("[${expo.title}] 연수 신청이 완료되었습니다.\n")
                programs
                    .sortedWith(
                        compareBy({ it.startedAt }, { it.id }),
                    ).forEach { append("* ${it.startedAt} ~ ${it.endedAt}: ${it.title}\n") }
            }.trimEnd()
        val text = if (fullText.length <= 2000) fullText else "연수 신청이 완료되었습니다. 신청 내역은 신청 화면에서 확인해 주세요."
        val fingerprint = "$mode:${programs.map { requireNotNull(it.id) }.sorted().joinToString(",")}"
        val operation =
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var prepared = outbox.prepare(expo.id, traineeId, fingerprint, text)
                while (prepared == null && System.nanoTime() < deadline) {
                    Thread.sleep(25)
                    prepared = outbox.prepare(expo.id, traineeId, fingerprint, text)
                }
                if (prepared == null) logger.warn("Training SMS journal busy")
                prepared
            } catch (exception: Exception) {
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                logger.warn("Training SMS journal preparation failed: type={}", exception.javaClass.simpleName)
                null
            }
        try {
            apply()
        } catch (exception: Exception) {
            if (operation != null) {
                val definite =
                    exception is ExpectedException &&
                        exception.status in setOf(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND, HttpStatus.CONFLICT)
                record(operation.eventId) { outbox.failed(operation, definite) }
            }
            throw exception
        }
        if (operation != null) record(operation.eventId) { outbox.confirmed(operation) }
    }

    private fun record(
        eventId: String,
        work: () -> Unit,
    ) {
        try {
            work()
        } catch (exception: Exception) {
            logger.warn("Training SMS journal update failed: eventId={}, type={}", eventId, exception.javaClass.simpleName)
        }
    }
}
