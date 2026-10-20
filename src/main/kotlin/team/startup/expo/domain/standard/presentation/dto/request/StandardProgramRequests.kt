package team.startup.expo.domain.standard.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonFormat
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.hibernate.validator.constraints.CodePointLength
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import java.time.LocalDateTime

data class AddStandardProgramRequest(
    @field:NotNull
    @field:CodePointLength(max = 50)
    @field:Schema(description = "제목", example = "예시 박람회")
    val title: String,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    @field:Schema(description = "시작 일시 (yyyy-MM-dd HH:mm)", example = "2030-01-01 10:00")
    val startedAt: LocalDateTime,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    @field:Schema(description = "종료 일시 (yyyy-MM-dd HH:mm)", example = "2030-01-01 11:00")
    val endedAt: LocalDateTime,
    @field:Valid
    @field:Schema(description = "프로그램 모집 정책. 수정 요청에서 null이면 기존 정책 유지")
    val policy: ProgramPolicyRequest? = null,
    @field:CodePointLength(max = 255)
    @field:Schema(description = "Optional place: omitted/null preserves on update; blank clears; max 255 UTF-16 units, no control chars")
    val place: String? = null,
)

data class UpdateStandardProgramRequest(
    @field:Schema(description = "식별자", example = "1")
    val id: Long,
    @field:NotNull
    @field:CodePointLength(max = 50)
    @field:Schema(description = "제목", example = "예시 박람회")
    val title: String,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    @field:Schema(description = "시작 일시 (yyyy-MM-dd HH:mm)", example = "2030-01-01 10:00")
    val startedAt: LocalDateTime,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    @field:Schema(description = "종료 일시 (yyyy-MM-dd HH:mm)", example = "2030-01-01 11:00")
    val endedAt: LocalDateTime,
    @field:Valid
    @field:Schema(description = "프로그램 모집 정책. 수정 요청에서 null이면 기존 정책 유지")
    val policy: ProgramPolicyRequest? = null,
    @field:jakarta.validation.constraints.Positive
    @field:Schema(description = "조회한 정의 버전", example = "1")
    val revision: Long? = null,
    @field:CodePointLength(max = 255)
    @field:Schema(description = "Optional place: omitted/null preserves on update; blank clears; max 255 UTF-16 units, no control chars")
    val place: String? = null,
)

data class ApplyStandardProgramsRequest(
    @field:NotNull
    @field:Schema(description = "가짜 예시 전화번호", example = "01000000000")
    val phoneNumber: String,
    @field:NotNull
    @field:Schema(description = "프로그램 식별자 목록", example = "[1,2]")
    val standardProIds: List<Long>,
)
