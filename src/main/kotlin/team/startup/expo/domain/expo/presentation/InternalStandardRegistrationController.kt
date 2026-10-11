package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.service.RecordStandardRegistrationService
import team.startup.expo.global.swagger.ApiError

@RestController
@RequestMapping("/internal/expo")
class InternalStandardRegistrationController(
    private val recordStandardRegistrationService: RecordStandardRegistrationService,
) {
    @PutMapping("/{expo_id}/standard-registrations/{participant_id}")
    @Operation(summary = "내부 일반 참가자 등록 집계")
    @ApiResponse(responseCode = "204", description = "처리 완료 (본문 없음)")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    fun recordStandardRegistration(
        @PathVariable("expo_id") expoId: String,
        @PathVariable("participant_id") participantId: Long,
    ): ResponseEntity<Void> {
        recordStandardRegistrationService.execute(expoId, participantId)
        return ResponseEntity.noContent().build()
    }
}
