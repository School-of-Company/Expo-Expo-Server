package team.startup.expo.domain.expo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.startup.expo.domain.expo.presentation.dto.request.ProgramChannelRequest
import team.startup.expo.domain.expo.presentation.dto.request.ProgramGuardianPolicyRequest
import team.startup.expo.domain.expo.presentation.dto.request.ProgramOccupation
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.global.exception.ExpectedException
import java.time.Instant
import java.util.UUID

class ProgramPolicyValidationTests {
    private val guardian = ProgramGuardianPolicyRequest(1, 101, "primary", 102, (1..6).associate { "g$it" to it })
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
    fun `guardian grade mapping covers exactly six grades without inferring labels`() {
        policy.copy(guardianPolicy = guardian).validate()
        listOf(
            guardian.copy(formVersion = 0),
            guardian.copy(schoolTypeQuestionId = 0),
            guardian.copy(gradeQuestionId = -1),
            guardian.copy(gradeQuestionId = guardian.schoolTypeQuestionId),
            guardian.copy(elementarySchoolValue = " "),
            guardian.copy(gradeValues = guardian.gradeValues - "g6"),
            guardian.copy(gradeValues = guardian.gradeValues + ("g6" to 3)),
            guardian.copy(gradeValues = guardian.gradeValues + ("g6" to 7)),
            guardian.copy(gradeValues = guardian.gradeValues - "g1" + (" " to 1)),
        ).forEach { invalid ->
            val exception = assertThrows(ExpectedException::class.java) { policy.copy(guardianPolicy = invalid).validate() }
            assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        }
    }

    @Test
    fun `zero waiting capacity is valid and offset timestamps identify the same instant`() {
        policy.validate()
        assertEquals(start, Instant.parse("2026-10-11T09:00:00+09:00"))
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
        ).forEach { invalid ->
            val exception = assertThrows(ExpectedException::class.java) { invalid.validate() }
            assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        }
    }

    private val general = ProgramChannelRequest("일반", setOf(ProgramOccupation.GENERAL, ProgramOccupation.PARENT), 3, 1)
    private val teacher = ProgramChannelRequest("교원", setOf(ProgramOccupation.TEACHER), 2, 0)
    private val channeled =
        policy.copy(
            allowedOccupations = setOf(ProgramOccupation.GENERAL, ProgramOccupation.PARENT, ProgramOccupation.TEACHER),
            capacity = 5,
            waitingCapacity = 1,
            channels = listOf(general, teacher),
        )

    @Test
    fun `channels whose totals and occupation union match the policy are valid`() {
        channeled.validate()
    }

    @Test
    fun `channels must be disjoint named and add up to the policy totals`() {
        listOf(
            channeled.copy(channels = emptyList()),
            channeled.copy(channels = listOf(general, teacher.copy(name = " 일반 "))),
            channeled.copy(channels = listOf(general, teacher.copy(name = " "))),
            channeled.copy(channels = listOf(general, teacher.copy(name = "가".repeat(51)))),
            channeled.copy(
                channels = listOf(general, teacher.copy(allowedOccupations = setOf(ProgramOccupation.TEACHER, ProgramOccupation.PARENT))),
            ),
            channeled.copy(channels = listOf(general, teacher.copy(allowedOccupations = emptySet()))),
            channeled.copy(channels = listOf(general, teacher.copy(capacity = 0)), capacity = 3),
            channeled.copy(channels = listOf(general, teacher.copy(waitingCapacity = -1)), waitingCapacity = 0),
            channeled.copy(capacity = 6),
            channeled.copy(waitingCapacity = 2),
            channeled.copy(allowedOccupations = channeled.allowedOccupations + ProgramOccupation.SCHOOL_STAFF),
            channeled.copy(channels = listOf(general)),
        ).forEach { invalid ->
            val exception = assertThrows(ExpectedException::class.java) { invalid.validate() }
            assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        }
    }
}
