package team.startup.expo.domain.training.presentation.dto.response

data class TrainingProgramTraineeResponse(
    val id: Long,
    val name: String,
    val programName: String,
    val status: Boolean,
    val entryTime: String?,
    val leaveTime: String?,
)
