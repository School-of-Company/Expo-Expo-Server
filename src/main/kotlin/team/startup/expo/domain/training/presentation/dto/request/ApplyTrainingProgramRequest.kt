package team.startup.expo.domain.training.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

data class ApplyTrainingProgramRequest(
    @field:NotBlank
    @field:Schema(description = "연수자 코드. 가짜 예시", example = "sample-training-code")
    val trainingId: String,
)
