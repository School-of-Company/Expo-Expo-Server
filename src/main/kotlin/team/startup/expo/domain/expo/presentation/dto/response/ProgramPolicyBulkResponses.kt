package team.startup.expo.domain.expo.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPromotionMode
import java.time.Instant

enum class ProgramPolicyBulkStatus { UPDATED, UNCHANGED, SKIPPED_NO_POLICY, FAILED }

data class ProgramPolicyBulkResult(
    @field:Schema(description = "프로그램 유형", example = "STANDARD")
    val programType: ProgramType,
    @field:Schema(description = "프로그램 식별자", example = "1")
    val id: Long,
    @field:Schema(description = "프로그램 제목", example = "예시 프로그램")
    val title: String,
    @field:Schema(description = "처리 결과. UPDATED 반영, UNCHANGED 변경 없음, SKIPPED_NO_POLICY 정책 없음, FAILED 검증 실패", example = "UPDATED")
    val status: ProgramPolicyBulkStatus,
    @field:Schema(description = "실패 사유. FAILED일 때만 값이 있음", example = "폼 기간이 프로그램 모집 기간을 포함해야 합니다.")
    val reason: String? = null,
)

data class ProgramPolicyBulkResponse(
    @field:Schema(description = "프로그램별 처리 결과. 같은 요청을 재시도해 이미 준비된 변경을 반영한 경우 빈 목록")
    val results: List<ProgramPolicyBulkResult>,
)

data class ProgramPromotionModeItem(
    @field:Schema(description = "프로그램 유형", example = "STANDARD")
    val programType: ProgramType,
    @field:Schema(description = "프로그램 식별자", example = "1")
    val id: Long,
    @field:Schema(description = "프로그램 제목", example = "예시 프로그램")
    val title: String,
    @field:Schema(description = "대기 승급 모드", example = "AUTO")
    val promotionMode: ProgramPromotionMode,
    @field:Schema(description = "자동 승급 종료 일시. null이면 모집 종료까지 승급", example = "2030-01-01T11:00:00Z")
    val promotionEndedAt: Instant?,
)

data class ProgramPromotionModesResponse(
    @field:Schema(description = "정책이 있는 프로그램 수", example = "23")
    val total: Int,
    @field:Schema(description = "AUTO 프로그램 수", example = "22")
    val auto: Int,
    @field:Schema(description = "MANUAL 프로그램 수", example = "1")
    val manual: Int,
    @field:Schema(description = "정책이 있는 프로그램별 승급 모드")
    val programs: List<ProgramPromotionModeItem>,
)
