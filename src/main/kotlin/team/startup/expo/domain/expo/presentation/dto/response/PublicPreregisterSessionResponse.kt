package team.startup.expo.domain.expo.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import team.startup.expo.domain.expo.entity.PreregisterSession
import java.time.Instant

data class PublicPreregisterSessionResponse(
    @field:Schema(description = "식별자", example = "1")
    val id: Long,
    @field:Schema(description = "제목", example = "오전 회차")
    val title: String,
    @field:Schema(description = "시작 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T10:00:00Z")
    val startedAt: Instant,
    @field:Schema(description = "종료 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val endedAt: Instant,
    @field:Schema(description = "회차 장소", example = "가상 강의실")
    val place: String,
    @field:Schema(description = "모집 마감 여부", example = "false")
    val closed: Boolean,
    @field:Schema(description = "취소 마감 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val cancelEndedAt: Instant? = null,
)

fun PreregisterSession.toPublicResponse(): PublicPreregisterSessionResponse =
    PublicPreregisterSessionResponse(requireNotNull(id), title, startedAt, endedAt, place, closed, cancelEndedAt)
