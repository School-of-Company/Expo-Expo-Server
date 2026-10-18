package team.startup.expo.domain.expo.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class InternalExpoPageResponse(
    @field:Schema(description = "진행 중이거나 예정된 박람회 목록")
    val content: List<InternalExpoSummaryResponse>,
    @field:Schema(description = "다음 페이지 여부", example = "false")
    val hasNext: Boolean,
)

data class InternalExpoSummaryResponse(
    @field:Schema(description = "박람회 식별자", example = "sample-expo")
    val expoId: String,
    @field:Schema(description = "제목", example = "예시 박람회")
    val title: String,
    @field:Schema(description = "박람회 시작일", example = "2030-01-01")
    val startedDay: String,
    @field:Schema(description = "박람회 종료일", example = "2030-01-02")
    val finishedDay: String,
)
