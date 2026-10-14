package team.startup.expo.domain.expo.presentation.dto.request

import jakarta.validation.constraints.NotNull

data class UpdateParticipantPhoneVerificationRequest(
    @field:NotNull
    val participantPhoneVerificationRequired: Boolean?,
)
