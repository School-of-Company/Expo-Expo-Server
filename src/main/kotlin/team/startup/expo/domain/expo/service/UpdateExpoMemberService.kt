package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoMemberRequest
import team.startup.expo.domain.expo.presentation.dto.response.ExpoMemberResponse

interface UpdateExpoMemberService {
    fun execute(
        expoId: String,
        adminId: Long,
        request: UpdateExpoMemberRequest,
    ): ExpoMemberResponse
}
