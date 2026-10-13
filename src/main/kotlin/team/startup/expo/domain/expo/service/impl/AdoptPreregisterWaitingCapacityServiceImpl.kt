package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.request.AdoptPreregisterWaitingCapacityRequest
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.PreregisterSessionRepository
import team.startup.expo.domain.expo.service.AdoptPreregisterWaitingCapacityService
import team.startup.expo.domain.expo.service.PreregisterSessionChangesClient
import team.startup.expo.global.exception.ExpectedException

// Application compares expected with its own Expo observation, so no local transaction is held across the call.
@Service
class AdoptPreregisterWaitingCapacityServiceImpl(
    private val expos: ExpoRepository,
    private val sessions: PreregisterSessionRepository,
    private val changes: PreregisterSessionChangesClient,
) : AdoptPreregisterWaitingCapacityService {
    override fun execute(
        expoId: String,
        sessionId: Long,
        request: AdoptPreregisterWaitingCapacityRequest,
    ) {
        val expo = expos.findByIdOrNull(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
        sessions.findByIdAndExpoId(sessionId, expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "회차를 찾지 못 했습니다.")
        changes.adoptWaitingCapacity(expoId, sessionId, request.expected)
    }
}
