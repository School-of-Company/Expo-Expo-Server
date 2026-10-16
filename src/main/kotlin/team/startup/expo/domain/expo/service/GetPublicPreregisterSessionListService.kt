package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.PublicPreregisterSessionResponse

interface GetPublicPreregisterSessionListService {
    fun execute(expoId: String): List<PublicPreregisterSessionResponse>
}
