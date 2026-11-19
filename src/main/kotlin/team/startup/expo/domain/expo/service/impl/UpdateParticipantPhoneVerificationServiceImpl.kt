package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.request.UpdateParticipantPhoneVerificationRequest
import team.startup.expo.domain.expo.presentation.dto.response.ParticipantPhoneVerificationResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.ExpoEventSupport
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.expo.service.UpdateParticipantPhoneVerificationService
import team.startup.expo.global.exception.ExpectedException

@Service
class UpdateParticipantPhoneVerificationServiceImpl(
    private val expoRepository: ExpoRepository,
    private val policies: ProgramPolicySupport,
    private val events: ExpoEventSupport,
) : UpdateParticipantPhoneVerificationService {
    @Transactional
    override fun execute(
        expoId: String,
        request: UpdateParticipantPhoneVerificationRequest,
    ): ParticipantPhoneVerificationResponse {
        val required =
            request.participantPhoneVerificationRequired
                ?: throw ExpectedException(HttpStatus.BAD_REQUEST, "전화번호 인증 설정이 필요합니다.")
        val expo =
            expoRepository.findLockedById(expoId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")
        policies.ensureAvailable(expoId)
        if (request.expectedRevision != null && request.expectedRevision != expo.participantPhoneVerificationRevision) {
            throw ExpectedException(HttpStatus.CONFLICT, "다른 곳에서 전화번호 인증 설정이 먼저 바뀌었습니다. 새로 불러온 뒤 다시 저장해 주세요.")
        }
        if (expo.participantPhoneVerificationRequired != required) events.changed(expoId)
        expo.updateParticipantPhoneVerification(required)
        return ParticipantPhoneVerificationResponse(
            expo.participantPhoneVerificationRequired,
            expo.participantPhoneVerificationRevision,
        )
    }
}
