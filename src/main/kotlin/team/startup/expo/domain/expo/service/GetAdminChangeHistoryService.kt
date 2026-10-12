package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.AdminChangeHistoryRequest
import team.startup.expo.domain.expo.presentation.dto.response.AdminChangeHistoryPage

interface GetAdminChangeHistoryService {
    fun execute(
        expoId: String,
        authorization: String,
        request: AdminChangeHistoryRequest,
    ): AdminChangeHistoryPage
}
