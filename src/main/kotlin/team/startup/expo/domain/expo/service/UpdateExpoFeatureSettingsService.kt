package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoFeatureSettingsRequest
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureSettingsResponse

interface UpdateExpoFeatureSettingsService {
    fun execute(
        expoId: String,
        request: UpdateExpoFeatureSettingsRequest,
    ): ExpoFeatureSettingsResponse
}
