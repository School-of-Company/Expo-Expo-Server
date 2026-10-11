package team.startup.expo.domain.image.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema

data class UploadImageResponse(
    @field:Schema(description = "업로드 이미지 URL", example = "https://example.invalid/image/sample")
    val imageURL: String,
)
