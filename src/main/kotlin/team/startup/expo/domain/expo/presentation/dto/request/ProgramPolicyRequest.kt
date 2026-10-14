package team.startup.expo.domain.expo.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
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

enum class ProgramPromotionMode { AUTO, MANUAL }

data class ProgramPolicyRequest(
    @field:Schema(description = "프로그램 묶음 식별자", example = "00000000-0000-0000-0000-000000000001")
    val groupId: UUID? = null,
    @field:Schema(description = "프로그램 폼 식별자", example = "00000000-0000-0000-0000-000000000002")
    val formId: UUID,
    @field:Schema(description = "신청 가능한 직종", example = "[\"TEACHER\"]")
    val allowedOccupations: Set<ProgramOccupation>,
    @field:Positive
    @field:Schema(description = "정원", example = "30")
    val capacity: Int,
    @field:PositiveOrZero
    @param:JsonProperty(required = true)
    @field:Schema(description = "대기 정원", example = "5")
    val waitingCapacity: Int,
    @field:Schema(description = "모집 시작 일시 (UTC 오프셋 ISO 8601). 종료와 함께 null이면 신청 불가", example = "2030-01-01T10:00:00Z")
    val applicationStartedAt: Instant? = null,
    @field:Schema(description = "모집 종료 일시 (UTC 오프셋 ISO 8601). 시작과 함께 null이면 신청 불가", example = "2030-01-01T11:00:00Z")
    val applicationEndedAt: Instant? = null,
    @field:Schema(description = "취소 마감 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val cancelEndedAt: Instant,
    @field:Schema(description = "대기 승급 마감 일시 (UTC 오프셋 ISO 8601). null이면 모집 종료까지 승급", example = "2030-01-01T11:00:00Z")
    val promotionEndedAt: Instant? = null,
    @param:JsonProperty(required = true)
    @field:Schema(description = "모집 마감 여부", example = "false")
    val closed: Boolean,
    @field:Schema(
        description = "대기 승급 모드. AUTO는 자동 승급, MANUAL은 자동 승급하지 않음. 생략하면 AUTO",
        example = "AUTO",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
    )
    val promotionMode: ProgramPromotionMode = ProgramPromotionMode.AUTO,
) {
    fun validate() {
        val start = applicationStartedAt
        val end = applicationEndedAt
        if (capacity <= 0 || waitingCapacity < 0 || allowedOccupations.isEmpty() || (start == null) != (end == null) ||
            (start != null && (!start.isBefore(end) || cancelEndedAt.isBefore(start) || promotionEndedAt?.isBefore(start) == true))
        ) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 모집 정책이 올바르지 않습니다.")
        }
        if (listOfNotNull(start, end, cancelEndedAt, promotionEndedAt).any { it.nano % 1000 != 0 }) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 정책 시각은 마이크로초 정밀도까지 지원합니다.")
        }
    }
}
