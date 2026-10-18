package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.AdminApplicationPromoteAccessResponse

interface GetAdminApplicationPromoteAccessService {
    fun execute(
        expoId: String,
        authorization: String?,
    ): AdminApplicationPromoteAccessResponse
}
