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
    val title: String,
    val startedAt: Instant,
    val endedAt: Instant,
    @field:NotBlank
    val place: String,
    @field:Positive
    val capacity: Int,
    @field:PositiveOrZero
    @param:JsonProperty(required = true)
    val waitingCapacity: Int,
    @param:JsonProperty(required = true)
    val closed: Boolean,
    @field:JsonDeserialize(using = CancelDeadlineDeserializer::class)
    @field:Schema(description = "별도 취소 마감. null 또는 생략 시 취소 차단. UTC 오프셋이 있는 ISO 8601 시각.")
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
    val revision: Long,
    @field:Valid
    val definition: PreregisterSessionRequest,
)
