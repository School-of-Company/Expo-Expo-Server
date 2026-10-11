package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.response.PreregisterSessionResponse
import team.startup.expo.domain.expo.service.GetInternalPreregisterSessionListService
import team.startup.expo.domain.expo.service.GetInternalPreregisterSessionService
import team.startup.expo.global.swagger.ApiError

@RestController
@RequestMapping("/internal/expo/{expo_id}/preregister-sessions")
class InternalPreregisterSessionController(
    private val list: GetInternalPreregisterSessionListService,
    private val get: GetInternalPreregisterSessionService,
) {
    @Operation(summary = "신청 서비스용 회차 정의 목록", description = "X-Internal-Token 필요. 삭제 중인 박람회는 409를 반환합니다.")
    @GetMapping
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    fun list(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<List<PreregisterSessionResponse>> = ResponseEntity.ok(list.execute(expoId))

    @Operation(summary = "신청 서비스용 회차 정의 단건", description = "정원·마감·revision을 제공합니다. 정원 판정과 신청 기록은 Application 소유입니다.")
    @GetMapping("/{session_id}")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    fun get(
        @PathVariable("expo_id") expoId: String,
        @PathVariable("session_id") sessionId: Long,
    ): ResponseEntity<PreregisterSessionResponse> = ResponseEntity.ok(get.execute(expoId, sessionId))
}
