package team.startup.expo.domain.standard.service

import team.startup.expo.domain.standard.presentation.dto.request.UpdateStandardProgramRequest

interface UpdateStandardProgramService {
    fun execute(
        programId: Long,
        request: UpdateStandardProgramRequest,
    )
}
