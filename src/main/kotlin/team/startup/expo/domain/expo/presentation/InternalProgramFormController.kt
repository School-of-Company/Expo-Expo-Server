package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.request.ProgramFormChangeRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramFormChangeResponse
import team.startup.expo.domain.expo.presentation.dto.response.ProgramFormUsageResponse
import team.startup.expo.domain.expo.service.GetProgramFormUsageService
import team.startup.expo.domain.expo.service.ReleaseProgramFormChangeService
import team.startup.expo.domain.expo.service.ReserveProgramFormChangeService
import team.startup.expo.global.swagger.ApiError
import java.util.UUID

@RestController
@RequestMapping("/internal/forms/by-id/{formId}")
class InternalProgramFormController(
    private val usage: GetProgramFormUsageService,
    private val reserve: ReserveProgramFormChangeService,
    private val release: ReleaseProgramFormChangeService,
) {
    @GetMapping("/usage")
    @Operation(summary = "프로그램 폼 사용 범위 조회", description = "X-Internal-Token 필요. 일반·연수 회차 및 대기 중 변경의 모집 기간 합집합을 반환합니다.")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    fun usage(
        @PathVariable formId: UUID,
    ): ProgramFormUsageResponse = usage.execute(formId)

    @PutMapping("/changes/{changeId}")
    @Operation(
        summary = "프로그램 폼 삭제·기간 축소·수정 예약",
        description =
            "UPDATE는 기간 없이 요청하며 보호자 정책이 참조하면 409입니다. " +
                "예약은 5분간 유효하며 같은 키·본문은 동일 응답을 반환합니다. 만료 후에도 명시적 해제 전에는 연결을 차단합니다. 충돌은 409입니다.",
    )
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    fun reserve(
        @PathVariable formId: UUID,
        @PathVariable changeId: UUID,
        @Valid @RequestBody request: ProgramFormChangeRequest,
    ): ProgramFormChangeResponse = reserve.execute(formId, changeId, request)

    @DeleteMapping("/changes/{changeId}")
    @Operation(summary = "프로그램 폼 변경 예약 해제", description = "Form 변경이 커밋되거나 확실히 중단된 뒤 호출해야 합니다. 반복·미등록 키 해제도 204이며 해당 키는 재사용할 수 없습니다.")
    @ApiResponse(responseCode = "204", description = "처리 완료 (본문 없음)")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    fun release(
        @PathVariable formId: UUID,
        @PathVariable changeId: UUID,
    ): ResponseEntity<Void> {
        release.execute(formId, changeId)
        return ResponseEntity.noContent().build()
    }
}
