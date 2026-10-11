package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.PreregisterSessionResponse

interface GetInternalPreregisterSessionListService {
    fun execute(expoId: String): List<PreregisterSessionResponse>
}
