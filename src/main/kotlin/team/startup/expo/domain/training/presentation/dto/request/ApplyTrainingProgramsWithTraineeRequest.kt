package team.startup.expo.domain.training.presentation.dto.request

import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull

// v1 ApplicationTrainingProListAndTraineeRequestDto와 같은 필드와 필수 조건을 유지한다
data class ApplyTrainingProgramsWithTraineeRequest(
    @field:NotNull
    val trainingId: String,
    @field:NotNull
    val phoneNumber: String,
    @field:NotNull
    val name: String,
    @field:NotNull
    val informationJson: String,
    @field:NotNull
    val personalInformationStatus: Boolean,
    @field:NotEmpty
    val trainingProIds: List<Long>,
)
