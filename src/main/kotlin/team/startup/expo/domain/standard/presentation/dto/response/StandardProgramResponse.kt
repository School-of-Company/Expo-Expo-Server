package team.startup.expo.domain.standard.presentation.dto.response

import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import java.util.UUID

data class StandardProgramResponse(
    val id: Long,
    val title: String,
    val startedAt: String,
    val endedAt: String,
    val expoId: String? = null,
    val programType: ProgramType = ProgramType.STANDARD,
    val groupId: UUID? = null,
    val policy: ProgramPolicyRequest? = null,
    val revision: Long = 1,
    val pendingRevision: Long? = null,
)

data class StandardProgramParticipantResponse(
    val id: Long,
    val name: String,
    val programName: String,
    val status: Boolean,
    val entryTime: String?,
    val leaveTime: String?,
)

data class StandardProgramTitleResponse(
    val id: Long,
    val title: String,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val expoId: String? = null,
    val programType: ProgramType = ProgramType.STANDARD,
    val groupId: UUID? = null,
    val policy: ProgramPolicyRequest? = null,
    val revision: Long = 1,
    val pendingRevision: Long? = null,
)
