package team.startup.expo.domain.training.service

import team.startup.expo.domain.training.entity.Category
import java.time.Instant
import java.util.UUID

data class TrainingApplicationCommand(
    val trainee: TrainingTraineeReference,
    val programs: List<TrainingProgramReference>,
    val operationId: UUID,
    val expectedVersion: Long,
)

data class TrainingTraineeReference(
    val id: Long,
    val expoId: String,
)

data class TrainingProgramReference(
    val id: Long,
    val expoId: String,
    val category: Category,
)

enum class TrainingOperationType { ADD, REPLACE }

data class TrainingOperation(
    val eventId: String,
    val token: String,
    val type: TrainingOperationType,
    val expoId: String,
    val traineeId: Long,
    val method: String,
    val path: String,
    val commandJson: String,
    val expectedVersion: Long,
    val createdAt: Instant,
    val responseReceived: Boolean = false,
)

data class TrainingOperationReceipt(
    val operationId: UUID,
    val operationType: TrainingOperationType,
    val expoId: String,
    val traineeId: Long,
    val version: Long,
    val changed: Boolean,
    val programIds: List<Long>,
    val completedAt: Instant,
    val status: String = "SUCCEEDED",
)
