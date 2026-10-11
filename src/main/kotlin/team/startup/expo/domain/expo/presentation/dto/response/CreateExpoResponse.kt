package team.startup.expo.domain.expo.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class CreateExpoResponse(
    @field:Schema(description = "박람회 식별자", example = "sample-expo")
    val expoId: String,
)
