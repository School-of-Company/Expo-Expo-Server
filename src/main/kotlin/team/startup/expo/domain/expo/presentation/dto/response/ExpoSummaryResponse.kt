package team.startup.expo.domain.expo.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class ExpoSummaryResponse(
    @field:Schema(description = "박람회 식별자", example = "sample-expo")
    val id: String,
    @field:Schema(description = "제목", example = "예시 박람회")
    val title: String,
    @field:Schema(description = "설명", example = "가상 행사 설명")
    val description: String,
    @field:Schema(description = "박람회 시작일", example = "2030-01-01")
    val startedDay: String,
    @field:Schema(description = "박람회 종료일", example = "2030-01-02")
    val finishedDay: String,
    @field:Schema(description = "업로드한 대표 이미지 URL", example = "https://example.invalid/image/sample")
    val coverImage: String?,
)
