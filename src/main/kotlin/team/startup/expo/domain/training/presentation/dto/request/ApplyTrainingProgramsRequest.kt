package team.startup.expo.domain.training.presentation.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

data class ApplyTrainingProgramsRequest(
    @field:NotBlank
    val trainingId: String,
    @field:NotEmpty
    val trainingProIds: List<Long>,
)
