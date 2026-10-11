package team.startup.expo.domain.training.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

data class TrainingProgramBatchRequest(
    @field:NotNull
    @field:Size(max = 100)
    @field:Schema(description = "프로그램 식별자 목록", example = "[1,2]")
    val programIds: List<@NotNull Long>,
)
