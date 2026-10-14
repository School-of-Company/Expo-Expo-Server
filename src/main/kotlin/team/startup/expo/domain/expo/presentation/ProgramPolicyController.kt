package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.request.UpdateProgramApplicationPeriodRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramPolicyBulkResponse
import team.startup.expo.domain.expo.service.UpdateProgramApplicationPeriodService
import team.startup.expo.global.swagger.ApiError

@RestController
class ProgramPolicyController(
    private val updateApplicationPeriod: UpdateProgramApplicationPeriodService,
) {
    @PatchMapping("/expo/{expo_id}/programs/application-period")
    @Operation(
        summary = "프로그램 신청기간 일괄 적용",
        description = "정책이 있는 모든 프로그램의 신청 시작·종료를 바꿉니다. 검증에 실패한 프로그램은 FAILED로 돌려주고 나머지만 반영합니다.",
    )
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간이 올바르지 않습니다.")
    @ApiError(status = 404, description = "박람회가 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전 또는 신청 정원이 충돌합니다.")
    @ApiError(status = 502, description = "연동 서비스 응답 상태 또는 본문이 올바르지 않습니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스에 연결할 수 없습니다.")
    fun updateApplicationPeriod(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: UpdateProgramApplicationPeriodRequest,
    ): ResponseEntity<ProgramPolicyBulkResponse> = ResponseEntity.ok(updateApplicationPeriod.execute(expoId, request))
}
