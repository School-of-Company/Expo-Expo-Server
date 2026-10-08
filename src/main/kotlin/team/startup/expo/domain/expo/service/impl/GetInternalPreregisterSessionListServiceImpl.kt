package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.PreregisterSessionResponse
import team.startup.expo.domain.expo.presentation.dto.response.toResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionRepository
import team.startup.expo.domain.expo.service.GetInternalPreregisterSessionListService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetInternalPreregisterSessionListServiceImpl(
    private val expos: ExpoRepository,
    private val sessions: PreregisterSessionRepository,
) : GetInternalPreregisterSessionListService {
    @Transactional(readOnly = true)
    override fun execute(expoId: String): List<PreregisterSessionResponse> {
        val expo = expos.findByIdOrNull(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
        return sessions.findAllByExpoIdOrderByStartedAtAscIdAsc(expoId).map { it.toResponse() }
    }
}
