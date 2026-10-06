package team.startup.expo.domain.training.presentation.dto.request

import jakarta.validation.constraints.NotNull

data class ApplyTrainingProgramRequest(
    @field:NotNull
    val trainingId: String,
)
