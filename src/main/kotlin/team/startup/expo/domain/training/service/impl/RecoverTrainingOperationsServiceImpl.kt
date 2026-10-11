package team.startup.expo.domain.training.service.impl

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import team.startup.expo.domain.training.repository.TrainingSmsOutboxRepository
import team.startup.expo.domain.training.service.RecoverTrainingOperationsService
import team.startup.expo.domain.training.service.TrainingDependenciesClient
import team.startup.expo.global.exception.ExpectedException

@Service
class RecoverTrainingOperationsServiceImpl(
    private val outbox: TrainingSmsOutboxRepository,
    private val dependencies: TrainingDependenciesClient,
) : RecoverTrainingOperationsService {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${expo.training.operations.recovery-delay-ms:30000}",
        initialDelayString = "\${expo.training.operations.recovery-delay-ms:30000}",
    )
    override fun execute() {
        try {
            outbox.maintain()
        } catch (exception: Exception) {
            logger.warn("Training operation maintenance pending: type={}", exception.javaClass.simpleName)
            return
        }
        repeat(20) {
            val operation =
                try {
                    outbox.claimRecovery()
                } catch (exception: Exception) {
                    logger.warn("Training operation claim pending: type={}", exception.javaClass.simpleName)
                    return
                } ?: return
            try {
                var receipt = dependencies.operationReceipt(operation)
                if (receipt == null && outbox.canReplay(operation)) {
                    try {
                        dependencies.executeOperation(operation)
                        outbox.responseReceived(operation)
                    } catch (exception: ExpectedException) {
                        if (exception.status !in setOf(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND, HttpStatus.CONFLICT)) throw exception
                        receipt = dependencies.operationReceipt(operation)
                        if (receipt == null) {
                            outbox.hold(operation, "RECOVERY_REJECTED")
                            return@repeat
                        }
                    }
                    if (receipt == null) receipt = dependencies.operationReceipt(operation)
                }
                if (receipt == null) outbox.pending(operation) else outbox.confirmed(operation, receipt)
            } catch (exception: Exception) {
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                logger.warn("Training operation recovery pending: eventId={}, type={}", operation.eventId, exception.javaClass.simpleName)
                try {
                    outbox.pending(operation)
                } catch (recording: Exception) {
                    logger.warn(
                        "Training operation recovery recording failed: eventId={}, type={}",
                        operation.eventId,
                        recording.javaClass.simpleName,
                    )
                }
                if (Thread.currentThread().isInterrupted) return
            }
        }
    }
}
