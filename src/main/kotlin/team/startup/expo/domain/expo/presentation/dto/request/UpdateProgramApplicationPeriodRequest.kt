package team.startup.expo.domain.expo.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.HttpStatus
import team.startup.expo.global.exception.ExpectedException
import java.time.Instant

data class UpdateProgramApplicationPeriodRequest(
    @field:Schema(description = "모집 시작 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T10:00:00Z")
    val applicationStartedAt: Instant,
    @field:Schema(description = "모집 종료 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val applicationEndedAt: Instant,
) {
    fun validate() {
        if (!applicationStartedAt.isBefore(applicationEndedAt)) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "신청 시작은 종료보다 빨라야 합니다.")
        }
        if (listOf(applicationStartedAt, applicationEndedAt).any { it.nano % 1000 != 0 }) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 정책 시각은 마이크로초 정밀도까지 지원합니다.")
        }
    }
}
