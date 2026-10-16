package team.startup.expo.domain.expo.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoRequest
import team.startup.expo.domain.training.entity.Category
import java.util.UUID

enum class ProgramType { STANDARD, TRAINING }

data class ProgramDefinition(
    @field:Schema(description = "박람회 식별자", example = "sample-expo")
    val expoId: String,
    @field:Schema(description = "프로그램 유형", example = "STANDARD")
    val programType: ProgramType,
    @field:Schema(description = "식별자", example = "1")
    val id: Long?,
    @field:Schema(description = "제목", example = "예시 박람회")
    val title: String,
    @field:Schema(description = "시작 일시 (ISO 8601 지역 일시)", example = "2030-01-01T10:00")
    val startedAt: String,
    @field:Schema(description = "종료 일시 (ISO 8601 지역 일시)", example = "2030-01-01T11:00")
    val endedAt: String,
    @field:Schema(description = "연수 구분 ESSENTIAL 또는 CHOICE", example = "ESSENTIAL")
    val category: Category? = null,
    @field:Schema(description = "프로그램 묶음 식별자", example = "00000000-0000-0000-0000-000000000001")
    val groupId: UUID,
    @field:Schema(description = "프로그램 모집 정책. 수정 요청에서 null이면 기존 정책 유지")
    val policy: ProgramPolicyRequest?,
    @field:Schema(description = "조회한 정의 버전", example = "1")
    val revision: Long,
    @field:Schema(description = "Session place; null means location to be announced")
    val place: String? = null,
)

data class ProgramMutation(
    @field:Schema(description = "변경 이전 프로그램 정의")
    val previous: ProgramDefinition?,
    @field:Schema(description = "회차 전체 정의")
    val definition: ProgramDefinition?,
)

data class ProgramChangePayload(
    @field:Schema(description = "프로그램 변경 목록", example = "[]")
    val mutations: List<ProgramMutation>,
    @field:Schema(description = "박람회 전체 수정 내용")
    val expoUpdate: UpdateExpoRequest? = null,
    @field:Schema(description = "이미지 업로드 관리자 식별자", example = "1")
    val uploadedBy: String? = null,
)
