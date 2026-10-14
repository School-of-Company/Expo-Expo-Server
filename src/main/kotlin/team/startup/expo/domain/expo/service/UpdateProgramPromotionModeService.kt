package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.UpdateProgramPromotionModeRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramPolicyBulkResponse

interface UpdateProgramPromotionModeService {
    fun execute(
        expoId: String,
        request: UpdateProgramPromotionModeRequest,
    ): ProgramPolicyBulkResponse
}
