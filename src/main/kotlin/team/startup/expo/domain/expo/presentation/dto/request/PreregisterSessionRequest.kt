package team.startup.expo.domain.expo.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import org.hibernate.validator.constraints.CodePointLength
import org.springframework.http.HttpStatus
import team.startup.expo.global.exception.ExpectedException
import java.time.Instant
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
) {
    fun validatePeriod() {
        if (!endedAt.truncatedTo(ChronoUnit.MICROS).isAfter(startedAt.truncatedTo(ChronoUnit.MICROS))) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "회차 종료는 시작보다 늦어야 합니다.")
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
