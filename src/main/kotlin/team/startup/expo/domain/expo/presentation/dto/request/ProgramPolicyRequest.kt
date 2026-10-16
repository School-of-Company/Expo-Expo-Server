package team.startup.expo.domain.expo.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.http.HttpStatus
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.annotation.JsonDeserialize
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
    @field:Schema(description = "프로그램 묶음 식별자", example = "00000000-0000-0000-0000-000000000001")
    val groupId: UUID? = null,
    @field:Schema(description = "프로그램 폼 식별자", example = "00000000-0000-0000-0000-000000000002")
    val formId: UUID,
    @field:Schema(description = "신청 가능한 직종", example = "[\"TEACHER\"]")
    val allowedOccupations: Set<ProgramOccupation>,
    @field:Positive
    @param:JsonProperty(required = true)
    @field:JsonDeserialize(using = ProgramCapacityDeserializer::class)
    @field:Schema(description = "총정원. 사전 접수 정원과 현장 접수 정원의 합이며 1 이상", example = "30")
    val capacity: Int,
    @field:PositiveOrZero
    @param:JsonProperty(required = true)
    @field:JsonDeserialize(using = ProgramCapacityDeserializer::class)
    @field:Schema(description = "사전 접수 정원. 0 이상, 생략 불가", example = "20")
    val preRegistrationCapacity: Int,
    @field:PositiveOrZero
    @param:JsonProperty(required = true)
    @field:JsonDeserialize(using = ProgramCapacityDeserializer::class)
    @field:Schema(description = "현장 접수 정원. 0 이상, 생략 불가. 현장 개방·신청 권한을 의미하지 않음", example = "10")
    val onsiteCapacity: Int,
    @field:PositiveOrZero
    @param:JsonProperty(required = true)
    @field:Schema(description = "대기 정원", example = "5")
    val waitingCapacity: Int,
    @field:Schema(description = "모집 시작 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T10:00:00Z")
    val applicationStartedAt: Instant,
    @field:Schema(description = "모집 종료 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val applicationEndedAt: Instant,
    @field:Schema(description = "취소 마감 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val cancelEndedAt: Instant,
    @field:Schema(description = "대기 승급 마감 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val promotionEndedAt: Instant,
    @param:JsonProperty(required = true)
    @field:Schema(description = "모집 마감 여부", example = "false")
    val closed: Boolean,
) {
    fun validate() {
        if (capacity <= 0 || preRegistrationCapacity < 0 || onsiteCapacity < 0 ||
            preRegistrationCapacity.toLong() + onsiteCapacity.toLong() != capacity.toLong() ||
            waitingCapacity < 0 || allowedOccupations.isEmpty() ||
            !applicationStartedAt.isBefore(applicationEndedAt) ||
            cancelEndedAt.isBefore(applicationStartedAt) || promotionEndedAt.isBefore(applicationStartedAt)
        ) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 모집 정책이 올바르지 않습니다.")
        }
        if (listOf(applicationStartedAt, applicationEndedAt, cancelEndedAt, promotionEndedAt).any { it.nano % 1000 != 0 }) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 정책 시각은 마이크로초 정밀도까지 지원합니다.")
        }
    }

    @JsonAnySetter
    fun rejectUnknownField(
        name: String,
        value: Any?,
    ): Unit = throw ExpectedException(HttpStatus.BAD_REQUEST, "지원하지 않는 프로그램 정책 필드입니다: $name")
}

class ProgramCapacityDeserializer : ValueDeserializer<Int>() {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): Int {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 정원은 정수여야 합니다.")
        }
        return parser.intValue
    }
}
