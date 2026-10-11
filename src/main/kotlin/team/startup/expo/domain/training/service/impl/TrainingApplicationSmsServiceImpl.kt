package team.startup.expo.domain.training.service.impl

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.repository.TrainingSmsOutboxRepository
import team.startup.expo.domain.training.service.TrainingApplicationCommand
import team.startup.expo.domain.training.service.TrainingApplicationSmsService
import team.startup.expo.domain.training.service.TrainingDependenciesClient
import team.startup.expo.domain.training.service.TrainingOperationType
import team.startup.expo.domain.training.service.TrainingProgramReference
import team.startup.expo.domain.training.service.TrainingTraineeReference
import team.startup.expo.global.exception.ExpectedException
import java.util.UUID

@Service
class TrainingApplicationSmsServiceImpl(
    @Value("\${expo.training.sms.enabled:false}") private val enabled: Boolean,
    private val outbox: TrainingSmsOutboxRepository,
    private val dependencies: TrainingDependenciesClient,
) : TrainingApplicationSmsService {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun execute(
        expo: Expo,
        traineeId: Long,
        programs: List<TrainingProgram>,
        type: TrainingOperationType,
        sendSms: Boolean,
    ) {
        val fullText =
            buildString {
                append("[${expo.title}] 연수 신청이 완료되었습니다.\n")
                programs
                    .sortedWith(
                        compareBy({ it.startedAt }, { it.id }),
                    ).forEach { append("* ${it.startedAt} ~ ${it.endedAt}: ${it.title}\n") }
            }.trimEnd()
        val text = if (fullText.length <= 2000) fullText else "연수 신청이 완료되었습니다. 신청 내역은 신청 화면에서 확인해 주세요."
        val command =
            TrainingApplicationCommand(
                TrainingTraineeReference(traineeId, expo.id),
                programs.sortedBy { it.id }.map { TrainingProgramReference(requireNotNull(it.id), expo.id, it.category) },
                UUID.randomUUID(),
                dependencies.applicationVersion(traineeId),
            )
        val operation =
            try {
                outbox.prepare(command, type, text.takeIf { enabled && sendSms && programs.isNotEmpty() })
            } catch (exception: ExpectedException) {
                throw exception
            } catch (exception: Exception) {
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                logger.warn("Training operation preparation failed: type={}", exception.javaClass.simpleName)
                throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "연수 신청 작업을 저장할 수 없습니다.")
            }
        try {
            dependencies.executeOperation(operation)
        } catch (exception: Exception) {
            val definite =
                exception is ExpectedException &&
                    exception.status in setOf(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND, HttpStatus.CONFLICT)
            record(operation.eventId) { outbox.pending(operation, definite) }
            throw exception
        }
        record(operation.eventId) { outbox.responseReceived(operation) }
        try {
            val receipt = dependencies.operationReceipt(operation)
            record(operation.eventId) {
                if (receipt == null) outbox.pending(operation) else outbox.confirmed(operation, receipt)
            }
        } catch (exception: Exception) {
            if (exception is InterruptedException) Thread.currentThread().interrupt()
            logger.warn("Training receipt lookup pending: eventId={}, type={}", operation.eventId, exception.javaClass.simpleName)
            record(operation.eventId) { outbox.pending(operation) }
        }
    }

    private fun record(
        eventId: String,
        work: () -> Unit,
    ) {
        try {
            work()
        } catch (exception: Exception) {
            logger.warn("Training operation update pending: eventId={}, type={}", eventId, exception.javaClass.simpleName)
        }
    }
}
