package team.startup.expo.domain.expo.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import org.hibernate.validator.constraints.CodePointLength
import org.springframework.http.HttpStatus
import team.startup.expo.global.exception.ExpectedException
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.annotation.JsonDeserialize
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

data class PreregisterSessionRequest(
    @field:NotBlank
    @field:CodePointLength(max = 50)
    @field:Schema(description = "제목", example = "예시 박람회")
    val title: String,
    @field:Schema(description = "시작 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T10:00:00Z")
    val startedAt: Instant,
    @field:Schema(description = "종료 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val endedAt: Instant,
    @field:NotBlank
    @field:Schema(description = "회차 장소", example = "가상 강의실")
    val place: String,
    @field:Positive
    @field:Schema(
        description = "총정원. 구분 정원이 있으면 생략할 수 있고, 보내면 두 구분 정원의 합과 같아야 합니다. 구분 정원이 없으면 필수입니다.",
        example = "30",
    )
    val capacity: Int? = null,
    @field:PositiveOrZero
    @param:JsonProperty(required = true)
    @field:Schema(description = "대기 정원", example = "5")
    val waitingCapacity: Int,
    @param:JsonProperty(required = true)
    @field:Schema(description = "모집 마감 여부", example = "false")
    val closed: Boolean,
    @field:JsonDeserialize(using = CancelDeadlineDeserializer::class)
    @field:Schema(description = "별도 취소 마감. null 또는 생략 시 취소 차단. UTC 오프셋이 있는 ISO 8601 시각.", example = "2030-01-01T09:00:00Z")
    val cancelEndedAt: Instant? = null,
    @field:PositiveOrZero
    @field:Schema(
        description = "교사·예비교사 정원. nonTeacherCapacity와 함께 보내거나 함께 생략합니다. 생성 시 생략하면 박람회 기본값, 수정 시 생략하면 기존 값을 씁니다.",
        example = "10",
    )
    val teacherCapacity: Int? = null,
    @field:PositiveOrZero
    @field:Schema(description = "교사·예비교사 외 대상 정원. teacherCapacity와 함께 보내거나 함께 생략합니다.", example = "20")
    val nonTeacherCapacity: Int? = null,
) {
    fun validatePeriod() {
        if (!endedAt.truncatedTo(ChronoUnit.MICROS).isAfter(startedAt.truncatedTo(ChronoUnit.MICROS))) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "회차 종료는 시작보다 늦어야 합니다.")
        }
    }

    // Omitted target capacities fall back to the expo default on create and the stored values on update.
    fun capacities(
        fallbackTeacher: Int?,
        fallbackNonTeacher: Int?,
    ): SessionCapacities {
        validateTargetCapacities(teacherCapacity, nonTeacherCapacity)
        val teacher = teacherCapacity ?: fallbackTeacher
        val nonTeacher = nonTeacherCapacity ?: fallbackNonTeacher
        if (teacher == null || nonTeacher == null) {
            return SessionCapacities(capacity ?: throw ExpectedException(HttpStatus.BAD_REQUEST, "정원이 필요합니다."), null, null)
        }
        val total = teacher.toLong() + nonTeacher
        if (capacity != null && capacity.toLong() != total) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "정원은 교사·예비교사 정원과 그 외 대상 정원의 합과 같아야 합니다.")
        }
        return SessionCapacities(total.toInt(), teacher, nonTeacher)
    }
}

data class SessionCapacities(
    val capacity: Int,
    val teacherCapacity: Int?,
    val nonTeacherCapacity: Int?,
)

fun validateTargetCapacities(
    teacher: Int?,
    nonTeacher: Int?,
) {
    if ((teacher == null) != (nonTeacher == null)) {
        throw ExpectedException(HttpStatus.BAD_REQUEST, "교사·예비교사 정원과 그 외 대상 정원은 함께 입력해야 합니다.")
    }
    if (teacher != null && nonTeacher != null && teacher.toLong() + nonTeacher !in 1..Int.MAX_VALUE.toLong()) {
        throw ExpectedException(HttpStatus.BAD_REQUEST, "구분 정원의 합은 1 이상 ${Int.MAX_VALUE} 이하여야 합니다.")
    }
}

class CancelDeadlineDeserializer : ValueDeserializer<Instant>() {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): Instant {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "취소 마감은 UTC 오프셋이 있는 ISO 8601 문자열이어야 합니다.")
        }
        return try {
            Instant.parse(parser.string)
        } catch (_: DateTimeParseException) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "취소 마감은 UTC 오프셋이 있는 ISO 8601 문자열이어야 합니다.")
        }
    }
}

data class UpdatePreregisterSessionRequest(
    @field:Positive
    @field:Max(Long.MAX_VALUE - 1)
    @field:Schema(description = "조회한 정의 버전", example = "1")
    val revision: Long,
    @field:Valid
    @field:Schema(description = "회차 전체 정의")
    val definition: PreregisterSessionRequest,
)
