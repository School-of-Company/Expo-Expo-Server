package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.AdminExpoAccessResponse

interface GetAdminExpoAccessService {
    fun execute(
        expoId: String,
        authorization: String?,
    ): AdminExpoAccessResponse
}
