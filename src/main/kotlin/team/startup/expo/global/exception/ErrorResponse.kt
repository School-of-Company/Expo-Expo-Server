package team.startup.expo.global.exception

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema

data class ErrorResponse(
    @field:Schema(description = "HTTP 오류 상태 코드", example = "400")
    val status: Int,
    @field:Schema(description = "오류 원인", example = "잘못된 요청입니다.")
    val message: String,
    @field:Schema(description = "신청 서비스가 회차 변경을 거절한 사유 코드. 사유가 없으면 생략", example = "TIME_LOCKED_BY_HISTORY")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val code: String? = null,
)
