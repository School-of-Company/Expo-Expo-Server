package team.startup.expo.domain.training.service

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.ProgramChangeRepository
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.repository.TrainingSmsOutboxRepository
import team.startup.expo.domain.training.repository.TrainingSmsOutboxRepository.OperationRow
import team.startup.expo.domain.training.repository.TrainingSmsOutboxRepository.Publication
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.databind.ObjectMapper
import java.util.UUID

enum class HoldReason {
    LEGACY_UNCERTAIN,
    LEGACY_PUBLICATION_UNCERTAIN,
    APPLICATION_REJECTED,
    RECOVERY_REJECTED,
    RECOVERY_LIMIT,
    SMS_EXPIRED,
    STALE_RECEIPT,
}

@Component
class TrainingSmsOutboxSupport(
    private val outbox: TrainingSmsOutboxRepository,
    private val expos: ExpoRepository,
    private val trainings: TrainingProgramRepository,
    private val changes: ProgramChangeRepository,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
) {
    fun prepare(
        command: TrainingApplicationCommand,
        type: TrainingOperationType,
        text: String?,
    ): TrainingOperation =
        requireNotNull(
            transactions.execute {
                val deleting =
                    expos.lockTrainingApplication(command.trainee.expoId)
                        ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
                if (deleting) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
                if (type == TrainingOperationType.REPLACE && trainings.existsByExpoIdAndPolicyJsonIsNotNull(command.trainee.expoId)) {
                    throw ExpectedException(HttpStatus.CONFLICT, "정책이 설정된 프로그램은 기존 일괄 교체 신청을 사용할 수 없습니다.")
                }
                if (changes.existsById(command.trainee.expoId) ||
                    (command.programs.isNotEmpty() && trainings.existsByIdInAndPolicyJsonIsNotNull(command.programs.map { it.id }))
                ) {
                    throw ExpectedException(HttpStatus.CONFLICT, "정책이 설정됐거나 변경 중인 프로그램에는 기존 신청을 사용할 수 없습니다.")
                }
                val token = UUID.randomUUID().toString()
                outbox
                    .prepare(
                        command,
                        type.name,
                        text,
                        token,
                        type.method,
                        type.path(command.trainee.id),
                        mapper.writeValueAsString(command),
                        text != null,
                    ).single()
                    .operation()
            },
        )

    fun hasUnresolvedApplications(expoId: String): Boolean = outbox.hasUnresolvedApplications(expoId)

    fun confirmed(
        operation: TrainingOperation,
        receipt: TrainingOperationReceipt,
    ) {
        transactions.executeWithoutResult {
            outbox.confirmed(
                operation,
                mapper.writeValueAsString(receipt),
                receipt.version,
                receipt.changed && receipt.programIds.isNotEmpty(),
            )
        }
    }

    fun responseReceived(operation: TrainingOperation) {
        transactions.executeWithoutResult { outbox.responseReceived(operation) }
    }

    fun pending(
        operation: TrainingOperation,
        rejected: Boolean = false,
    ) {
        transactions.executeWithoutResult {
            outbox.pending(
                operation,
                if (rejected) REJECTED else UNKNOWN,
                if (rejected) REJECTED else PENDING,
                if (rejected) HoldReason.APPLICATION_REJECTED.name else null,
                rejected,
            )
        }
    }

    fun claimRecovery(): TrainingOperation? =
        transactions.execute {
            outbox.claimRecovery(UUID.randomUUID().toString()).firstOrNull()?.operation()
        }

    fun canReplay(operation: TrainingOperation): Boolean = outbox.canReplay(operation) == true

    fun hold(
        operation: TrainingOperation,
        reason: HoldReason,
    ) {
        transactions.executeWithoutResult { outbox.hold(operation, reason.name) }
    }

    fun claim(): Publication? =
        transactions.execute {
            outbox.claim(UUID.randomUUID().toString()).firstOrNull()?.let {
                Publication(
                    it.eventId,
                    it.publishToken,
                    it.expoId,
                    it.traineeId,
                    it.notificationText,
                    it.payload,
                    it.receiptVersion ?: 0,
                    it.firstPublishAttemptAt,
                )
            }
        }

    fun savePayload(
        publication: Publication,
        payload: String,
    ): Boolean = transactions.execute { outbox.savePayload(publication, payload) == 1 } == true

    fun beginPublication(publication: Publication): Boolean = transactions.execute { outbox.beginPublication(publication) == 1 } == true

    fun holdPublication(
        publication: Publication,
        reason: HoldReason,
    ) {
        transactions.executeWithoutResult { outbox.holdPublication(publication, reason.name) }
    }

    fun maintain() {
        transactions.executeWithoutResult {
            outbox.holdRecoveryLimit(HoldReason.RECOVERY_LIMIT.name)
            outbox.expireSms(HoldReason.SMS_EXPIRED.name)
            outbox.deleteTerminal()
        }
    }

    fun sent(publication: Publication) {
        transactions.executeWithoutResult { outbox.sent(publication) }
    }

    fun retry(publication: Publication) {
        transactions.executeWithoutResult { outbox.retry(publication) }
    }

    private fun OperationRow.operation() =
        TrainingOperation(
            eventId,
            leaseToken,
            TrainingOperationType.valueOf(operationType),
            expoId,
            traineeId,
            requestMethod,
            requestPath,
            commandJson,
            expectedVersion,
            createdAt,
            responseReceived,
        )

    private companion object {
        const val REJECTED = "REJECTED"
        const val UNKNOWN = "UNKNOWN"
        const val PENDING = "PENDING"
    }
}
