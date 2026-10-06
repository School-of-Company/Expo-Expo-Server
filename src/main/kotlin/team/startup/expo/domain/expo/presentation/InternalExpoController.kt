package team.startup.expo.domain.expo.presentation

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.response.ExpoPeriodResponse
import team.startup.expo.domain.expo.service.GetExpoDetailService

@RestController
@RequestMapping("/internal/expo")
class InternalExpoController(
    private val getExpoDetailService: GetExpoDetailService,
) {
    @GetMapping("/{expo_id}")
    fun getExpoPeriod(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<ExpoPeriodResponse> {
        val detail = getExpoDetailService.execute(expoId)
        return ResponseEntity.ok(ExpoPeriodResponse(startedDay = detail.startedDay, finishedDay = detail.finishedDay))
    }
}
