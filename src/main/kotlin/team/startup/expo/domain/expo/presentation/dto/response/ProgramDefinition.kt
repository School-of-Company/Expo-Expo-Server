package team.startup.expo.domain.expo.presentation.dto.response

import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoRequest
import team.startup.expo.domain.training.entity.Category
import java.util.UUID

enum class ProgramType { STANDARD, TRAINING }

data class ProgramDefinition(
    val expoId: String,
    val programType: ProgramType,
    val id: Long?,
    val title: String,
    val startedAt: String,
    val endedAt: String,
    val category: Category? = null,
    val groupId: UUID,
    val policy: ProgramPolicyRequest?,
    val revision: Long,
)

data class ProgramMutation(
    val previous: ProgramDefinition?,
    val definition: ProgramDefinition?,
)

data class ProgramChangePayload(
    val mutations: List<ProgramMutation>,
    val expoUpdate: UpdateExpoRequest? = null,
    val uploadedBy: String? = null,
)
