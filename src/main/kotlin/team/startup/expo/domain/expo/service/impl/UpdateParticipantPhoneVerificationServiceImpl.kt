package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.request.UpdateParticipantPhoneVerificationRequest
import team.startup.expo.domain.expo.presentation.dto.response.ParticipantPhoneVerificationResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.ProgramChangeRepository
import team.startup.expo.domain.expo.service.UpdateParticipantPhoneVerificationService
import team.startup.expo.global.exception.ExpectedException

@Service
class UpdateParticipantPhoneVerificationServiceImpl(
    private val expoRepository: ExpoRepository,
    private val programChanges: ProgramChangeRepository,
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
        if (expo.deletingAt != null) {
            throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
        }
        if (programChanges.existsById(expoId)) {
            throw ExpectedException(HttpStatus.CONFLICT, "진행 중인 프로그램 변경을 먼저 재시도해 주세요.")
        }
        expo.updateParticipantPhoneVerification(required)
        return ParticipantPhoneVerificationResponse(
            expo.participantPhoneVerificationRequired,
            expo.participantPhoneVerificationRevision,
        )
    }
}
