package team.startup.expo.domain.expo

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.startup.expo.domain.expo.presentation.dto.request.CreateExpoRequest
import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoRequest
import team.startup.expo.global.exception.ExpectedException
import java.time.LocalDate

class ExpoPeriodRequestTests {
    private fun create(
        start: String,
        end: String,
    ) = CreateExpoRequest("t", "d", LocalDate.parse(start), LocalDate.parse(end), "l", "c", "1", "1", emptyList(), emptyList())

    private fun update(
        start: String,
        end: String,
    ) = UpdateExpoRequest("t", "d", LocalDate.parse(start), LocalDate.parse(end), "l", "c", "1", "1", emptyList(), emptyList())

    @Test
    fun `create rejects finish before start`() {
        val e = assertThrows(ExpectedException::class.java) { create("2030-01-05", "2030-01-02").validatePeriod() }
        e.status shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `update rejects finish before start`() {
        val e = assertThrows(ExpectedException::class.java) { update("2030-01-05", "2030-01-02").validatePeriod() }
        e.status shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `same day and later finish are accepted`() {
        create("2030-01-02", "2030-01-02").validatePeriod()
        update("2030-01-02", "2030-01-03").validatePeriod()
    }
}
