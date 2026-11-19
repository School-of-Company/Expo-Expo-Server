package team.startup.expo.domain.expo.presentation.dto.request

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero

data class UpdateParticipantPhoneVerificationRequest(
    @field:NotNull
    val participantPhoneVerificationRequired: Boolean?,
    // 보내면 기능 설정 일괄 저장과 같은 방식으로 현재 revision과 비교한다. 다르면 409다.
    @field:PositiveOrZero
    val expectedRevision: Long? = null,
)
