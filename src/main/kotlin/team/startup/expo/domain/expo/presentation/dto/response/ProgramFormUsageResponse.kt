package team.startup.expo.domain.expo.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class ProgramFormUsageResponse(
    @field:Schema(description = "프로그램 폼 식별자", example = "00000000-0000-0000-0000-000000000002")
    val formId: UUID,
    @field:Schema(description = "폼을 참조하는 회차 존재 여부", example = "true")
    val referenced: Boolean,
    @field:Schema(description = "참조 회차의 모집·현장 접수 기간 중 가장 이른 시작 (UTC 오프셋 ISO 8601)", example = "2030-01-01T10:00:00Z")
    val applicationStartedAt: Instant?,
    @field:Schema(description = "참조 회차의 모집·현장 접수 기간 중 가장 늦은 종료 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val applicationEndedAt: Instant?,
)

data class ProgramFormChangeResponse(
    @field:Schema(description = "프로그램 폼 식별자", example = "00000000-0000-0000-0000-000000000002")
    val formId: UUID,
    @field:Schema(description = "폼을 참조하는 회차 존재 여부", example = "true")
    val referenced: Boolean,
    @field:Schema(description = "참조 회차의 모집·현장 접수 기간 중 가장 이른 시작 (UTC 오프셋 ISO 8601)", example = "2030-01-01T10:00:00Z")
    val applicationStartedAt: Instant?,
    @field:Schema(description = "참조 회차의 모집·현장 접수 기간 중 가장 늦은 종료 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val applicationEndedAt: Instant?,
    @field:Schema(description = "폼 변경 예약 식별자", example = "00000000-0000-0000-0000-000000000003")
    val changeId: UUID,
    @field:Schema(description = "예약 만료 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T10:00:00Z")
    val expiresAt: Instant,
)
