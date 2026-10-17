package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.InternalExpoPageRequest
import team.startup.expo.domain.expo.presentation.dto.response.InternalExpoPageResponse

interface GetInternalExpoListService {
    fun execute(request: InternalExpoPageRequest): InternalExpoPageResponse
}
