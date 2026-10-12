package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.AdminApplicationCancelAccessResponse

interface GetAdminApplicationCancelAccessService {
    fun execute(
        expoId: String,
        authorization: String?,
    ): AdminApplicationCancelAccessResponse
}
