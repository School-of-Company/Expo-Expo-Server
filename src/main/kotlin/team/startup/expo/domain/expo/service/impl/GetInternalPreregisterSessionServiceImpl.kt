package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.PreregisterSessionResponse
import team.startup.expo.domain.expo.presentation.dto.response.toResponse
import team.startup.expo.domain.expo.repository.PreregisterSessionRepository
import team.startup.expo.domain.expo.service.GetInternalPreregisterSessionService
import team.startup.expo.global.exception.ExpectedException

@Service
class GetInternalPreregisterSessionServiceImpl(
    private val sessions: PreregisterSessionRepository,
) : GetInternalPreregisterSessionService {
    @Transactional(readOnly = true)
    override fun execute(
        expoId: String,
        sessionId: Long,
    ): PreregisterSessionResponse {
        val session = sessions.findByIdAndExpoId(sessionId, expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "회차를 찾지 못 했습니다.")
        if (session.expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
        return session.toResponse()
    }
}
