package team.startup.expo.domain.expo.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero

data class UpdateExpoFeatureSettingsRequest(
    @field:Schema(description = "조회 때 받은 기능 설정 revision", example = "3")
    @field:NotNull
    @field:PositiveOrZero
    val expectedRevision: Long?,
    @field:Schema(description = "바꿀 기능만 보냅니다. 빈 목록이면 기능 값은 그대로 둡니다.")
    @field:NotNull
    @field:Valid
    val changes: List<ExpoFeatureChangeRequest>?,
    @field:Schema(description = "전화번호 인증 필요 여부를 함께 바꿀 때만 보냅니다.", nullable = true)
    val participantPhoneVerificationRequired: Boolean? = null,
    @field:Schema(description = "전화번호 인증 값을 보낼 때 필수. 조회 때 받은 전화 인증 revision", nullable = true)
    @field:PositiveOrZero
    val expectedPhoneRevision: Long? = null,
)

data class ExpoFeatureChangeRequest(
    @field:Schema(description = "기능 키", example = "PAPER_QR")
    @field:NotBlank
    val key: String?,
    @field:NotNull
    val enabled: Boolean?,
)
