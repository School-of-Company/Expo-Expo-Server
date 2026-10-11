package team.startup.expo.domain.expo.presentation.dto.request

import org.springframework.http.HttpStatus
import team.startup.expo.global.exception.ExpectedException
import java.time.Instant

enum class ProgramFormChangeKind { DELETE, SHRINK }

data class ProgramFormChangeRequest(
    val kind: ProgramFormChangeKind,
    val startDate: Instant? = null,
    val endDate: Instant? = null,
) {
    fun validate() {
        val valid =
            when (kind) {
                ProgramFormChangeKind.DELETE -> startDate == null && endDate == null
                ProgramFormChangeKind.SHRINK -> startDate != null && endDate != null && startDate.isBefore(endDate)
            }
        if (!valid || listOfNotNull(startDate, endDate).any { it.nano % 1000 != 0 }) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "폼 변경 종류와 기간이 올바르지 않습니다.")
        }
    }
}
