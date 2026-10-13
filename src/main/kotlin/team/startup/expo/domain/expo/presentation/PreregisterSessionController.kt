package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.request.AdoptPreregisterWaitingCapacityRequest
import team.startup.expo.domain.expo.presentation.dto.request.PreregisterSessionRequest
import team.startup.expo.domain.expo.presentation.dto.request.UpdatePreregisterSessionRequest
import team.startup.expo.domain.expo.presentation.dto.response.PreregisterSessionResponse
import team.startup.expo.domain.expo.service.AdoptPreregisterWaitingCapacityService
import team.startup.expo.domain.expo.service.CreatePreregisterSessionService
import team.startup.expo.domain.expo.service.DeletePreregisterSessionService
import team.startup.expo.domain.expo.service.GetPreregisterSessionListService
import team.startup.expo.domain.expo.service.GetPreregisterSessionService
import team.startup.expo.domain.expo.service.RetryPreregisterSessionChangeService
import team.startup.expo.domain.expo.service.UpdatePreregisterSessionService
import team.startup.expo.global.swagger.ApiError

@RestController
@RequestMapping("/expo/{expo_id}/preregister-sessions")
class PreregisterSessionController(
    private val create: CreatePreregisterSessionService,
    private val list: GetPreregisterSessionListService,
    private val get: GetPreregisterSessionService,
    private val update: UpdatePreregisterSessionService,
    private val delete: DeletePreregisterSessionService,
    private val retry: RetryPreregisterSessionChangeService,
    private val adoptWaitingCapacity: AdoptPreregisterWaitingCapacityService,
) {
    @Operation(summary = "사전등록 회차 생성", description = "일시는 UTC 오프셋이 있는 ISO 8601 형식입니다. 인원 집계는 신청 서비스가 담당합니다.")
    @PostMapping
    @ApiResponse(responseCode = "201", description = "처리 성공")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    fun create(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: PreregisterSessionRequest,
    ): ResponseEntity<PreregisterSessionResponse> = ResponseEntity.status(HttpStatus.CREATED).body(create.execute(expoId, request))

    @Operation(summary = "사전등록 회차 목록 조회")
    @GetMapping
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    fun list(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<List<PreregisterSessionResponse>> = ResponseEntity.ok(list.execute(expoId))

    @Operation(summary = "사전등록 회차 단건 조회")
    @GetMapping("/{session_id}")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    fun get(
        @PathVariable("expo_id") expoId: String,
        @PathVariable("session_id") sessionId: Long,
    ): ResponseEntity<PreregisterSessionResponse> = ResponseEntity.ok(get.execute(expoId, sessionId))

    @Operation(
        summary = "사전등록 회차 수정",
        description = "조회한 revision과 definition 전체를 보냅니다. 신청 이력이 있으면 마감/재개만 허용합니다. Application 변경 연동이 필요합니다.",
    )
    @PatchMapping("/{session_id}")
    @ApiResponse(responseCode = "204", description = "처리 완료 (본문 없음)")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    @ApiError(status = 502, description = "연동 서비스 응답 상태 또는 본문이 올바르지 않습니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
    fun update(
        @PathVariable("expo_id") expoId: String,
        @PathVariable("session_id") sessionId: Long,
        @Valid @RequestBody request: UpdatePreregisterSessionRequest,
    ): ResponseEntity<Void> {
        update.execute(expoId, sessionId, request)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "사전등록 회차 삭제", description = "조회한 revision을 보내며 신청 이력이 있으면 거부합니다. Application 변경 연동이 필요합니다.")
    @DeleteMapping("/{session_id}")
    @ApiResponse(responseCode = "204", description = "처리 완료 (본문 없음)")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    @ApiError(status = 502, description = "연동 서비스 응답 상태 또는 본문이 올바르지 않습니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
    fun delete(
        @PathVariable("expo_id") expoId: String,
        @PathVariable("session_id") sessionId: Long,
        @RequestParam revision: Long,
    ): ResponseEntity<Void> {
        delete.execute(expoId, sessionId, revision)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "중단된 회차 변경 재시도", description = "서버에 저장된 변경을 재시도합니다. 원래 요청 본문이나 revision은 필요하지 않습니다.")
    @PostMapping("/{session_id}/changes/retry")
    @ApiResponse(responseCode = "204", description = "처리 완료 (본문 없음)")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    @ApiError(status = 502, description = "연동 서비스 응답 상태 또는 본문이 올바르지 않습니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
    fun retry(
        @PathVariable("expo_id") expoId: String,
        @PathVariable("session_id") sessionId: Long,
    ): ResponseEntity<Void> {
        retry.execute(expoId, sessionId)
        return ResponseEntity.noContent().build()
    }

    @Operation(
        summary = "신청 이력이 있는 회차의 대기 정원 채택",
        description =
            "#99 이전 신청 이력이 있어 신청 서비스에서 대기가 미적용(UNADOPTED)인 회차에 Expo 현재 대기 정원을 적용합니다. " +
                "expected는 조회한 waitingCapacity이며 신청 서비스가 관찰한 값과 다르면 409입니다. 이미 같은 값이면 204입니다.",
    )
    @PutMapping("/{session_id}/waiting-capacity")
    @ApiResponse(responseCode = "204", description = "처리 완료 (본문 없음)")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
    @ApiError(status = 502, description = "연동 서비스 응답 상태 또는 본문이 올바르지 않습니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
    fun adoptWaitingCapacity(
        @PathVariable("expo_id") expoId: String,
        @PathVariable("session_id") sessionId: Long,
        @Valid @RequestBody request: AdoptPreregisterWaitingCapacityRequest,
    ): ResponseEntity<Void> {
        adoptWaitingCapacity.execute(expoId, sessionId, request)
        return ResponseEntity.noContent().build()
    }
}
