package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.ExpoPeriodResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.ProgramChangeRepository
import team.startup.expo.domain.expo.service.GetExpoPeriodService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetExpoPeriodServiceImpl(
    private val expoRepository: ExpoRepository,
    private val programChanges: ProgramChangeRepository,
) : GetExpoPeriodService {
    @Transactional(readOnly = true)
    override fun execute(expoId: String): ExpoPeriodResponse {
        val expo =
            expoRepository.findByIdOrNull(expoId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")
        if (expo.deletingAt != null) {
            throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
        }
        if (programChanges.existsById(expoId)) {
            throw ExpectedException(HttpStatus.CONFLICT, "진행 중인 프로그램 변경을 먼저 재시도해 주세요.")
        }
        return ExpoPeriodResponse(
            title = expo.title,
            startedDay = expo.startedDay,
            finishedDay = expo.finishedDay,
            participantPhoneVerificationRequired = expo.participantPhoneVerificationRequired,
            participantPhoneVerificationRevision = expo.participantPhoneVerificationRevision,
        )
    }
}
