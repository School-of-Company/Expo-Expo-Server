package team.startup.expo.domain.expo.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.HttpStatus
import team.startup.expo.global.exception.ExpectedException
import java.time.Instant

enum class ProgramFormChangeKind { DELETE, SHRINK, UPDATE }

data class ProgramFormChangeRequest(
    @field:Schema(description = "폼 삭제, 기간 축소 또는 수정", example = "SHRINK")
    val kind: ProgramFormChangeKind,
    @field:Schema(description = "변경할 시작 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T10:00:00Z")
    val startDate: Instant? = null,
    @field:Schema(description = "변경할 종료 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val endDate: Instant? = null,
) {
    fun validate() {
        val valid =
            when (kind) {
                ProgramFormChangeKind.DELETE, ProgramFormChangeKind.UPDATE -> startDate == null && endDate == null
                ProgramFormChangeKind.SHRINK -> startDate != null && endDate != null && startDate.isBefore(endDate)
            }
        if (!valid || listOfNotNull(startDate, endDate).any { it.nano % 1000 != 0 }) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "폼 변경 종류와 기간이 올바르지 않습니다.")
        }
    }
}
