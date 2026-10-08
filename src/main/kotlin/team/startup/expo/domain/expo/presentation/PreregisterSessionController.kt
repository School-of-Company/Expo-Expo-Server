package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.request.PreregisterSessionRequest
import team.startup.expo.domain.expo.presentation.dto.request.UpdatePreregisterSessionRequest
import team.startup.expo.domain.expo.presentation.dto.response.PreregisterSessionResponse
import team.startup.expo.domain.expo.service.CreatePreregisterSessionService
import team.startup.expo.domain.expo.service.DeletePreregisterSessionService
import team.startup.expo.domain.expo.service.GetPreregisterSessionListService
import team.startup.expo.domain.expo.service.GetPreregisterSessionService
import team.startup.expo.domain.expo.service.RetryPreregisterSessionChangeService
import team.startup.expo.domain.expo.service.UpdatePreregisterSessionService

@RestController
@RequestMapping("/expo/{expo_id}/preregister-sessions")
class PreregisterSessionController(
    private val create: CreatePreregisterSessionService,
    private val list: GetPreregisterSessionListService,
    private val get: GetPreregisterSessionService,
    private val update: UpdatePreregisterSessionService,
    private val delete: DeletePreregisterSessionService,
    private val retry: RetryPreregisterSessionChangeService,
) {
    @Operation(summary = "사전등록 회차 생성", description = "일시는 UTC 오프셋이 있는 ISO 8601 형식입니다. 인원 집계는 신청 서비스가 담당합니다.")
    @PostMapping
    fun create(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: PreregisterSessionRequest,
    ): ResponseEntity<PreregisterSessionResponse> = ResponseEntity.status(HttpStatus.CREATED).body(create.execute(expoId, request))

    @Operation(summary = "사전등록 회차 목록 조회")
    @GetMapping
    fun list(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<List<PreregisterSessionResponse>> = ResponseEntity.ok(list.execute(expoId))

    @Operation(summary = "사전등록 회차 단건 조회")
    @GetMapping("/{session_id}")
    fun get(
        @PathVariable("expo_id") expoId: String,
        @PathVariable("session_id") sessionId: Long,
    ): ResponseEntity<PreregisterSessionResponse> = ResponseEntity.ok(get.execute(expoId, sessionId))

    @Operation(
        summary = "사전등록 회차 수정",
        description = "조회한 revision과 definition 전체를 보냅니다. 신청 이력이 있으면 마감/재개만 허용합니다. Application 변경 연동이 필요합니다.",
    )
    @PatchMapping("/{session_id}")
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
    fun retry(
        @PathVariable("expo_id") expoId: String,
        @PathVariable("session_id") sessionId: Long,
    ): ResponseEntity<Void> {
        retry.execute(expoId, sessionId)
        return ResponseEntity.noContent().build()
    }
}
