package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.service.RetryProgramChangeService
import team.startup.expo.global.swagger.ApiError

@RestController
class ProgramChangeController(
    private val retry: RetryProgramChangeService,
) {
    @PostMapping("/expo/{expo_id}/program-changes/retry")
    @Operation(summary = "중단된 프로그램 변경 재시도")
    @ApiError(status = 400, description = "연동 폼의 행사·참여 유형 또는 모집 기간이 프로그램 정책과 일치하지 않습니다.")
    @ApiResponse(responseCode = "204", description = "처리 완료 (본문 없음)")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    @ApiError(status = 502, description = "연동 서비스 응답 상태 또는 본문이 올바르지 않습니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
    fun retry(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<Void> {
        retry.execute(expoId)
        return ResponseEntity.noContent().build()
    }
}
