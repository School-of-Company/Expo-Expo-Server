package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.response.RepublishExpoEventsResponse
import team.startup.expo.domain.expo.service.RepublishExpoEventsService
import team.startup.expo.global.swagger.ApiError

@RestController
@RequestMapping("/internal/expo-events")
class InternalExpoEventController(
    private val republishExpoEventsService: RepublishExpoEventsService,
) {
    @PostMapping("/republish")
    @Operation(
        summary = "박람회 변경 이벤트 전체 재발행",
        description = "모든 박람회의 현재 상태를 expo.expo.changed 아웃박스에 다시 싣습니다. 받는 쪽 프로젝션을 처음 만들거나 다시 만들 때 씁니다.",
    )
    @ApiResponse(responseCode = "202", description = "아웃박스 적재 완료. 발행은 릴레이가 이어서 합니다.")
    @ApiError(status = 503, description = "박람회 변경 이벤트 발행(KAFKA_EXPO_EVENTS_ENABLED)이 꺼져 있습니다.")
    fun republish(): ResponseEntity<RepublishExpoEventsResponse> = ResponseEntity.accepted().body(republishExpoEventsService.execute())
}
