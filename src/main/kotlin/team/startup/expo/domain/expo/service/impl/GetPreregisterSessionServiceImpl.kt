package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.PreregisterSessionResponse
import team.startup.expo.domain.expo.presentation.dto.response.toResponse
import team.startup.expo.domain.expo.repository.PreregisterSessionRepository
import team.startup.expo.domain.expo.service.GetPreregisterSessionService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetPreregisterSessionServiceImpl(
    private val sessions: PreregisterSessionRepository,
) : GetPreregisterSessionService {
    @Transactional(readOnly = true)
    override fun execute(
        expoId: String,
        sessionId: Long,
    ): PreregisterSessionResponse =
        sessions.findByIdAndExpoId(sessionId, expoId)?.toResponse()
            ?: throw ExpectedException(HttpStatus.NOT_FOUND, "회차를 찾지 못 했습니다.")
}
