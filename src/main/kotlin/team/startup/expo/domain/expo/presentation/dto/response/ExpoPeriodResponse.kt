package team.startup.expo.domain.expo.presentation.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class ExpoPeriodResponse(
    @field:Schema(description = "제목", example = "예시 박람회")
    val title: String,
    @field:Schema(description = "박람회 시작일", example = "2030-01-01")
    val startedDay: String,
    @field:Schema(description = "박람회 종료일", example = "2030-01-02")
    val finishedDay: String,
    @field:Schema(description = "참가자 전화번호 인증 필요 여부", example = "false")
    val participantPhoneVerificationRequired: Boolean,
    @field:Schema(description = "참가자 전화번호 인증 설정 revision", example = "0", minimum = "0")
    val participantPhoneVerificationRevision: Long,
    @field:Schema(description = "박람회 장소", example = "광주 김대중컨벤션센터", nullable = true)
    val location: String? = null,
)
