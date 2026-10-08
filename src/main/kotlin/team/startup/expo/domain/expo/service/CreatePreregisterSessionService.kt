package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.PreregisterSessionRequest
import team.startup.expo.domain.expo.presentation.dto.response.PreregisterSessionResponse

interface CreatePreregisterSessionService {
    fun execute(
        expoId: String,
        request: PreregisterSessionRequest,
    ): PreregisterSessionResponse
}
