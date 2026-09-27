package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.CreateExpoRequest
import team.startup.expo.domain.expo.presentation.dto.response.CreateExpoResponse

interface CreateExpoService {
    fun execute(request: CreateExpoRequest): CreateExpoResponse
}
