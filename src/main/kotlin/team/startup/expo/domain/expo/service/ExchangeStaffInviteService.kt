package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.ExchangeStaffInviteRequest
import team.startup.expo.domain.expo.presentation.dto.response.ExchangeStaffInviteResponse

interface ExchangeStaffInviteService {
    fun execute(request: ExchangeStaffInviteRequest): ExchangeStaffInviteResponse
}
