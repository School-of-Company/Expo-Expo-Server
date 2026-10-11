package team.startup.expo.domain.expo.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.http.HttpStatus
import team.startup.expo.global.exception.ExpectedException
import java.time.Instant
import java.util.UUID

enum class ProgramOccupation {
    KINDERGARTEN_STUDENT,
    ELEMENTARY_STUDENT,
    MIDDLE_SCHOOL_STUDENT,
    HIGH_SCHOOL_STUDENT,
    SCHOOL_STAFF,
    PARENT,
    GENERAL,
    TEACHER,
    PRE_SERVICE_TEACHER,
}

data class ProgramPolicyRequest(
    val groupId: UUID? = null,
    val formId: UUID,
    val allowedOccupations: Set<ProgramOccupation>,
    @field:Positive
    val capacity: Int,
    @field:PositiveOrZero
    @param:JsonProperty(required = true)
    val waitingCapacity: Int,
    val applicationStartedAt: Instant,
    val applicationEndedAt: Instant,
    val cancelEndedAt: Instant,
    val promotionEndedAt: Instant,
    @param:JsonProperty(required = true)
    val closed: Boolean,
) {
    fun validate() {
        if (capacity <= 0 || waitingCapacity < 0 || allowedOccupations.isEmpty() ||
            !applicationStartedAt.isBefore(applicationEndedAt) ||
            cancelEndedAt.isBefore(applicationStartedAt) || promotionEndedAt.isBefore(applicationStartedAt)
        ) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 모집 정책이 올바르지 않습니다.")
        }
        if (listOf(applicationStartedAt, applicationEndedAt, cancelEndedAt, promotionEndedAt).any { it.nano % 1000 != 0 }) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 정책 시각은 마이크로초 정밀도까지 지원합니다.")
        }
    }
}
