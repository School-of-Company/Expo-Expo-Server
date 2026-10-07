package team.startup.expo.domain.training.presentation.dto.request

import jakarta.validation.constraints.NotBlank

data class ApplyTrainingProgramRequest(
    @field:NotBlank
    val trainingId: String,
)
