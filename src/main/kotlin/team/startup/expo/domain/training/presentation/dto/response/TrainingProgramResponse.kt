package team.startup.expo.domain.training.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.training.entity.Category
import java.util.UUID

data class TrainingProgramResponse(
    @field:Schema(description = "식별자", example = "1")
    val id: Long,
    @field:Schema(description = "제목", example = "예시 박람회")
    val title: String,
    @field:Schema(description = "시작 일시 (ISO 8601 지역 일시)", example = "2030-01-01T10:00")
    val startedAt: String,
    @field:Schema(description = "종료 일시 (ISO 8601 지역 일시)", example = "2030-01-01T11:00")
    val endedAt: String,
    @field:Schema(description = "연수 구분 ESSENTIAL 또는 CHOICE", example = "ESSENTIAL")
    val category: Category,
    @field:Schema(description = "박람회 식별자", example = "sample-expo")
    val expoId: String? = null,
    @field:Schema(description = "프로그램 유형", example = "TRAINING")
    val programType: ProgramType = ProgramType.TRAINING,
    @field:Schema(description = "프로그램 묶음 식별자", example = "00000000-0000-0000-0000-000000000001")
    val groupId: UUID? = null,
    @field:Schema(description = "프로그램 모집 정책. 수정 요청에서 null이면 기존 정책 유지")
    val policy: ProgramPolicyRequest? = null,
    @field:Schema(description = "조회한 정의 버전", example = "1")
    val revision: Long = 1,
    @field:Schema(description = "진행 중 변경의 다음 버전", example = "2")
    val pendingRevision: Long? = null,
)
