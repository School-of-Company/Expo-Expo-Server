package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.service.RecordStandardRegistrationService
import team.startup.expo.global.swagger.ApiConflictError
import team.startup.expo.global.swagger.ApiInputError
import team.startup.expo.global.swagger.ApiNotFoundError

@RestController
@RequestMapping("/internal/expo")
class InternalStandardRegistrationController(
    private val recordStandardRegistrationService: RecordStandardRegistrationService,
) {
    @PutMapping("/{expo_id}/standard-registrations/{participant_id}")
    @Operation(summary = "내부 일반 참가자 등록 집계")
    @ApiResponse(responseCode = "204", description = "처리 완료 (본문 없음)")
    @ApiInputError
    @ApiNotFoundError
    @ApiConflictError
    fun recordStandardRegistration(
        @PathVariable("expo_id") expoId: String,
        @PathVariable("participant_id") participantId: Long,
    ): ResponseEntity<Void> {
        recordStandardRegistrationService.execute(expoId, participantId)
        return ResponseEntity.noContent().build()
    }
}
