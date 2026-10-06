package team.startup.expo.domain.training.presentation.dto.request

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

data class TrainingProgramBatchRequest(
    @field:NotNull
    @field:Size(max = 100)
    val programIds: List<@NotNull Long>,
)
