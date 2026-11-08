package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.CreateExpoMemberRequest
import team.startup.expo.domain.expo.presentation.dto.response.ExpoMemberResponse

interface CreateExpoMemberService {
    fun execute(
        expoId: String,
        request: CreateExpoMemberRequest,
    ): ExpoMemberResponse
}
