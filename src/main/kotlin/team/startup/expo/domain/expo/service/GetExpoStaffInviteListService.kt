package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.ExpoStaffInviteResponse

interface GetExpoStaffInviteListService {
    fun execute(expoId: String): List<ExpoStaffInviteResponse>
}
