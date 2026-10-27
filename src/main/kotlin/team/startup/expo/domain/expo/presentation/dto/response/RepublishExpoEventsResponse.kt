package team.startup.expo.domain.expo.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class RepublishExpoEventsResponse(
    @field:Schema(description = "아웃박스에 다시 실은 박람회 수", example = "12", minimum = "0")
    val enqueued: Int,
)
