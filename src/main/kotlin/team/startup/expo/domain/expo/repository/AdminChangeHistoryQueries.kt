package team.startup.expo.domain.expo.repository

import team.startup.expo.domain.expo.presentation.dto.request.AdminChangeHistoryRequest
import team.startup.expo.domain.expo.presentation.dto.response.AdminChangeHistoryPage

interface AdminChangeHistoryQueries {
    fun page(
        expoId: String,
        request: AdminChangeHistoryRequest,
    ): AdminChangeHistoryPage
}
