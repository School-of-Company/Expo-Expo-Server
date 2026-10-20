package team.startup.expo.domain.expo.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonFormat
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.hibernate.validator.constraints.CodePointLength
import org.springframework.http.HttpStatus
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.domain.training.entity.Category
import team.startup.expo.global.exception.ExpectedException
import java.time.LocalDate
import java.time.LocalDateTime

data class UpdateExpoRequest(
    @field:Schema(description = "제목", example = "예시 박람회")
    val title: String,
    @field:Size(max = 1000)
    @field:Schema(description = "설명", example = "가상 행사 설명")
    val description: String,
    @field:JsonFormat(pattern = "yyyy-MM-dd")
    @field:Schema(description = "박람회 시작일", example = "2030-01-01")
    val startedDay: LocalDate,
    @field:JsonFormat(pattern = "yyyy-MM-dd")
    @field:Schema(description = "박람회 종료일", example = "2030-01-02")
    val finishedDay: LocalDate,
    @field:Schema(description = "행사 장소", example = "가상 전시장")
    val location: String,
    @field:Schema(description = "업로드한 대표 이미지 URL", example = "https://example.invalid/image/sample")
    val coverImage: String,
    @field:CodePointLength(max = 15)
    @field:Schema(description = "경도 문자열", example = "127.0000")
    val x: String,
    @field:CodePointLength(max = 15)
    @field:Schema(description = "위도 문자열", example = "37.0000")
    val y: String,
    @field:Valid
    @field:Schema(description = "전체 교체할 프로그램 목록. 생략된 기존 프로그램은 삭제", example = "[]")
    val updateStandardProRequestDto: List<UpdateStandardProgramRequest>,
    @field:Valid
    @field:Schema(description = "전체 교체할 프로그램 목록. 생략된 기존 프로그램은 삭제", example = "[]")
    val updateTrainingProRequestDto: List<UpdateTrainingProgramRequest>,
    @field:PositiveOrZero
    @field:Schema(
        description = "새 사전등록 회차의 교사·예비교사 정원 기본값. 그 외 대상 기본값과 함께 보내거나 함께 생략(null=미설정)합니다. 바꿔도 기존 회차 정원은 그대로입니다.",
        example = "300",
    )
    val preregisterTeacherCapacity: Int? = null,
    @field:PositiveOrZero
    @field:Schema(description = "새 사전등록 회차의 교사·예비교사 외 대상 정원 기본값", example = "1000")
    val preregisterNonTeacherCapacity: Int? = null,
) {
    fun validatePeriod() {
        if (finishedDay.isBefore(startedDay)) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "박람회 종료일은 시작일보다 빠를 수 없습니다.")
        }
    }

    fun validatePreregisterCapacities() = validateTargetCapacities(preregisterTeacherCapacity, preregisterNonTeacherCapacity)
}

@Schema(name = "ExpoUpdateStandardProgramRequest")
data class UpdateStandardProgramRequest(
    @field:Positive
    @field:Schema(description = "식별자", example = "1")
    val id: Long? = null,
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
    @field:Schema(description = "Optional session place: omitted/null preserves on update; blank clears; max 255 code points")
    val place: String? = null,
)

@Schema(name = "ExpoUpdateTrainingProgramRequest")
data class UpdateTrainingProgramRequest(
    @field:Positive
    @field:Schema(description = "식별자", example = "1")
    val id: Long? = null,
    @field:CodePointLength(max = 50)
    @field:Schema(description = "제목", example = "예시 박람회")
    val title: String,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    @field:Schema(description = "시작 일시 (yyyy-MM-dd HH:mm)", example = "2030-01-01 10:00")
    val startedAt: LocalDateTime,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    @field:Schema(description = "종료 일시 (yyyy-MM-dd HH:mm)", example = "2030-01-01 11:00")
    val endedAt: LocalDateTime,
    @field:Schema(description = "연수 구분 ESSENTIAL 또는 CHOICE", example = "ESSENTIAL")
    val category: Category,
    @field:Valid
    @field:Schema(description = "프로그램 모집 정책. 수정 요청에서 null이면 기존 정책 유지")
    val policy: ProgramPolicyRequest? = null,
    @field:jakarta.validation.constraints.Positive
    @field:Schema(description = "조회한 정의 버전", example = "1")
    val revision: Long? = null,
    @field:CodePointLength(max = 255)
    @field:Schema(description = "Optional session place: omitted/null preserves on update; blank clears; max 255 code points")
    val place: String? = null,
)
