package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.ExpoValidationResponse

interface GetExpoValidationService {
    fun execute(): ExpoValidationResponse
}
