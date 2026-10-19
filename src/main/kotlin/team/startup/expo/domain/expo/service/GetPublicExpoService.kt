package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.PublicExpoResponse

interface GetPublicExpoService {
    fun execute(expoId: String): PublicExpoResponse
}
