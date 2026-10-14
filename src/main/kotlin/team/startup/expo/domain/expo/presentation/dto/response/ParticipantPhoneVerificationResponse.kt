package team.startup.expo.domain.expo.presentation.dto.response

data class ParticipantPhoneVerificationResponse(
    val participantPhoneVerificationRequired: Boolean,
    val participantPhoneVerificationRevision: Long,
)
