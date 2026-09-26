package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.ExpoDetailResponse

interface GetExpoDetailService {
    fun execute(expoId: String): ExpoDetailResponse
}
