package team.startup.expo.global.exception

import io.swagger.v3.oas.annotations.media.Schema

data class ErrorResponse(
    @field:Schema(description = "HTTP 오류 상태 코드", example = "400")
    val status: Int,
    @field:Schema(description = "오류 원인", example = "잘못된 요청입니다.")
    val message: String,
)
