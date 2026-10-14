package team.startup.expo.domain.expo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.startup.expo.domain.expo.presentation.dto.request.ProgramOccupation
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.global.exception.ExpectedException
import java.time.Instant
import java.util.UUID

class ProgramPolicyValidationTests {
    private val start = Instant.parse("2026-10-11T00:00:00Z")
    private val end = Instant.parse("2026-10-12T00:00:00Z")
    private val policy =
        ProgramPolicyRequest(
            formId = UUID.randomUUID(),
            allowedOccupations = setOf(ProgramOccupation.TEACHER),
            capacity = 1,
            waitingCapacity = 0,
            applicationStartedAt = start,
            applicationEndedAt = end,
            cancelEndedAt = end,
            promotionEndedAt = end,
            closed = false,
        )

    @Test
    fun `zero waiting capacity is valid and offset timestamps identify the same instant`() {
        policy.validate()
        assertEquals(start, Instant.parse("2026-10-11T09:00:00+09:00"))
    }

    @Test
    fun `cleared application period and open-ended promotion are valid`() {
        policy.copy(applicationStartedAt = null, applicationEndedAt = null).validate()
        policy.copy(promotionEndedAt = null).validate()
    }

    @Test
    fun `invalid capacities occupations periods and unsupported precision are rejected`() {
        listOf(
            policy.copy(capacity = 0),
            policy.copy(waitingCapacity = -1),
            policy.copy(allowedOccupations = emptySet()),
            policy.copy(applicationEndedAt = start),
            policy.copy(applicationEndedAt = start.minusSeconds(1)),
            policy.copy(cancelEndedAt = start.minusSeconds(1)),
            policy.copy(promotionEndedAt = start.minusSeconds(1)),
            policy.copy(applicationStartedAt = start.plusNanos(1)),
            policy.copy(applicationStartedAt = null),
            policy.copy(applicationEndedAt = null),
        ).forEach { invalid ->
            val exception = assertThrows(ExpectedException::class.java) { invalid.validate() }
            assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        }
    }
}
