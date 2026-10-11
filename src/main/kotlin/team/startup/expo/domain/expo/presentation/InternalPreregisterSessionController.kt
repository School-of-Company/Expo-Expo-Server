package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.response.PreregisterSessionResponse
import team.startup.expo.domain.expo.service.GetInternalPreregisterSessionListService
import team.startup.expo.domain.expo.service.GetInternalPreregisterSessionService

@RestController
@RequestMapping("/internal/expo/{expo_id}/preregister-sessions")
class InternalPreregisterSessionController(
    private val list: GetInternalPreregisterSessionListService,
    private val get: GetInternalPreregisterSessionService,
) {
    @Operation(summary = "신청 서비스용 회차 정의 목록", description = "X-Internal-Token 필요. 삭제 중인 박람회는 409를 반환합니다.")
    @GetMapping
    fun list(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<List<PreregisterSessionResponse>> = ResponseEntity.ok(list.execute(expoId))

    @Operation(summary = "신청 서비스용 회차 정의 단건", description = "정원·마감·revision을 제공합니다. 정원 판정과 신청 기록은 Application 소유입니다.")
    @GetMapping("/{session_id}")
    fun get(
        @PathVariable("expo_id") expoId: String,
        @PathVariable("session_id") sessionId: Long,
    ): ResponseEntity<PreregisterSessionResponse> = ResponseEntity.ok(get.execute(expoId, sessionId))
}
