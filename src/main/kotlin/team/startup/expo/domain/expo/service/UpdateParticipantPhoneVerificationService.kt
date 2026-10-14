package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.UpdateParticipantPhoneVerificationRequest
import team.startup.expo.domain.expo.presentation.dto.response.ParticipantPhoneVerificationResponse

interface UpdateParticipantPhoneVerificationService {
    fun execute(
        expoId: String,
        request: UpdateParticipantPhoneVerificationRequest,
    ): ParticipantPhoneVerificationResponse
}
