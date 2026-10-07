package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.ExpoPageRequest

interface GetExpoListService {
    // page나 size가 있으면 ExpoPageResponse, 없으면 List<ExpoSummaryResponse>를 돌려준다
    fun execute(request: ExpoPageRequest): Any
}
