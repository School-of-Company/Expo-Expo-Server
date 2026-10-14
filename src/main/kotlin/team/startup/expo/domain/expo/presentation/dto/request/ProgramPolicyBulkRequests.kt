package team.startup.expo.domain.expo.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.HttpStatus
import team.startup.expo.global.exception.ExpectedException
import java.time.Instant

data class UpdateProgramApplicationPeriodRequest(
    @field:Schema(description = "모집 시작 일시 (UTC 오프셋 ISO 8601). 종료와 함께 null이면 신청기간 해제(신청 불가)", example = "2030-01-01T10:00:00Z")
    val applicationStartedAt: Instant? = null,
    @field:Schema(description = "모집 종료 일시 (UTC 오프셋 ISO 8601). 시작과 함께 null이면 신청기간 해제(신청 불가)", example = "2030-01-01T11:00:00Z")
    val applicationEndedAt: Instant? = null,
) {
    fun validate() {
        val start = applicationStartedAt
        val end = applicationEndedAt
        if ((start == null) != (end == null) || (start != null && !start.isBefore(end))) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "신청 시작은 종료보다 빨라야 하며 해제할 때는 둘 다 비워야 합니다.")
        }
        if (listOfNotNull(start, end).any { it.nano % 1000 != 0 }) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 정책 시각은 마이크로초 정밀도까지 지원합니다.")
        }
    }
}

data class UpdateProgramPromotionModeRequest(
    @field:Schema(description = "대기 승급 모드", example = "AUTO")
    val promotionMode: ProgramPromotionMode,
    @field:Schema(description = "자동 승급 종료 일시 (UTC 오프셋 ISO 8601). null이면 모집 종료까지 승급", example = "2030-01-01T11:00:00Z")
    val promotionEndedAt: Instant? = null,
) {
    fun validate() {
        if (promotionEndedAt != null && promotionEndedAt.nano % 1000 != 0) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 정책 시각은 마이크로초 정밀도까지 지원합니다.")
        }
    }
}
