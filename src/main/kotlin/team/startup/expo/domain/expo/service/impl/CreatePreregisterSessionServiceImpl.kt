package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.entity.PreregisterSession
import team.startup.expo.domain.expo.presentation.dto.request.PreregisterSessionRequest
import team.startup.expo.domain.expo.presentation.dto.response.PreregisterSessionResponse
import team.startup.expo.domain.expo.presentation.dto.response.toResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionRepository
import team.startup.expo.domain.expo.service.CreatePreregisterSessionService
import team.startup.expo.global.exception.ExpectedException
import java.time.temporal.ChronoUnit

@Service
class CreatePreregisterSessionServiceImpl(
    private val expos: ExpoRepository,
    private val sessions: PreregisterSessionRepository,
) : CreatePreregisterSessionService {
    @Transactional
    override fun execute(
        expoId: String,
        request: PreregisterSessionRequest,
    ): PreregisterSessionResponse {
        request.validatePeriod()
        val expo = expos.findLockedById(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
        return sessions
            .save(
                PreregisterSession(
                    expo = expo,
                    title = request.title,
                    startedAt = request.startedAt.truncatedTo(ChronoUnit.MICROS),
                    endedAt = request.endedAt.truncatedTo(ChronoUnit.MICROS),
                    place = request.place,
                    capacity = request.capacity,
                    waitingCapacity = request.waitingCapacity,
                    closed = request.closed,
                ),
            ).toResponse()
    }
}
