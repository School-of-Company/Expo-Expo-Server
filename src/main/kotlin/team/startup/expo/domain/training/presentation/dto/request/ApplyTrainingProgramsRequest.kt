package team.startup.expo.domain.training.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

data class ApplyTrainingProgramsRequest(
    @field:NotBlank
    @field:Schema(description = "연수자 코드. 가짜 예시", example = "sample-training-code")
    val trainingId: String,
    @field:NotEmpty
    @field:Schema(description = "프로그램 식별자 목록", example = "[1,2]")
    val trainingProIds: List<Long>,
)
