package team.startup.expo.domain.training.presentation.dto.response

import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.training.entity.Category
import java.util.UUID

data class TrainingProgramResponse(
    val id: Long,
    val title: String,
    val startedAt: String,
    val endedAt: String,
    val category: Category,
    val expoId: String? = null,
    val programType: ProgramType = ProgramType.TRAINING,
    val groupId: UUID? = null,
    val policy: ProgramPolicyRequest? = null,
    val revision: Long = 1,
    val pendingRevision: Long? = null,
)
