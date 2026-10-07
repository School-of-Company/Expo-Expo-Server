package team.startup.expo.domain.expo.presentation

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.service.RecordStandardRegistrationService

@RestController
@RequestMapping("/internal/expo")
class InternalStandardRegistrationController(
    private val recordStandardRegistrationService: RecordStandardRegistrationService,
) {
    @PutMapping("/{expo_id}/standard-registrations/{participant_id}")
    fun recordStandardRegistration(
        @PathVariable("expo_id") expoId: String,
        @PathVariable("participant_id") participantId: Long,
    ): ResponseEntity<Void> {
        recordStandardRegistrationService.execute(expoId, participantId)
        return ResponseEntity.noContent().build()
    }
}
