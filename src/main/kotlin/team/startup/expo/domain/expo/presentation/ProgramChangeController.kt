package team.startup.expo.domain.expo.presentation

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.service.RetryProgramChangeService

@RestController
class ProgramChangeController(
    private val retry: RetryProgramChangeService,
) {
    @PostMapping("/expo/{expo_id}/program-changes/retry")
    fun retry(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<Void> {
        retry.execute(expoId)
        return ResponseEntity.noContent().build()
    }
}
