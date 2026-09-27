package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.ExpoSummaryResponse

interface GetExpoListService {
    fun execute(): List<ExpoSummaryResponse>
}
