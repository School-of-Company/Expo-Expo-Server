package team.startup.expo.domain.expo.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonFormat
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.hibernate.validator.constraints.CodePointLength
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.domain.training.entity.Category
import java.time.LocalDate
import java.time.LocalDateTime

data class CreateExpoRequest(
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
    @field:Schema(description = "생성할 프로그램 목록", example = "[]")
    val addStandardProRequestDto: List<CreateStandardProgramRequest>,
    @field:Valid
    @field:Schema(description = "생성할 프로그램 목록", example = "[]")
    val addTrainingProRequestDto: List<CreateTrainingProgramRequest>,
)

data class CreateStandardProgramRequest(
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
)

data class CreateTrainingProgramRequest(
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
)
