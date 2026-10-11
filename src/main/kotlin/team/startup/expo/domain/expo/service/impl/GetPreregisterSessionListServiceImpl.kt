package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.PreregisterSessionResponse
import team.startup.expo.domain.expo.presentation.dto.response.toResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionRepository
import team.startup.expo.domain.expo.service.GetPreregisterSessionListService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetPreregisterSessionListServiceImpl(
    private val expos: ExpoRepository,
    private val sessions: PreregisterSessionRepository,
) : GetPreregisterSessionListService {
    @Transactional(readOnly = true)
    override fun execute(expoId: String): List<PreregisterSessionResponse> {
        if (!expos.existsById(expoId)) throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        return sessions.findAllByExpoIdOrderByStartedAtAscIdAsc(expoId).map { it.toResponse() }
    }
}
