package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.ProgramFormUsageResponse
import java.util.UUID

interface GetProgramFormUsageService {
    fun execute(formId: UUID): ProgramFormUsageResponse
}
