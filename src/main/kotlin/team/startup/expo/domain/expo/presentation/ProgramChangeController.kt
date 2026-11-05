package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.service.RetryProgramChangeService
import team.startup.expo.global.swagger.ApiConflictError
import team.startup.expo.global.swagger.ApiError
import team.startup.expo.global.swagger.ApiIntegrationErrors
import team.startup.expo.global.swagger.ApiNotFoundError

@RestController
class ProgramChangeController(
    private val retry: RetryProgramChangeService,
) {
    @PostMapping("/expo/{expo_id}/program-changes/retry")
    @Operation(summary = "중단된 프로그램 변경 재시도")
    @ApiError(status = 400, description = "연동 폼의 행사·참여 유형 또는 모집 기간이 프로그램 정책과 일치하지 않습니다.")
    @ApiResponse(responseCode = "204", description = "처리 완료 (본문 없음)")
    @ApiNotFoundError
    @ApiConflictError
    @ApiIntegrationErrors
    fun retry(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<Void> {
        retry.execute(expoId)
        return ResponseEntity.noContent().build()
    }
}
