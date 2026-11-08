package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.ExpoMemberResponse

interface GetExpoMemberListService {
    fun execute(expoId: String): List<ExpoMemberResponse>
}
