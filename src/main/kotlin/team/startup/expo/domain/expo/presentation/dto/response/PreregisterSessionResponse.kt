package team.startup.expo.domain.expo.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import team.startup.expo.domain.expo.entity.PreregisterSession
import java.time.Instant

data class PreregisterSessionResponse(
    @field:Schema(description = "식별자", example = "1")
    val id: Long,
    @field:Schema(description = "박람회 식별자", example = "sample-expo")
    val expoId: String,
    @field:Schema(description = "제목", example = "예시 박람회")
    val title: String,
    @field:Schema(description = "시작 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T10:00:00Z")
    val startedAt: Instant,
    @field:Schema(description = "종료 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val endedAt: Instant,
    @field:Schema(description = "회차 장소", example = "가상 강의실")
    val place: String,
    @field:Schema(description = "정원", example = "30")
    val capacity: Int,
    @field:Schema(description = "대기 정원", example = "5")
    val waitingCapacity: Int,
    @field:Schema(description = "모집 마감 여부", example = "false")
    val closed: Boolean,
    @field:Schema(description = "조회한 정의 버전", example = "1")
    val revision: Long,
    @field:Schema(description = "취소 마감 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val cancelEndedAt: Instant? = null,
    @field:Schema(description = "교사·예비교사 정원. null이면 구분 없이 capacity 하나로 운영하는 기존 회차", example = "10")
    val teacherCapacity: Int? = null,
    @field:Schema(description = "교사·예비교사 외 대상 정원. teacherCapacity와 함께 null이거나 함께 있으며 합은 capacity", example = "20")
    val nonTeacherCapacity: Int? = null,
)

fun PreregisterSession.toResponse(): PreregisterSessionResponse =
    PreregisterSessionResponse(
        requireNotNull(id),
        expo.id,
        title,
        startedAt,
        endedAt,
        place,
        capacity,
        waitingCapacity,
        closed,
        revision,
        cancelEndedAt,
        teacherCapacity,
        nonTeacherCapacity,
    )
