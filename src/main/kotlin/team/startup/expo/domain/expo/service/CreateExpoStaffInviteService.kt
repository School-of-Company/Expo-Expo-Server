package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.CreateExpoStaffInviteRequest
import team.startup.expo.domain.expo.presentation.dto.response.ExpoStaffInviteResponse

interface CreateExpoStaffInviteService {
    fun execute(
        expoId: String,
        request: CreateExpoStaffInviteRequest,
    ): ExpoStaffInviteResponse
}
