package team.startup.expo.domain.expo.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import team.startup.expo.global.exception.ExpectedException
import java.time.Instant
import java.util.UUID

enum class ProgramOccupation {
    KINDERGARTEN_STUDENT,
    ELEMENTARY_STUDENT,
    MIDDLE_SCHOOL_STUDENT,
    HIGH_SCHOOL_STUDENT,
    SCHOOL_STAFF,
    PARENT,
    GENERAL,
    TEACHER,
    PRE_SERVICE_TEACHER,
}

data class ProgramPolicyRequest(
    @field:Schema(description = "프로그램 묶음 식별자", example = "00000000-0000-0000-0000-000000000001")
    val groupId: UUID? = null,
    @field:Schema(description = "프로그램 폼 식별자", example = "00000000-0000-0000-0000-000000000002")
    val formId: UUID,
    @field:Schema(description = "신청 가능한 직종", example = "[\"TEACHER\"]")
    val allowedOccupations: Set<ProgramOccupation>,
    @field:Positive
    @field:Schema(description = "정원", example = "30")
    val capacity: Int,
    @field:PositiveOrZero
    @param:JsonProperty(required = true)
    @field:Schema(description = "대기 정원", example = "5")
    val waitingCapacity: Int,
    @field:Schema(description = "모집 시작 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T10:00:00Z")
    val applicationStartedAt: Instant,
    @field:Schema(description = "모집 종료 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val applicationEndedAt: Instant,
    @field:Schema(description = "취소 마감 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val cancelEndedAt: Instant,
    @field:Schema(description = "대기 승급 마감 일시 (UTC 오프셋 ISO 8601)", example = "2030-01-01T11:00:00Z")
    val promotionEndedAt: Instant,
    @param:JsonProperty(required = true)
    @field:Schema(description = "모집 마감 여부", example = "false")
    val closed: Boolean,
    @field:Valid
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:Schema(
        description =
            "신청 채널(신청 구분)별 정원. null이면 채널 없이 capacity·waitingCapacity 하나로 모집. " +
                "있으면 채널 사이 직종이 겹치지 않아야 하고 capacity·waitingCapacity는 채널 합, allowedOccupations는 채널 직종 합집합과 같아야 함",
    )
    val channels: List<ProgramChannelRequest>? = null,
    @field:Valid
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:Schema(description = "이 회차의 초등 1~3학년 보호자 필수 설정. null/생략은 OFF이며 같은 묶음의 다른 회차로 전파하지 않음")
    val guardianPolicy: ProgramGuardianPolicyRequest? = null,
) {
    fun validate() {
        if (capacity <= 0 || waitingCapacity < 0 || allowedOccupations.isEmpty() ||
            !applicationStartedAt.isBefore(applicationEndedAt) ||
            cancelEndedAt.isBefore(applicationStartedAt) || promotionEndedAt.isBefore(applicationStartedAt)
        ) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 모집 정책이 올바르지 않습니다.")
        }
        if (listOf(applicationStartedAt, applicationEndedAt, cancelEndedAt, promotionEndedAt).any { it.nano % 1000 != 0 }) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 정책 시각은 마이크로초 정밀도까지 지원합니다.")
        }
        channels?.let { validateChannels(it) }
        guardianPolicy?.validate()
    }

    private fun validateChannels(channels: List<ProgramChannelRequest>) {
        val occupations = channels.flatMap { it.allowedOccupations }
        val names = channels.map { it.name.trim() }
        if (channels.isEmpty() || names.any { it.isEmpty() || it.length > 50 } || names.toSet().size != names.size ||
            channels.any { it.capacity <= 0 || it.waitingCapacity < 0 || it.allowedOccupations.isEmpty() } ||
            occupations.size != occupations.toSet().size || occupations.toSet() != allowedOccupations ||
            channels.sumOf { it.capacity.toLong() } != capacity.toLong() ||
            channels.sumOf { it.waitingCapacity.toLong() } != waitingCapacity.toLong()
        ) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "프로그램 신청 채널 정책이 올바르지 않습니다.")
        }
    }
}

data class ProgramChannelRequest(
    @field:NotBlank
    @field:Size(max = 50)
    @field:Schema(description = "채널 이름. 회차 안에서 고유하며 신청 집계 키로 쓰임", example = "교원")
    val name: String,
    @field:Schema(description = "이 채널로 신청하는 직종. 다른 채널과 겹칠 수 없음", example = "[\"TEACHER\"]")
    val allowedOccupations: Set<ProgramOccupation>,
    @field:Positive
    @field:Schema(description = "채널 정원", example = "10")
    val capacity: Int,
    @field:PositiveOrZero
    @param:JsonProperty(required = true)
    @field:Schema(description = "채널 대기 정원", example = "2")
    val waitingCapacity: Int,
)
