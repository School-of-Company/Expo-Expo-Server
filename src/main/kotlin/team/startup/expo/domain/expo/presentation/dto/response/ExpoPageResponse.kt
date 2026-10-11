package team.startup.expo.domain.expo.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class ExpoPageResponse(
    @field:Schema(description = "박람회 목록", example = "[]")
    val content: List<ExpoSummaryResponse>,
    @field:Schema(description = "0부터 시작하는 페이지 번호", example = "0")
    val page: Int,
    @field:Schema(description = "페이지 크기. 1~100, 기본 20", example = "20")
    val size: Int,
    @field:Schema(description = "전체 항목 수", example = "1")
    val totalElements: Long,
    @field:Schema(description = "전체 페이지 수", example = "1")
    val totalPages: Int,
    @field:Schema(description = "다음 페이지 여부", example = "false")
    val hasNext: Boolean,
)
