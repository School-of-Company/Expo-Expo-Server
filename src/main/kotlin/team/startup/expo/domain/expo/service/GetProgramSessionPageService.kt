package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.ProgramSessionPageRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramSessionPageResponse

interface GetProgramSessionPageService {
    fun execute(
        expoId: String,
        request: ProgramSessionPageRequest,
    ): ProgramSessionPageResponse
}
