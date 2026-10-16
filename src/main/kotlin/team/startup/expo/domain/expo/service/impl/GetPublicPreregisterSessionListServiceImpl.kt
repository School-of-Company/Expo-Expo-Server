package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.PublicPreregisterSessionResponse
import team.startup.expo.domain.expo.presentation.dto.response.toPublicResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionRepository
import team.startup.expo.domain.expo.service.GetPublicPreregisterSessionListService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetPublicPreregisterSessionListServiceImpl(
    private val expos: ExpoRepository,
    private val sessions: PreregisterSessionRepository,
) : GetPublicPreregisterSessionListService {
    @Transactional(readOnly = true)
    override fun execute(expoId: String): List<PublicPreregisterSessionResponse> {
        val expo = expos.findByIdOrNull(expoId)
        if (expo == null || expo.deletingAt != null) throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        return sessions.findAllByExpoIdOrderByStartedAtAscIdAsc(expoId).map { it.toPublicResponse() }
    }
}
