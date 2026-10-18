package team.startup.expo.domain.expo.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

data class InternalExpoPageRequest(
    @field:Min(0)
    @field:Schema(description = "0부터 시작하는 페이지 번호", example = "0")
    val page: Int = 0,
    @field:Min(1)
    @field:Max(100)
    @field:Schema(description = "페이지 크기. 1~100, 기본 100", example = "100")
    val size: Int = 100,
)
