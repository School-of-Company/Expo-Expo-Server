package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.UpdatePreregisterSessionRequest

interface UpdatePreregisterSessionService {
    fun execute(
        expoId: String,
        sessionId: Long,
        request: UpdatePreregisterSessionRequest,
    )
}
