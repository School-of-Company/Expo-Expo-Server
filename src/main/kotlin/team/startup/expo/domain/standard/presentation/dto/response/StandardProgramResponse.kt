package team.startup.expo.domain.standard.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import java.util.UUID

data class StandardProgramResponse(
    @field:Schema(description = "식별자", example = "1")
    val id: Long,
    @field:Schema(description = "제목", example = "예시 박람회")
    val title: String,
    @field:Schema(description = "시작 일시 (ISO 8601 지역 일시)", example = "2030-01-01T10:00")
    val startedAt: String,
    @field:Schema(description = "종료 일시 (ISO 8601 지역 일시)", example = "2030-01-01T11:00")
    val endedAt: String,
    @field:Schema(description = "박람회 식별자", example = "sample-expo")
    val expoId: String? = null,
    @field:Schema(description = "프로그램 유형", example = "STANDARD")
    val programType: ProgramType = ProgramType.STANDARD,
    @field:Schema(description = "프로그램 묶음 식별자", example = "00000000-0000-0000-0000-000000000001")
    val groupId: UUID? = null,
    @field:Schema(description = "프로그램 모집 정책. 수정 요청에서 null이면 기존 정책 유지")
    val policy: ProgramPolicyRequest? = null,
    @field:Schema(description = "조회한 정의 버전", example = "1")
    val revision: Long = 1,
    @field:Schema(description = "진행 중 변경의 다음 버전", example = "2")
    val pendingRevision: Long? = null,
)

data class StandardProgramParticipantResponse(
    @field:Schema(description = "식별자", example = "1")
    val id: Long,
    @field:Schema(description = "참가자 이름", example = "가상 참가자")
    val name: String,
    @field:Schema(description = "프로그램 제목", example = "예시 프로그램")
    val programName: String,
    @field:Schema(description = "출석 여부", example = "true")
    val status: Boolean,
    @field:Schema(description = "입장 시각 HH:mm:ss", example = "10:00:00")
    val entryTime: String?,
    @field:Schema(description = "퇴장 시각 HH:mm:ss", example = "11:00:00")
    val leaveTime: String?,
)

data class StandardProgramTitleResponse(
    @field:Schema(description = "식별자", example = "1")
    val id: Long,
    @field:Schema(description = "제목", example = "예시 박람회")
    val title: String,
    @field:Schema(description = "시작 일시 (ISO 8601 지역 일시)", example = "2030-01-01T10:00")
    val startedAt: String? = null,
    @field:Schema(description = "종료 일시 (ISO 8601 지역 일시)", example = "2030-01-01T11:00")
    val endedAt: String? = null,
    @field:Schema(description = "박람회 식별자", example = "sample-expo")
    val expoId: String? = null,
    @field:Schema(description = "프로그램 유형", example = "STANDARD")
    val programType: ProgramType = ProgramType.STANDARD,
    @field:Schema(description = "프로그램 묶음 식별자", example = "00000000-0000-0000-0000-000000000001")
    val groupId: UUID? = null,
    @field:Schema(description = "프로그램 모집 정책. 수정 요청에서 null이면 기존 정책 유지")
    val policy: ProgramPolicyRequest? = null,
    @field:Schema(description = "조회한 정의 버전", example = "1")
    val revision: Long = 1,
    @field:Schema(description = "진행 중 변경의 다음 버전", example = "2")
    val pendingRevision: Long? = null,
)
