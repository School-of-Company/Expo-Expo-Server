package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.PreregisterSessionResponse

interface GetInternalPreregisterSessionService {
    fun execute(
        expoId: String,
        sessionId: Long,
    ): PreregisterSessionResponse
}
