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
    @field:Schema(description = "정원", example = "30")
    val capacity: Int,
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
) {
    fun validatePeriod() {
        if (!endedAt.truncatedTo(ChronoUnit.MICROS).isAfter(startedAt.truncatedTo(ChronoUnit.MICROS))) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "회차 종료는 시작보다 늦어야 합니다.")
        }
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
