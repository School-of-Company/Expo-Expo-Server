package team.startup.expo.domain.standard.presentation.dto.response

data class StandardProgramResponse(
    val id: Long,
    val title: String,
    val startedAt: String,
    val endedAt: String,
)

data class StandardProgramParticipantResponse(
    val id: Long,
    val name: String,
    val programName: String,
    val status: Boolean,
    val entryTime: String?,
    val leaveTime: String?,
)
