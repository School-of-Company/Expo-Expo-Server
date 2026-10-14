package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.request.UpdateProgramApplicationPeriodRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramPolicyBulkResponse

interface UpdateProgramApplicationPeriodService {
    fun execute(
        expoId: String,
        request: UpdateProgramApplicationPeriodRequest,
    ): ProgramPolicyBulkResponse
}
